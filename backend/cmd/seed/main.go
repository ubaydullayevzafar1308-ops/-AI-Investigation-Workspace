// Command seed — генератор синтетических данных (ARCHITECTURE.md §16).
//
// Запуск ТОЛЬКО на пустой базе (свежие миграции без данных):
//
//	DATABASE_URL=... go run ./cmd/seed
//
// Повторный запуск на непустой базе даст конфликты id — пересоздайте БД.
package main

import (
	"context"
	"log"

	"github.com/tiredjon/cbu/backend/internal/config"
	"github.com/tiredjon/cbu/backend/internal/db"
	"github.com/tiredjon/cbu/backend/internal/seed"
)

func main() {
	cfg := config.Load()

	ctx := context.Background()
	pool, err := db.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("db connect: %v", err)
	}
	defer pool.Close()

	if err := seed.Run(ctx, pool, cfg.Seed); err != nil {
		log.Fatalf("seed: %v", err)
	}
}
