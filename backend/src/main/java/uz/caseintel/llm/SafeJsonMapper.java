package uz.caseintel.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.CompanyView;
import uz.caseintel.casebuilder.dto.DossierDto.RelationView;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.explainability.ExplanationDto;
import uz.caseintel.risk.RiskResult;
import org.springframework.stereotype.Component;

/**
 * Строит SafeCaseJson — единственное, что уходит в LLM.
 * См. AI_LAYER_ARCHITECTURE.md §3 — это САМАЯ критичная по безопасности
 * часть всей системы: ошибка здесь означает утечку ФИО/ИНН в сторонний
 * API (Claude/OpenAI/Gemini).
 *
 * Порядок работы toSafeJson():
 *  1. Строит словарь псевдонимов по ВСЕМ субъектам кейса (клиент — всегда
 *     К-1, остальные клиенты К-2, К-3..., компании С-1, С-2...).
 *  2. Строит обезличенный профиль клиента (без ФИО/ИНН/телефона).
 *  3. Прогоняет каждую строку (Reason.detail, Evidence.title, значения
 *     details/aggregates) через replaceRealNamesWithPseudonyms —
 *     заменяет реальные ФИО/названия компаний на псевдонимы. Числа
 *     (суммы, количества) не трогает.
 *  4. Собирает итоговый SafeCaseJson — только поля, определённые в этой
 *     структуре; всё остальное (сырые id, счета, ИНН) физически
 *     некуда положить, структура сама это гарантирует.
 *
 * ИЗВЕСТНОЕ ОГРАНИЧЕНИЕ ЭТОЙ РЕАЛИЗАЦИИ: замена работает через точное
 * совпадение подстроки (String.replace), отсортированное от самого
 * длинного имени к самому короткому, чтобы избежать частичных замен
 * (см. комментарий в PseudonymRegistry.pseudonymize). Это НЕ защищает
 * от следующих случаев:
 *  - опечатки/вариации написания реального имени в разных источниках
 *    (например "Каримов А." в одном месте и "Каримов Алишер" в другом
 *    считаются РАЗНЫМИ строками и не свяжутся автоматически);
 *  - реальное имя клиента, случайно совпадающее с общеупотребимым словом
 *    в другом контексте (маловероятно для полных ФИО/названий компаний,
 *    но не невозможно).
 * Обязательный unit-тест-инвариант (см. AI_LAYER_ARCHITECTURE.md §3):
 * сериализованный SafeCaseJson для golden case не должен содержать НИ
 * ОДНОГО реального ФИО/названия компании — тест должен проверяться на
 * КОНКРЕТНЫХ данных golden case, а не полагаться только на общую логику
 * этого класса.
 */
@Component
public class SafeJsonMapper {

    public SafeCaseJson toSafeJson(
            RiskResult risk,
            ExplanationDto explanation,
            EvidenceBundle evidenceBundle,
            DossierDto dossier) {

        PseudonymRegistry registry = buildPseudonymRegistry(dossier);

        SafeCaseJson.ClientProfile clientProfile = new SafeCaseJson.ClientProfile(
                registry.clientPseudonym(),
                dossier.clientType(),
                yearsWithBank(dossier),
                dossier.accountIds().size(),
                !dossier.pastAlerts().isEmpty()
        );

        List<SafeCaseJson.Reason> reasons = explanation.reasons().stream()
                .map(r -> new SafeCaseJson.Reason(
                        r.factor(),
                        r.contribution(),
                        registry.pseudonymize(r.detail())
                ))
                .toList();

        List<SafeCaseJson.SafeEvidence> evidence = evidenceBundle.items().stream()
                .map(e -> new SafeCaseJson.SafeEvidence(
                        e.type(),
                        registry.pseudonymize(e.title()),
                        pseudonymizeAggregates(e.details(), registry)
                ))
                .toList();

        return new SafeCaseJson(risk.score(), risk.level(), clientProfile, reasons, evidence);
    }

    private int yearsWithBank(DossierDto dossier) {
        if (dossier.registrationDate() == null) {
            return 0;
        }
        return java.time.Period.between(dossier.registrationDate(), java.time.LocalDate.now()).getYears();
    }

    /**
     * Прогоняет aggregates (Evidence.details / Map<String,Object>) через
     * замену имён ТОЛЬКО в значениях типа String — числа (суммы,
     * количества, id) остаются как есть, они не несут PII сами по себе
     * (id транзакции без сопоставления с реальным клиентом бесполезен
     * для деанонимизации, а суммы нужны LLM для содержательного текста).
     */
    private Map<String, Object> pseudonymizeAggregates(Map<String, Object> details, PseudonymRegistry registry) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                result.put(entry.getKey(), registry.pseudonymize(s));
            } else if (value instanceof List<?> list) {
                List<Object> pseudonymizedList = new ArrayList<>();
                for (Object item : list) {
                    pseudonymizedList.add(item instanceof String s ? registry.pseudonymize(s) : item);
                }
                result.put(entry.getKey(), pseudonymizedList);
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /**
     * Обходит ВСЕХ субъектов кейса, доступных в DossierDto, и назначает
     * псевдонимы. Порядок обхода фиксированный (relations, затем
     * relatedCompanies, затем moneyCycles.pathLabels), чтобы одинаковый
     * DossierDto всегда давал одинаковую нумерацию — это важно для
     * воспроизводимости кейса при аудите (см. Audit Log requirement).
     */
    private PseudonymRegistry buildPseudonymRegistry(DossierDto dossier) {
        PseudonymRegistry registry = new PseudonymRegistry();

        // Исходный клиент — ВСЕГДА К-1, назначается первым, безусловно.
        registry.registerClient(dossier.fullName());

        for (RelationView r : dossier.relations()) {
            if ("client".equals(r.counterpartType())) {
                registry.registerClient(r.counterpartLabel());
            } else {
                registry.registerCompany(r.counterpartLabel());
            }
        }
        for (CompanyView c : dossier.relatedCompanies()) {
            registry.registerCompany(c.name());
        }
        for (var cycle : dossier.moneyCycles()) {
            for (String label : cycle.pathLabels()) {
                if (!label.equals(dossier.fullName())) {
                    registry.registerCompanyIfUnknown(label);
                }
            }
        }

        return registry;
    }

    /**
     * Инкапсулирует словарь реальное_имя -> псевдоним и подстановку в
     * произвольные строки. Не персистится никуда за пределы одного
     * вызова toSafeJson() — таблица соответствия "живёт только на
     * бэкенде", как того требует спека, и здесь даже не сохраняется
     * между вызовами (пересобирается каждый раз из DossierDto, который
     * сам хранится в Case.dossierJson на бэкенде).
     */
    private static final class PseudonymRegistry {
        private final Map<String, String> realToPseudonym = new LinkedHashMap<>();
        private int clientCounter = 0;
        private int companyCounter = 0;
        private String clientPseudonym;

        void registerClient(String realName) {
            if (realName == null || realToPseudonym.containsKey(realName)) {
                return;
            }
            clientCounter++;
            String pseudonym = "Клиент К-" + clientCounter;
            realToPseudonym.put(realName, pseudonym);
            if (clientPseudonym == null) {
                clientPseudonym = pseudonym;
            }
        }

        void registerCompany(String realName) {
            if (realName == null || realToPseudonym.containsKey(realName)) {
                return;
            }
            companyCounter++;
            realToPseudonym.put(realName, "Компания С-" + companyCounter);
        }

        void registerCompanyIfUnknown(String realName) {
            if (realName == null || realToPseudonym.containsKey(realName)) {
                return;
            }
            registerCompany(realName);
        }

        String clientPseudonym() {
            return clientPseudonym != null ? clientPseudonym : "Клиент К-1";
        }

        /**
         * Заменяет ВСЕ известные реальные имена на псевдонимы в произвольной строке.
         *
         * КРИТИЧНО ДЛЯ БЕЗОПАСНОСТИ: сортируем по убыванию длины имени перед
         * заменой. Если этого не делать, короткое имя ("Иван") может быть
         * подстрокой более длинного ("Иванов Иван Иванович") и заменится
         * первым, оставив кусок реального ФИО в результирующей строке
         * (например "Клиент К-1ов Иван Иванович" вместо чистого "Клиент К-1") —
         * то есть частичная утечка PII вместо полной псевдонимизации.
         */
        String pseudonymize(String text) {
            if (text == null || text.isBlank()) {
                return text;
            }
            String result = text;
            List<Map.Entry<String, String>> byLengthDesc = new ArrayList<>(realToPseudonym.entrySet());
            byLengthDesc.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
            for (Map.Entry<String, String> entry : byLengthDesc) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
            return result;
        }
    }
}
