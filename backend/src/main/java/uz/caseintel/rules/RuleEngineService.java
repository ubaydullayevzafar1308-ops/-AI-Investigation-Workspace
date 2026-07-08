package uz.caseintel.rules;

import java.util.List;
import uz.caseintel.casebuilder.dto.DossierDto;
import org.springframework.stereotype.Service;

/**
 * Rule Engine (② в пайплайне). Прогоняет DossierDto через все
 * зарегистрированные правила (R01..R10) и возвращает список сработавших.
 *
 * Список Rule инжектится Spring'ом автоматически — каждая реализация в
 * rules.impl помечена @Component и попадает сюда без ручной регистрации.
 * Добавление нового правила = один новый класс, ноль изменений здесь.
 */
@Service
public class RuleEngineService {

    /**
     * Версия набора правил — пишется в AuditLog.rulesVersion при каждом
     * прогоне (см. ARCHITECTURE.md §12). Бампать вручную при изменении
     * состава или весов правил, чтобы старые кейсы оставались
     * воспроизводимы относительно версии правил, с которой были собраны.
     */
    public static final String RULES_VERSION = "v1.0";

    private final List<Rule> rules;

    public RuleEngineService(List<Rule> rules) {
        this.rules = rules;
    }

    public List<RuleResult> runAll(DossierDto dossier) {
        return rules.stream()
                .map(rule -> rule.check(dossier))
                .flatMap(java.util.Optional::stream)
                .toList();
    }
}
