# AI Layer — Архитектура LLM-модуля

## AI Case Intelligence Platform · Отдельный документ для разработчика AI-слоя

Версия: 1.0 (соответствует ARCHITECTURE.md v2.0, разделы 11-12)
Стек: Java 21, Spring Boot 3.3, PostgreSQL
Твоя зона ответственности: пакет `uz.caseintel.llm` + интеграция с Audit Log

---

## 1. Контекст: что за продукт и где твоя часть

Мы делаем платформу для банков, которая автоматически превращает Alert
(сигнал о подозрительной операции) в готовое расследование (Case).

Пайплайн системы:

```
Alert → Data Collector → Rule Engine → Graph Engine
     → Evidence Collector → Risk Engine → Explainability Engine
     → ┌─────────────────────────────────────────┐
       │            ТВОЯ ЧАСТЬ (AI Layer)         │
       │  ⑦ Safe JSON  →  ⑧ AI Adapter  →  ⑨ Отчёт │
       └─────────────────────────────────────────┘
     → Ready Case → Analyst → Decision → Audit Log
```

К моменту, когда данные попадают к тебе, ВСЯ аналитика уже сделана
детерминированными движками:
- Risk Score (0-100) уже посчитан;
- список причин («круговая схема +25», «дробление +20») уже готов;
- доказательства (evidence) уже собраны.

**Роль LLM — только язык, не анализ.** LLM переводит готовые факты
в человеческий текст. Он не считает риск, не ищет связи, не делает
выводов. Это принципиально: так мы отвечаем судьям на вопросы
«а если AI ошибётся?» и «а если AI галлюцинирует?» — AI не может
ошибиться в анализе, потому что анализ делает не он.

---

## 2. Три функции, которые ты реализуешь

| # | Функция | Вход | Выход | Когда вызывается |
|---|---|---|---|---|
| 1 | `explainRisk` | SafeCaseJson | Объяснение риска на русском, 1-2 абзаца | Автоматически при сборке кейса |
| 2 | `generateReport` | SafeCaseJson | Черновик отчёта о подозрительной операции (структурированный) | Автоматически при сборке кейса |
| 3 | `answerQuestion` | SafeCaseJson + вопрос аналитика | Ответ по фактам кейса | По запросу из чата на фронте |

---

## 3. Главное правило безопасности: Safe JSON

**LLM НИКОГДА не получает банковские данные.**

LLM НЕ получает:
- ФИО клиентов, ИНН, номера счетов, телефоны;
- сырые таблицы транзакций и полную историю операций;
- SQL, доступ к БД в любом виде.

LLM получает ТОЛЬКО `SafeCaseJson`:
- итоговый Risk Score и уровень;
- список причин из Explainability Engine (фактор, вклад, деталь);
- доказательства-агрегаты (заголовки, суммы, количества);
- обезличенный профиль клиента (тип, стаж в банке, число счетов);
- **псевдонимы вместо имён**: «Клиент К-1», «Компания С-1», «Компания С-2».

Таблица соответствия псевдоним ↔ реальный субъект живёт только на
бэкенде (в dossier_json кейса) и в LLM не уходит.

### Структура SafeCaseJson

```java
public record SafeCaseJson(
    int riskScore,                 // 91
    String riskLevel,              // "high"
    ClientProfile client,          // обезличенный профиль
    List<Reason> reasons,          // из Explainability Engine
    List<SafeEvidence> evidence    // агрегаты доказательств
) {
    public record ClientProfile(
        String pseudonym,          // "Клиент К-1"
        String clientType,         // individual | entrepreneur
        int yearsWithBank,
        int accountsCount,
        boolean hasPreviousAlerts
    ) {}

    public record Reason(
        String factor,             // "Круговая схема переводов"
        int contribution,          // 25
        String detail              // "К-1 → С-1 → С-2 → К-1, оборот 850 млн UZS"
    ) {}

    public record SafeEvidence(
        String type,               // rule_hit | relation | previous_alert | anomaly
        String title,              // "Rule R03: Circular flow"
        Map<String, Object> aggregates  // {"totalAmount": "850 млн UZS", "txCount": 9}
    ) {}
}
```

### SafeJsonMapper

Твой класс `SafeJsonMapper.toSafeJson(CasePipelineContext ctx)`:
1. Берёт из контекста RiskResult, ExplanationDto, EvidenceBundle, DossierDto.
2. Строит словарь псевдонимов: обходит всех субъектов кейса, назначает
   К-1, К-2… для клиентов и С-1, С-2… для компаний (исходный клиент — всегда К-1).
3. Прогоняет ВСЕ строки (details, titles) через замену реальных имён на
   псевдонимы. Числа-агрегаты (суммы, количества) оставляет.
4. Отбрасывает всё, чего нет в структуре SafeCaseJson.

Тест-инвариант (обязательный unit-тест): сериализованный SafeCaseJson
для golden case не содержит ни одного ФИО, ИНН и номера счёта из досье.

---

## 4. AI Adapter Layer — мультипровайдер

Система не привязана к одному вендору. Единый интерфейс:

```java
public interface LlmAdapter {
    String complete(String systemPrompt, String userPrompt);
    String providerName();   // "claude" | "openai" | "local" — для Audit Log
    String modelName();      // "claude-sonnet-4-6" и т.п.
}
```

Реализации (в `uz.caseintel.llm.providers`):

| Адаптер | API | Приоритет |
|---|---|---|
| `ClaudeAdapter` | Anthropic Messages API | Основной для демо |
| `LocalLlmAdapter` | OpenAI-совместимый `/v1/chat/completions` (Ollama, vLLM → Llama/Qwen/DeepSeek) | Обязателен — это наш аргумент «on-premise» для судей |
| `OpenAiAdapter`, `GeminiAdapter` | соответствующие API | Опционально, если останется время |

Выбор провайдера — только конфигом, без изменения кода:

```yaml
# application.yml
llm:
  provider: claude            # claude | openai | gemini | local
  model: claude-sonnet-4-6
  base-url: ${LLM_BASE_URL:}  # для local: http://localhost:11434/v1
  api-key: ${LLM_API_KEY:}
  cache-enabled: true
  timeout-seconds: 60
```

```java
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {
    @Bean
    LlmAdapter llmAdapter(LlmProperties props) {
        return switch (props.provider()) {
            case "claude" -> new ClaudeAdapter(props);
            case "local"  -> new LocalLlmAdapter(props);
            case "openai" -> new OpenAiAdapter(props);
            default -> throw new IllegalStateException("Unknown LLM provider");
        };
    }
}
```

Детали ClaudeAdapter: POST https://api.anthropic.com/v1/messages,
заголовки `x-api-key`, `anthropic-version: 2023-06-01`, тело
`{model, max_tokens: 2000, system, messages:[{role:"user",content}]}`,
ответ — `content[0].text`. Используй Spring `RestClient`.

---

## 5. Кэш ответов — офлайн-демо

На демо хакатона интернета может не быть. Поэтому:

`LlmResponseCache` — файловый кэш:
- ключ: SHA-256 от (provider + model + systemPrompt + userPrompt);
- значение: ответ LLM, файл `./llm-cache/{hash}.txt`;
- при `llm.cache-enabled=true`: сначала смотрим кэш, при промахе — зовём
  API и сохраняем.

Перед демо мы «прогреваем» кэш на golden case (объяснение, отчёт,
3 типовых вопроса чата) — и вся система работает без сети.

---

## 6. Устойчивость к ошибкам

Пайплайн сборки кейса НЕ должен падать из-за LLM. Правила:

1. Любая ошибка LLM (сеть, ключ, таймаут, 429) → лог + fallback-текст:
   - explainRisk → «Автоматическое объяснение временно недоступно.
     См. структурированные причины выше.»
   - generateReport → «Черновик отчёта будет сгенерирован позже.»
2. Кейс при этом сохраняется полноценно: score, evidence, объяснение
   из Explainability Engine не зависят от LLM вообще.
3. Таймаут на вызов — 60 секунд, один повтор при сетевой ошибке.

---

## 7. Audit Log — каждый вызов фиксируется

Требование банковского аудита: каждый вызов LLM полностью записан.
После КАЖДОГО обращения к адаптеру вызывай (сервис уже есть в проекте):

```java
audit.logLlm(caseId,
    adapter.providerName(),   // "claude"
    adapter.modelName(),      // "claude-sonnet-4-6"
    fullPrompt,               // system + user, полностью
    fullResponse);            // ответ, полностью
```

Это же — способ ПРОВЕРИТЬ безопасность: в записанном промпте не должно
быть реальных имён (см. тест-инвариант из раздела 3).

---

## 8. Промпты

Все промпты — константы в `Prompts.java`. Языки: русский (основной),
узбекский (если успеем — параметр `lang`).

### 8.1 Системный промпт (общий)

```
Ты — ассистент AML-аналитика банка в системе AI Case Intelligence Platform.
Ты работаешь ТОЛЬКО с предоставленными структурированными данными кейса.

Жёсткие правила:
1. Используй ТОЛЬКО факты из JSON. Ничего не придумывай и не домысливай.
2. Не изменяй и не пересчитывай Risk Score — он уже рассчитан системой.
3. Каждое утверждение опирай на конкретный пункт из reasons или evidence.
4. Субъекты называй только их метками (Клиент К-1, Компания С-1).
5. Не давай юридических заключений о виновности. Формулировки:
   «признаки», «паттерн, характерный для», «требует проверки».
6. Пиши на русском языке, деловым стилем, без воды.
```

### 8.2 explainRisk (user prompt)

```
Ниже — структурированные данные кейса. Напиши краткое объяснение
(1-2 абзаца, до 120 слов) для аналитика: почему у кейса такой уровень
риска и на что обратить внимание в первую очередь. Начни с главного
фактора (наибольший вклад).

Данные кейса:
{safe_json}
```

### 8.3 generateReport (user prompt)

```
Ниже — структурированные данные кейса. Составь черновик внутреннего
отчёта о подозрительной операции.

Структура отчёта (используй именно эти заголовки):
1. Сводка по кейсу
2. Описание подозрительной активности
3. Выявленные связи
4. Оценка риска и её обоснование
5. Рекомендация

В п.2 — по пункту на каждую причину из reasons, с цифрами из evidence.
В п.5 — одна из: «направить на дополнительную проверку»,
«эскалировать в комплаенс», «закрыть как ложное срабатывание» —
выбери по уровню риска (high → эскалировать).

Данные кейса:
{safe_json}
```

### 8.4 answerQuestion (user prompt)

```
Ниже — структурированные данные кейса и вопрос аналитика.
Ответь кратко и по фактам. Если ответа в данных нет — прямо скажи:
«В данных кейса этой информации нет», не придумывай.

Данные кейса:
{safe_json}

Вопрос аналитика:
{question}
```

---

## 9. Фасад LlmService и точки интеграции

```java
@Service
@RequiredArgsConstructor
public class LlmService {
    private final LlmAdapter adapter;
    private final LlmResponseCache cache;
    private final AuditService audit;

    public String explainRisk(Long caseId, SafeCaseJson json) { ... }
    public String generateReport(Long caseId, SafeCaseJson json) { ... }
    public String answerQuestion(Long caseId, SafeCaseJson json, String question) { ... }
}
```

Каждый метод: собрать промпт → кэш → (адаптер при промахе) →
audit.logLlm → вернуть текст (или fallback из раздела 6).

Кто тебя вызывает (контракт с остальной системой):
1. `CaseBuilderService` (шаги ⑦⑧⑨ пайплайна): toSafeJson → explainRisk
   (результат в поле humanText внутри explanation_json кейса) →
   generateReport (результат в reports.draft_text).
2. `ChatController`: POST /api/cases/{id}/chat, body `{question}` →
   answerQuestion → `{answer}`.

Тебе НЕ нужно трогать: правила, граф, риск, фронтенд. Ты берёшь готовый
CasePipelineContext и возвращаешь тексты.

---

## 10. Структура твоих файлов

```
backend/src/main/java/uz/caseintel/llm/
├── SafeCaseJson.java            # record: безопасный срез кейса
├── SafeJsonMapper.java          # контекст → SafeCaseJson + псевдонимизация
├── LlmAdapter.java              # интерфейс провайдера
├── LlmProperties.java           # @ConfigurationProperties(prefix="llm")
├── LlmConfig.java               # выбор адаптера по конфигу
├── LlmService.java              # фасад: 3 функции + кэш + audit + fallback
├── Prompts.java                 # все промпты-константы
├── providers/
│   ├── ClaudeAdapter.java
│   ├── LocalLlmAdapter.java     # Ollama/vLLM (OpenAI-совместимый)
│   ├── OpenAiAdapter.java       # опционально
│   └── GeminiAdapter.java       # опционально
└── cache/
    └── LlmResponseCache.java    # файловый кэш по SHA-256 промпта
```

---

## 11. Чеклист готовности (приёмка)

- [ ] SafeCaseJson для golden case не содержит ФИО/ИНН/номеров счетов (unit-тест)
- [ ] Псевдонимы стабильны в рамках кейса (К-1 везде один и тот же субъект)
- [ ] ClaudeAdapter работает с реальным ключом: осмысленный русский отчёт по структуре из 8.3
- [ ] LocalLlmAdapter работает с Ollama (проверить хотя бы на llama3/qwen локально)
- [ ] Смена провайдера — только правкой application.yml, без перекомпиляции логики
- [ ] Кэш: повторный вызов с тем же промптом не ходит в сеть (видно по логам)
- [ ] Неверный ключ / нет сети → кейс собирается, fallback-тексты на месте, пайплайн не падает
- [ ] Каждый вызов — запись в audit_log: provider, model, полный prompt, полный response
- [ ] Чат: вопрос «какие компании связаны с клиентом?» по golden case → ответ с метками С-1/С-2 по фактам
- [ ] Вопрос о том, чего нет в данных → честное «в данных кейса этой информации нет»

## 12. Ответы судьям по твоей части (знать наизусть)

| Вопрос | Ответ |
|---|---|
| Видит ли LLM банковские данные? | Нет. Только Safe JSON: score, причины, агрегаты, псевдонимы. Это проверяемо — каждый prompt записан в Audit Log |
| Что если LLM галлюцинирует? | LLM не анализирует — анализ детерминированный. LLM только формулирует готовые факты; промпт запрещает домыслы, чат честно отвечает «данных нет» |
| Привязка к OpenAI/Anthropic? | Нет. Adapter Layer: провайдер меняется одной строкой конфига, включая локальные Llama/Qwen внутри контура банка |
| Как аудитировать AI? | Каждый вызов в audit_log: модель, полный промпт, полный ответ. Любой кейс воспроизводим |
