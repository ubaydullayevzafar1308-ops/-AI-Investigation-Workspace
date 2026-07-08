package uz.caseintel.rules;

import java.util.Optional;
import uz.caseintel.casebuilder.dto.DossierDto;

/**
 * Контракт одного AML-правила (R01..R10, см. ARCHITECTURE.md §6).
 *
 * Правило — чистая функция: DossierDto -> Optional<RuleResult>, без
 * побочных эффектов и без прямого доступа к репозиториям/БД. Это
 * намеренное ограничение:
 *  - позволяет юнит-тестировать каждое правило синтетическим DossierDto,
 *    не поднимая Postgres/Spring-контекст;
 *  - гарантирует детерминированность: одинаковый вход всегда даёт
 *    одинаковый результат — это прямой ответ на вопрос судей
 *    "как AI не ошибётся?" (правила детерминированы, LLM их не трогает).
 *
 * Все реализации — Spring @Component в пакете rules.impl; RuleEngineService
 * подхватывает их автоматически через DI (List<Rule> инжектится Spring'ом).
 */
public interface Rule {

    /** Код правила, например "R01". Используется в RuleHit.ruleCode и Audit Log. */
    String code();

    /** Человекочитаемое имя, например "Structuring (дробление сумм)". */
    String name();

    /** Вклад в Risk Score при срабатывании (0-100), см. таблицу правил в ARCHITECTURE.md §6. */
    int weight();

    /**
     * Проверяет правило на переданном досье.
     * Optional.empty() — правило не сработало, ничего не добавляется в EvidenceBundle.
     */
    Optional<RuleResult> check(DossierDto dossier);
}
