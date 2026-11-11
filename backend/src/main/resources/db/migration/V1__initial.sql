CREATE TABLE departments (
 id bigserial PRIMARY KEY, name varchar(100) NOT NULL, parent_id bigint REFERENCES departments(id), head_id bigint
);
CREATE TABLE users (
 id bigserial PRIMARY KEY, employee_no varchar(40) UNIQUE NOT NULL, phone varchar(20) UNIQUE,
 name varchar(80) NOT NULL, password_hash varchar(100) NOT NULL, roles varchar(200) NOT NULL,
 department_id bigint NOT NULL REFERENCES departments(id), manager_id bigint REFERENCES users(id),
 enabled boolean NOT NULL DEFAULT true, must_change_password boolean NOT NULL DEFAULT true,
 session_version int NOT NULL DEFAULT 0, created_at timestamp NOT NULL DEFAULT current_timestamp
);
ALTER TABLE departments ADD CONSTRAINT department_head_fk FOREIGN KEY(head_id) REFERENCES users(id);
CREATE TABLE networks (id bigserial PRIMARY KEY, kind varchar(10) NOT NULL CHECK(kind IN ('SSID','IP')), value varchar(200) NOT NULL, UNIQUE(kind,value));
CREATE TABLE leave_types (id bigserial PRIMARY KEY, code varchar(30) UNIQUE NOT NULL, name varchar(60) NOT NULL,
 deduct_balance boolean NOT NULL DEFAULT false, annual_limit numeric(6,1), proof_required boolean NOT NULL DEFAULT false, enabled boolean NOT NULL DEFAULT true);
CREATE TABLE balances (user_id bigint REFERENCES users(id), type_id bigint REFERENCES leave_types(id), year int NOT NULL, amount numeric(6,1) NOT NULL CHECK(amount>=0), PRIMARY KEY(user_id,type_id,year));
CREATE TABLE workflow_config (id int PRIMARY KEY CHECK(id=1), leave_threshold numeric(6,1) NOT NULL, travel_threshold numeric(6,1) NOT NULL, expense_threshold numeric(12,2) NOT NULL, finance_id bigint REFERENCES users(id));
INSERT INTO workflow_config VALUES(1,1,1,1000,NULL);
INSERT INTO leave_types(code,name,deduct_balance,annual_limit,proof_required) VALUES ('ANNUAL','年假',true,NULL,false),('PERSONAL','事假',false,NULL,false),('SICK','病假',false,NULL,false),('COMP','调休',true,NULL,false),('MARRIAGE','婚假',false,3,true),('MATERNITY','产假',false,98,true),('PATERNITY','陪产假',false,15,true);
CREATE TABLE attachments (id uuid PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), original_name varchar(200) NOT NULL, media_type varchar(80) NOT NULL, created_at timestamp NOT NULL DEFAULT current_timestamp);
CREATE TABLE requests (id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), kind varchar(20) NOT NULL CHECK(kind IN ('LEAVE','TRAVEL','OUTING','EXPENSE')),
 type_id bigint REFERENCES leave_types(id), start_at timestamp, end_at timestamp, days numeric(6,1), amount numeric(12,2), reason varchar(2000) NOT NULL,
 attachment_id uuid REFERENCES attachments(id), status varchar(20) NOT NULL DEFAULT 'PENDING', current_step int NOT NULL DEFAULT 1,
 deduct_balance boolean NOT NULL DEFAULT false, annual_limit numeric(6,1), created_at timestamp NOT NULL DEFAULT current_timestamp);
CREATE TABLE approval_steps (request_id bigint REFERENCES requests(id), step int NOT NULL, approver_id bigint NOT NULL REFERENCES users(id), status varchar(20) NOT NULL DEFAULT 'PENDING', comment varchar(1000), acted_at timestamp, PRIMARY KEY(request_id,step));
CREATE TABLE notifications (id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), message varchar(500) NOT NULL, read_at timestamp, created_at timestamp NOT NULL DEFAULT current_timestamp);
CREATE TABLE punches (id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), punched_at timestamp NOT NULL, kind varchar(20) NOT NULL CHECK(kind IN ('NORMAL','FIELD')), latitude double precision NOT NULL, longitude double precision NOT NULL, accuracy double precision NOT NULL, ssid varchar(200), exit_ip varchar(100) NOT NULL, simulated boolean NOT NULL DEFAULT false);
CREATE TABLE audit_logs (id bigserial PRIMARY KEY, actor_id bigint REFERENCES users(id), action varchar(100) NOT NULL, target varchar(200) NOT NULL, created_at timestamp NOT NULL DEFAULT current_timestamp);
CREATE INDEX request_user_time ON requests(user_id,start_at,end_at);
CREATE INDEX approval_assignee ON approval_steps(approver_id,status);
CREATE INDEX punch_time ON punches(punched_at);
