# AI Case Intelligence Platform

Платформа, автоматически превращающая банковский Alert в готовое AML-расследование (Case) с объяснимым Risk Score — аналитик только проверяет и утверждает.

## Структура

- `backend/` — Java 21 + Spring Boot 3.3 (Maven)
- `frontend/` — React 18 + Vite + Tailwind CSS
- `seed/` — генератор синтетических данных теперь живёт в
  `backend/src/main/java/uz/caseintel/seed/` (Java CommandLineRunner,
  профиль `seed`); эта папка оставлена для совместимости со структурой
  из ARCHITECTURE.md §16, реальный код — в backend.
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

### 2.5. Заполнить базу синтетическими данными (обязательно перед демо)

**Только на пустой базе** (сразу после миграций Flyway, без данных):

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=seed
```

Займёт какое-то время из-за ~100 000 фоновых транзакций (batch insert).
В конце в логах будет строка вида:

```
Alert id для демо: structuring=..., circular=..., transit=..., newCompanySpike=..., fanInOut=..., GOLDEN_CASE=...
```

`GOLDEN_CASE` — id алерта главного демо-кейса. Прогони его через пайплайн:

```bash
curl -X POST http://localhost:8080/api/alerts/<GOLDEN_CASE_ID>/investigate
```

Повторный запуск seed на непустой базе приведёт к конфликтам id — либо
`docker compose down -v && docker compose up -d db` (пересоздать БД),
либо вручную `TRUNCATE` всех таблиц перед повторным сидированием.

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
