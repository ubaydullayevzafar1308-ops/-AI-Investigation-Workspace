package uz.caseintel.rules.impl;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.CycleView;
import uz.caseintel.rules.Rule;
import uz.caseintel.rules.RuleResult;
import org.springframework.stereotype.Component;

/**
 * R03 — Circular flow (круговая схема переводов, A → B → C → A).
 *
 * Классический паттерн отмывания: деньги возвращаются к исходному
 * субъекту через цепочку подставных компаний, создавая видимость
 * легитимного оборота.
 *
 * В отличие от остальных правил, это НЕ проверяет транзакции напрямую —
 * цикл в графе денежных потоков находит CycleDetector (часть Graph
 * Engine, ③) ДО Rule Engine и кладёт результат в DossierDto.moneyCycles
 * (см. комментарий у поля в DossierDto.java). R03 остаётся чистой
 * функцией: она просто читает уже готовый список циклов и берёт
 * самый крупный по обороту, если он есть.
 */
@Component
public class R03CircularFlowRule implements Rule {

    @Override
    public String code() {
        return "R03";
    }

    @Override
    public String name() {
        return "Circular flow (круговая схема переводов)";
    }

    @Override
    public int weight() {
        return 25;
    }

    @Override
    public Optional<RuleResult> check(DossierDto dossier) {
        if (dossier.moneyCycles() == null || dossier.moneyCycles().isEmpty()) {
            return Optional.empty();
        }

        CycleView biggest = dossier.moneyCycles().stream()
                .max(Comparator.comparing(CycleView::totalAmount))
                .orElseThrow();

        String path = String.join(" → ", biggest.pathLabels());
        String explanation = "Обнаружена круговая схема переводов: %s, оборот %s (%d операций)."
                .formatted(path, formatAmount(biggest.totalAmount()), biggest.transactionCount());

        return Optional.of(new RuleResult(
                code(), name(), weight(),
                Map.of(
                        "path", biggest.pathLabels(),
                        "total_amount", biggest.totalAmount(),
                        "transaction_count", biggest.transactionCount(),
                        "cycles_found", dossier.moneyCycles().size()
                ),
                explanation
        ));
    }

    private static String formatAmount(BigDecimal amount) {
        return "%,.0f UZS".formatted(amount).replace(",", " ");
    }
}
