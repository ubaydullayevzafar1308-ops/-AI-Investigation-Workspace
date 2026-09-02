#!/usr/bin/env bash
# End-to-end проверка Go-бэкенда: свежая схема -> миграции -> seed ->
# сервер -> прогон всех эндпоинтов на демо-кейсах. Печатает читаемый отчёт.
#
# Требуется работающий Postgres. Переменные (со значениями по умолчанию):
#   PGHOST=localhost PGPORT=5432 PGDATABASE=case_intelligence
#   PGUSER=app PGPASSWORD=app_secret
#   E2E_SCHEMA=cbu_e2e  E2E_PORT=8899
set -euo pipefail

PGHOST=${PGHOST:-localhost}
PGPORT=${PGPORT:-5432}
PGDATABASE=${PGDATABASE:-case_intelligence}
PGUSER=${PGUSER:-app}
PGPASSWORD=${PGPASSWORD:-app_secret}
SCHEMA=${E2E_SCHEMA:-cbu_e2e}
PORT=${E2E_PORT:-8899}

ADMIN_DSN="postgres://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${PGDATABASE}?sslmode=disable"
APP_DSN="${ADMIN_DSN}&search_path=${SCHEMA}"
BASE="http://localhost:${PORT}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

say()  { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
fail() { printf '  \033[31m✗ %s\033[0m\n' "$*"; exit 1; }
jqget() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

SERVER_PID=""
cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  psql "$ADMIN_DSN" -qc "DROP SCHEMA IF EXISTS ${SCHEMA} CASCADE;" >/dev/null 2>&1 || true
}
trap cleanup EXIT

say "1. Свежая схема ${SCHEMA}"
psql "$ADMIN_DSN" -qc "DROP SCHEMA IF EXISTS ${SCHEMA} CASCADE; CREATE SCHEMA ${SCHEMA} AUTHORIZATION ${PGUSER};" >/dev/null
ok "схема пересоздана"

say "2. go build"
( cd "$ROOT" && go build -o /tmp/e2e-server ./cmd/server && go build -o /tmp/e2e-seed ./cmd/seed )
ok "server + seed собраны"

say "3. Seed (миграции применяются автоматически)"
DATABASE_URL="$APP_DSN" SEED_CLIENT_COUNT=600 SEED_COMPANY_COUNT=90 SEED_BACKGROUND_TX_COUNT=8000 \
  /tmp/e2e-seed 2>&1 | sed 's/^/  /'
TABLES=$(psql "$ADMIN_DSN" -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='${SCHEMA}'")
[ "$TABLES" -ge 12 ] && ok "таблиц в схеме: ${TABLES}" || fail "ожидалось >=12 таблиц, получено ${TABLES}"
CLIENTS=$(psql "$ADMIN_DSN" -tAc "SELECT count(*) FROM ${SCHEMA}.clients")
TXS=$(psql "$ADMIN_DSN" -tAc "SELECT count(*) FROM ${SCHEMA}.transactions")
ok "clients=${CLIENTS}, transactions=${TXS}"

say "4. Старт сервера на :${PORT}"
DATABASE_URL="$APP_DSN" PORT="$PORT" LLM_PROVIDER=stub /tmp/e2e-server >/tmp/e2e-server.log 2>&1 &
SERVER_PID=$!
for i in $(seq 1 30); do
  curl -fsS "${BASE}/api/health" >/dev/null 2>&1 && break
  sleep 0.3
done
curl -fsS "${BASE}/api/health" | grep -q '"status":"ok"' && ok "health = ok" || fail "сервер не поднялся"

say "5. Swagger"
curl -fsS -o /dev/null "${BASE}/swagger-ui.html" && ok "/swagger-ui.html 200"
curl -fsS "${BASE}/api/openapi.json" | jqget "'  paths в openapi.json: %d' % len(d['paths'])"

say "6. Список алертов (фильтр severity=high, пагинация)"
ALERTS=$(curl -fsS "${BASE}/api/alerts?severity=high&size=5")
echo "$ALERTS" | jqget "'  всего high-алертов: %d, на странице: %d' % (d['totalElements'], d['numberOfElements'])"
GOLDEN_ALERT=$(psql "$ADMIN_DSN" -tAc "SELECT id FROM ${SCHEMA}.alerts WHERE trigger_reason='GOLDEN_CASE_DEMO'")
ok "golden alert id = ${GOLDEN_ALERT}"

say "7. Прогон Case Builder по всем 6 демо-схемам"
printf '  %-22s %-6s %-8s %s\n' "scheme" "score" "level" "rules"
for reason in R01_STRUCTURING_PATTERN R03_CIRCULAR_FLOW R02_RAPID_MOVEMENT R04_NEW_ENTITY_SPIKE R09_FAN_IN_OUT GOLDEN_CASE_DEMO; do
  AID=$(psql "$ADMIN_DSN" -tAc "SELECT id FROM ${SCHEMA}.alerts WHERE trigger_reason='${reason}'")
  RESP=$(curl -fsS -X POST "${BASE}/api/alerts/${AID}/investigate")
  echo "$RESP" | python3 -c "
import sys,json
d=json.load(sys.stdin)
rules=sorted({i['title'].split(':')[0].replace('Rule ','') for i in d['evidence']['items'] if i['type']=='rule_hit'})
print('  %-22s %-6s %-8s %s' % ('${reason}'[:22], d['risk']['score'], d['risk']['level'], ' '.join(rules)))
"
done

say "8. Golden case — детальная проверка"
GID=$(psql "$ADMIN_DSN" -tAc "SELECT id FROM ${SCHEMA}.cases WHERE alert_id=${GOLDEN_ALERT}")
CASE=$(curl -fsS "${BASE}/api/cases/${GID}")
echo "$CASE" | jqget "'  score=%d level=%s status=%s dossier=%s explanation=%s' % (d['riskScore'], d['riskLevel'], d['status'], d['dossier'] is not None, d['explanation'] is not None)"
SCORE=$(echo "$CASE" | jqget "d['riskScore']")
[ "$SCORE" -ge 60 ] && ok "score ${SCORE} >= 60 (high)" || fail "ожидался high score, получен ${SCORE}"

say "9. Idempotent re-investigate"
C1=$(curl -fsS -X POST "${BASE}/api/alerts/${GOLDEN_ALERT}/investigate" | jqget "d['caseId']")
[ "$C1" = "$GID" ] && ok "повторный investigate вернул тот же caseId=${GID}" || fail "caseId изменился: ${C1}"

say "10. Graph"
curl -fsS "${BASE}/api/cases/${GID}/graph" | jqget "'  nodes=%d edges=%d suspicious=%d' % (len(d['nodes']), len(d['edges']), sum(1 for e in d['edges'] if e.get('suspicious')))"

say "11. Explanation (причина -> вклад, сортировка по убыванию)"
curl -fsS "${BASE}/api/cases/${GID}/explanation" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for r in d['reasons']:
    print('  %+3d  %s' % (r['contribution'], r['factor']))
c=[r['contribution'] for r in d['reasons']]
assert c==sorted(c,reverse=True), 'не отсортировано'
print('  сумма вкладов: %d, итоговый score: %d' % (sum(c), d['riskScore']))
"
ok "reasons отсортированы по убыванию вклада"

say "12. Audit trail"
AUDIT=$(curl -fsS "${BASE}/api/cases/${GID}/audit")
echo "$AUDIT" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('  события:', ' -> '.join(e['eventType'] for e in d))
llm=[e for e in d if e['eventType']=='llm_called']
assert len(llm)>=2, 'ожидалось >=2 llm_called'
for e in llm:
    assert e['llmPrompt'] is None or len(e['llmPrompt'])<=201, 'prompt не обрезан в summary'
print('  llm_called:', len(llm), '(prompt/response обрезаны до 200)')
"
EVID=$(echo "$AUDIT" | jqget "[e['id'] for e in d if e['eventType']=='llm_called'][0]")

say "13. Инвариант безопасности Safe JSON (audit detail, полный prompt)"
curl -fsS "${BASE}/api/cases/${GID}/audit/${EVID}" | python3 -c "
import sys,json
d=json.load(sys.stdin)
p=d['llmPrompt'] or ''
leaked=[n for n in ('Karimov','Alisher','Botirovich','Xolmatov','Barakat Trade','Vega Import') if n in p]
assert not leaked, 'УТЕЧКА PII в промпт LLM: %s' % leaked
assert 'Клиент К-1' in p, 'нет псевдонима в промпте'
print('  провайдер=%s модель=%s' % (d['llmProvider'], d['llmModel']))
print('  реальных ФИО/названий в промпте: 0, псевдоним «Клиент К-1»: есть')
"
ok "LLM не видел реальных данных"

say "14. Report (черновик от LLM) + правка аналитика"
curl -fsS "${BASE}/api/cases/${GID}/report" | jqget "'  черновик: %d символов, final_text: %s' % (len(d['draftText']), d['finalText'])"
curl -fsS -X PUT "${BASE}/api/cases/${GID}/report" -H 'content-type: application/json' \
  -d '{"finalText":"Отредактировано аналитиком. Рекомендация: эскалировать.","approvedBy":"analyst-1"}' \
  | jqget "'  после правки: approvedBy=%s' % d['approvedBy']"
ok "отчёт обновлён"

say "15. Chat по кейсу"
curl -fsS -X POST "${BASE}/api/cases/${GID}/chat" -H 'content-type: application/json' \
  -d '{"question":"почему такой высокий риск?"}' | jqget "'  ответ (%d символов): %s...' % (len(d['answer']), d['answer'][:70])"

say "16. Решение аналитика -> Audit Log"
curl -fsS -X PATCH "${BASE}/api/cases/${GID}/decision" -H 'content-type: application/json' \
  -d '{"status":"escalated","comment":"передано в комплаенс"}' \
  | jqget "'  status=%s closedAt=%s' % (d['status'], d['closedAt'] is not None)"
curl -fsS "${BASE}/api/cases/${GID}/audit" | jqget "'  decision_made в audit: %s' % any(e['eventType']=='decision_made' for e in d)"
ok "решение зафиксировано"

say "17. Modules (feature flags)"
curl -fsS "${BASE}/api/modules" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for m in d: print('  %-11s %s' % (m['moduleCode'], 'ON' if m['enabled'] else 'off'))
assert [m for m in d if m['moduleCode']=='AML'][0]['enabled'], 'AML должен быть включён'
"

say "18. Dashboard metrics"
curl -fsS "${BASE}/api/dashboard/metrics" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('  totalCases=%d casesToday=%d avgRiskScore=%.1f' % (d['totalCases'], d['casesToday'], d['avgRiskScore']))
print('  ruleDistribution:', d['ruleDistribution'])
print('  estimatedHoursSaved=%.1f' % d['estimatedHoursSaved'])
"

say "19. Обработка ошибок"
CODE=$(curl -s -o /tmp/e2e-err.json -w '%{http_code}' "${BASE}/api/cases/999999")
BODY=$(cat /tmp/e2e-err.json)
[ "$CODE" = "404" ] && echo "$BODY" | grep -q '"status":404' && ok "404 {timestamp,status,message}: ${BODY}" || fail "неверный формат 404: ${CODE} ${BODY}"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE}/api/alerts/999999/investigate")
[ "$CODE" = "400" ] && ok "investigate несуществующего alert -> 400" || fail "ожидался 400, получен ${CODE}"

say "20. go test ./..."
( cd "$ROOT" && go test ./... 2>&1 | grep -Ev 'no test files' | sed 's/^/  /' )

printf '\n\033[1;32m═══ E2E: всё прошло ═══\033[0m\n'
