#!/usr/bin/env bash
#
# dev.sh — поднимает всё локальное окружение одной командой:
# Postgres (Docker) -> backend (Maven, фон) -> frontend (Vite, форграунд).
#
# Использование: ./dev.sh
# Остановка: Ctrl+C — корректно убивает backend и frontend.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT_DIR/logs"
BACKEND_LOG="$LOG_DIR/backend.log"
HEALTH_URL="http://localhost:8080/api/health"

BACKEND_PID=""
FRONTEND_PID=""
CLEANED_UP=0

log()  { printf '\033[1;34m[dev.sh]\033[0m %s\n' "$1"; }
err()  { printf '\033[1;31m[dev.sh]\033[0m %s\n' "$1" >&2; }

cleanup() {
  if [ "$CLEANED_UP" -eq 1 ]; then
    return
  fi
  CLEANED_UP=1
  echo
  log "Останавливаю окружение..."

  if [ -n "$FRONTEND_PID" ]; then
    pkill -P "$FRONTEND_PID" 2>/dev/null || true
    kill "$FRONTEND_PID" 2>/dev/null || true
  fi

  if [ -n "$BACKEND_PID" ]; then
    pkill -P "$BACKEND_PID" 2>/dev/null || true
    kill "$BACKEND_PID" 2>/dev/null || true
    # ./mvnw spring-boot:run форкает дочерний JVM-процесс — на всякий
    # случай подчищаем и его по имени команды, если он пережил kill выше.
    pkill -f "spring-boot:run" 2>/dev/null || true
  fi

  log "Окружение остановлено."
}
trap cleanup INT TERM EXIT

mkdir -p "$LOG_DIR"

# 1. Docker должен быть установлен и запущен.
if ! command -v docker >/dev/null 2>&1; then
  err "Docker не найден в PATH. Установи Docker (Docker Desktop) и попробуй снова."
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  err "Docker не запущен. Запусти Docker Desktop (или дождись его старта) и попробуй снова."
  exit 1
fi
log "Docker запущен."

# 2. Поднимаем Postgres и ждём готовности.
log "Запускаю Postgres (docker compose up -d db)..."
(cd "$ROOT_DIR" && docker compose up -d db)

log "Жду готовности Postgres..."
DB_READY=0
for _ in $(seq 1 30); do
  if docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T db pg_isready -U app >/dev/null 2>&1; then
    DB_READY=1
    break
  fi
  sleep 2
done
if [ "$DB_READY" -ne 1 ]; then
  err "Postgres не ответил pg_isready за отведённое время. Смотри 'docker compose logs db'."
  exit 1
fi
log "Postgres готов."

# 3. Запускаем backend в фоне, лог — в logs/backend.log.
log "Запускаю backend (лог: logs/backend.log)..."
cd "$ROOT_DIR/backend"
./mvnw spring-boot:run > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
cd "$ROOT_DIR"

# 4. Ждём, пока backend не ответит на /api/health (максимум 90 секунд).
log "Жду готовности backend ($HEALTH_URL)..."
HEALTH_READY=0
for _ in $(seq 1 45); do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    err "Backend завершился при старте. Смотри logs/backend.log:"
    tail -n 40 "$BACKEND_LOG" >&2 || true
    exit 1
  fi
  if curl -sf "$HEALTH_URL" 2>/dev/null | grep -q '"status":"ok"'; then
    HEALTH_READY=1
    break
  fi
  sleep 2
done
if [ "$HEALTH_READY" -ne 1 ]; then
  err "Backend не ответил на $HEALTH_URL за 90 секунд. Смотри logs/backend.log:"
  tail -n 40 "$BACKEND_LOG" >&2 || true
  exit 1
fi
log "Backend готов."

# 5. Запускаем frontend в форграунде (Ctrl+C остановит всё через trap).
if [ ! -d "$ROOT_DIR/frontend/node_modules" ]; then
  log "frontend/node_modules не найден, ставлю зависимости (npm install)..."
  (cd "$ROOT_DIR/frontend" && npm install)
fi

log "Запускаю frontend (npm run dev)..."
cd "$ROOT_DIR/frontend"
npm run dev &
FRONTEND_PID=$!
cd "$ROOT_DIR"

wait "$FRONTEND_PID"
