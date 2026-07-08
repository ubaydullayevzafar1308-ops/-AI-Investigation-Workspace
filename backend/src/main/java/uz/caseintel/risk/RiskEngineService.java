package uz.caseintel.risk;

import uz.caseintel.evidence.Evidence;
import uz.caseintel.evidence.EvidenceBundle;
import org.springframework.stereotype.Service;

/**
 * Risk Engine (⑤ в пайплайне) — отдельный сервис. Считает score ТОЛЬКО
 * из EvidenceBundle (не из графа/правил напрямую) — это намеренная
 * развязка: Risk Engine не знает, откуда взялись веса, он просто
 * суммирует их и применяет усилители. См. ARCHITECTURE.md §9.
 *
 * Детерминированность здесь — ключевой аргумент для судей ("почему AI
 * не ошибётся": сам score считает не AI, а этот простой суммирующий
 * сервис).
 */
@Service
public class RiskEngineService {

    /** "Повторный фигурант" — усилитель, если у клиента уже были прошлые алерты. */
    public static final int REPEAT_OFFENDER_BONUS = 10;

    public static final int HIGH_THRESHOLD = 60;
    public static final int MEDIUM_THRESHOLD = 30;

    public RiskResult score(EvidenceBundle evidence) {
        int raw = evidence.items().stream().mapToInt(Evidence::weight).sum();

        boolean repeatOffender = evidence.items().stream()
                .anyMatch(e -> Evidence.TYPE_PREVIOUS_ALERT.equals(e.type()));
        if (repeatOffender) {
            raw += REPEAT_OFFENDER_BONUS;
        }

        int score = Math.min(100, raw);
        String level = score >= HIGH_THRESHOLD ? RiskResult.LEVEL_HIGH
                : score >= MEDIUM_THRESHOLD ? RiskResult.LEVEL_MEDIUM
                : RiskResult.LEVEL_LOW;

        return new RiskResult(score, level);
    }
}
