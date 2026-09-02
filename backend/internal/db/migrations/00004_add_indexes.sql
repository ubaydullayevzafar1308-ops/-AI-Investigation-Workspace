CREATE INDEX idx_alerts_client  ON alerts(client_id);
CREATE INDEX idx_cases_client   ON cases(client_id);
CREATE INDEX idx_rule_hits_case ON rule_hits(case_id);
CREATE INDEX idx_reports_case   ON reports(case_id);

