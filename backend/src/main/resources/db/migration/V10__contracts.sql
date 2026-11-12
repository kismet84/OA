ALTER TABLE workflow_config ADD COLUMN contract_threshold numeric(14,2) NOT NULL DEFAULT 50000 CHECK(contract_threshold>=0);
CREATE SEQUENCE contract_code_seq;
CREATE TABLE contracts (
 id bigserial PRIMARY KEY, code varchar(100) NOT NULL UNIQUE, name varchar(200) NOT NULL,
 type varchar(20) NOT NULL CHECK(type IN ('PURCHASE','SALES','SERVICE','LEASE','LABOR','OTHER')),
 counterparty varchar(200) NOT NULL, contact varchar(100), phone varchar(50),
 user_id bigint NOT NULL REFERENCES users(id), department_id bigint NOT NULL REFERENCES departments(id),
 amount numeric(14,2) CHECK(amount>=0), signed_date date NOT NULL, effective_date date NOT NULL, expiry_date date,
 performance varchar(5000), note varchar(5000),
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PENDING','REJECTED','WITHDRAWN','TO_ARCHIVE','ACTIVE','TERMINATED')),
 current_step integer NOT NULL DEFAULT 1, revision integer NOT NULL DEFAULT 0,
 termination_reason varchar(1000), archived_at timestamp, created_at timestamp NOT NULL DEFAULT localtimestamp
);
CREATE TABLE contract_steps (
 contract_id bigint NOT NULL REFERENCES contracts(id) ON DELETE CASCADE, revision integer NOT NULL,
 step integer NOT NULL, approver_id bigint REFERENCES users(id),
 status varchar(20) NOT NULL DEFAULT 'PENDING', actor_id bigint REFERENCES users(id), note varchar(1000), acted_at timestamp,
 PRIMARY KEY(contract_id,revision,step)
);
CREATE TABLE contract_files (
 contract_id bigint NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
 attachment_id uuid NOT NULL REFERENCES attachments(id), sealed boolean NOT NULL DEFAULT false,
 PRIMARY KEY(contract_id,attachment_id)
);
CREATE TABLE contract_reminders (
 contract_id bigint NOT NULL REFERENCES contracts(id) ON DELETE CASCADE, node integer NOT NULL CHECK(node IN (30,7)),
 sent_at timestamp NOT NULL DEFAULT localtimestamp, PRIMARY KEY(contract_id,node)
);
ALTER TABLE notifications ADD COLUMN contract_id bigint REFERENCES contracts(id) ON DELETE SET NULL;
CREATE INDEX contract_owner ON contracts(user_id);
CREATE INDEX contract_expiry ON contracts(expiry_date);
CREATE INDEX contract_pending ON contracts(status,current_step);
