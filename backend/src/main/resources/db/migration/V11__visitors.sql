CREATE TABLE visitors (
 id bigserial PRIMARY KEY,
 host_id bigint NOT NULL REFERENCES users(id),
 name varchar(100) NOT NULL, phone varchar(20) NOT NULL, company varchar(200) NOT NULL,
 purpose varchar(1000) NOT NULL, people integer NOT NULL CHECK(people BETWEEN 1 AND 100),
 companions varchar(1000), start_at timestamp NOT NULL, end_at timestamp NOT NULL CHECK(end_at>start_at),
 plate varchar(30), note varchar(2000), code varchar(6) NOT NULL CHECK(code ~ '^[0-9]{6}$'),
 active_code varchar(6) UNIQUE CHECK(active_code=code),
 status varchar(20) NOT NULL CHECK(status IN ('WAITING','ARRIVED','LEFT','CANCELLED')),
 temporary boolean NOT NULL DEFAULT false, checked_in_at timestamp, checked_out_at timestamp,
 created_at timestamp NOT NULL DEFAULT localtimestamp,
 CHECK((status='ARRIVED' AND checked_in_at IS NOT NULL AND checked_out_at IS NULL)
    OR (status='LEFT' AND checked_in_at IS NOT NULL AND checked_out_at IS NOT NULL)
    OR (status IN ('WAITING','CANCELLED') AND checked_in_at IS NULL AND checked_out_at IS NULL))
);
CREATE INDEX visitor_host ON visitors(host_id);
CREATE INDEX visitor_dates ON visitors(start_at,end_at);
CREATE INDEX visitor_code ON visitors(code);
ALTER TABLE notifications ADD COLUMN visitor_id bigint REFERENCES visitors(id) ON DELETE SET NULL;
CREATE UNIQUE INDEX visitor_notification_once ON notifications(visitor_id) WHERE visitor_id IS NOT NULL;
