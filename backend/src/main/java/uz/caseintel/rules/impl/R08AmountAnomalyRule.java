package uz.caseintel.rules.impl;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.TxView;
import uz.caseintel.rules.Rule;
import uz.caseintel.rules.RuleResult;
import org.springframework.stereotype.Component;

/**
 * R08 — Amount anomaly (сумма в 5+ раз выше исторического профиля).
 *
 * Резкий скачок суммы операции относительно обычного поведения клиента —
 * легитимные клиенты обычно имеют предсказуемый разброс сумм; операция,
 * в разы превышающая типичную, заслуживает отдельного внимания, даже
 * если сама по себе не нарушает других правил.
 *
 * Профиль строится по всем транзакциям КРОМЕ последней (baseline),
 * последняя транзакция (по времени) сравнивается с медианой baseline —
 * медиана устойчивее к выбросам, чем среднее, что важно, если в истории
 * уже была одна аномалия ранее.
 */
@Component
public class R08AmountAnomalyRule implements Rule {

    static final BigDecimal ANOMALY_MULTIPLIER = new BigDecimal("5");
    static final int MIN_HISTORY_COUNT = 5;

    @Override
    public String code() {
        return "R08";
    }

    @Override
    public String name() {
        return "Amount anomaly (аномальная сумма операции)";
    }

    @Override
    public int weight() {
        return 10;
    }

    @Override
    public Optional<RuleResult> check(DossierDto dossier) {
        List<TxView> sorted = dossier.transactions().stream()
                .sorted((a, b) -> a.timestamp().compareTo(b.timestamp()))
                .toList();

        if (sorted.size() < MIN_HISTORY_COUNT + 1) {
            return Optional.empty();
        }

        TxView latest = sorted.get(sorted.size() - 1);
        List<BigDecimal> history = sorted.subList(0, sorted.size() - 1).stream()
                .map(TxView::amount)
                .sorted()
                .toList();

        BigDecimal median = median(history);
        if (median.signum() == 0) {
            return Optional.empty();
        }

        BigDecimal ratio = latest.amount().divide(median, MathContext.DECIMAL64);
        if (ratio.compareTo(ANOMALY_MULTIPLIER) < 0) {
            return Optional.empty();
        }

        String explanation = "Сумма последней операции (%s) в %.1f раз выше типичной для клиента (медиана %s)."
                .formatted(formatAmount(latest.amount()), ratio.doubleValue(), formatAmount(median));

        return Optional.of(new RuleResult(
                code(), name(), weight(),
                Map.of(
                        "transaction_id", latest.transactionId(),
                        "amount", latest.amount(),
                        "historical_median", median,
                        "ratio", ratio.setScale(1, java.math.RoundingMode.HALF_UP)
                ),
                explanation
        ));
    }

    private static BigDecimal median(List<BigDecimal> sortedValues) {
        int n = sortedValues.size();
        if (n % 2 == 1) {
            return sortedValues.get(n / 2);
        }
        BigDecimal lower = sortedValues.get(n / 2 - 1);
        BigDecimal upper = sortedValues.get(n / 2);
        return lower.add(upper).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
    }

    private static String formatAmount(BigDecimal amount) {
        return "%,.0f UZS".formatted(amount).replace(",", " ");
    }
}
