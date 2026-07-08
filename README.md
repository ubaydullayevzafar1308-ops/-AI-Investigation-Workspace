# AI Case Intelligence Platform

Платформа, автоматически превращающая банковский Alert в готовое AML-расследование (Case) с объяснимым Risk Score — аналитик только проверяет и утверждает.

## Структура

- `backend/` — Java 21 + Spring Boot 3.3 (Maven)
- `frontend/` — React 18 + Vite + Tailwind CSS
- `seed/` — генератор синтетических данных (следующий этап)
- `ARCHITECTURE.md` — полная техническая спецификация

## Запуск

### 1. База данных

```bash
docker compose up -d db
```

### 2. Backend

```bash
cd backend
./mvnw spring-boot:run
```

Проверка: `curl http://localhost:8080/api/health` → `{"status":"ok"}`
Swagger UI: http://localhost:8080/swagger-ui.html

### 3. Frontend

```bash
cd frontend
npm install
npm run dev
```

Откроется на http://localhost:5173 (запросы `/api/*` проксируются на backend).

### Всё сразу (Docker)

```bash
docker compose up --build
```

## Требования

- Docker + Docker Compose
- Java 21 (для локального запуска backend)
- Node.js 20+ (для локального запуска frontend)
