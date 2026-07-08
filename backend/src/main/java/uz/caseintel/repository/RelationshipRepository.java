package uz.caseintel.repository;

import java.util.List;
import uz.caseintel.entity.Relationship;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationshipRepository extends JpaRepository<Relationship, Long> {

    /**
     * Прямые связи субъекта (глубина 1) — используется при сидировании
     * и в местах, где не нужен полный рекурсивный подграф.
     * Полный подграф глубины 2 для Graph Engine (③) строится отдельным
     * нативным CTE-запросом (см. ARCHITECTURE.md §7), а не через этот метод.
     */
    List<Relationship> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    List<Relationship> findByTargetTypeAndTargetId(String targetType, Long targetId);
}
