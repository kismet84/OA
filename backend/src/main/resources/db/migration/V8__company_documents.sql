CREATE TABLE documents (
 id BIGSERIAL PRIMARY KEY,
 title VARCHAR(200) NOT NULL,
 description VARCHAR(5000),
 category VARCHAR(20) NOT NULL CHECK(category IN ('POLICY','FILE','OTHER')),
 department_id BIGINT REFERENCES departments(id),
 publisher_id BIGINT NOT NULL REFERENCES users(id),
 allow_download BOOLEAN NOT NULL DEFAULT true,
 status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' CHECK(status IN ('PUBLISHED','WITHDRAWN')),
 published_at TIMESTAMP NOT NULL DEFAULT localtimestamp,
 updated_at TIMESTAMP NOT NULL DEFAULT localtimestamp
);
CREATE TABLE document_attachments (
 document_id BIGINT NOT NULL REFERENCES documents(id),
 attachment_id UUID NOT NULL REFERENCES attachments(id),
 position INT NOT NULL,
 PRIMARY KEY(document_id,attachment_id)
);
-- Retain bindings after replacement to prevent the generic attachment route exposing old originals.
CREATE TABLE document_file_history (
 attachment_id UUID PRIMARY KEY REFERENCES attachments(id)
);
CREATE TABLE document_receipts (
 document_id BIGINT NOT NULL REFERENCES documents(id),
 user_id BIGINT NOT NULL REFERENCES users(id),
 read_at TIMESTAMP NOT NULL DEFAULT localtimestamp,
 PRIMARY KEY(document_id,user_id)
);
ALTER TABLE notifications ADD COLUMN document_id BIGINT REFERENCES documents(id);
CREATE INDEX documents_published ON documents(published_at DESC,id DESC);
CREATE INDEX document_notifications ON notifications(document_id,user_id);
