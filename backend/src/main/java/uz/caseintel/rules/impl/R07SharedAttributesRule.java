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
 * R07 — Shared attributes (общий признак с blacklisted-субъектом).
 *
 * Общий адрес, телефон, устройство или директор с клиентом/компанией из
 * чёрного списка — сильный косвенный признак координации или подставной
 * структуры: люди/компании физически не связаны, но выдают себя общими
 * атрибутами (одно устройство для входа, один и тот же director-фасад).
 *
 * Смотрит на relations (глубина 1 вокруг клиента, DossierDto.relations),
 * отбирает только связи типа same_address/same_phone/same_device/director/
 * founder/family, где counterpart помечен blacklisted=true.
 */
@Component
public class R07SharedAttributesRule implements Rule {

    private static final List<String> RELEVANT_TYPES = List.of(
            Relationship.TYPE_SAME_ADDRESS,
            Relationship.TYPE_SAME_PHONE,
            Relationship.TYPE_SAME_DEVICE,
            Relationship.TYPE_DIRECTOR,
            Relationship.TYPE_FOUNDER,
            Relationship.TYPE_FAMILY
    );

    @Override
    public String code() {
        return "R07";
    }

    @Override
    public String name() {
        return "Shared attributes (общий признак с blacklisted-субъектом)";
    }

    @Override
    public int weight() {
        return 20;
    }

    @Override
    public Optional<RuleResult> check(DossierDto dossier) {
        List<RelationView> hits = dossier.relations().stream()
                .filter(RelationView::counterpartBlacklisted)
                .filter(r -> RELEVANT_TYPES.contains(r.relationType()))
                .toList();

        if (hits.isEmpty()) {
            return Optional.empty();
        }

        RelationView first = hits.get(0);
        String explanation = "Обнаружена связь «%s» с %s из чёрного списка."
                .formatted(relationLabel(first.relationType()), first.counterpartLabel());

        return Optional.of(new RuleResult(
                code(), name(), weight(),
                Map.of(
                        "relation_type", first.relationType(),
                        "counterpart_id", first.counterpartId(),
                        "counterpart_type", first.counterpartType(),
                        "matches_count", hits.size()
                ),
                explanation
        ));
    }

    private static String relationLabel(String relationType) {
        return switch (relationType) {
            case "same_address" -> "общий адрес";
            case "same_phone" -> "общий телефон";
            case "same_device" -> "общее устройство";
            case "director" -> "общий директор";
            case "founder" -> "общий учредитель";
            case "family" -> "родственная связь";
            default -> relationType;
        };
    }
}
