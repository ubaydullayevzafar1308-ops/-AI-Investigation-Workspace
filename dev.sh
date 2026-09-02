#!/usr/bin/env bash
#
# dev.sh — поднимает всё локальное окружение одной командой:
# Postgres (Docker) -> backend (Go, фон) -> frontend (Vite, форграунд).
#
# Использование: ./dev.sh
# Остановка: Ctrl+C — корректно убивает backend и frontend.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT_DIR/logs"
BACKEND_LOG="$LOG_DIR/backend.log"
HEALTH_URL="http://localhost:8080/api/health"
DB_URL="postgres://app:app_secret@localhost:5432/case_intelligence?sslmode=disable"

BACKEND_PID=""
FRONTEND_PID=""
CLEANED_UP=0

log()  { printf '\033[1;34m[dev.sh]\033[0m %s\n' "$1"; }
err()  { printf '\033[1;31m[dev.sh]\033[0m %s\n' "$1" >&2; }

cleanup() {
  [ "$CLEANED_UP" -eq 1 ] && return
  CLEANED_UP=1
  echo
  log "Останавливаю окружение..."
  [ -n "$FRONTEND_PID" ] && { pkill -P "$FRONTEND_PID" 2>/dev/null || true; kill "$FRONTEND_PID" 2>/dev/null || true; }
  [ -n "$BACKEND_PID" ]  && { pkill -P "$BACKEND_PID" 2>/dev/null || true; kill "$BACKEND_PID" 2>/dev/null || true; }
  log "Окружение остановлено."
}
trap cleanup INT TERM EXIT

mkdir -p "$LOG_DIR"

if ! command -v docker >/dev/null 2>&1; then
  err "Docker не найден в PATH. Установи Docker Desktop и попробуй снова."
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  err "Docker не запущен. Запусти Docker Desktop и попробуй снова."
  exit 1
fi
log "Docker запущен."

log "Запускаю Postgres (docker compose up -d db)..."
(cd "$ROOT_DIR" && docker compose up -d db)

log "Жду готовности Postgres..."
DB_READY=0
for _ in $(seq 1 30); do
  if docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T db pg_isready -U app >/dev/null 2>&1; then
    DB_READY=1; break
  fi
  sleep 2
done
[ "$DB_READY" -eq 1 ] || { err "Postgres не ответил pg_isready. Смотри 'docker compose logs db'."; exit 1; }
log "Postgres готов."

log "Запускаю backend (Go, лог: logs/backend.log)..."
( cd "$ROOT_DIR/backend" && DATABASE_URL="$DB_URL" go run ./cmd/server ) > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

log "Жду готовности backend ($HEALTH_URL)..."
HEALTH_READY=0
for _ in $(seq 1 45); do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    err "Backend завершился при старте. Смотри logs/backend.log:"; tail -n 40 "$BACKEND_LOG" >&2 || true; exit 1
  fi
  if curl -sf "$HEALTH_URL" 2>/dev/null | grep -q '"status":"ok"'; then HEALTH_READY=1; break; fi
  sleep 2
done
[ "$HEALTH_READY" -eq 1 ] || { err "Backend не ответил на $HEALTH_URL. Смотри logs/backend.log:"; tail -n 40 "$BACKEND_LOG" >&2 || true; exit 1; }
log "Backend готов."

if [ ! -d "$ROOT_DIR/frontend/node_modules" ]; then
  log "frontend/node_modules не найден, ставлю зависимости (npm install)..."
  (cd "$ROOT_DIR/frontend" && npm install)
fi

log "Запускаю frontend (npm run dev)..."
( cd "$ROOT_DIR/frontend" && npm run dev ) &
FRONTEND_PID=$!
wait "$FRONTEND_PID"
