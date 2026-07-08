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
 * R05 — Dormant awakening (спящий счёт внезапно ожил).
 *
 * Счёт без активности 6+ месяцев, на котором внезапно появляется
 * операция, — подозрительно: легитимные счета обычно имеют более
 * равномерную активность, а долгое затишье перед разовой операцией
 * характерно для счетов, специально "придержанных" для одной схемы.
 *
 * Ищет по отсортированным транзакциям клиента первый разрыв (gap)
 * между двумя последовательными операциями длиной >= DORMANT_PERIOD;
 * триггером считается операция ПОСЛЕ разрыва (та, что "разбудила" счёт).
 */
@Component
public class R05DormantAwakeningRule implements Rule {

    static final Duration DORMANT_PERIOD = Duration.ofDays(180);

    @Override
    public String code() {
        return "R05";
    }

    @Override
    public String name() {
        return "Dormant awakening (спящий счёт ожил)";
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

        if (sorted.size() < 2) {
            return Optional.empty();
        }

        for (int i = 1; i < sorted.size(); i++) {
            var previous = sorted.get(i - 1);
            var current = sorted.get(i);
            var gap = Duration.between(previous.timestamp(), current.timestamp());

            if (gap.compareTo(DORMANT_PERIOD) >= 0) {
                long gapDays = gap.toDays();
                String explanation = "Счёт не имел операций %d дней, затем внезапно появилась операция на %s."
                        .formatted(gapDays, formatAmount(current.amount()));

                return Optional.of(new RuleResult(
                        code(), name(), weight(),
                        Map.of(
                                "dormant_days", gapDays,
                                "awakening_transaction_id", current.transactionId(),
                                "last_activity_transaction_id", previous.transactionId(),
                                "amount", current.amount()
                        ),
                        explanation
                ));
            }
        }
        return Optional.empty();
    }

    private static String formatAmount(BigDecimal amount) {
        return "%,.0f UZS".formatted(amount).replace(",", " ");
    }
}
