-- Nullable for existing rows and old clients. Owner locking serializes keyed submit side effects.
ALTER TABLE requests ADD COLUMN client_request_id varchar(64);
CREATE UNIQUE INDEX requests_user_client_request_id
  ON requests(user_id, client_request_id) WHERE client_request_id IS NOT NULL;
