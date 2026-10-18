-- F03-S01: the operator console's platform records (docs/architecture/operator-console.md). None of
-- these tables has a tenant_id: they are platform tables (T-1). The ordinary tenant role
-- (application_role) receives no grant on any of them; the separately configured platform runtime
-- role (platform_role) receives only the operations its services need. Neither role is created here:
-- the environment creates both before the first migration.
--
-- Every time is the application's clock, written by the application; nothing defaults to now().

-- Operator accounts. Sign-in and MFA follow identity's documented policies; the tables are separate
-- from every salon's identity tables, so no salon credential can ever authenticate here.
CREATE TABLE operator_user (
    id            uuid        NOT NULL,
    email         text        NOT NULL,
    display_name  text        NOT NULL,
    password_hash text        NOT NULL,
    role          text        NOT NULL,
    active        boolean     NOT NULL,
    mfa_secret    text,
    -- Created by the local fixture hook; only such rows may be reset by it.
    fixture       boolean     NOT NULL DEFAULT false,
    version       bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT operator_user_pkey PRIMARY KEY (id),
    CONSTRAINT operator_user_email_key UNIQUE (email),
    CONSTRAINT operator_user_email_normalized CHECK (email = lower(btrim(email)) AND length(email) BETWEEN 3 AND 254),
    CONSTRAINT operator_user_display_name_present CHECK (btrim(display_name) <> '' AND length(display_name) <= 120),
    CONSTRAINT operator_user_role_known CHECK (role IN ('OPERATOR_ADMIN', 'OPERATOR_SUPPORT'))
);

CREATE TABLE operator_session (
    id           uuid        NOT NULL,
    user_id      uuid        NOT NULL,
    refresh_hash text        NOT NULL,
    device_label text        NOT NULL,
    mfa_verified boolean     NOT NULL,
    generation   bigint      NOT NULL,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT operator_session_pkey PRIMARY KEY (id),
    CONSTRAINT operator_session_user_fkey FOREIGN KEY (user_id) REFERENCES operator_user (id),
    CONSTRAINT operator_session_refresh_key UNIQUE (refresh_hash)
);
CREATE INDEX operator_session_user_idx ON operator_session (user_id);

-- Every refresh credential a session has spent: presenting one again revokes the family.
CREATE TABLE operator_refresh_history (
    id              uuid        NOT NULL,
    session_id      uuid        NOT NULL,
    credential_hash text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT operator_refresh_history_pkey PRIMARY KEY (id),
    CONSTRAINT operator_refresh_history_session_fkey FOREIGN KEY (session_id)
        REFERENCES operator_session (id) ON DELETE CASCADE
);
CREATE INDEX operator_refresh_history_hash_idx ON operator_refresh_history (credential_hash);

CREATE TABLE operator_mfa_challenge (
    id              uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    purpose         text        NOT NULL,
    credential_hash text        NOT NULL,
    pending_secret  text,
    device_label    text        NOT NULL,
    expires_at      timestamptz NOT NULL,
    attempts        integer     NOT NULL DEFAULT 0,
    consumed_at     timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT operator_mfa_challenge_pkey PRIMARY KEY (id),
    CONSTRAINT operator_mfa_challenge_user_fkey FOREIGN KEY (user_id) REFERENCES operator_user (id),
    CONSTRAINT operator_mfa_challenge_purpose_known CHECK (purpose IN ('enroll_mfa', 'verify_mfa')),
    CONSTRAINT operator_mfa_challenge_attempts_bounded CHECK (attempts BETWEEN 0 AND 5)
);
CREATE INDEX operator_mfa_challenge_expiry_idx ON operator_mfa_challenge (expires_at);

-- A TOTP step, once used, is never accepted again for that operator (simultaneous requests too).
CREATE TABLE operator_mfa_step (
    id         uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    step       bigint      NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT operator_mfa_step_pkey PRIMARY KEY (id),
    CONSTRAINT operator_mfa_step_user_fkey FOREIGN KEY (user_id) REFERENCES operator_user (id),
    CONSTRAINT operator_mfa_step_once UNIQUE (user_id, step)
);

-- Password attempt counters, keyed by a keyed hash of the normalized email or trusted address.
CREATE TABLE operator_rate_bucket (
    id           uuid        NOT NULL,
    dimension    text        NOT NULL,
    key_hash     text        NOT NULL,
    window_start timestamptz NOT NULL,
    attempts     integer     NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT operator_rate_bucket_pkey PRIMARY KEY (id),
    CONSTRAINT operator_rate_bucket_key UNIQUE (dimension, key_hash),
    CONSTRAINT operator_rate_bucket_dimension_known CHECK (dimension IN ('operator-email', 'operator-address'))
);

-- Immutable plan definitions: configurable limits and feature flags, never prices (D-06 is open).
CREATE TABLE plan (
    id                uuid        NOT NULL,
    key               text        NOT NULL,
    name              text        NOT NULL,
    staff_seats       integer     NOT NULL,
    branches          integer     NOT NULL,
    message_allowance integer     NOT NULL,
    custom_domain     boolean     NOT NULL,
    dedicated_app     boolean     NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT plan_pkey PRIMARY KEY (id),
    CONSTRAINT plan_key_key UNIQUE (key),
    CONSTRAINT plan_key_format CHECK (key ~ '^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$'),
    CONSTRAINT plan_name_present CHECK (name = btrim(name) AND name <> '' AND length(name) <= 120),
    CONSTRAINT plan_staff_seats_bounded CHECK (staff_seats BETWEEN 1 AND 1000000),
    CONSTRAINT plan_branches_bounded CHECK (branches BETWEEN 1 AND 1000000),
    CONSTRAINT plan_message_allowance_bounded CHECK (message_allowance BETWEEN 0 AND 1000000),
    CONSTRAINT plan_unchanged CHECK (updated_at = created_at)
);
CREATE INDEX plan_created_idx ON plan (created_at DESC, id);

-- A definition, once created, is never changed or removed, whichever role asks.
CREATE FUNCTION plan_refuse_change()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'a plan definition is never changed'
        USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER plan_immutable
    BEFORE UPDATE OR DELETE ON plan
    FOR EACH ROW EXECUTE FUNCTION plan_refuse_change();

-- The provisioning and contact profile of a salon, owned by platform (decision 042). Every column
-- is nullable, so the synthetic salons created before this migration stay exactly as they were and
-- are shown as "not configured" until someone configures them.
ALTER TABLE tenant
    ADD COLUMN name             text,
    ADD COLUMN country          text,
    ADD COLUMN currency         text,
    ADD COLUMN default_language text,
    ADD COLUMN contact_email    text,
    ADD COLUMN contact_phone    text,
    ADD COLUMN address          text,
    ADD COLUMN owner_email      text,
    ADD COLUMN plan_id          uuid,
    ADD CONSTRAINT tenant_plan_fkey FOREIGN KEY (plan_id) REFERENCES plan (id),
    ADD CONSTRAINT tenant_name_present CHECK (name IS NULL OR (name = btrim(name) AND name <> '' AND length(name) <= 120)),
    ADD CONSTRAINT tenant_country_format CHECK (country IS NULL OR country ~ '^[A-Z]{2}$'),
    ADD CONSTRAINT tenant_currency_format CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT tenant_default_language_known CHECK (default_language IS NULL OR default_language IN ('en', 'si', 'ta')),
    ADD CONSTRAINT tenant_contact_email_bounded CHECK (contact_email IS NULL OR length(contact_email) <= 254),
    ADD CONSTRAINT tenant_contact_phone_format CHECK (contact_phone IS NULL OR contact_phone ~ '^\+947[0-9]{8}$'),
    ADD CONSTRAINT tenant_address_bounded CHECK (address IS NULL OR length(address) <= 500),
    ADD CONSTRAINT tenant_owner_email_bounded CHECK (owner_email IS NULL OR length(owner_email) <= 254);
CREATE INDEX tenant_created_idx ON tenant (created_at DESC, id);

-- One row per successful idempotent creation: the request's keyed fingerprint (never its body) and
-- the nonsecret result, written in the creation's own transaction. A failed transaction reserves
-- nothing.
CREATE TABLE provisioning_receipt (
    operator_id     uuid        NOT NULL,
    action          text        NOT NULL,
    idempotency_key uuid        NOT NULL,
    fingerprint     text        NOT NULL,
    subject_id      uuid        NOT NULL,
    result          jsonb       NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT provisioning_receipt_pkey PRIMARY KEY (operator_id, action, idempotency_key),
    CONSTRAINT provisioning_receipt_operator_fkey FOREIGN KEY (operator_id) REFERENCES operator_user (id),
    CONSTRAINT provisioning_receipt_action_known CHECK (action IN ('plan.create', 'salon.create')),
    CONSTRAINT provisioning_receipt_fingerprint_format CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT provisioning_receipt_unchanged CHECK (updated_at = created_at)
);

-- The platform audit: append-only, protected by the database against change by any role.
CREATE TABLE platform_audit_entry (
    id           uuid        NOT NULL,
    actor_kind   text        NOT NULL,
    actor_id     text        NOT NULL,
    action       text        NOT NULL,
    subject_kind text        NOT NULL,
    subject_id   text        NOT NULL,
    details      jsonb,
    trace_id     text,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT platform_audit_entry_pkey PRIMARY KEY (id),
    CONSTRAINT platform_audit_entry_actor_kind_known CHECK (actor_kind IN ('operator', 'system')),
    CONSTRAINT platform_audit_entry_action_present CHECK (btrim(action) <> ''),
    CONSTRAINT platform_audit_entry_trace_id_format CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT platform_audit_entry_unchanged CHECK (updated_at = created_at)
);
CREATE INDEX platform_audit_entry_subject_idx ON platform_audit_entry (subject_kind, subject_id, created_at);
CREATE INDEX platform_audit_entry_created_idx ON platform_audit_entry (created_at);

CREATE FUNCTION platform_audit_entry_refuse_change()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'a platform audit entry is never changed'
        USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER platform_audit_entry_append_only
    BEFORE UPDATE OR DELETE ON platform_audit_entry
    FOR EACH ROW EXECUTE FUNCTION platform_audit_entry_refuse_change();

-- Nobody but the owner and the grants below.
REVOKE ALL ON TABLE operator_user, operator_session, operator_refresh_history, operator_mfa_challenge,
    operator_mfa_step, operator_rate_bucket, plan, provisioning_receipt, platform_audit_entry FROM PUBLIC;
REVOKE ALL ON FUNCTION plan_refuse_change(), platform_audit_entry_refuse_change() FROM PUBLIC;

-- The platform runtime role: exactly the operations its services perform.
GRANT SELECT, INSERT, UPDATE ON TABLE operator_user TO "${platform_role}";
GRANT SELECT, INSERT, UPDATE ON TABLE operator_session TO "${platform_role}";
GRANT SELECT, INSERT ON TABLE operator_refresh_history TO "${platform_role}";
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE operator_mfa_challenge TO "${platform_role}";
GRANT SELECT, INSERT, DELETE ON TABLE operator_mfa_step TO "${platform_role}";
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE operator_rate_bucket TO "${platform_role}";
GRANT SELECT, INSERT ON TABLE plan TO "${platform_role}";
GRANT SELECT, INSERT ON TABLE tenant TO "${platform_role}";
GRANT SELECT, INSERT ON TABLE provisioning_receipt TO "${platform_role}";
GRANT SELECT, INSERT ON TABLE platform_audit_entry TO "${platform_role}";
-- The registry's time-zone check calls is_iana_time_zone(), which PUBLIC may not execute; the role
-- that inserts salons needs it for that check and for nothing else.
GRANT EXECUTE ON FUNCTION is_iana_time_zone(text) TO "${platform_role}";
