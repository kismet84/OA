CREATE TABLE tasks (
 id BIGSERIAL PRIMARY KEY,
 creator_id BIGINT NOT NULL REFERENCES users(id),
 title VARCHAR(200) NOT NULL,
 description VARCHAR(5000),
 due_at TIMESTAMP,
 status VARCHAR(20) NOT NULL DEFAULT 'TODO' CHECK(status IN ('TODO','IN_PROGRESS','COMPLETED','CANCELLED')),
 created_at TIMESTAMP NOT NULL DEFAULT localtimestamp,
 updated_at TIMESTAMP NOT NULL DEFAULT localtimestamp
);
CREATE TABLE task_assignees (
 task_id BIGINT NOT NULL REFERENCES tasks(id),
 user_id BIGINT NOT NULL REFERENCES users(id),
 PRIMARY KEY(task_id,user_id)
);
CREATE INDEX task_assignees_user ON task_assignees(user_id,task_id);
CREATE TABLE task_followups (
 id BIGSERIAL PRIMARY KEY,
 task_id BIGINT NOT NULL REFERENCES tasks(id),
 user_id BIGINT NOT NULL REFERENCES users(id),
 content VARCHAR(5000) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT localtimestamp
);
ALTER TABLE notifications ADD COLUMN task_id BIGINT REFERENCES tasks(id);
CREATE INDEX tasks_due ON tasks(due_at) WHERE status IN ('TODO','IN_PROGRESS');
