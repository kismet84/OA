CREATE TABLE expense_items (
 request_id bigint NOT NULL REFERENCES requests(id), line_no int NOT NULL,
 description varchar(2000) NOT NULL, amount numeric(12,2) NOT NULL CHECK(amount>0),
 PRIMARY KEY(request_id,line_no)
);
CREATE TABLE expense_attachments (
 request_id bigint NOT NULL REFERENCES requests(id), attachment_id uuid NOT NULL REFERENCES attachments(id),
 line_no int NOT NULL, PRIMARY KEY(request_id,attachment_id), UNIQUE(request_id,line_no)
);
-- 保留一期历史单据，将原有总额/附件映射到新结构，不补造缺失票据。
INSERT INTO expense_items SELECT id,1,reason,amount FROM requests WHERE kind='EXPENSE' AND amount>0;
INSERT INTO expense_attachments SELECT id,attachment_id,1 FROM requests WHERE kind='EXPENSE' AND attachment_id IS NOT NULL;
ALTER TABLE requests ADD COLUMN payment_status varchar(20) CHECK(payment_status IN ('UNPAID','PAID'));
ALTER TABLE requests ADD COLUMN payment_actor_id bigint REFERENCES users(id);
ALTER TABLE requests ADD COLUMN payment_acted_at timestamp;
CREATE TABLE expense_payment_history (
 id bigserial PRIMARY KEY, request_id bigint NOT NULL REFERENCES requests(id),
 status varchar(20) NOT NULL CHECK(status IN ('UNPAID','PAID')),
 actor_id bigint NOT NULL REFERENCES users(id), acted_at timestamp NOT NULL, reason varchar(1000)
);
UPDATE requests SET payment_status='UNPAID' WHERE kind='EXPENSE' AND status='APPROVED';
