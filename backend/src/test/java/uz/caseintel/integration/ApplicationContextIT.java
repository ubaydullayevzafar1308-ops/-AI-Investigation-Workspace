package uz.caseintel.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * САМЫЙ БАЗОВЫЙ, но самый важный тест во всём проекте: поднимает
 * реальную Postgres (Testcontainers), даёт Flyway накатить все
 * миграции (V1-V3), даёт Hibernate провалидировать все 11 entity
 * против реальных таблиц (ddl-auto: validate — см. application.yml).
 *
 * Если этот тест падает — где-то разошлись имя/тип колонки между
 * entity и SQL-миграцией, и НИЧЕГО остальное в проекте работать не
 * может, пока это не исправлено.
 *
 * ВАЖНО: этот тест требует Docker на машине, где он запускается.
 * Testcontainers сам скачает образ postgres:16-alpine и поднимет
 * контейнер. В песочнице, где писался этот код, Docker Hub был
 * недоступен (сетевой allowlist), поэтому этот тест НЕ был прогнан
 * до коммита — синтаксически он корректен (собран по официальному
 * паттерну Spring Boot 3.1+ @ServiceConnection), но первый реальный
 * прогон должен произойти на твоей машине.
 */
@Testcontainers
@SpringBootTest
class ApplicationContextIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoadsAndAllMigrationsApply() {
        Integer clientsTableExists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'clients'
            """, Integer.class);
        assertThat(clientsTableExists).isEqualTo(1);

        Integer featureFlagsSeeded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM feature_flags", Integer.class);
        assertThat(featureFlagsSeeded).isEqualTo(5);
    }
}
