// Command server — HTTP API AI Case Intelligence Platform.
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/tiredjon/cbu/backend/internal/api"
	"github.com/tiredjon/cbu/backend/internal/audit"
	"github.com/tiredjon/cbu/backend/internal/casebuilder"
	"github.com/tiredjon/cbu/backend/internal/config"
	"github.com/tiredjon/cbu/backend/internal/datacollector"
	"github.com/tiredjon/cbu/backend/internal/db"
	"github.com/tiredjon/cbu/backend/internal/graph"
	"github.com/tiredjon/cbu/backend/internal/llm"
	"github.com/tiredjon/cbu/backend/internal/report"
	"github.com/tiredjon/cbu/backend/internal/rules"
)

func main() {
	cfg := config.Load()

	ctx := context.Background()
	pool, err := db.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("db connect: %v", err)
	}
	defer pool.Close()
	log.Println("db connected, migrations applied")

	graphEngine := graph.New()
	collector := datacollector.New(graphEngine)
	ruleEngine := rules.NewEngine()
	auditSvc := audit.New()
	reportGen := report.New()

	adapter, err := llm.NewAdapter(cfg.LLM)
	if err != nil {
		log.Fatalf("llm adapter: %v", err)
	}
	llmSvc := llm.NewService(adapter, llm.NewResponseCache(cfg.LLM.CacheDir, cfg.LLM.CacheEnabled), auditSvc)
	log.Printf("llm provider: %s (%s)", adapter.ProviderName(), adapter.ModelName())

	caseBuilder := casebuilder.New(pool, collector, graphEngine, ruleEngine, llmSvc, reportGen, auditSvc)

	handler := api.NewHandler(api.Deps{
		Pool:        pool,
		CaseBuilder: caseBuilder,
		Graph:       graphEngine,
		LLM:         llmSvc,
		ReportGen:   reportGen,
		Audit:       auditSvc,
	})

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           handler.Router(cfg.CORSOrigin),
		ReadHeaderTimeout: 10 * time.Second,
	}

	go func() {
		log.Printf("listening on :%s", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("http server: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	log.Println("shutting down...")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}
