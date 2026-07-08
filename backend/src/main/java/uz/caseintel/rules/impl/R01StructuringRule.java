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
 * R01 — Structuring (дробление сумм).
 *
 * Ловит намеренное дробление крупной суммы на несколько операций чуть
 * ниже порога обязательного контроля, совершённых за короткое окно —
 * классический паттерн ухода от отчётности перед банком/регулятором.
 *
 * Правило детерминированное: пороги — константы, окно ищется простым
 * sliding-window по отсортированным по времени транзакциям клиента
 * (transactions в DossierDto уже отсортированы Data Collector'ом).
 */
@Component
public class R01StructuringRule implements Rule {

    /** Порог обязательного контроля (условно для демо, UZS). */
    static final BigDecimal THRESHOLD = new BigDecimal("100000000");

    /** Нижняя граница диапазона "подозрительно близко к порогу" — доля от THRESHOLD. */
    static final BigDecimal NEAR_RATIO = new BigDecimal("0.90");

    static final int WINDOW_DAYS = 3;
    static final int MIN_COUNT = 3;

    @Override
    public String code() {
        return "R01";
    }

    @Override
    public String name() {
        return "Structuring (дробление сумм)";
    }

    @Override
    public int weight() {
        return 20;
    }

    @Override
    public Optional<RuleResult> check(DossierDto dossier) {
        BigDecimal lowerBound = THRESHOLD.multiply(NEAR_RATIO);

        List<TxView> candidates = dossier.transactions().stream()
                .filter(tx -> tx.amount().compareTo(lowerBound) >= 0
                        && tx.amount().compareTo(THRESHOLD) < 0)
                .sorted((a, b) -> a.timestamp().compareTo(b.timestamp()))
                .toList();

        Optional<List<TxView>> windowHit = findWindowWithMinCount(candidates);
        if (windowHit.isEmpty()) {
            return Optional.empty();
        }

        List<TxView> hitGroup = windowHit.get();
        List<Long> txIds = hitGroup.stream().map(TxView::transactionId).toList();
        BigDecimal totalAmount = hitGroup.stream()
                .map(TxView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String explanation = "Обнаружено %d операций на суммы %s–%s (чуть ниже порога обязательного контроля %s) за %d дня — признак умышленного дробления."
                .formatted(
                        hitGroup.size(),
                        formatAmount(lowerBound),
                        formatAmount(THRESHOLD),
                        formatAmount(THRESHOLD),
                        WINDOW_DAYS);

        return Optional.of(new RuleResult(
                code(), name(), weight(),
                Map.of(
                        "transaction_ids", txIds,
                        "total_amount", totalAmount,
                        "count", hitGroup.size(),
                        "window_days", WINDOW_DAYS
                ),
                explanation
        ));
    }

    /**
     * Ищет первое скользящее окно шириной WINDOW_DAYS, в котором
     * набирается MIN_COUNT операций-кандидатов. Кандидаты уже
     * отсортированы по времени (см. check()).
     */
    private Optional<List<TxView>> findWindowWithMinCount(List<TxView> sortedCandidates) {
        for (int start = 0; start < sortedCandidates.size(); start++) {
            var windowStart = sortedCandidates.get(start).timestamp();
            var windowEnd = windowStart.plus(Duration.ofDays(WINDOW_DAYS));

            List<TxView> inWindow = sortedCandidates.stream()
                    .skip(start)
                    .takeWhile(tx -> !tx.timestamp().isAfter(windowEnd))
                    .toList();

            if (inWindow.size() >= MIN_COUNT) {
                return Optional.of(inWindow);
            }
        }
        return Optional.empty();
    }

    private static String formatAmount(BigDecimal amount) {
        return "%,.0f UZS".formatted(amount).replace(",", " ");
    }
}
