CREATE TABLE attendance_rules (
 id integer PRIMARY KEY CHECK(id=1), enabled boolean NOT NULL DEFAULT true,
 work_start time NOT NULL DEFAULT '09:00', work_end time NOT NULL DEFAULT '18:00',
 late_grace_minutes integer NOT NULL DEFAULT 5 CHECK(late_grace_minutes BETWEEN 0 AND 240),
 early_grace_minutes integer NOT NULL DEFAULT 5 CHECK(early_grace_minutes BETWEEN 0 AND 240),
 workdays integer[] NOT NULL DEFAULT ARRAY[1,2,3,4,5] CHECK(workdays <@ ARRAY[1,2,3,4,5,6,7] AND cardinality(workdays)>0),
 effective_from date NOT NULL DEFAULT (now() AT TIME ZONE 'Asia/Shanghai')::date,
 updated_at timestamptz NOT NULL DEFAULT now(), updated_by bigint REFERENCES users(id),
 CHECK(work_start<work_end)
);
INSERT INTO attendance_rules(id) VALUES(1);
CREATE TABLE work_calendar (
 day date PRIMARY KEY, kind varchar(10) NOT NULL CHECK(kind IN ('HOLIDAY','WORKDAY')),
 name varchar(100) NOT NULL DEFAULT '', created_by bigint REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS punches_user_time ON punches(user_id,punched_at);
