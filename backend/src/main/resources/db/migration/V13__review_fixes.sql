-- Nullable provenance preserves historical and older client records.
ALTER TABLE punches ADD COLUMN located_at timestamptz;
ALTER TABLE punches ADD COLUMN location_source varchar(32)
 CHECK(location_source IN ('LIVE','WARMUP_CACHE','LAST_KNOWN','STALE_FALLBACK','CORRECTION','UNKNOWN'));
UPDATE punches SET location_source='CORRECTION' WHERE correction_request_id IS NOT NULL;
ALTER TABLE approval_steps ADD COLUMN actor_id bigint REFERENCES users(id);
ALTER TABLE asset_requests ADD COLUMN approval_actor_id bigint REFERENCES users(id);
ALTER TABLE asset_requests ADD COLUMN approval_acted_at timestamptz;
-- Append-only assignment history: changing the current step never destroys past visibility.
CREATE TABLE approval_reassignments (
 id bigserial PRIMARY KEY, business varchar(16) NOT NULL CHECK(business IN ('CONTRACT','ASSET','REQUEST')),
 target_id bigint NOT NULL, revision integer NOT NULL DEFAULT 0, step integer NOT NULL DEFAULT 1,
 previous_approver_id bigint REFERENCES users(id), approver_id bigint NOT NULL REFERENCES users(id),
 actor_id bigint NOT NULL REFERENCES users(id), note varchar(1000), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX approval_reassignments_target ON approval_reassignments(business,target_id);
CREATE INDEX contract_step_approver ON contract_steps(approver_id,contract_id);
CREATE INDEX asset_request_pending_approver ON asset_requests(approver_id) WHERE status='PENDING';

-- Preserve the full 1000-character user note plus administrator provenance prefix.
ALTER TABLE contract_steps ALTER COLUMN note TYPE varchar(1100);
ALTER TABLE approval_steps ALTER COLUMN comment TYPE varchar(1100);
ALTER TABLE asset_requests ALTER COLUMN approval_note TYPE varchar(1100);
