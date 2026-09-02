// Command seed — генератор синтетических данных (ARCHITECTURE.md §16).
//
// PHASE 2: полный порт из uz.caseintel.seed.* (фон ~5000 клиентов /
// ~500 компаний / ~100k транзакций + 6 срежиссированных схем + golden
// case) ещё не выполнен. Сейчас — заглушка, которая только применяет
// миграции и сообщает об этом.
package main

import (
	"context"
	"log"

	"github.com/tiredjon/cbu/backend/internal/config"
	"github.com/tiredjon/cbu/backend/internal/db"
)

func main() {
	cfg := config.Load()
	pool, err := db.Connect(context.Background(), cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("db connect: %v", err)
	}
	defer pool.Close()

	log.Println("migrations applied.")
	log.Println("seed generator port pending (Phase 2) — см. cmd/seed/main.go")
}
