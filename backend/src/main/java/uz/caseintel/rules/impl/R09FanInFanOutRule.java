package uz.caseintel.rules.impl;

import java.math.BigDecimal;
import java.math.MathContext;
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
 * R09 — Fan-in / Fan-out (много мелких входящих → один крупный исходящий).
 *
 * Классический паттерн "collector account": на счёт поступает много
 * небольших сумм (часто от разных источников — дробление на входе), а
 * затем единой крупной операцией всё выводится — типично для схем сбора
 * денег от "дропов" с последующей консолидацией и выводом.
 *
 * Ищет исходящую операцию, сумма которой близка (в пределах
 * FAN_IN_TOLERANCE) к сумме нескольких (>= MIN_FAN_IN_COUNT) входящих
 * операций за предшествующее окно FAN_IN_WINDOW.
 */
@Component
public class R09FanInFanOutRule implements Rule {

    static final Duration FAN_IN_WINDOW = Duration.ofDays(2);
    static final int MIN_FAN_IN_COUNT = 4;
    static final BigDecimal FAN_IN_TOLERANCE = new BigDecimal("0.15"); // ±15%

    @Override
    public String code() {
        return "R09";
    }

    @Override
    public String name() {
        return "Fan-in / Fan-out (сбор мелких сумм с последующим выводом)";
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

        for (TxView outgoing : sorted) {
            if (!TxView.DIRECTION_OUT.equals(outgoing.direction())) {
                continue;
            }
            var windowStart = outgoing.timestamp().minus(FAN_IN_WINDOW);

            List<TxView> incomingBefore = sorted.stream()
                    .filter(tx -> TxView.DIRECTION_IN.equals(tx.direction()))
                    .filter(tx -> !tx.timestamp().isBefore(windowStart))
                    .filter(tx -> tx.timestamp().isBefore(outgoing.timestamp()))
                    .toList();

            if (incomingBefore.size() < MIN_FAN_IN_COUNT) {
                continue;
            }

            BigDecimal incomingTotal = incomingBefore.stream()
                    .map(TxView::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (isCloseAmount(outgoing.amount(), incomingTotal)) {
                String explanation = "%d входящих операций на общую сумму %s были собраны и выведены одной операцией на %s."
                        .formatted(incomingBefore.size(), formatAmount(incomingTotal), formatAmount(outgoing.amount()));

                return Optional.of(new RuleResult(
                        code(), name(), weight(),
                        Map.of(
                                "outgoing_transaction_id", outgoing.transactionId(),
                                "incoming_transaction_ids", incomingBefore.stream().map(TxView::transactionId).toList(),
                                "incoming_count", incomingBefore.size(),
                                "incoming_total", incomingTotal,
                                "outgoing_amount", outgoing.amount()
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
        BigDecimal tolerance = b.abs().multiply(FAN_IN_TOLERANCE, MathContext.DECIMAL64);
        return diff.compareTo(tolerance) <= 0;
    }

    private static String formatAmount(BigDecimal amount) {
        return "%,.0f UZS".formatted(amount).replace(",", " ");
    }
}
