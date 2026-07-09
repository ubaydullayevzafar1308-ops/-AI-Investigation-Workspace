-- FK-колонки, по которым приложение постоянно фильтрует, но для которых
-- Postgres не создаёт индексы автоматически:
--   alerts.client_id  — история алертов клиента (Data Collector, buildPastAlerts)
--   cases.client_id   — кейсы клиента
--   rule_hits.case_id — сработавшие правила кейса
--   reports.case_id   — отчёт кейса
CREATE INDEX idx_alerts_client    ON alerts(client_id);
CREATE INDEX idx_cases_client     ON cases(client_id);
CREATE INDEX idx_rule_hits_case   ON rule_hits(case_id);
CREATE INDEX idx_reports_case     ON reports(case_id);
