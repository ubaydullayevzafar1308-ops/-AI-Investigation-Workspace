package uz.caseintel.explainability;

import java.util.ArrayList;
import java.util.List;
import uz.caseintel.evidence.Evidence;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.risk.RiskEngineService;
import uz.caseintel.risk.RiskResult;
import org.springframework.stereotype.Service;

/**
 * Explainability Engine (⑥ в пайплайне). Превращает Risk Score в
 * структурированный список причин — вклад каждого фактора отдельно.
 * См. ARCHITECTURE.md §10.
 *
 * Источник причин — ТОЛЬКО EvidenceBundle (rule_hit пункты) плюс
 * repeat-offender бонус, который применяет Risk Engine. Этот сервис не
 * пересчитывает риск заново — он берёт уже готовый RiskResult и просто
 * раскладывает его на составляющие для читаемости.
 *
 * Reasons сортируются по вкладу по убыванию (самый весомый фактор
 * первым) — так и показано в примере §10 ("начни с главного фактора" —
 * этот же порядок передаётся LLM в explainRisk, см.
 * AI_LAYER_ARCHITECTURE.md §8.2).
 *
 * РАСХОЖДЕНИЕ С ПРИМЕРОМ В §10, О КОТОРОМ СТОИТ ЗНАТЬ: иллюстрация в
 * спеке показывает "Ночные операции (+13)" как причину с ненулевым
 * вкладом в score. В таблице правил (§6) нет отдельного правила на
 * ночные операции — в моей реализации это Evidence.TYPE_ANOMALY с
 * weight=0 (см. EvidenceCollectorService.fromNightTransactions):
 * информационный пункт для контекста аналитику, но не влияющий на
 * score. Поэтому "Ночные операции" НЕ появится как Reason с
 * ненулевым вкладом — только правила (rule_hit) и repeat-offender
 * бонус попадают в reasons. Если нужно, чтобы ночные операции реально
 * влияли на риск, это стоит оформить как отдельное 11-е правило с
 * явным весом, а не молча "подгонять" под иллюстративный пример.
 */
@Service
public class ExplainabilityService {

    public ExplanationDto explain(RiskResult risk, EvidenceBundle evidence) {
        List<ExplanationDto.Reason> reasons = new ArrayList<>();

        for (Evidence e : evidence.items()) {
            if (Evidence.TYPE_RULE_HIT.equals(e.type()) && e.weight() > 0) {
                reasons.add(new ExplanationDto.Reason(
                        ruleFactorLabel(e.title()),
                        e.weight(),
                        formatDetail(e)
                ));
            }
        }

        boolean repeatOffender = evidence.items().stream()
                .anyMatch(e -> Evidence.TYPE_PREVIOUS_ALERT.equals(e.type()));
        if (repeatOffender) {
            reasons.add(new ExplanationDto.Reason(
                    "Повторный фигурант",
                    RiskEngineService.REPEAT_OFFENDER_BONUS,
                    "клиент уже фигурировал в прошлых алертах"
            ));
        }

        reasons.sort((a, b) -> Integer.compare(b.contribution(), a.contribution()));
        reasons = applyCapAnnotation(reasons, risk.score());

        return new ExplanationDto(risk.score(), risk.level(), reasons);
    }

    /**
     * "Rule R01: Structuring (дробление сумм)" -> "Structuring (дробление сумм)".
     * Убираем технический префикс "Rule R01: " — аналитику/LLM интереснее
     * человекочитаемое имя фактора, код правила остаётся в самом Evidence.
     */
    private static String ruleFactorLabel(String evidenceTitle) {
        int colonIdx = evidenceTitle.indexOf(": ");
        return colonIdx >= 0 ? evidenceTitle.substring(colonIdx + 2) : evidenceTitle;
    }

    /**
     * Строит detail-строку из Evidence.details — те же данные, которые
     * правило положило в RuleResult.evidence() (id транзакций, суммы,
     * даты). Показываем самое информативное для человека: если явно
     * есть готовое текстовое summary в details, берём его; иначе просто
     * перечисляем ключ-значения.
     */
    private static String formatDetail(Evidence e) {
        if (e.details() == null || e.details().isEmpty()) {
            return "";
        }
        return e.details().entrySet().stream()
                .map(entry -> "%s: %s".formatted(entry.getKey(), entry.getValue()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    /**
     * Если сумма вкладов превышает итоговый (капнутый) score, последняя
     * (наименее весомая, т.к. список уже отсортирован по убыванию) причина
     * помечается как частично учтённая — ровно как в примере §10:
     * "Ночные операции (+13 → капнуто до 100)".
     */
    private List<ExplanationDto.Reason> applyCapAnnotation(List<ExplanationDto.Reason> reasons, int cappedScore) {
        int rawSum = reasons.stream().mapToInt(ExplanationDto.Reason::contribution).sum();
        if (rawSum <= cappedScore || reasons.isEmpty()) {
            return reasons;
        }

        List<ExplanationDto.Reason> result = new ArrayList<>(reasons.subList(0, reasons.size() - 1));
        ExplanationDto.Reason last = reasons.get(reasons.size() - 1);
        String cappedDetail = last.detail().isBlank()
                ? "капнуто до %d".formatted(cappedScore)
                : "%s (капнуто до %d)".formatted(last.detail(), cappedScore);
        result.add(new ExplanationDto.Reason(last.factor(), last.contribution(), cappedDetail));

        return result;
    }
}
