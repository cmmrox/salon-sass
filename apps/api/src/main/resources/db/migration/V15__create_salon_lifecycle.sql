-- F03-S03: suspension, closure, export and retention (docs/architecture/operator-console.md,
-- "Suspension, closure, export and retention"). The registry gains the closed and deleted states, the
-- closure and deletion instants and a guard that keeps a retired slug and a tombstone for ever; the
-- lifecycle history, the export archive and its parts are platform tables (no tenant_id) that only the
-- platform runtime role (platform_role) reaches. Every write into a tenant table that closure and
-- deletion need goes through a function owned by the module that owns the table, granted to the
-- platform role only, refusing any salon that is not closed (or not past its retention), setting that
-- one salon for row-level security inside the function (which stays forced) and putting the caller's
-- setting back before it returns.
--
-- Every time is the application's clock, written by the application; nothing defaults to now(). The
-- previous application version treats a status it does not know as no salon, so a closed or deleted
-- salon is never served by it either.

-- --- the registry ----------------------------------------------------------------------------------

ALTER TABLE tenant DROP CONSTRAINT tenant_status_known;
ALTER TABLE tenant
    ADD CONSTRAINT tenant_status_known CHECK (status IN ('active', 'suspended', 'closed', 'deleted')),
    ADD COLUMN closed_at  timestamptz,
    ADD COLUMN delete_at  timestamptz,
    ADD COLUMN deleted_at timestamptz,
    ADD CONSTRAINT tenant_closure_shape CHECK (
        (status IN ('closed', 'deleted')) = (closed_at IS NOT NULL AND delete_at IS NOT NULL)),
    ADD CONSTRAINT tenant_deletion_shape CHECK ((status = 'deleted') = (deleted_at IS NOT NULL)),
    ADD CONSTRAINT tenant_delete_after_closure CHECK (delete_at IS NULL OR delete_at > closed_at);

GRANT UPDATE (status, closed_at, delete_at, deleted_at) ON TABLE tenant TO "${platform_role}";

-- Closure is irreversible and a slug is never reused: a salon's slug never changes, a closed salon only
-- becomes deleted, a deleted one never changes state again, its closure instants never move, and the
-- row of a closed or deleted salon (its tombstone, holding the retired slug) is never removed. The
-- unique slug constraint then refuses the retired slug to every new salon. Whoever asks.
CREATE FUNCTION tenant_lifecycle_guard()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('closed', 'deleted') THEN
            RAISE EXCEPTION 'a closed salon''s registry row is kept as its tombstone'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.slug <> OLD.slug THEN
        RAISE EXCEPTION 'a salon''s slug never changes' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.status = 'closed' AND NEW.status NOT IN ('closed', 'deleted') THEN
        RAISE EXCEPTION 'closure is irreversible' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.status = 'deleted' AND NEW.status <> 'deleted' THEN
        RAISE EXCEPTION 'a deleted salon stays deleted' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.closed_at IS NOT NULL AND (NEW.closed_at IS DISTINCT FROM OLD.closed_at
            OR NEW.delete_at IS DISTINCT FROM OLD.delete_at) THEN
        RAISE EXCEPTION 'a closure''s instants never change' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'a deletion''s instant never changes' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION tenant_lifecycle_guard() FROM PUBLIC;
CREATE TRIGGER tenant_lifecycle
    BEFORE UPDATE OR DELETE ON tenant
    FOR EACH ROW EXECUTE FUNCTION tenant_lifecycle_guard();

-- Whether a salon is closed and its retention has run out at the given instant (the application's
-- clock): the one condition under which a module's eraser may remove its rows. Used by the erasers
-- below, which run with the owner's rights; nobody else may call it.
CREATE FUNCTION tenant_retention_passed(target_tenant uuid, at_time timestamptz)
    RETURNS boolean
    LANGUAGE sql
    STABLE
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM public.tenant t
                    WHERE t.id = target_tenant AND t.status = 'closed' AND t.delete_at <= at_time)
$$;
REVOKE ALL ON FUNCTION tenant_retention_passed(uuid, timestamptz) FROM PUBLIC;

-- The ids of the suspended salons and nothing else: the scheduler counts each one's skipped job runs
-- (a suspended salon's jobs are skipped; the local background-state hook shows the count).
CREATE FUNCTION suspended_tenant_ids()
    RETURNS TABLE (id uuid)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id FROM public.tenant t WHERE t.status = 'suspended' ORDER BY t.id
$$;
REVOKE ALL ON FUNCTION suspended_tenant_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION suspended_tenant_ids() TO "${application_role}";

-- --- lifecycle history -----------------------------------------------------------------------------

-- One row per suspension, reactivation, closure and deletion of a salon: who (an operator, or the
-- system for a deletion), why and when. Kept with the tombstone after deletion.
CREATE TABLE salon_lifecycle_event (
    id         uuid        NOT NULL,
    salon_id   uuid        NOT NULL,
    kind       text        NOT NULL,
    reason     text,
    actor_kind text        NOT NULL,
    actor_id   text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT salon_lifecycle_event_pkey PRIMARY KEY (id),
    CONSTRAINT salon_lifecycle_event_salon_fkey FOREIGN KEY (salon_id) REFERENCES tenant (id),
    CONSTRAINT salon_lifecycle_event_kind_known CHECK (kind IN ('suspended', 'reactivated', 'closed', 'deleted')),
    CONSTRAINT salon_lifecycle_event_actor_kind_known CHECK (actor_kind IN ('operator', 'system')),
    CONSTRAINT salon_lifecycle_event_reason_bounded CHECK (reason IS NULL OR length(reason) BETWEEN 10 AND 500),
    CONSTRAINT salon_lifecycle_event_unchanged CHECK (updated_at = created_at)
);
CREATE INDEX salon_lifecycle_event_salon_idx ON salon_lifecycle_event (salon_id, created_at DESC, id);

CREATE FUNCTION salon_lifecycle_event_refuse_change()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'a lifecycle event is never changed' USING ERRCODE = 'insufficient_privilege';
END
$$;
REVOKE ALL ON FUNCTION salon_lifecycle_event_refuse_change() FROM PUBLIC;
CREATE TRIGGER salon_lifecycle_event_append_only
    BEFORE UPDATE ON salon_lifecycle_event
    FOR EACH ROW EXECUTE FUNCTION salon_lifecycle_event_refuse_change();

-- --- the export ------------------------------------------------------------------------------------

-- A closed salon's one export (decision 049: stored in a platform table, no object storage yet). It is
-- scheduled in the closure's transaction and built by the export job; the ZIP archive is stored
-- encrypted with SALON_EXPORT_KEY, with the SHA-256 of the archive itself, and the Owner's download
-- link carries a 256-bit token of which only a hash is stored. Deletion removes the archive and the
-- token hash and keeps the record.
CREATE TABLE salon_export (
    id          uuid        NOT NULL,
    salon_id    uuid        NOT NULL,
    status      text        NOT NULL,
    ready_at    timestamptz,
    delete_at   timestamptz NOT NULL,
    size_bytes  bigint,
    sha256      text,
    archive     bytea,
    token_hash  text,
    deleted_at  timestamptz,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT salon_export_pkey PRIMARY KEY (id),
    CONSTRAINT salon_export_salon_key UNIQUE (salon_id),
    CONSTRAINT salon_export_token_key UNIQUE (token_hash),
    CONSTRAINT salon_export_salon_fkey FOREIGN KEY (salon_id) REFERENCES tenant (id),
    CONSTRAINT salon_export_status_known CHECK (status IN ('pending', 'ready', 'deleted')),
    CONSTRAINT salon_export_ready_shape CHECK ((status = 'ready') = (archive IS NOT NULL AND token_hash IS NOT NULL)),
    CONSTRAINT salon_export_built_shape CHECK ((status = 'pending') = (ready_at IS NULL AND size_bytes IS NULL
        AND sha256 IS NULL)),
    CONSTRAINT salon_export_deleted_shape CHECK ((status = 'deleted') = (deleted_at IS NOT NULL)),
    CONSTRAINT salon_export_sha256_format CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT salon_export_token_hash_format CHECK (token_hash IS NULL OR token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT salon_export_size_counted CHECK (size_bytes IS NULL OR size_bytes > 0)
);
CREATE INDEX salon_export_pending_idx ON salon_export (created_at) WHERE status = 'pending';

-- One tenant table's rows, as each owning module's exporter wrote them, encrypted like the archive.
-- Written one table per transaction, so an export interrupted part-way resumes from the next table;
-- removed once the archive is stored.
CREATE TABLE salon_export_part (
    export_id  uuid        NOT NULL,
    table_name text        NOT NULL,
    module     text        NOT NULL,
    row_count  integer     NOT NULL,
    content    bytea       NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT salon_export_part_pkey PRIMARY KEY (export_id, table_name),
    CONSTRAINT salon_export_part_export_fkey FOREIGN KEY (export_id) REFERENCES salon_export (id),
    CONSTRAINT salon_export_part_rows_counted CHECK (row_count >= 0)
);

REVOKE ALL ON TABLE salon_lifecycle_event, salon_export, salon_export_part FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE salon_lifecycle_event TO "${platform_role}";
GRANT SELECT, INSERT, UPDATE ON TABLE salon_export TO "${platform_role}";
GRANT SELECT, INSERT, DELETE ON TABLE salon_export_part TO "${platform_role}";

-- --- closure: identity's sessions and the outbox's undelivered messages ------------------------------

-- Owned by identity: revokes every staff and customer session of a salon that is closed (in the
-- calling transaction or before), so no credential of it works again. Refuses any other salon.
CREATE FUNCTION identity_revoke_closed_salon_sessions(target_tenant uuid, at_time timestamptz)
    RETURNS integer
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
    revoked  integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.tenant t WHERE t.id = target_tenant AND t.status = 'closed') THEN
        RAISE EXCEPTION 'sessions are revoked this way only for a closed salon'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    UPDATE public.identity_session s SET revoked_at = at_time, updated_at = at_time
     WHERE s.tenant_id = target_tenant AND s.revoked_at IS NULL;
    GET DIAGNOSTICS revoked = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN revoked;
END
$$;
REVOKE ALL ON FUNCTION identity_revoke_closed_salon_sessions(uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_revoke_closed_salon_sessions(uuid, timestamptz) TO "${platform_role}";

-- Owned by the outbox: cancels every undelivered (pending or in-flight) message of a closed salon. A
-- delivery in flight cannot turn a cancelled message back: its result is written only while its claim
-- still holds. Refuses any other salon.
CREATE FUNCTION outbox_cancel_closed_salon(target_tenant uuid, at_time timestamptz)
    RETURNS integer
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous  text := pg_catalog.current_setting('app.tenant_id', true);
    cancelled integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.tenant t WHERE t.id = target_tenant AND t.status = 'closed') THEN
        RAISE EXCEPTION 'messages are cancelled this way only for a closed salon'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    UPDATE public.outbox_message m
       SET status = 'cancelled', lease_until = NULL, lease_token = NULL, updated_at = at_time
     WHERE m.tenant_id = target_tenant AND m.status IN ('pending', 'sending');
    GET DIAGNOSTICS cancelled = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN cancelled;
END
$$;
REVOKE ALL ON FUNCTION outbox_cancel_closed_salon(uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION outbox_cancel_closed_salon(uuid, timestamptz) TO "${platform_role}";

-- The platform's own email to a closed salon's Owner contact (the export-ready email) is written on
-- the channel 'platform-email', the one channel delivered for a closed salon. Billing's channels stay
-- as they were.
CREATE OR REPLACE FUNCTION outbox_enqueue_for_salon(target_tenant uuid, message_id uuid, message_channel text,
        message_payload jsonb, message_trace_parent text, at_time timestamptz)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
BEGIN
    IF message_channel NOT IN ('email', 'billing-charge', 'platform-email') THEN
        RAISE EXCEPTION 'only billing and platform channels are written from the platform connection'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    INSERT INTO public.outbox_message (tenant_id, id, channel, payload, status, attempts, next_attempt_at,
            trace_parent, created_at, updated_at)
        VALUES (target_tenant, message_id, message_channel, message_payload, 'pending', 0, at_time,
            message_trace_parent, at_time, at_time);
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
END
$$;

-- What is due across salons: every channel of an active salon, as before, and only 'platform-email'
-- of a closed one. A suspended salon's messages still wait; a deleted salon has none.
CREATE OR REPLACE FUNCTION outbox_due(at_time timestamptz, channels text[], per_salon integer, max_count integer)
    RETURNS TABLE (message_id uuid, tenant_id uuid)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    callers_salon text := coalesce(current_setting('app.tenant_id', true), '');
    salon         uuid;
    salon_status  text;
    remaining     integer := max_count;
    found         integer;
BEGIN
    FOR salon, salon_status IN
        SELECT t.id, t.status FROM public.tenant t WHERE t.status IN ('active', 'closed') ORDER BY random()
    LOOP
        EXIT WHEN remaining <= 0;
        PERFORM set_config('app.tenant_id', salon::text, true);
        RETURN QUERY
            SELECT due.id, due.tenant_id FROM (
                SELECT m.id, m.tenant_id, m.next_attempt_at AS due_at FROM public.outbox_message m
                 WHERE m.tenant_id = salon AND m.status = 'pending' AND m.next_attempt_at <= at_time
                   AND m.channel = ANY (channels)
                   AND (salon_status = 'active' OR m.channel = 'platform-email')
                UNION ALL
                SELECT m.id, m.tenant_id, m.lease_until FROM public.outbox_message m
                 WHERE m.tenant_id = salon AND m.status = 'sending' AND m.lease_until <= at_time
                   AND m.channel = ANY (channels)
                   AND (salon_status = 'active' OR m.channel = 'platform-email')
            ) due
            ORDER BY due.due_at, due.id
            LIMIT least(per_salon, remaining);
        GET DIAGNOSTICS found = ROW_COUNT;
        remaining := remaining - found;
    END LOOP;
    PERFORM set_config('app.tenant_id', callers_salon, true);
END
$$;

-- --- retention: each module's eraser -------------------------------------------------------------

-- Each removes at most max_rows rows of one of its module's tables for a salon whose retention has run
-- out (closed, delete_at at or before at_time), and returns how many; 0 means that table is empty for
-- the salon. Called table by table, in the module's order, in bounded batches, so a deletion that stops
-- part-way resumes where it was. Any other salon or table is refused.

CREATE FUNCTION identity_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz,
        max_rows integer)
    RETURNS integer
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
    erased   integer;
BEGIN
    IF NOT public.tenant_retention_passed(target_tenant, at_time) THEN
        RAISE EXCEPTION 'a salon''s rows are erased only after its retention' USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    CASE table_name
        WHEN 'identity_refresh_history' THEN
            DELETE FROM public.identity_refresh_history r WHERE r.ctid IN (SELECT x.ctid FROM public.identity_refresh_history x
                WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'identity_mfa_challenge' THEN
            DELETE FROM public.identity_mfa_challenge r WHERE r.ctid IN (SELECT x.ctid FROM public.identity_mfa_challenge x
                WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'identity_mfa_step' THEN
            DELETE FROM public.identity_mfa_step r WHERE r.ctid IN (SELECT x.ctid FROM public.identity_mfa_step x
                WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'identity_session' THEN
            DELETE FROM public.identity_session r WHERE r.ctid IN (SELECT x.ctid FROM public.identity_session x
                WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'identity_customer_challenge' THEN
            DELETE FROM public.identity_customer_challenge r WHERE r.ctid IN (SELECT x.ctid
                FROM public.identity_customer_challenge x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'identity_rate_bucket' THEN
            DELETE FROM public.identity_rate_bucket r WHERE r.ctid IN (SELECT x.ctid FROM public.identity_rate_bucket x
                WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'identity_guard' THEN
            DELETE FROM public.identity_guard r WHERE r.ctid IN (SELECT x.ctid FROM public.identity_guard x
                WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'identity_staff' THEN
            DELETE FROM public.identity_staff r WHERE r.ctid IN (SELECT x.ctid FROM public.identity_staff x
                WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'identity_customer' THEN
            DELETE FROM public.identity_customer r WHERE r.ctid IN (SELECT x.ctid FROM public.identity_customer x
                WHERE x.tenant_id = target_tenant LIMIT max_rows);
        ELSE
            RAISE EXCEPTION 'not an identity table' USING ERRCODE = 'insufficient_privilege';
    END CASE;
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;
REVOKE ALL ON FUNCTION identity_erase_salon_rows(uuid, text, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_erase_salon_rows(uuid, text, timestamptz, integer) TO "${platform_role}";

CREATE FUNCTION branding_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz,
        max_rows integer)
    RETURNS integer
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
    erased   integer;
BEGIN
    IF NOT public.tenant_retention_passed(target_tenant, at_time) THEN
        RAISE EXCEPTION 'a salon''s rows are erased only after its retention' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF table_name <> 'salon_brand' THEN
        RAISE EXCEPTION 'not a branding table' USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    DELETE FROM public.salon_brand r WHERE r.ctid IN (SELECT x.ctid FROM public.salon_brand x
        WHERE x.tenant_id = target_tenant LIMIT max_rows);
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;
REVOKE ALL ON FUNCTION branding_erase_salon_rows(uuid, text, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION branding_erase_salon_rows(uuid, text, timestamptz, integer) TO "${platform_role}";

-- The tenant audit trail is never changed; after a closed salon's retention it is removed with the rest
-- of the salon's data (the platform audit keeps the platform's own record).
CREATE FUNCTION audit_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz,
        max_rows integer)
    RETURNS integer
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
    erased   integer;
BEGIN
    IF NOT public.tenant_retention_passed(target_tenant, at_time) THEN
        RAISE EXCEPTION 'a salon''s rows are erased only after its retention' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF table_name <> 'audit_entry' THEN
        RAISE EXCEPTION 'not an audit table' USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    DELETE FROM public.audit_entry r WHERE r.ctid IN (SELECT x.ctid FROM public.audit_entry x
        WHERE x.tenant_id = target_tenant LIMIT max_rows);
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;
REVOKE ALL ON FUNCTION audit_erase_salon_rows(uuid, text, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_erase_salon_rows(uuid, text, timestamptz, integer) TO "${platform_role}";

CREATE FUNCTION outbox_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz,
        max_rows integer)
    RETURNS integer
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
    erased   integer;
BEGIN
    IF NOT public.tenant_retention_passed(target_tenant, at_time) THEN
        RAISE EXCEPTION 'a salon''s rows are erased only after its retention' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF table_name <> 'outbox_message' THEN
        RAISE EXCEPTION 'not an outbox table' USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    DELETE FROM public.outbox_message r WHERE r.ctid IN (SELECT x.ctid FROM public.outbox_message x
        WHERE x.tenant_id = target_tenant LIMIT max_rows);
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;
REVOKE ALL ON FUNCTION outbox_erase_salon_rows(uuid, text, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION outbox_erase_salon_rows(uuid, text, timestamptz, integer) TO "${platform_role}";
