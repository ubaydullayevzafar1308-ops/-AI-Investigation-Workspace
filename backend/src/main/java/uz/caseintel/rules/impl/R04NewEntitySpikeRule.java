package uz.caseintel.rules.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.CompanyView;
import uz.caseintel.rules.Rule;
import uz.caseintel.rules.RuleResult;
import org.springframework.stereotype.Component;

/**
 * R04 — New entity spike (крупный оборот у компании младше 90 дней).
 *
 * Свежезарегистрированная компания без истории, через которую внезапно
 * проходит крупная сумма, — типичный признак подставной фирмы,
 * созданной для конкретной разовой схемы, а не для реальной деятельности.
 *
 * Смотрит на компании из DossierDto.relatedCompanies (где клиент —
 * директор/учредитель): возраст компании считается на "сейчас"
 * (LocalDate.now()), оборот — уже агрегированное поле turnoverAmount,
 * посчитанное Data Collector'ом.
 */
@Component
public class R04NewEntitySpikeRule implements Rule {

    static final int MAX_AGE_DAYS = 90;
    static final BigDecimal TURNOVER_THRESHOLD = new BigDecimal("200000000"); // 200 млн UZS

    @Override
    public String code() {
        return "R04";
    }

    @Override
    public String name() {
        return "New entity spike (крупный оборот у новой компании)";
    }

    @Override
    public int weight() {
        return 15;
    }

    @Override
    public Optional<RuleResult> check(DossierDto dossier) {
        LocalDate today = LocalDate.now();

        List<CompanyView> hits = dossier.relatedCompanies().stream()
                .filter(c -> daysBetween(c.registrationDate(), today) <= MAX_AGE_DAYS)
                .filter(c -> c.turnoverAmount() != null
                        && c.turnoverAmount().compareTo(TURNOVER_THRESHOLD) >= 0)
                .toList();

        if (hits.isEmpty()) {
            return Optional.empty();
        }

        CompanyView worst = hits.stream()
                .max((a, b) -> a.turnoverAmount().compareTo(b.turnoverAmount()))
                .orElseThrow();

        long ageDays = daysBetween(worst.registrationDate(), today);
        String explanation = "Компания «%s» зарегистрирована %d дн. назад, но оборот по её счетам уже составил %s — нетипично для новой организации."
                .formatted(worst.name(), ageDays, formatAmount(worst.turnoverAmount()));

        return Optional.of(new RuleResult(
                code(), name(), weight(),
                Map.of(
                        "company_id", worst.companyId(),
                        "age_days", ageDays,
                        "turnover_amount", worst.turnoverAmount(),
                        "flagged_companies_count", hits.size()
                ),
                explanation
        ));
    }

    private static long daysBetween(LocalDate from, LocalDate to) {
        return java.time.temporal.ChronoUnit.DAYS.between(from, to);
    }

    private static String formatAmount(BigDecimal amount) {
        return "%,.0f UZS".formatted(amount).replace(",", " ");
    }
}
