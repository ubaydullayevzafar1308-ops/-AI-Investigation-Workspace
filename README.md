# AI Case Intelligence Platform

Платформа, автоматически превращающая банковский Alert в готовое AML-расследование (Case) с объяснимым Risk Score — аналитик только проверяет и утверждает.

## Структура

- `backend/` — **Go 1.26** (chi + pgx + встроенный мигратор). Порт с Java/Spring.
- `frontend/` — React 18 + Vite + Tailwind CSS
- `seed/` — генератор синтетических данных (порт в `backend/cmd/seed`)
- `ARCHITECTURE.md` — полная техническая спецификация
- `AI_LAYER_ARCHITECTURE.md` — спецификация LLM-слоя

## Backend (Go)

```
backend/
├── cmd/server/       — HTTP API
├── cmd/seed/         — генератор синтетических данных
└── internal/
    ├── config/       — конфиг из ENV
    ├── db/           — pgxpool + миграции (embed .sql, свой мигратор)
    ├── domain/       — доменные структуры + DTO пайплайна
    ├── repo/         — слой доступа к данным (pgx, заменяет Spring Data JPA)
    ├── datacollector/— ① сбор досье
    ├── rules/        — ② Rule Engine (10 правил R01..R10)
    ├── graph/        — ③ Graph Engine + CycleDetector
    ├── evidence/     — ④ Evidence Collector
    ├── risk/         — ⑤ Risk Engine
    ├── explain/      — ⑥ Explainability Engine
    ├── llm/          — ⑦⑧ Safe JSON + AI Adapter (claude/openai/gemini/local/stub) + кэш
    ├── report/       — ⑨ Report Generator
    ├── audit/        — Audit Log
    ├── casebuilder/  — ⭐ оркестратор пайплайна
    └── api/          — REST-контроллеры (chi)
```

### Отклонения от Spring-версии

- **sqlc не используется** — вместо него pgx с рукописными запросами: рекурсивные
  CTE Graph Engine и динамический VALUES-список подграфа всё равно не выразить в sqlc.
- **Миграции** — те же 4 .sql-файла (перенос Flyway V1..V4), применяет минимальный
  встроенный мигратор (таблица `schema_migrations`), отдельный CLI не нужен.
- **Транзакции** — `casebuilder.BuildCase` и `PATCH /decision` выполняются в одной
  `pgx.Tx`; read-only шаги движков работают на пуле.
- **Swagger** — вместо springdoc: рукописный `openapi.json` (embed) + Swagger UI
  из CDN на `/swagger-ui.html`. Сам спец доступен офлайн на `/api/openapi.json`.
- **seed** — `cmd/seed` вместо Spring-профиля `seed`; фон грузится через
  `pgx.CopyFrom`, схемы — обычными INSERT, sequences сдвигаются в конце.

## Быстрый старт: ./dev.sh

Поднимает всё окружение локально одной командой: Postgres в Docker,
backend (Maven, в фоне, лог пишется в `logs/backend.log`) и frontend
(Vite, в форграунде) — ждёт готовности БД и `/api/health` перед стартом
frontend.

```bash
./dev.sh
```

Откроется на http://localhost:5173. Ctrl+C в терминале корректно
останавливает и backend, и frontend. Требует запущенный Docker — если
он не запущен, скрипт выведет понятное сообщение и завершится, ничего
не запуская.

**Внимание:** база должна быть заполнена данными (см. п. 2.5 ниже) —
`dev.sh` только поднимает окружение, сидирование запускается отдельно.

## Запуск (по шагам, вручную)

### 1. База данных

```bash
docker compose up -d db
```

### 2. Backend

```bash
cd backend
DATABASE_URL="postgres://app:app_secret@localhost:5432/case_intelligence?sslmode=disable" \
  go run ./cmd/server
```

Миграции накатываются автоматически при старте.

Проверка: `curl http://localhost:8080/api/health` → `{"status":"ok"}`
Swagger UI: http://localhost:8080/swagger-ui.html (спец — `/api/openapi.json`)

Переменные окружения:

| ENV | По умолчанию | Назначение |
|---|---|---|
| `DATABASE_URL` | из `SPRING_DATASOURCE_*` или `postgres://app:app_secret@localhost:5432/case_intelligence` | DSN pgx |
| `PORT` | `8080` | HTTP-порт |
| `CORS_ORIGIN` | `http://localhost:5173` | origin фронтенда |
| `LLM_PROVIDER` | `stub` | `claude` \| `openai` \| `gemini` \| `local` \| `stub` |
| `LLM_MODEL` | `template-v1.0` | модель |
| `LLM_BASE_URL` | — | для `local` (напр. `http://localhost:11434/v1`) |
| `LLM_API_KEY` | — | ключ провайдера |
| `LLM_CACHE_ENABLED` | `true` | файловый кэш ответов (`./llm-cache`) |

### 2.5. Заполнить базу синтетическими данными (перед демо, только на пустой БД)

```bash
cd backend
DATABASE_URL="postgres://app:app_secret@localhost:5432/case_intelligence?sslmode=disable" \
  go run ./cmd/seed
```

~5000 клиентов / ~500 компаний / ~100k транзакций + 6 схем + golden case
(регулируется `SEED_CLIENT_COUNT`, `SEED_COMPANY_COUNT`, `SEED_BACKGROUND_TX_COUNT`,
`SEED_RANDOM_SEED`). В конце в логах — id алертов, в т.ч. `GOLDEN_CASE`:

```bash
curl -X POST http://localhost:8080/api/alerts/<GOLDEN_CASE_ID>/investigate
```

Повторный запуск на непустой базе даст конфликты id — пересоздайте БД
(`docker compose down -v && docker compose up -d db`).

### 3. Frontend

```bash
cd frontend
npm install && npm run dev
```

Откроется на http://localhost:5173 (запросы `/api/*` проксируются на backend).

### Всё сразу (Docker)

```bash
docker compose up --build
```

## Тесты

```bash
cd backend && go test ./...
```

Покрыты: CycleDetector, ключевые правила (R01/R03/R07/R10), полный чистый пайплайн
(rules → evidence → risk → explain) и инвариант безопасности Safe JSON
(реальные ФИО/названия компаний не попадают в то, что уходит в LLM).

## Требования

- Go 1.26 (для локального запуска backend)
- Docker + Docker Compose
- Node.js 20+ (для локального запуска frontend)
