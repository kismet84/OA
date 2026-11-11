ALTER TABLE users ADD COLUMN job_title varchar(100), ADD COLUMN work_phone varchar(40);
CREATE TABLE login_sessions (
  id varchar(64) PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES users(id),
  session_version integer NOT NULL,
  device_name varchar(100) NOT NULL,
  platform varchar(30) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);
CREATE INDEX login_sessions_user ON login_sessions(user_id, expires_at);
