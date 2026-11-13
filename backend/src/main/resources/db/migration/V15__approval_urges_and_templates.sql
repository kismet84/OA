ALTER TABLE workflow_config ADD COLUMN urge_cooldown_minutes integer NOT NULL DEFAULT 120 CHECK(urge_cooldown_minutes BETWEEN 1 AND 10080),
 ADD COLUMN auto_urge_enabled boolean NOT NULL DEFAULT true,
 ADD COLUMN auto_urge_hours integer NOT NULL DEFAULT 24 CHECK(auto_urge_hours BETWEEN 1 AND 8760),
 ADD COLUMN auto_urge_repeat_hours integer NOT NULL DEFAULT 0 CHECK(auto_urge_repeat_hours BETWEEN 0 AND 8760);
ALTER TABLE approval_steps ADD COLUMN activated_at timestamptz;
ALTER TABLE contract_steps ADD COLUMN activated_at timestamptz;
ALTER TABLE asset_requests ADD COLUMN activated_at timestamptz NOT NULL DEFAULT now();
UPDATE approval_steps s SET activated_at=greatest(
 (r.created_at AT TIME ZONE 'Asia/Shanghai'),
 (SELECT max(p.acted_at) AT TIME ZONE 'Asia/Shanghai' FROM approval_steps p WHERE p.request_id=r.id AND p.step<s.step),
 (SELECT max(h.created_at) FROM approval_reassignments h WHERE h.business='REQUEST' AND h.target_id=r.id AND h.step=s.step))
 FROM requests r WHERE r.id=s.request_id AND s.step=r.current_step AND r.status='PENDING';
UPDATE contract_steps s SET activated_at=greatest(
 (c.created_at AT TIME ZONE 'Asia/Shanghai'),
 (SELECT max(p.acted_at) AT TIME ZONE 'Asia/Shanghai' FROM contract_steps p WHERE p.contract_id=c.id AND p.revision=c.revision AND p.step<s.step),
 (SELECT max(h.created_at) FROM approval_reassignments h WHERE h.business='CONTRACT' AND h.target_id=c.id AND h.revision=c.revision AND h.step=s.step))
 FROM contracts c WHERE c.id=s.contract_id AND c.revision=s.revision AND s.step=c.current_step AND c.status='PENDING';
UPDATE asset_requests a SET activated_at=greatest(a.created_at AT TIME ZONE 'Asia/Shanghai',
 (SELECT max(h.created_at) FROM approval_reassignments h WHERE h.business='ASSET' AND h.target_id=a.id));
-- Activation follows the authoritative business transition in the same transaction.
CREATE FUNCTION activate_approval_step() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='PENDING' AND (TG_OP='INSERT' OR OLD.status IS DISTINCT FROM NEW.status OR OLD.current_step IS DISTINCT FROM NEW.current_step) THEN
  IF TG_TABLE_NAME='requests' THEN
   UPDATE approval_steps SET activated_at=now() WHERE request_id=NEW.id AND step=NEW.current_step;
  ELSE
   UPDATE contract_steps SET activated_at=now() WHERE contract_id=NEW.id AND revision=NEW.revision AND step=NEW.current_step;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER request_activation AFTER INSERT OR UPDATE ON requests FOR EACH ROW EXECUTE FUNCTION activate_approval_step();
CREATE TRIGGER contract_activation AFTER INSERT OR UPDATE ON contracts FOR EACH ROW EXECUTE FUNCTION activate_approval_step();
CREATE FUNCTION reset_approval_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF TG_TABLE_NAME='approval_steps' AND NEW.step=1 THEN NEW.activated_at=now(); END IF;
 ELSE
  IF OLD.approver_id IS DISTINCT FROM NEW.approver_id THEN NEW.activated_at=now(); END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER request_step_activation BEFORE INSERT OR UPDATE ON approval_steps FOR EACH ROW EXECUTE FUNCTION reset_approval_activation();
CREATE TRIGGER contract_step_activation BEFORE UPDATE ON contract_steps FOR EACH ROW EXECUTE FUNCTION reset_approval_activation();
CREATE TRIGGER asset_activation BEFORE UPDATE ON asset_requests FOR EACH ROW EXECUTE FUNCTION reset_approval_activation();
CREATE TABLE approval_urges (
 id bigserial PRIMARY KEY, business varchar(10) NOT NULL CHECK(business IN ('REQUEST','CONTRACT','ASSET')),
 target_id bigint NOT NULL, revision integer NOT NULL DEFAULT 0, step integer NOT NULL,
 approver_id bigint NOT NULL REFERENCES users(id), actor_id bigint REFERENCES users(id),
 mode varchar(6) NOT NULL CHECK(mode IN ('MANUAL','AUTO')), activated_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX urge_target_time ON approval_urges(business,target_id,revision,step,approver_id,created_at);
CREATE TABLE request_templates (
 id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), name varchar(60) NOT NULL CHECK(length(trim(name))>0),
 kind varchar(10) NOT NULL CHECK(kind IN ('LEAVE','TRAVEL','OUTING','EXPENSE')), payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(user_id,name)
);
