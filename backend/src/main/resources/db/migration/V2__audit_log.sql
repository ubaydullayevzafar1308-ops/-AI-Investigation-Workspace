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
