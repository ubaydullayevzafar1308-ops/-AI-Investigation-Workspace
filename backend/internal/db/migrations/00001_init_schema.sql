-- ============ КЛИЕНТЫ ============
CREATE TABLE clients (
    id                BIGSERIAL PRIMARY KEY,
    full_name         VARCHAR(255) NOT NULL,
    birth_date        DATE,
    inn               VARCHAR(14) UNIQUE,
    phone             VARCHAR(20),
    device_id         VARCHAR(64),
    address           TEXT,
    registration_date DATE NOT NULL,
    client_type       VARCHAR(20) NOT NULL DEFAULT 'individual',
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
    owner_type     VARCHAR(10) NOT NULL,
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
    severity       VARCHAR(10) NOT NULL,
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
    analyst_decision TEXT,
    dossier_json     JSONB,
    evidence_json    JSONB,
    explanation_json JSONB,
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

