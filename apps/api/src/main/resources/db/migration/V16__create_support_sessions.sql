-- Audited support sessions (F03-S04; docs/architecture/operator-console.md, "Support sessions").
-- A support session and its single-use entry codes are platform records: no tenant_id, reached only
-- by the platform runtime role (platform_role). The salon's own audit trail gains the actor kind
-- 'support', and one guarded function lets the platform connection write a support entry into the
-- salon's audit trail in the same transaction as the session change it records.

CREATE TABLE support_session (
    id               uuid        NOT NULL,
    salon_id         uuid        NOT NULL,
    operator_id      uuid        NOT NULL,
    reason           text        NOT NULL,
    duration_minutes integer     NOT NULL,
    started_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    -- Set once, when the session ends: who or what ended it, and when.
    ended_at         timestamptz,
    ended_by         text,
    ended_by_id      text,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT support_session_pkey PRIMARY KEY (id),
    CONSTRAINT support_session_salon_fkey FOREIGN KEY (salon_id) REFERENCES tenant (id),
    CONSTRAINT support_session_operator_fkey FOREIGN KEY (operator_id) REFERENCES operator_user (id),
    CONSTRAINT support_session_reason_length CHECK (btrim(reason) = reason AND length(reason) BETWEEN 10 AND 500),
    CONSTRAINT support_session_duration_range CHECK (duration_minutes BETWEEN 5 AND 120),
    CONSTRAINT support_session_expiry CHECK (expires_at = started_at + make_interval(mins => duration_minutes)),
    CONSTRAINT support_session_end_known CHECK (ended_by IS NULL OR ended_by IN ('operator', 'admin', 'owner', 'expiry')),
    CONSTRAINT support_session_end_complete CHECK ((ended_at IS NULL) = (ended_by IS NULL))
);

-- One operator holds at most one open session per salon; the loser of a race gets the violation.
CREATE UNIQUE INDEX support_session_one_open ON support_session (operator_id, salon_id) WHERE ended_at IS NULL;
CREATE INDEX support_session_salon_idx ON support_session (salon_id, started_at DESC, id);
CREATE INDEX support_session_operator_idx ON support_session (operator_id, started_at DESC, id);
CREATE INDEX support_session_due_idx ON support_session (expires_at) WHERE ended_at IS NULL;

-- A session is never renewed or reopened: once ended nothing about it changes, and only its end may
-- ever be written.
CREATE FUNCTION support_session_guard()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a support session is never removed' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.ended_at IS NOT NULL THEN
        RAISE EXCEPTION 'an ended support session is never changed' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.id <> OLD.id OR NEW.salon_id <> OLD.salon_id OR NEW.operator_id <> OLD.operator_id
            OR NEW.reason <> OLD.reason OR NEW.duration_minutes <> OLD.duration_minutes
            OR NEW.started_at <> OLD.started_at OR NEW.expires_at <> OLD.expires_at
            OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'only the end of a support session is written' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER support_session_guarded
    BEFORE UPDATE OR DELETE ON support_session
    FOR EACH ROW EXECUTE FUNCTION support_session_guard();

-- Single-use entry codes: 256 random bits, of which only the SHA-256 is stored, valid for 60 seconds.
CREATE TABLE support_entry_code (
    id          uuid        NOT NULL,
    session_id  uuid        NOT NULL,
    code_hash   text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT support_entry_code_pkey PRIMARY KEY (id),
    CONSTRAINT support_entry_code_session_fkey FOREIGN KEY (session_id) REFERENCES support_session (id),
    CONSTRAINT support_entry_code_hash_key UNIQUE (code_hash),
    CONSTRAINT support_entry_code_hash_format CHECK (code_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX support_entry_code_session_idx ON support_entry_code (session_id);

REVOKE ALL ON TABLE support_session, support_entry_code FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE support_session TO "${platform_role}";
GRANT UPDATE (ended_at, ended_by, ended_by_id, updated_at) ON TABLE support_session TO "${platform_role}";
GRANT SELECT, INSERT ON TABLE support_entry_code TO "${platform_role}";
GRANT UPDATE (consumed_at, updated_at) ON TABLE support_entry_code TO "${platform_role}";

-- The salon's audit trail names a support session as its own kind of actor.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_actor_kind_known;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_actor_kind_known
    CHECK (actor_kind IN ('staff', 'customer', 'operator', 'system', 'support'));

-- The Owner revokes a session from the workspace: the platform audit names a staff actor too.
ALTER TABLE platform_audit_entry DROP CONSTRAINT platform_audit_entry_actor_kind_known;
ALTER TABLE platform_audit_entry ADD CONSTRAINT platform_audit_entry_actor_kind_known
    CHECK (actor_kind IN ('operator', 'system', 'staff'));

-- Writes one support entry into one salon's audit trail from the platform connection, in the
-- caller's transaction. Only 'support.' actions, only the actors a support session's start, use and
-- end can have; a deleted salon (whose data is gone) gets nothing, and the answer says so.
CREATE FUNCTION audit_record_support(target_tenant uuid, entry_id uuid, entry_actor_kind text,
        entry_actor_id text, entry_action text, entry_subject_id text, entry_reason text,
        entry_after jsonb, entry_trace_id text, at_time timestamptz)
    RETURNS boolean
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
    salon_status text;
BEGIN
    IF entry_action NOT LIKE 'support.%' OR entry_actor_kind NOT IN ('support', 'operator', 'staff', 'system') THEN
        RAISE EXCEPTION 'only support entries are written from the platform connection'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT t.status INTO salon_status FROM public.tenant t WHERE t.id = target_tenant;
    IF salon_status IS NULL OR salon_status = 'deleted' THEN
        RETURN false;
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    INSERT INTO public.audit_entry (tenant_id, id, actor_kind, actor_id, action, subject_kind, subject_id, reason,
            before_values, after_values, trace_id, created_at, updated_at)
        VALUES (target_tenant, entry_id, entry_actor_kind, entry_actor_id, entry_action, 'support_session',
            entry_subject_id, entry_reason, NULL, entry_after, entry_trace_id, at_time, at_time);
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN true;
END
$$;

REVOKE ALL ON FUNCTION audit_record_support(uuid, uuid, text, text, text, text, text, jsonb, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_record_support(uuid, uuid, text, text, text, text, text, jsonb, text, timestamptz)
    TO "${platform_role}";
