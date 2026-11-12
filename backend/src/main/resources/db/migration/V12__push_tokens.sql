CREATE TABLE push_tokens (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES users(id),
  token varchar(4096) NOT NULL UNIQUE,
  platform varchar(10) NOT NULL CHECK (platform IN ('ANDROID','IOS')),
  device_name varchar(100),
  session_id varchar(64) REFERENCES login_sessions(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX push_tokens_user ON push_tokens(user_id);
