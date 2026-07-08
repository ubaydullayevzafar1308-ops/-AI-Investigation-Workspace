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
