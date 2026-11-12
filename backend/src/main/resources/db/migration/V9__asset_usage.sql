CREATE TABLE asset_categories (
 id bigserial PRIMARY KEY, name varchar(100) NOT NULL UNIQUE,
 kind varchar(20) NOT NULL CHECK(kind IN ('ITEM','CONSUMABLE')), enabled boolean NOT NULL DEFAULT true
);
CREATE TABLE assets (
 id bigserial PRIMARY KEY, category_id bigint NOT NULL REFERENCES asset_categories(id),
 code varchar(100) UNIQUE, name varchar(100) NOT NULL, brand varchar(200), serial_number varchar(200),
 purchase_date date, location varchar(200), note varchar(2000), attachment_id uuid REFERENCES attachments(id),
 unit varchar(30), stock integer NOT NULL DEFAULT 0 CHECK(stock>=0),
 status varchar(20) NOT NULL DEFAULT 'AVAILABLE' CHECK(status IN ('AVAILABLE','ASSIGNED','REPAIR','SCRAPPED')),
 enabled boolean NOT NULL DEFAULT true, created_at timestamp NOT NULL DEFAULT localtimestamp
);
CREATE TABLE asset_requests (
 id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), department_id bigint NOT NULL REFERENCES departments(id),
 category_id bigint NOT NULL REFERENCES asset_categories(id), consumable_id bigint REFERENCES assets(id),
 quantity integer NOT NULL CHECK(quantity>0), purpose varchar(2000) NOT NULL, expected_date date NOT NULL,
 needs_return boolean NOT NULL, return_date date, approver_id bigint REFERENCES users(id),
 status varchar(20) NOT NULL CHECK(status IN ('PENDING','READY','ISSUED','IN_USE','RETURNED','REJECTED','WITHDRAWN')),
 approval_note varchar(1000), issue_note varchar(1000), return_note varchar(1000),
 return_requested boolean NOT NULL DEFAULT false, issued_at timestamp, returned_at timestamp,
 created_at timestamp NOT NULL DEFAULT localtimestamp
);
CREATE TABLE asset_assignments (
 request_id bigint NOT NULL REFERENCES asset_requests(id), asset_id bigint NOT NULL REFERENCES assets(id),
 user_id bigint NOT NULL REFERENCES users(id), issued_at timestamp NOT NULL DEFAULT localtimestamp, returned_at timestamp,
 PRIMARY KEY(request_id,asset_id)
);
CREATE UNIQUE INDEX asset_single_holder ON asset_assignments(asset_id) WHERE returned_at IS NULL;
CREATE INDEX asset_request_user ON asset_requests(user_id,id);
CREATE INDEX asset_request_pending ON asset_requests(status,approver_id);
ALTER TABLE notifications ADD COLUMN asset_request_id bigint REFERENCES asset_requests(id);
