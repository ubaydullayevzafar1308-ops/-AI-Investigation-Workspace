package uz.caseintel.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфиг генератора синтетических данных (см. ARCHITECTURE.md §16).
 * Активен только в профиле seed (--spring.profiles.active=seed).
 */
@ConfigurationProperties(prefix = "seed")
public record SeedProperties(
        int clientCount,
        int companyCount,
        int backgroundTransactionCount,
        long randomSeed
) {
    public SeedProperties {
        if (clientCount <= 0) {
            clientCount = 5000;
        }
        if (companyCount <= 0) {
            companyCount = 500;
        }
        if (backgroundTransactionCount <= 0) {
            backgroundTransactionCount = 100_000;
        }
        if (randomSeed == 0) {
            randomSeed = 42L;
        }
    }
}
