// Package api — REST-слой (chi). Эндпоинты по ARCHITECTURE.md §14.
package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/tiredjon/cbu/backend/internal/audit"
	"github.com/tiredjon/cbu/backend/internal/casebuilder"
	"github.com/tiredjon/cbu/backend/internal/graph"
	"github.com/tiredjon/cbu/backend/internal/llm"
	"github.com/tiredjon/cbu/backend/internal/repo"
	"github.com/tiredjon/cbu/backend/internal/report"
)

type Handler struct {
	pool        *pgxpool.Pool
	q           *repo.Queries
	caseBuilder *casebuilder.Service
	graph       *graph.Engine
	llm         *llm.Service
	reportGen   *report.Service
	audit       *audit.Service

	hoursSavedPerCase float64
}

type Deps struct {
	Pool        *pgxpool.Pool
	CaseBuilder *casebuilder.Service
	Graph       *graph.Engine
	LLM         *llm.Service
	ReportGen   *report.Service
	Audit       *audit.Service
}

func NewHandler(d Deps) *Handler {
	return &Handler{
		pool:              d.Pool,
		q:                 repo.New(d.Pool),
		caseBuilder:       d.CaseBuilder,
		graph:             d.Graph,
		llm:               d.LLM,
		reportGen:         d.ReportGen,
		audit:             d.Audit,
		hoursSavedPerCase: 3.5,
	}
}

func chiURLParam(r *http.Request, key string) string { return chi.URLParam(r, key) }

func (h *Handler) Router(corsOrigin string) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.Recoverer)
	r.Use(corsMiddleware(corsOrigin))

	r.Route("/api", func(r chi.Router) {
		r.Get("/health", handle(h.health))

		r.Get("/alerts", handle(h.listAlerts))
		r.Post("/alerts/{id}/investigate", handle(h.investigate))

		r.Get("/cases", handle(h.listCases))
		r.Get("/cases/{id}", handle(h.getCase))
		r.Get("/cases/{id}/evidence", handle(h.getEvidence))
		r.Get("/cases/{id}/explanation", handle(h.getExplanation))
		r.Get("/cases/{id}/graph", handle(h.getGraph))
		r.Get("/cases/{id}/report", handle(h.getReport))
		r.Put("/cases/{id}/report", handle(h.updateReport))
		r.Get("/cases/{id}/audit", handle(h.getAudit))
		r.Get("/cases/{id}/audit/{eventId}", handle(h.getAuditEvent))
		r.Patch("/cases/{id}/decision", handle(h.decide))
		r.Post("/cases/{id}/chat", handle(h.chat))

		r.Get("/modules", handle(h.listModules))
		r.Get("/dashboard/metrics", handle(h.dashboardMetrics))
	})

	return r
}

func corsMiddleware(origin string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "*")
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
