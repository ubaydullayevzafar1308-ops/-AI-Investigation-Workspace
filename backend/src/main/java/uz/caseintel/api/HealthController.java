package uz.caseintel.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Простой health-check, чтобы проверить, что приложение и подключение
 * к базе (через Flyway/JPA автоконфигурацию) поднимаются корректно.
 *
 * curl http://localhost:8080/api/health -> {"status":"ok"}
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
