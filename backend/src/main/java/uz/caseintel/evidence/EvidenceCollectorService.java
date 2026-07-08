package uz.caseintel.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.CompanyView;
import uz.caseintel.casebuilder.dto.DossierDto.CycleView;
import uz.caseintel.casebuilder.dto.DossierDto.PastAlertView;
import uz.caseintel.casebuilder.dto.DossierDto.RelationView;
import uz.caseintel.rules.RuleResult;
import org.springframework.stereotype.Service;

/**
 * Evidence Collector (④ в пайплайне). Собирает ВСЕ доказательства в
 * единый пакет перед оценкой риска — см. ARCHITECTURE.md §8.
 *
 * Работает НАД уже готовыми данными (RuleResult от Rule Engine ②,
 * DossierDto от Data Collector ①) — сам не ходит в БД. Это чистая
 * функция агрегации/трансформации, поэтому легко тестируема без Spring.
 *
 * Порядок сборки items() важен для читаемости пакета аналитиком
 * (см. пример в §8): сначала правила (самое весомое), потом связи,
 * потом история, потом остальные информационные пункты.
 */
@Service
public class EvidenceCollectorService {

    /** Порог "ночная операция" — используется для anomaly-пункта "Night transactions". */
    private static final int NIGHT_START_HOUR = 0;
    private static final int NIGHT_END_HOUR = 5;

    public EvidenceBundle collect(Long clientId, List<RuleResult> ruleResults, DossierDto dossier) {
        List<Evidence> items = new ArrayList<>();

        items.addAll(fromRuleHits(ruleResults));
        items.addAll(fromRelations(dossier.relations()));
        items.addAll(fromMoneyCycles(dossier.moneyCycles()));
        items.addAll(fromRelatedCompanies(dossier.relatedCompanies()));
        items.addAll(fromPastAlerts(dossier.pastAlerts()));
        items.addAll(fromNightTransactions(dossier));

        int totalRuleWeight = items.stream()
                .filter(e -> Evidence.TYPE_RULE_HIT.equals(e.type()))
                .mapToInt(Evidence::weight)
                .sum();

        return new EvidenceBundle(clientId, items, totalRuleWeight);
    }

    private List<Evidence> fromRuleHits(List<RuleResult> ruleResults) {
        return ruleResults.stream()
                .map(r -> new Evidence(
                        Evidence.TYPE_RULE_HIT,
                        "Rule %s: %s".formatted(r.code(), r.name()),
                        r.weight(),
                        r.evidence()
                ))
                .toList();
    }

    /**
     * Связи с blacklisted-субъектами становятся отдельным информационным
     * пунктом (weight=0 — вклад в score уже учтён правилами R07/R10,
     * которые смотрят на эти же relations). same_device выделяется в
     * отдельный тип TYPE_SHARED_DEVICE — он самый "человеческий" сигнал
     * для аналитика (см. пример "Same device: клиент #77 (blacklisted)"
     * в §8), остальные типы связей идут как TYPE_RELATION.
     */
    private List<Evidence> fromRelations(List<RelationView> relations) {
        List<Evidence> items = new ArrayList<>();
        for (RelationView r : relations) {
            if (!r.counterpartBlacklisted()) {
                continue;
            }
            boolean isSameDevice = "same_device".equals(r.relationType());
            String title = isSameDevice
                    ? "Same device: %s (blacklisted)".formatted(r.counterpartLabel())
                    : "Related %s: %s (%s)".formatted(r.counterpartType(), r.counterpartLabel(), relationLabel(r.relationType()));

            items.add(new Evidence(
                    isSameDevice ? Evidence.TYPE_SHARED_DEVICE : Evidence.TYPE_RELATION,
                    title,
                    0,
                    Map.of(
                            "counterpart_id", r.counterpartId(),
                            "counterpart_type", r.counterpartType(),
                            "relation_type", r.relationType()
                    )
            ));
        }
        return items;
    }

    private List<Evidence> fromMoneyCycles(List<CycleView> cycles) {
        List<Evidence> items = new ArrayList<>();
        for (CycleView cycle : cycles) {
            items.add(new Evidence(
                    Evidence.TYPE_SUSPICIOUS_TX,
                    "Circular transfers: %s".formatted(String.join(" → ", cycle.pathLabels())),
                    0,
                    Map.of(
                            "total_amount", cycle.totalAmount(),
                            "transaction_count", cycle.transactionCount()
                    )
            ));
        }
        return items;
    }

    private List<Evidence> fromRelatedCompanies(List<CompanyView> companies) {
        List<Evidence> items = new ArrayList<>();
        for (CompanyView c : companies) {
            if (!c.blacklisted()) {
                continue;
            }
            items.add(new Evidence(
                    Evidence.TYPE_RELATION,
                    "Related company: %s (blacklisted, %s)".formatted(c.name(), c.roleOfClient()),
                    0,
                    Map.of("company_id", c.companyId(), "role", c.roleOfClient())
            ));
        }
        return items;
    }

    /**
     * Прошлые алерты — тип TYPE_PREVIOUS_ALERT. Risk Engine (⑤) ищет
     * именно этот type, чтобы применить "repeat offender" усилитель
     * (+10, см. §9) — важно не переименовывать тип без синхронизации.
     */
    private List<Evidence> fromPastAlerts(List<PastAlertView> pastAlerts) {
        List<Evidence> items = new ArrayList<>();
        for (PastAlertView a : pastAlerts) {
            String statusLabel = a.status() != null ? a.status() : "не расследовался";
            items.add(new Evidence(
                    Evidence.TYPE_PREVIOUS_ALERT,
                    "Previous alert: #%d (%s)".formatted(a.alertId(), statusLabel),
                    0,
                    Map.of(
                            "alert_id", a.alertId(),
                            "case_id", a.caseId() != null ? a.caseId() : "none",
                            "status", statusLabel
                    )
            ));
        }
        return items;
    }

    /**
     * Аномалия профиля: операции в ночное время (00:00–05:00) — не
     * отдельное правило Rule Engine, а информационный пункт для
     * контекста аналитику (см. пример "Night transactions: 6 операций
     * 02:00–04:00" в §8). Возвращает максимум один Evidence-пункт.
     */
    private List<Evidence> fromNightTransactions(DossierDto dossier) {
        long nightCount = dossier.transactions().stream()
                .filter(tx -> {
                    int hour = tx.timestamp().getHour();
                    return hour >= NIGHT_START_HOUR && hour < NIGHT_END_HOUR;
                })
                .count();

        if (nightCount == 0) {
            return List.of();
        }

        return List.of(new Evidence(
                Evidence.TYPE_ANOMALY,
                "Night transactions: %d операций %02d:00–%02d:00".formatted(nightCount, NIGHT_START_HOUR, NIGHT_END_HOUR),
                0,
                Map.of("count", nightCount)
        ));
    }

    private static String relationLabel(String relationType) {
        return switch (relationType) {
            case "same_address" -> "общий адрес";
            case "same_phone" -> "общий телефон";
            case "same_device" -> "общее устройство";
            case "director" -> "общий директор";
            case "founder" -> "общий учредитель";
            case "family" -> "родственная связь";
            case "frequent_counterparty" -> "частый контрагент";
            default -> relationType;
        };
    }
}
