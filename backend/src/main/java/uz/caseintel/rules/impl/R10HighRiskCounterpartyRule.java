package uz.caseintel.rules.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.RelationView;
import uz.caseintel.entity.Relationship;
import uz.caseintel.rules.Rule;
import uz.caseintel.rules.RuleResult;
import org.springframework.stereotype.Component;

/**
 * R10 — High-risk counterparty (контрагент из чёрного списка).
 *
 * Прямая связь типа frequent_counterparty с blacklisted-субъектом:
 * в отличие от R07 (общие атрибуты — общий адрес/устройство/директор,
 * то есть косвенная связь), здесь речь о ПРЯМЫХ денежных операциях с
 * контрагентом, который уже находится в чёрном списке банка. Это
 * самый однозначный сигнал из всех десяти правил — отсюда наивысший вес.
 *
 * Смотрит на relations с relationType=frequent_counterparty и
 * counterpartBlacklisted=true.
 */
@Component
public class R10HighRiskCounterpartyRule implements Rule {

    @Override
    public String code() {
        return "R10";
    }

    @Override
    public String name() {
        return "High-risk counterparty (контрагент из чёрного списка)";
    }

    @Override
    public int weight() {
        return 25;
    }

    @Override
    public Optional<RuleResult> check(DossierDto dossier) {
        List<RelationView> hits = dossier.relations().stream()
                .filter(RelationView::counterpartBlacklisted)
                .filter(r -> Relationship.TYPE_FREQUENT_COUNTERPARTY.equals(r.relationType()))
                .toList();

        if (hits.isEmpty()) {
            return Optional.empty();
        }

        RelationView first = hits.get(0);
        String explanation = "Клиент регулярно проводит операции с контрагентом «%s», который находится в чёрном списке банка."
                .formatted(first.counterpartLabel());

        return Optional.of(new RuleResult(
                code(), name(), weight(),
                Map.of(
                        "counterpart_id", first.counterpartId(),
                        "counterpart_type", first.counterpartType(),
                        "matches_count", hits.size()
                ),
                explanation
        ));
    }
}
