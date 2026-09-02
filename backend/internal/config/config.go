// Package config собирает настройки приложения из переменных окружения.
// Значения по умолчанию совпадают с прежним application.yml Spring-версии.
package config

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	// Postgres DSN в формате pgx (postgres://user:pass@host:port/db).
	DatabaseURL string
	// HTTP-порт сервера.
	Port string
	// CORS origin фронтенда (Vite dev server).
	CORSOrigin string

	LLM LLMConfig
	// Параметры генератора синтетических данных (используются cmd/seed).
	Seed SeedConfig
}

type LLMConfig struct {
	Provider     string // claude | openai | gemini | local | stub
	Model        string
	BaseURL      string
	APIKey       string
	CacheEnabled bool
	CacheDir     string
	Timeout      time.Duration
}

type SeedConfig struct {
	ClientCount            int
	CompanyCount           int
	BackgroundTransactions int
	RandomSeed             int64
}

// Load читает конфигурацию. SPRING_DATASOURCE_* поддержаны для
// совместимости со старым docker-compose.yml.
func Load() Config {
	dbURL := os.Getenv("DATABASE_URL")
	if dbURL == "" {
		dbURL = springDatasourceURL()
	}

	timeout := 60 * time.Second
	if v := os.Getenv("LLM_TIMEOUT_SECONDS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			timeout = time.Duration(n) * time.Second
		}
	}

	return Config{
		DatabaseURL: dbURL,
		Port:        envDefault("PORT", "8080"),
		CORSOrigin:  envDefault("CORS_ORIGIN", "http://localhost:5173"),
		LLM: LLMConfig{
			Provider:     envDefault("LLM_PROVIDER", "stub"),
			Model:        envDefault("LLM_MODEL", "template-v1.0"),
			BaseURL:      os.Getenv("LLM_BASE_URL"),
			APIKey:       os.Getenv("LLM_API_KEY"),
			CacheEnabled: envBool("LLM_CACHE_ENABLED", true),
			CacheDir:     envDefault("LLM_CACHE_DIR", "./llm-cache"),
			Timeout:      timeout,
		},
		Seed: SeedConfig{
			ClientCount:            envInt("SEED_CLIENT_COUNT", 5000),
			CompanyCount:           envInt("SEED_COMPANY_COUNT", 500),
			BackgroundTransactions: envInt("SEED_BACKGROUND_TX_COUNT", 100000),
			RandomSeed:             int64(envInt("SEED_RANDOM_SEED", 42)),
		},
	}
}

// springDatasourceURL конвертирует старый jdbc:postgresql://host:port/db
// (+ SPRING_DATASOURCE_USERNAME/PASSWORD) в pgx-совместимый DSN.
func springDatasourceURL() string {
	jdbc := os.Getenv("SPRING_DATASOURCE_URL")
	user := envDefault("SPRING_DATASOURCE_USERNAME", "app")
	pass := envDefault("SPRING_DATASOURCE_PASSWORD", "app_secret")
	hostPortDB := "localhost:5432/case_intelligence"
	const prefix = "jdbc:postgresql://"
	if len(jdbc) > len(prefix) && jdbc[:len(prefix)] == prefix {
		hostPortDB = jdbc[len(prefix):]
	}
	return "postgres://" + user + ":" + pass + "@" + hostPortDB + "?sslmode=disable"
}

func envDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envBool(key string, def bool) bool {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		return def
	}
	return b
}

func envInt(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}
