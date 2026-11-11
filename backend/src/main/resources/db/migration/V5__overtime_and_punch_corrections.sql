ALTER TABLE requests DROP CONSTRAINT requests_kind_check;
ALTER TABLE requests ADD CONSTRAINT requests_kind_check CHECK(kind IN ('LEAVE','TRAVEL','OUTING','EXPENSE','OVERTIME','CORRECTION'));
ALTER TABLE workflow_config ADD COLUMN correction_days int NOT NULL DEFAULT 3 CHECK(correction_days BETWEEN 0 AND 365);
ALTER TABLE workflow_config ADD COLUMN overtime_ratio numeric(6,2) NOT NULL DEFAULT 1 CHECK(overtime_ratio > 0 AND overtime_ratio <= 100);
ALTER TABLE requests ADD COLUMN punch_kind varchar(20) CHECK(punch_kind IN ('NORMAL','FIELD'));
ALTER TABLE requests ADD COLUMN overtime_ratio numeric(6,2);
ALTER TABLE requests ADD COLUMN credited_days numeric(12,4);
ALTER TABLE balances ALTER COLUMN amount TYPE numeric(12,4);
ALTER TABLE punches ALTER COLUMN latitude DROP NOT NULL;
ALTER TABLE punches ALTER COLUMN longitude DROP NOT NULL;
ALTER TABLE punches ALTER COLUMN accuracy DROP NOT NULL;
ALTER TABLE punches ALTER COLUMN exit_ip DROP NOT NULL;
ALTER TABLE punches ADD COLUMN correction_request_id bigint UNIQUE REFERENCES requests(id);
CREATE UNIQUE INDEX correction_active ON requests(user_id,start_at,punch_kind) WHERE kind='CORRECTION' AND status IN ('PENDING','APPROVED');
CREATE TABLE balance_changes (
 id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), type_id bigint NOT NULL REFERENCES leave_types(id),
 year int NOT NULL, delta numeric(12,4) NOT NULL, balance_after numeric(12,4) NOT NULL,
 request_id bigint REFERENCES requests(id), actor_id bigint NOT NULL REFERENCES users(id),
 reason varchar(100) NOT NULL, created_at timestamp NOT NULL DEFAULT current_timestamp
);
CREATE INDEX balance_changes_user ON balance_changes(user_id,year,id);
