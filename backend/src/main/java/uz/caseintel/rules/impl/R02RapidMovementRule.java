package uz.caseintel.rules.impl;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.TxView;
import uz.caseintel.rules.Rule;
import uz.caseintel.rules.RuleResult;
import org.springframework.stereotype.Component;

/**
 * R02 — Rapid movement (транзитные переводы).
 *
 * Ловит паттерн "деньги пришли и почти сразу ушли" — типичный признак
 * транзитного счёта в цепочке отмывания: средства не задерживаются,
 * счёт используется только как промежуточное звено.
 *
 * Эвристика: входящая операция (direction=in) на сумму X, за которой в
 * пределах MAX_GAP следует исходящая операция (direction=out) на сумму,
 * близкую к X (в пределах AMOUNT_TOLERANCE) — это и есть "прошло транзитом".
 */
@Component
public class R02RapidMovementRule implements Rule {

    static final Duration MAX_GAP = Duration.ofHours(6);
    static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.10"); // ±10%

    @Override
    public String code() {
        return "R02";
    }

    @Override
    public String name() {
        return "Rapid movement (транзитные переводы)";
    }

    @Override
    public int weight() {
        return 15;
    }

    @Override
    public Optional<RuleResult> check(DossierDto dossier) {
        List<TxView> sorted = dossier.transactions().stream()
                .sorted((a, b) -> a.timestamp().compareTo(b.timestamp()))
                .toList();

        for (TxView incoming : sorted) {
            if (!TxView.DIRECTION_IN.equals(incoming.direction())) {
                continue;
            }
            var gapEnd = incoming.timestamp().plus(MAX_GAP);

            Optional<TxView> matchingOutgoing = sorted.stream()
                    .filter(tx -> TxView.DIRECTION_OUT.equals(tx.direction()))
                    .filter(tx -> !tx.timestamp().isBefore(incoming.timestamp()))
                    .filter(tx -> !tx.timestamp().isAfter(gapEnd))
                    .filter(tx -> isCloseAmount(tx.amount(), incoming.amount()))
                    .findFirst();

            if (matchingOutgoing.isPresent()) {
                TxView outgoing = matchingOutgoing.get();
                long gapMinutes = Duration.between(incoming.timestamp(), outgoing.timestamp()).toMinutes();

                String explanation = "Поступление %s прошло транзитом: списание почти той же суммы (%s) через %d мин."
                        .formatted(formatAmount(incoming.amount()), formatAmount(outgoing.amount()), gapMinutes);

                return Optional.of(new RuleResult(
                        code(), name(), weight(),
                        Map.of(
                                "incoming_transaction_id", incoming.transactionId(),
                                "outgoing_transaction_id", outgoing.transactionId(),
                                "gap_minutes", gapMinutes,
                                "amount", incoming.amount()
                        ),
                        explanation
                ));
            }
        }
        return Optional.empty();
    }

    private static boolean isCloseAmount(BigDecimal a, BigDecimal b) {
        if (b.signum() == 0) {
            return a.signum() == 0;
        }
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal tolerance = b.abs().multiply(AMOUNT_TOLERANCE);
        return diff.compareTo(tolerance) <= 0;
    }

    private static String formatAmount(BigDecimal amount) {
        return "%,.0f UZS".formatted(amount).replace(",", " ");
    }
}
