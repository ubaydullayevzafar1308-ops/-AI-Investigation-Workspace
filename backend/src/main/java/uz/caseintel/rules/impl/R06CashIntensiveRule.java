package uz.caseintel.rules.impl;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.TxView;
import uz.caseintel.entity.Transaction;
import uz.caseintel.rules.Rule;
import uz.caseintel.rules.RuleResult;
import org.springframework.stereotype.Component;

/**
 * R06 — Cash intensive (высокая доля наличных операций).
 *
 * Доля cash_in/cash_out операций выше порога в общем обороте клиента —
 * наличные хуже отслеживаются и чаще используются для разрыва
 * бумажного следа в схемах отмывания, особенно в сочетании с другими
 * правилами (структуринг, транзит).
 *
 * Считается по сумме (amount), а не по количеству операций — так доля
 * отражает реальный оборот, а не просто много мелких кассовых чеков.
 * Требует минимального количества операций (MIN_TX_COUNT), чтобы не
 * триггериться на клиенте с одной-двумя операциями за всю историю.
 */
@Component
public class R06CashIntensiveRule implements Rule {

    static final BigDecimal CASH_SHARE_THRESHOLD = new BigDecimal("0.70");
    static final int MIN_TX_COUNT = 5;

    @Override
    public String code() {
        return "R06";
    }

    @Override
    public String name() {
        return "Cash intensive (высокая доля наличных)";
    }

    @Override
    public int weight() {
        return 10;
    }

    @Override
    public Optional<RuleResult> check(DossierDto dossier) {
        List<TxView> all = dossier.transactions();
        if (all.size() < MIN_TX_COUNT) {
            return Optional.empty();
        }

        BigDecimal totalAmount = all.stream()
                .map(TxView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalAmount.signum() == 0) {
            return Optional.empty();
        }

        BigDecimal cashAmount = all.stream()
                .filter(tx -> Transaction.TYPE_CASH_IN.equals(tx.txType())
                        || Transaction.TYPE_CASH_OUT.equals(tx.txType()))
                .map(TxView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal share = cashAmount.divide(totalAmount, MathContext.DECIMAL64);

        if (share.compareTo(CASH_SHARE_THRESHOLD) < 0) {
            return Optional.empty();
        }

        int sharePercent = share.multiply(BigDecimal.valueOf(100)).intValue();
        String explanation = "%d%% оборота клиента приходится на наличные операции (%s из %s) — нетипично высокая доля."
                .formatted(sharePercent, formatAmount(cashAmount), formatAmount(totalAmount));

        return Optional.of(new RuleResult(
                code(), name(), weight(),
                Map.of(
                        "cash_share_percent", sharePercent,
                        "cash_amount", cashAmount,
                        "total_amount", totalAmount
                ),
                explanation
        ));
    }

    private static String formatAmount(BigDecimal amount) {
        return "%,.0f UZS".formatted(amount).replace(",", " ");
    }
}
