CREATE TABLE announcements (
 id bigserial PRIMARY KEY, title varchar(200) NOT NULL, body text NOT NULL,
 department_id bigint REFERENCES departments(id), publisher_id bigint NOT NULL REFERENCES users(id),
 published_at timestamp NOT NULL DEFAULT current_timestamp, withdrawn_at timestamp
);
CREATE TABLE announcement_recipients (
 announcement_id bigint REFERENCES announcements(id), user_id bigint REFERENCES users(id),
 name varchar(80) NOT NULL, employee_no varchar(40) NOT NULL, department_name varchar(100) NOT NULL,
 read_at timestamp, PRIMARY KEY(announcement_id,user_id)
);
CREATE INDEX announcement_recipient_user ON announcement_recipients(user_id,announcement_id);
CREATE TABLE announcement_attachments (
 announcement_id bigint REFERENCES announcements(id), attachment_id uuid REFERENCES attachments(id),
 PRIMARY KEY(announcement_id,attachment_id)
);
CREATE TABLE request_cc (
 request_id bigint REFERENCES requests(id), user_id bigint REFERENCES users(id),
 PRIMARY KEY(request_id,user_id)
);
CREATE INDEX request_cc_user ON request_cc(user_id,request_id);
ALTER TABLE notifications ADD COLUMN request_id bigint REFERENCES requests(id);
CREATE INDEX notification_user_unread ON notifications(user_id,read_at);
