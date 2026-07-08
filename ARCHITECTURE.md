# AI Case Intelligence Platform — Архитектура проекта v2.0

Версия: 2.0
Backend: **Java Spring Boot (обязательное требование хакатона)**
Назначение: техническая спецификация для реализации MVP на CBU Coding Hackathon

## Главная идея

Мы строим не AI-чат и не систему поиска мошенничества.
Мы строим **AI Case Intelligence Platform**, где центральным элементом является **Case Builder**, автоматически превращающий банковский Alert в полностью готовое расследование (Case), которое аналитик проверяет и утверждает.

Ключевые архитектурные принципы:
1. **Case Builder — центр системы.** Все engines — его подчинённые компоненты.
2. **Каждый компонент отвечает за одну задачу** (пайплайн из отдельных сервисов).
3. **LLM никогда не получает банковские данные** — только безопасный структурированный JSON.
4. **Полная объяснимость:** каждый балл Risk Score трассируется до правила и транзакции.
5. **Полный Audit Log:** каждое расследование воспроизводимо.
6. **Платформенность:** MVP = модуль AML, остальные модули за feature flags.

---

## 1. Итоговая схема системы

```
                        Alert
                          │
                          ▼
                ┌──────────────────┐
                │   CASE BUILDER    │   ← центральный компонент,
                │  (оркестратор)    │     главная ценность продукта
                └────────┬─────────┘
                         │ запускает пайплайн:
                         ▼
                 ① Data Collector
                    (сбор досье: клиент, счета,
                     транзакции, связи, история)
                         │
                         ▼
                 ② Rule Engine
                    (10 правил AML → hits)
                         │
                         ▼
                 ③ Graph Engine
                    (подграф связей, циклы,
                     денежные потоки)
                         │
                         ▼
                 ④ Evidence Collector
                    (агрегация всех доказательств:
                     правила, связи, транзакции,
                     прошлые алерты, аномалии)
                         │
                         ▼
                 ⑤ Risk Engine
                    (Risk Score 0–100 + уровень,
                     отдельный сервис)
                         │
                         ▼
                 ⑥ Explainability Engine
                    (структурированное объяснение:
                     причина → вклад в score)
                         │
                         ▼
                 ⑦ Safe JSON Output
                    (безопасный срез: факты, без
                     SQL, без сырых таблиц)
                         │
                         ▼
                 ⑧ AI Adapter Layer
                    ├── OpenAI
                    ├── Claude
                    ├── Gemini
                    ├── Llama (local)
                    ├── Qwen (local)
                    └── DeepSeek
                         │
                         ▼
                 ⑨ Investigation Report Generator
                    (человеческое объяснение +
                     черновик отчёта)
                         │
                         ▼
              Ready Investigation Case
                         │
                         ▼
                  Analyst Review
                         │
                         ▼
                  Final Decision
                         │
                         ▼
                    Audit Log
              (полная запись расследования)
```

---

## 2. Технологический стек

| Слой | Технология | Примечание |
|---|---|---|
| Backend | **Java 21 + Spring Boot 3.3** | Требование хакатона |
| Сборка | Maven | |
| ORM | Spring Data JPA (Hibernate) | |
| БД | PostgreSQL 16 | Рекурсивные CTE для графа |
| Миграции | Flyway | Версионирование схемы БД |
| Frontend | React 18 + Vite + Tailwind CSS | |
| Граф-визуализация | react-force-graph-2d | |
| Графики | recharts | Dashboard |
| LLM | AI Adapter Layer (мультипровайдер) | Claude/GPT на демо, Llama/Qwen on-premise |
| Контейнеризация | Docker + docker-compose | Демо одной командой |
| Генератор данных | Java (модуль seed) или standalone-скрипт | Синтетический датасет |
| Документация API | springdoc-openapi (Swagger UI) | Впечатляет судей |

---

## 3. Структура репозитория

```
case-intelligence/
├── README.md
├── ARCHITECTURE.md
├── docker-compose.yml
├── .env.example
│
├── backend/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── resources/
│       │   ├── application.yml
│       │   └── db/migration/            # Flyway
│       │       ├── V1__init_schema.sql
│       │       ├── V2__audit_log.sql
│       │       └── V3__feature_flags.sql
│       └── java/uz/caseintel/
│           ├── CaseIntelligenceApplication.java
│           │
│           ├── config/
│           │   ├── FeatureFlagsConfig.java
│           │   ├── LlmProperties.java
│           │   └── OpenApiConfig.java
│           │
│           ├── entity/                   # JPA-сущности
│           │   ├── Client.java
│           │   ├── Company.java
│           │   ├── Account.java
│           │   ├── Transaction.java
│           │   ├── Relationship.java
│           │   ├── Alert.java
│           │   ├── Case.java
│           │   ├── RuleHit.java
│           │   ├── Report.java
│           │   └── AuditLog.java
│           │
│           ├── repository/               # Spring Data JPA
│           │   ├── ClientRepository.java
│           │   ├── TransactionRepository.java
│           │   ├── RelationshipRepository.java
│           │   ├── AlertRepository.java
│           │   ├── CaseRepository.java
│           │   └── AuditLogRepository.java
│           │
│           ├── casebuilder/              # ⭐ ЦЕНТР СИСТЕМЫ
│           │   ├── CaseBuilderService.java      # оркестратор пайплайна
│           │   ├── CasePipelineContext.java     # контекст, идущий по пайплайну
│           │   └── dto/
│           │       ├── DossierDto.java
│           │       └── ReadyCaseDto.java
│           │
│           ├── datacollector/            # ① сбор данных
│           │   └── DataCollectorService.java
│           │
│           ├── rules/                    # ② Rule Engine
│           │   ├── Rule.java                    # интерфейс
│           │   ├── RuleEngineService.java       # раннер
│           │   ├── RuleResult.java
│           │   └── impl/
│           │       ├── R01StructuringRule.java
│           │       ├── R02RapidMovementRule.java
│           │       ├── R03CircularFlowRule.java
│           │       ├── R04NewEntitySpikeRule.java
│           │       ├── R05DormantAwakeningRule.java
│           │       ├── R06CashIntensiveRule.java
│           │       ├── R07SharedAttributesRule.java
│           │       ├── R08AmountAnomalyRule.java
│           │       ├── R09FanInFanOutRule.java
│           │       └── R10HighRiskCounterpartyRule.java
│           │
│           ├── graph/                    # ③ Graph Engine
│           │   ├── GraphEngineService.java
│           │   ├── GraphDto.java                # nodes + edges
│           │   └── CycleDetector.java           # поиск круговых схем
│           │
│           ├── evidence/                 # ④ Evidence Collector
│           │   ├── EvidenceCollectorService.java
│           │   ├── Evidence.java                # единица доказательства
│           │   └── EvidenceBundle.java          # полный пакет
│           │
│           ├── risk/                     # ⑤ Risk Engine (отдельный сервис)
│           │   ├── RiskEngineService.java
│           │   └── RiskResult.java              # score + level
│           │
│           ├── explainability/           # ⑥ Explainability Engine
│           │   ├── ExplainabilityService.java
│           │   └── ExplanationDto.java          # причина → вклад
│           │
│           ├── llm/                      # ⑦⑧ Safe JSON + AI Adapter
│           │   ├── SafeJsonMapper.java          # досье → безопасный JSON
│           │   ├── LlmAdapter.java              # интерфейс
│           │   ├── LlmService.java              # фасад (объяснение/отчёт/чат)
│           │   ├── LlmAuditInterceptor.java     # логирует prompt+response
│           │   ├── providers/
│           │   │   ├── ClaudeAdapter.java
│           │   │   ├── OpenAiAdapter.java
│           │   │   ├── GeminiAdapter.java
│           │   │   └── LocalLlmAdapter.java     # Llama/Qwen/DeepSeek (OpenAI-совместимый API)
│           │   └── cache/
│           │       └── LlmResponseCache.java    # офлайн-демо
│           │
│           ├── report/                   # ⑨ Report Generator
│           │   └── ReportGeneratorService.java
│           │
│           ├── audit/                    # Audit Log
│           │   └── AuditService.java
│           │
│           ├── featureflags/             # модульность платформы
│           │   └── FeatureFlagService.java
│           │
│           └── api/                      # REST-контроллеры
│               ├── AlertController.java
│               ├── CaseController.java
│               ├── GraphController.java
│               ├── ReportController.java
│               ├── ChatController.java
│               ├── DashboardController.java
│               └── ModuleController.java        # список модулей платформы
│
├── frontend/
│   ├── package.json
│   ├── Dockerfile
│   ├── vite.config.js
│   ├── tailwind.config.js
│   └── src/
│       ├── main.jsx
│       ├── App.jsx
│       ├── api/client.js
│       ├── pages/
│       │   ├── AlertQueue.jsx
│       │   ├── CaseWorkspace.jsx        # главный экран
│       │   ├── ReportView.jsx
│       │   ├── Dashboard.jsx
│       │   └── Modules.jsx              # платформенность: AML ✅, остальные 🔒
│       └── components/
│           ├── ClientDossier.jsx
│           ├── TransactionTimeline.jsx
│           ├── RelationGraph.jsx
│           ├── EvidencePanel.jsx        # список доказательств
│           ├── ExplainabilityPanel.jsx  # причина → вклад в score
│           ├── RiskScoreBadge.jsx
│           ├── CaseChat.jsx
│           └── ui/
│
└── seed/                                 # генератор синтетических данных
    ├── (вариант A: Java-модуль backend/src/.../seed/)
    ├── (вариант B: standalone Node/Python-скрипт → SQL insert)
    ├── generators/
    │   ├── clients / companies / accounts / normalTransactions
    │   └── schemes/
    │       ├── structuring
    │       ├── circular
    │       ├── transit
    │       ├── newCompanySpike
    │       ├── fanInOut
    │       └── goldenCase              # главный демо-кейс
    └── data/ (uzbekNames, companyNames)
```

---

## 4. Схема базы данных (PostgreSQL + Flyway)

### V1__init_schema.sql

```sql
-- ============ КЛИЕНТЫ ============
CREATE TABLE clients (
    id                BIGSERIAL PRIMARY KEY,
    full_name         VARCHAR(255) NOT NULL,
    birth_date        DATE,
    inn               VARCHAR(14) UNIQUE,
    phone             VARCHAR(20),
    device_id         VARCHAR(64),            -- для правила Same Device
    address           TEXT,
    registration_date DATE NOT NULL,
    client_type       VARCHAR(20) NOT NULL DEFAULT 'individual',
                      -- individual | entrepreneur
    risk_level        VARCHAR(10) NOT NULL DEFAULT 'low',
    is_blacklisted    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============ КОМПАНИИ ============
CREATE TABLE companies (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    inn               VARCHAR(14) UNIQUE,
    address           TEXT,
    registration_date DATE NOT NULL,
    director_id       BIGINT REFERENCES clients(id),
    status            VARCHAR(20) NOT NULL DEFAULT 'active',
    is_blacklisted    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============ СЧЕТА ============
CREATE TABLE accounts (
    id             BIGSERIAL PRIMARY KEY,
    owner_type     VARCHAR(10) NOT NULL,      -- client | company
    owner_id       BIGINT NOT NULL,
    account_number VARCHAR(30) UNIQUE NOT NULL,
    currency       VARCHAR(3) NOT NULL DEFAULT 'UZS',
    opened_at      DATE NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_accounts_owner ON accounts(owner_type, owner_id);

-- ============ ТРАНЗАКЦИИ ============
CREATE TABLE transactions (
    id           BIGSERIAL PRIMARY KEY,
    from_account BIGINT REFERENCES accounts(id),
    to_account   BIGINT REFERENCES accounts(id),
    amount       NUMERIC(18,2) NOT NULL,
    currency     VARCHAR(3) NOT NULL DEFAULT 'UZS',
    tx_type      VARCHAR(20) NOT NULL,
                 -- transfer | cash_in | cash_out | card_payment | international
    description  TEXT,
    tx_timestamp TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tx_from ON transactions(from_account, tx_timestamp);
CREATE INDEX idx_tx_to   ON transactions(to_account, tx_timestamp);
CREATE INDEX idx_tx_time ON transactions(tx_timestamp);

-- ============ СВЯЗИ (граф) ============
CREATE TABLE relationships (
    id            BIGSERIAL PRIMARY KEY,
    source_type   VARCHAR(10) NOT NULL,
    source_id     BIGINT NOT NULL,
    target_type   VARCHAR(10) NOT NULL,
    target_id     BIGINT NOT NULL,
    relation_type VARCHAR(30) NOT NULL,
                  -- founder | director | same_address | same_phone
                  -- | same_device | frequent_counterparty | family
    confidence    NUMERIC(3,2) NOT NULL DEFAULT 1.00,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rel_source ON relationships(source_type, source_id);
CREATE INDEX idx_rel_target ON relationships(target_type, target_id);

-- ============ АЛЕРТЫ ============
CREATE TABLE alerts (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT REFERENCES transactions(id),
    client_id      BIGINT REFERENCES clients(id),
    trigger_reason VARCHAR(100) NOT NULL,
    severity       VARCHAR(10) NOT NULL,     -- low | medium | high
    status         VARCHAR(20) NOT NULL DEFAULT 'new',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============ КЕЙСЫ ============
CREATE TABLE cases (
    id               BIGSERIAL PRIMARY KEY,
    alert_id         BIGINT REFERENCES alerts(id) UNIQUE,
    client_id        BIGINT REFERENCES clients(id),
    risk_score       INTEGER NOT NULL DEFAULT 0,
    risk_level       VARCHAR(10) NOT NULL DEFAULT 'low',
    status           VARCHAR(20) NOT NULL DEFAULT 'open',
                     -- open | approved | rejected | escalated
    analyst_decision TEXT,
    dossier_json     JSONB,       -- снапшот досье
    evidence_json    JSONB,       -- пакет доказательств (Evidence Collector)
    explanation_json JSONB,       -- объяснение score (Explainability Engine)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at        TIMESTAMPTZ
);

-- ============ СРАБОТАВШИЕ ПРАВИЛА ============
CREATE TABLE rule_hits (
    id            BIGSERIAL PRIMARY KEY,
    case_id       BIGINT REFERENCES cases(id) ON DELETE CASCADE,
    rule_code     VARCHAR(10) NOT NULL,
    rule_name     VARCHAR(100) NOT NULL,
    weight        INTEGER NOT NULL,
    evidence_json JSONB NOT NULL,
    explanation   TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============ ОТЧЁТЫ ============
CREATE TABLE reports (
    id           BIGSERIAL PRIMARY KEY,
    case_id      BIGINT REFERENCES cases(id) ON DELETE CASCADE,
    draft_text   TEXT NOT NULL,
    final_text   TEXT,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    approved_by  VARCHAR(100)
);
```

### V2__audit_log.sql

```sql
CREATE TABLE audit_log (
    id             BIGSERIAL PRIMARY KEY,
    case_id        BIGINT REFERENCES cases(id),
    event_type     VARCHAR(40) NOT NULL,
                   -- case_created | rules_executed | graph_built
                   -- | evidence_collected | risk_scored | explained
                   -- | llm_called | report_generated | decision_made
    rules_version  VARCHAR(20),          -- версия набора правил
    risk_score     INTEGER,
    evidence_json  JSONB,
    llm_provider   VARCHAR(30),          -- claude | openai | local-llama ...
    llm_model      VARCHAR(60),
    llm_prompt     TEXT,                 -- полный prompt, отправленный в LLM
    llm_response   TEXT,                 -- полный ответ LLM
    actor          VARCHAR(100),         -- system | имя аналитика
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_case ON audit_log(case_id, created_at);
```

### V3__feature_flags.sql

```sql
CREATE TABLE feature_flags (
    id          BIGSERIAL PRIMARY KEY,
    module_code VARCHAR(30) UNIQUE NOT NULL,
    module_name VARCHAR(100) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT
);

INSERT INTO feature_flags (module_code, module_name, enabled, description) VALUES
('AML',        'AML Investigation',        TRUE,  'Расследование отмывания денег'),
('FRAUD',      'Fraud Investigation',      FALSE, 'Расследование мошенничества'),
('CREDIT',     'Credit Investigation',     FALSE, 'Кредитные расследования'),
('KYC',        'KYC Investigation',        FALSE, 'Проверка клиентов'),
('COMPLIANCE', 'Compliance Investigation', FALSE, 'Комплаенс-проверки');
```

---

## 5. Case Builder — центральный компонент ⭐

Case Builder — оркестратор. Он не содержит бизнес-логики анализа,
а запускает пайплайн и передаёт контекст от этапа к этапу.

```java
// casebuilder/CaseBuilderService.java
@Service
@RequiredArgsConstructor
public class CaseBuilderService {

    private final DataCollectorService dataCollector;        // ①
    private final RuleEngineService ruleEngine;              // ②
    private final GraphEngineService graphEngine;            // ③
    private final EvidenceCollectorService evidenceCollector;// ④
    private final RiskEngineService riskEngine;              // ⑤
    private final ExplainabilityService explainability;      // ⑥
    private final SafeJsonMapper safeJsonMapper;             // ⑦
    private final LlmService llmService;                     // ⑧
    private final ReportGeneratorService reportGenerator;    // ⑨
    private final AuditService audit;
    private final CaseRepository caseRepository;

    @Transactional
    public ReadyCaseDto buildCase(Long alertId) {
        var ctx = new CasePipelineContext(alertId);

        // ① Сбор данных
        ctx.setDossier(dataCollector.collect(alertId));
        audit.log(ctx, "case_created");

        // ② Правила
        ctx.setRuleHits(ruleEngine.runAll(ctx.getDossier()));
        audit.log(ctx, "rules_executed");

        // ③ Граф
        ctx.setGraph(graphEngine.build(ctx.getDossier().clientId()));
        audit.log(ctx, "graph_built");

        // ④ Доказательства
        ctx.setEvidence(evidenceCollector.collect(ctx));
        audit.log(ctx, "evidence_collected");

        // ⑤ Риск
        ctx.setRisk(riskEngine.score(ctx.getEvidence()));
        audit.log(ctx, "risk_scored");

        // ⑥ Объяснимость
        ctx.setExplanation(explainability.explain(ctx.getRisk(), ctx.getEvidence()));
        audit.log(ctx, "explained");

        // ⑦ Безопасный JSON  ⑧ LLM  ⑨ Отчёт
        var safeJson = safeJsonMapper.toSafeJson(ctx);
        var humanExplanation = llmService.explainRisk(safeJson);   // audit внутри
        var reportDraft = llmService.generateReport(safeJson);     // audit внутри
        reportGenerator.saveDraft(ctx, reportDraft);
        audit.log(ctx, "report_generated");

        return persistReadyCase(ctx, humanExplanation);
    }
}
```

Строгий порядок пайплайна (каждый компонент — одна задача):

```
Data Collector → Rule Engine → Graph Engine → Evidence Collector
→ Risk Engine → Explainability Engine → Safe JSON → AI Adapter
→ Report Generator → Ready Case → Analyst → Decision → Audit Log
```

---

## 6. Rule Engine (② в пайплайне)

### Интерфейс правила

```java
// rules/Rule.java
public interface Rule {
    String code();          // "R01"
    String name();          // "Structuring (дробление сумм)"
    int weight();           // вклад в Risk Score
    Optional<RuleResult> check(DossierDto dossier);
}

// rules/RuleResult.java
public record RuleResult(
    String code,
    String name,
    int weight,
    Map<String, Object> evidence,   // id транзакций, суммы, даты
    String explanation              // готовая фраза-факт
) {}
```

### Пример реализации

```java
// rules/impl/R01StructuringRule.java
@Component
public class R01StructuringRule implements Rule {

    private static final BigDecimal THRESHOLD = new BigDecimal("100000000"); // UZS
    private static final double NEAR = 0.9;
    private static final int WINDOW_DAYS = 3;
    private static final int MIN_COUNT = 3;

    @Override public String code()  { return "R01"; }
    @Override public String name()  { return "Structuring (дробление сумм)"; }
    @Override public int weight()   { return 20; }

    @Override
    public Optional<RuleResult> check(DossierDto dossier) {
        var lower = THRESHOLD.multiply(BigDecimal.valueOf(NEAR));
        var suspicious = dossier.transactions().stream()
            .filter(tx -> tx.amount().compareTo(lower) >= 0
                       && tx.amount().compareTo(THRESHOLD) < 0)
            .toList();
        // ...группировка по окнам WINDOW_DAYS...
        if (suspicious.size() >= MIN_COUNT) {
            return Optional.of(new RuleResult(
                code(), name(), weight(),
                Map.of("transaction_ids", suspicious.stream().map(TxDto::id).toList()),
                "Обнаружено %d операций на суммы чуть ниже порога обязательного контроля за %d дня — признак умышленного дробления."
                    .formatted(suspicious.size(), WINDOW_DAYS)
            ));
        }
        return Optional.empty();
    }
}
```

### Раннер (все правила подхватываются через DI)

```java
// rules/RuleEngineService.java
@Service
@RequiredArgsConstructor
public class RuleEngineService {
    private final List<Rule> rules;   // Spring инжектит все @Component-правила

    public List<RuleResult> runAll(DossierDto dossier) {
        return rules.stream()
            .map(r -> r.check(dossier))
            .flatMap(Optional::stream)
            .toList();
    }
}
```

### Таблица правил

| Код | Правило | Что ловит | Вес |
|---|---|---|---|
| R01 | Structuring | Дробление: суммы чуть ниже порога за короткое окно | 20 |
| R02 | Rapid movement | Деньги пришли и ушли в течение часов (транзит) | 15 |
| R03 | Circular flow | Круговая схема A→B→C→A (цикл в графе) | 25 |
| R04 | New entity spike | Крупный оборот у компании младше 90 дней | 15 |
| R05 | Dormant awakening | Спящий 6+ мес. счёт внезапно ожил | 10 |
| R06 | Cash intensive | Доля наличных операций > 70% | 10 |
| R07 | Shared attributes | Общий адрес/телефон/устройство/директор с blacklisted | 20 |
| R08 | Amount anomaly | Сумма в 5+ раз выше исторического профиля | 10 |
| R09 | Fan-in / Fan-out | Много мелких входящих → один крупный исходящий | 15 |
| R10 | High-risk counterparty | Контрагент из чёрного списка | 25 |

---

## 7. Graph Engine (③)

Подграф связей вокруг клиента (глубина 2) — рекурсивный CTE в PostgreSQL, без Neo4j:

```sql
WITH RECURSIVE graph AS (
    SELECT source_type, source_id, target_type, target_id, relation_type, 1 AS depth
    FROM relationships
    WHERE (source_type = 'client' AND source_id = :clientId)
       OR (target_type = 'client' AND target_id = :clientId)
  UNION
    SELECT r.source_type, r.source_id, r.target_type, r.target_id, r.relation_type, g.depth + 1
    FROM relationships r
    JOIN graph g ON (r.source_type = g.target_type AND r.source_id = g.target_id)
    WHERE g.depth < 2
)
SELECT DISTINCT * FROM graph;
```

Дополнительно `CycleDetector` (DFS по денежным потокам между участниками
подграфа) находит круговые схемы для R03.

Формат `GET /api/cases/{id}/graph`:

```json
{
  "nodes": [
    { "id": "client_123", "type": "client",  "label": "Каримов А.", "flagged": true },
    { "id": "company_45", "type": "company", "label": "OOO Barakat", "flagged": false }
  ],
  "edges": [
    { "source": "client_123", "target": "company_45",
      "kind": "money_flow", "total": 450000000, "count": 12, "suspicious": true },
    { "source": "client_123", "target": "company_45",
      "kind": "relation", "relationType": "director" }
  ]
}
```

---

## 8. Evidence Collector (④)

Агрегирует ВСЕ доказательства в единый пакет перед оценкой риска:

```java
// evidence/Evidence.java
public record Evidence(
    String type,        // rule_hit | relation | suspicious_tx
                        // | previous_alert | anomaly | shared_device
    String title,       // "Rule R03: Circular flow"
    int weight,         // вклад (0 — если информационное)
    Map<String, Object> details
) {}

// evidence/EvidenceBundle.java
public record EvidenceBundle(
    Long clientId,
    List<Evidence> items,
    int totalRuleWeight
) {}
```

Что собирает:
- сработавшие правила (из Rule Engine);
- найденные связи (из Graph Engine): подставные фирмы, общий директор/адрес/устройство;
- подозрительные транзакции (объединение evidence всех правил);
- связанные компании с флагами;
- предыдущие алерты и кейсы клиента (история!);
- аномалии профиля.

Пример пакета (как увидит аналитик):

```text
Evidence
✔ Rule R01: Structuring                          (+20)
✔ Rule R03: Circular flow                        (+25)
✔ Related company: OOO Barakat (общий директор)
✔ Previous alert: #482 (закрыт как escalated)
✔ Circular transfers: A → OOO_1 → OOO_2 → A
✔ Same device: клиент #77 (blacklisted)
✔ Night transactions: 6 операций 02:00–04:00
```

---

## 9. Risk Engine (⑤) — отдельный сервис

Считает score ТОЛЬКО из EvidenceBundle (не из графа напрямую):

```java
// risk/RiskEngineService.java
@Service
public class RiskEngineService {

    public RiskResult score(EvidenceBundle evidence) {
        int raw = evidence.items().stream().mapToInt(Evidence::weight).sum();

        // усилители: повторный клиент, blacklisted-связи
        boolean repeatOffender = evidence.items().stream()
            .anyMatch(e -> e.type().equals("previous_alert"));
        if (repeatOffender) raw += 10;

        int score = Math.min(100, raw);
        String level = score >= 60 ? "high" : score >= 30 ? "medium" : "low";
        return new RiskResult(score, level);
    }
}
```

---

## 10. Explainability Engine (⑥)

Превращает score в структурированное объяснение. LLM НЕ придумывает
причины — он получает уже готовый список и только переводит его
в человеческий текст.

```java
// explainability/ExplanationDto.java
public record ExplanationDto(
    int riskScore,
    String riskLevel,
    List<Reason> reasons
) {
    public record Reason(String factor, int contribution, String detail) {}
}
```

Пример вывода (показывается и аналитику, и LLM):

```text
Risk Score: 91 / 100  (HIGH)

Причины:
✔ Круговая схема переводов          (+25)  A → OOO_1 → OOO_2 → A
✔ Дробление сумм                    (+20)  5 операций по 95–99 млн за 2 дня
✔ Связь с blacklisted-клиентом      (+20)  общее устройство
✔ Транзитные переводы               (+15)  вход/выход в течение 3 часов
✔ Повторный фигурант                (+10)  предыдущий алерт #482
✔ Ночные операции                   (+13→ капнуто до 100)
```

---

## 11. Safe JSON + AI Adapter Layer (⑦⑧)

### Принцип безопасности: LLM никогда не получает банковские данные

```
Rule Engine → Graph Engine → Evidence Collector
→ Explainability Engine → SAFE JSON → LLM
```

LLM получает ТОЛЬКО:
- итоговый Risk Score и уровень;
- список причин из Explainability Engine;
- пакет доказательств (заголовки + агрегаты);
- обезличенные метки субъектов (опционально: псевдонимизация имён).

LLM НЕ получает: SQL, доступ к БД, сырые таблицы транзакций,
полную историю операций, номера счетов.

```java
// llm/SafeJsonMapper.java
@Component
public class SafeJsonMapper {
    public SafeCaseJson toSafeJson(CasePipelineContext ctx) {
        return new SafeCaseJson(
            ctx.getRisk().score(),
            ctx.getRisk().level(),
            ctx.getExplanation().reasons(),
            ctx.getEvidence().items().stream()
                .map(e -> new SafeEvidence(e.type(), e.title(), e.details()))
                .toList()
        );
    }
}
```

### AI Adapter — мультипровайдер

```java
// llm/LlmAdapter.java
public interface LlmAdapter {
    String complete(String systemPrompt, String userPrompt);
    String providerName();   // для Audit Log
    String modelName();
}
```

Реализации: `ClaudeAdapter`, `OpenAiAdapter`, `GeminiAdapter`,
`LocalLlmAdapter` (Llama / Qwen / DeepSeek через OpenAI-совместимый
endpoint — vLLM/Ollama). Выбор через конфиг:

```yaml
# application.yml
llm:
  provider: claude          # claude | openai | gemini | local
  model: claude-sonnet-4-6
  base-url: ${LLM_BASE_URL:}
  api-key: ${LLM_API_KEY:}
  cache-enabled: true       # офлайн-демо: ответы golden case закэшированы
```

```java
// llm/LlmService.java — фасад
@Service
@RequiredArgsConstructor
public class LlmService {
    private final LlmAdapter adapter;          // выбран по конфигу
    private final LlmResponseCache cache;
    private final AuditService audit;

    public String explainRisk(SafeCaseJson json) { /* prompt → adapter → audit.logLlm(...) */ }
    public String generateReport(SafeCaseJson json) { /* ... */ }
    public String answerQuestion(SafeCaseJson json, String question) { /* chat-in-case */ }
}
```

Каждый вызов LLM пишется в Audit Log: provider, model, полный prompt,
полный response.

### Промпт генерации отчёта

```
Ты — помощник AML-аналитика банка. На основе структурированных данных
кейса составь черновик отчёта о подозрительной операции на русском языке.

Структура отчёта:
1. Сводка по кейсу
2. Описание подозрительной активности (строго по списку причин)
3. Выявленные связи
4. Оценка риска и её обоснование
5. Рекомендация (проверить / эскалировать / закрыть)

Используй ТОЛЬКО факты из JSON. Не придумывай данные.
Не изменяй Risk Score. Каждое утверждение опирайся на пункт Evidence.

Данные кейса:
{safe_json}
```

---

## 12. Audit Log

Каждый шаг расследования фиксируется. Кейс полностью воспроизводим.

```java
// audit/AuditService.java
@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repo;

    public void log(CasePipelineContext ctx, String eventType) {
        repo.save(AuditLog.builder()
            .caseId(ctx.getCaseId())
            .eventType(eventType)
            .rulesVersion(RuleEngineService.RULES_VERSION)   // "v1.0"
            .riskScore(ctx.getRisk() != null ? ctx.getRisk().score() : null)
            .actor("system")
            .build());
    }

    public void logLlm(Long caseId, String provider, String model,
                       String prompt, String response) {
        repo.save(AuditLog.builder()
            .caseId(caseId).eventType("llm_called")
            .llmProvider(provider).llmModel(model)
            .llmPrompt(prompt).llmResponse(response)
            .actor("system").build());
    }

    public void logDecision(Long caseId, String analyst, String decision) {
        repo.save(AuditLog.builder()
            .caseId(caseId).eventType("decision_made")
            .actor(analyst).build());
    }
}
```

Что хранится по каждому кейсу: Case ID, дата, версия правил, Risk Score,
доказательства, prompt в LLM, ответ LLM, модель, аналитик, решение.

Зачем (аргументы для судей): аудит регулятора, объяснимость решений,
воспроизводимость расследований, доверие банков.

---

## 13. Feature Flags — платформенность

```java
// featureflags/FeatureFlagService.java
@Service
@RequiredArgsConstructor
public class FeatureFlagService {
    private final FeatureFlagRepository repo;

    public boolean isEnabled(String moduleCode) {
        return repo.findByModuleCode(moduleCode)
                   .map(FeatureFlag::isEnabled).orElse(false);
    }
    public List<FeatureFlag> allModules() { return repo.findAll(); }
}
```

Экран `Modules.jsx` на фронте показывает платформу:

```text
AI Case Intelligence Platform
├── AML Investigation          ✅ активен (MVP)
├── Fraud Investigation        🔒 скоро
├── Credit Investigation       🔒 скоро
├── KYC Investigation          🔒 скоро
└── Compliance Investigation   🔒 скоро
```

Это слайд «масштабируемость» прямо внутри продукта — судьи видят,
что MVP является одним модулем большой платформы.

---

## 14. REST API

```
# Алерты
GET    /api/alerts                       список (фильтры: status, severity)
POST   /api/alerts/{id}/investigate      запустить Case Builder → case_id

# Кейсы
GET    /api/cases                        список кейсов
GET    /api/cases/{id}                   полное досье (dossier + evidence + explanation)
GET    /api/cases/{id}/graph             подграф для визуализации
GET    /api/cases/{id}/evidence          пакет доказательств
GET    /api/cases/{id}/explanation       объяснение Risk Score
PATCH  /api/cases/{id}/decision          { status, comment } → пишет Audit Log

# Отчёты
GET    /api/cases/{id}/report            черновик
PUT    /api/cases/{id}/report            правки аналитика

# AI
POST   /api/cases/{id}/chat              { question } → ответ по Safe JSON кейса

# Аудит
GET    /api/cases/{id}/audit             полная история расследования

# Платформа
GET    /api/modules                      feature flags (для экрана Modules)

# Dashboard
GET    /api/dashboard/metrics            время на кейс, обработано, экономия часов
```

Swagger UI: `/swagger-ui.html` (springdoc-openapi) — показать судьям.

---

## 15. Frontend — экраны

1. **Alert Queue** (`/alerts`) — таблица алертов, сортировка по severity,
   кнопка «Расследовать» → вызывает Case Builder → редирект в кейс.
2. **Case Workspace** (`/cases/:id`) — ГЛАВНЫЙ, три колонки:
   - слева: досье клиента + таймлайн транзакций (подозрительные — красным);
   - центр: интерактивный граф (react-force-graph-2d), подсветка цепочек;
   - справа: **EvidencePanel** (список доказательств) +
     **ExplainabilityPanel** (Risk Score с разбивкой причина→вклад) +
     блок «Объяснение AI».
3. **Report View** (`/cases/:id/report`) — редактируемый черновик,
   кнопки Утвердить / Отклонить / Эскалировать (решение → Audit Log).
4. **Dashboard** (`/dashboard`) — метрики: время на кейс до/после,
   обработано кейсов, часов сэкономлено.
5. **Modules** (`/modules`) — платформенность: AML ✅, остальные 🔒.

Дизайн: строгая тёмная «банковская» тема.

---

## 16. Генератор синтетических данных

~5000 клиентов, ~500 компаний, ~100 000 фоновых транзакций
+ 6 срежиссированных схем (по одной на ключевые правила):

| Схема | Правила, которые она триггерит |
|---|---|
| structuring | R01 |
| circular | R03 |
| transit | R02 |
| newCompanySpike | R04 |
| fanInOut | R09 |
| **goldenCase** | R01 + R03 + R07 + история (главный демо-кейс) |

Golden case полируется вручную: клиент + 3 подставные фирмы + общий
директор + общее устройство с blacklisted-клиентом + предыдущий алерт.
Красивый граф, Risk Score ~91, кэшированные ответы LLM → демо офлайн.

Реализация: standalone-скрипт (Node или Python), генерирующий SQL/CSV
для загрузки, либо Java-модуль `seed` в backend с CommandLineRunner
(`--seed` профиль). Для скорости разработки допустим любой вариант —
судьи оценивают продукт, а не сидер.

---

## 17. docker-compose.yml

```yaml
version: "3.9"
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: case_intelligence
      POSTGRES_USER: app
      POSTGRES_PASSWORD: app_secret
    ports: ["5432:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]

  backend:
    build: ./backend            # Spring Boot, порт 8080
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/case_intelligence
      SPRING_DATASOURCE_USERNAME: app
      SPRING_DATASOURCE_PASSWORD: app_secret
      LLM_PROVIDER: ${LLM_PROVIDER:-claude}
      LLM_API_KEY: ${LLM_API_KEY:-}
      LLM_CACHE_ENABLED: "true"
    depends_on: [db]
    ports: ["8080:8080"]

  frontend:
    build: ./frontend
    depends_on: [backend]
    ports: ["5173:5173"]

volumes:
  pgdata:
```

---

## 18. Порядок реализации (маппинг на 50 дней)

| Этап | Дни | Что делаем |
|---|---|---|
| 1. Дизайн | 1–5 | Финализация spec, Flyway-миграции, каркас Spring Boot, разбивка на промпты |
| 2. Данные | 6–12 | seed-генератор: фон + 6 схем + golden case |
| 3. Backend core | 13–24 | Entities, Data Collector, 10 правил, Graph Engine, Evidence Collector, Risk Engine, Explainability, Case Builder, API |
| 4. Frontend | 25–33 | 5 экранов, граф, Evidence/Explainability панели |
| 5. AI Layer | 34–39 | Safe JSON, адаптеры, кэш, Audit для LLM |
| 6. Полировка | 40–45 | Golden case, метрики, Audit-экран, видео-бэкап демо |
| 7. Питч | 46–50 | Слайды, репетиции, ответы судьям |

Примечание: этап Backend расширен на 2 дня (Java медленнее в разработке,
чем Node, + новые компоненты Evidence/Explainability/Audit).

---

## 19. Заготовленные ответы на вопросы судей

| Вопрос | Ответ |
|---|---|
| Откуда данные? | Синтетический датасет с реалистичными паттернами отмывания; в проде — API банковских систем, данные не покидают банк |
| Нужны GPU? | Нет. Правила, граф, риск — детерминированные. LLM — только финальный текст |
| Можно без OpenAI? | Да. AI Adapter Layer: OpenAI / Claude / Gemini / Llama / Qwen / DeepSeek. On-premise ready |
| Видит ли LLM банковские данные? | Нет. LLM получает только Safe JSON: score, причины, доказательства-агрегаты. Ни SQL, ни таблиц, ни счетов |
| Почему AI не ошибётся в выводах? | AI не делает выводов. Score и причины считают детерминированные движки; LLM только переводит готовые факты в текст |
| Как проходит аудит? | Полный Audit Log: версия правил, score, evidence, prompt, ответ LLM, модель, решение аналитика. Каждый кейс воспроизводим |
| Чем лучше SAS / Actimize? | Они детектируют, мы автоматизируем расследование ПОСЛЕ алерта; дешевле в разы; локализация ru/uz |
| Кто принимает решение? | Всегда человек (human-in-the-loop). Система собирает, объясняет, рекомендует |
| Масштабируемость? | Платформа с feature flags: AML — MVP, дальше Fraud / Credit / KYC / Compliance на том же Case Builder |
| Сколько стоит внедрение? | Слой поверх существующих систем через API, без перестройки инфраструктуры банка |
