CREATE TABLE audit_log (
    id             BIGSERIAL PRIMARY KEY,
    case_id        BIGINT REFERENCES cases(id),
    event_type     VARCHAR(40) NOT NULL,
    rules_version  VARCHAR(20),
    risk_score     INTEGER,
    evidence_json  JSONB,
    llm_provider   VARCHAR(30),
    llm_model      VARCHAR(60),
    llm_prompt     TEXT,
    llm_response   TEXT,
    actor          VARCHAR(100),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_case ON audit_log(case_id, created_at);

