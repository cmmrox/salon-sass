-- Customer identities stay tenant-local; existing staff ownership is preserved.
CREATE TABLE identity_customer (
 tenant_id uuid NOT NULL REFERENCES tenant(id),id uuid NOT NULL,mobile text NOT NULL,name text NOT NULL,
 active boolean NOT NULL,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,version bigint NOT NULL,
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,mobile));
CREATE TABLE identity_customer_challenge (
 tenant_id uuid NOT NULL REFERENCES tenant(id),id uuid NOT NULL,number_hash text NOT NULL,
 encrypted_recipient text NOT NULL,code_hash text NOT NULL,device_hash text NOT NULL,device_label text NOT NULL,
 channel text NOT NULL CHECK(channel IN ('sms','whatsapp')),message_id uuid NOT NULL,expires_at timestamptz NOT NULL,
 attempts integer NOT NULL CHECK(attempts BETWEEN 0 AND 5),invalidated_at timestamptz,consumed_at timestamptz,
 created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,PRIMARY KEY(tenant_id,id));
CREATE INDEX identity_customer_challenges_number ON identity_customer_challenge(tenant_id,number_hash);
CREATE INDEX identity_customer_challenges_expiry ON identity_customer_challenge(tenant_id,expires_at);
ALTER TABLE identity_customer ENABLE ROW LEVEL SECURITY;ALTER TABLE identity_customer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON identity_customer USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE identity_customer_challenge ENABLE ROW LEVEL SECURITY;ALTER TABLE identity_customer_challenge FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON identity_customer_challenge USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,UPDATE,DELETE ON identity_customer,identity_customer_challenge TO ${application_role};
ALTER TABLE identity_session ALTER COLUMN account_id DROP NOT NULL;
ALTER TABLE identity_session ADD COLUMN customer_account_id uuid;
ALTER TABLE identity_session ADD CONSTRAINT session_customer FOREIGN KEY(tenant_id,customer_account_id) REFERENCES identity_customer(tenant_id,id);
ALTER TABLE identity_session ADD CONSTRAINT session_kind_owner CHECK(
 (kind='staff' AND account_id IS NOT NULL AND customer_account_id IS NULL) OR
 (kind='customer' AND account_id IS NULL AND customer_account_id IS NOT NULL AND mfa_verified=false));
CREATE INDEX identity_sessions_customer ON identity_session(tenant_id,customer_account_id);
ALTER TABLE outbox_message DROP CONSTRAINT outbox_message_status_known;
ALTER TABLE outbox_message ADD CONSTRAINT outbox_message_status_known CHECK(status IN ('pending','sending','sent','dead','cancelled'));
