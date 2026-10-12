-- The outbox (NFR-15): everything that leaves the system is written here in the transaction of the
-- change that caused it, and delivered afterwards by the dispatcher, with retry and a dead-letter
-- state. A tenant table, created with the whole checklist in docs/architecture/multi-tenancy.md.
-- Every time in it is the application's clock, written by the application.

CREATE TABLE outbox_message (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL,
    -- Which provider delivers it ('probe' locally; later 'sms', 'whatsapp', 'email', ...).
    channel         text        NOT NULL,
    payload         jsonb       NOT NULL,
    status          text        NOT NULL,
    -- Attempts begun so far, the one in progress included.
    attempts        integer     NOT NULL DEFAULT 0,
    -- When it may next be attempted.
    next_attempt_at timestamptz NOT NULL,
    -- While it is being delivered: until when the dispatcher that claimed it holds it, and which
    -- claim that is. A claim whose lease has run out is attempted again.
    lease_until     timestamptz,
    lease_token     uuid,
    last_error      text,
    -- The W3C trace context of the work that wrote it, so that its delivery joins that trace.
    trace_parent    text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    sent_at         timestamptz,
    CONSTRAINT outbox_message_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT outbox_message_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT outbox_message_channel_present CHECK (btrim(channel) <> ''),
    CONSTRAINT outbox_message_status_known CHECK (status IN ('pending', 'sending', 'sent', 'dead')),
    CONSTRAINT outbox_message_attempts_counted CHECK (attempts >= 0),
    CONSTRAINT outbox_message_lease_held_while_sending CHECK (
        (status = 'sending') = (lease_until IS NOT NULL AND lease_token IS NOT NULL)),
    CONSTRAINT outbox_message_sent_has_time CHECK ((status = 'sent') = (sent_at IS NOT NULL))
);

CREATE INDEX outbox_message_due_idx ON outbox_message (tenant_id, next_attempt_at) WHERE status = 'pending';
CREATE INDEX outbox_message_lease_idx ON outbox_message (tenant_id, lease_until) WHERE status = 'sending';
CREATE INDEX outbox_message_sent_idx ON outbox_message (tenant_id, sent_at) WHERE status = 'sent';

ALTER TABLE outbox_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_message FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON outbox_message
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

REVOKE ALL ON TABLE outbox_message FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE outbox_message TO "${application_role}";

-- How the dispatcher learns what is due across salons: the ids of due messages and of their salons,
-- and nothing else. It then handles each message in a transaction with that salon set.
--
-- Row-level security is forced on the table, so it holds for the owner this function runs as
-- (unless the owner is a superuser, as on a developer's machine). The function therefore sets each
-- active salon in turn and asks for that salon's messages, and before it returns puts back the
-- salon its caller had set, or none: a caller is never left with another salon, and reads no more
-- afterwards than it did before. (A SET clause on the function would do the same, but only a
-- superuser may attach one for a setting of our own, and the owning role is not one.)
--
-- A due message is a pending one whose time has come, or one whose delivery was interrupted: its
-- lease has run out. "Now" is the application's clock, passed in. At most per_salon messages are
-- returned for one salon and max_count in all, salons taken in a random order, so that one salon's
-- backlog cannot hold back the others. A suspended salon's messages wait until it is active again.
CREATE FUNCTION outbox_due(at_time timestamptz, channels text[], per_salon integer, max_count integer)
    RETURNS TABLE (message_id uuid, tenant_id uuid)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    callers_salon text := coalesce(current_setting('app.tenant_id', true), '');
    salon         uuid;
    remaining     integer := max_count;
    found         integer;
BEGIN
    FOR salon IN SELECT t.id FROM public.tenant t WHERE t.status = 'active' ORDER BY random() LOOP
        EXIT WHEN remaining <= 0;
        PERFORM set_config('app.tenant_id', salon::text, true);
        RETURN QUERY
            SELECT due.id, due.tenant_id FROM (
                SELECT m.id, m.tenant_id, m.next_attempt_at AS due_at FROM public.outbox_message m
                 WHERE m.tenant_id = salon AND m.status = 'pending' AND m.next_attempt_at <= at_time
                   AND m.channel = ANY (channels)
                UNION ALL
                SELECT m.id, m.tenant_id, m.lease_until FROM public.outbox_message m
                 WHERE m.tenant_id = salon AND m.status = 'sending' AND m.lease_until <= at_time
                   AND m.channel = ANY (channels)
            ) due
            ORDER BY due.due_at, due.id
            LIMIT least(per_salon, remaining);
        GET DIAGNOSTICS found = ROW_COUNT;
        remaining := remaining - found;
    END LOOP;
    PERFORM set_config('app.tenant_id', callers_salon, true);
END
$$;

REVOKE ALL ON FUNCTION outbox_due(timestamptz, text[], integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION outbox_due(timestamptz, text[], integer, integer) TO "${application_role}";

-- For the meters: how many messages are waiting (pending or being delivered) across every salon,
-- and when the oldest one that is due became due. Counts and a time only, nothing of any message.
-- It sets each salon in turn and puts its caller's back, as outbox_due does.
CREATE FUNCTION outbox_backlog(at_time timestamptz)
    RETURNS TABLE (waiting bigint, oldest_due_at timestamptz)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    callers_salon text := coalesce(current_setting('app.tenant_id', true), '');
    salon         uuid;
    salon_count   bigint;
    salon_oldest  timestamptz;
BEGIN
    waiting := 0;
    oldest_due_at := NULL;
    FOR salon IN SELECT t.id FROM public.tenant t LOOP
        PERFORM set_config('app.tenant_id', salon::text, true);
        SELECT count(*) INTO salon_count FROM public.outbox_message m
         WHERE m.tenant_id = salon AND m.status IN ('pending', 'sending');
        SELECT min(least(CASE WHEN m.status = 'pending' THEN m.next_attempt_at END,
                         CASE WHEN m.status = 'sending' THEN m.lease_until END))
          INTO salon_oldest FROM public.outbox_message m
         WHERE m.tenant_id = salon
           AND ((m.status = 'pending' AND m.next_attempt_at <= at_time)
             OR (m.status = 'sending' AND m.lease_until <= at_time));
        waiting := waiting + salon_count;
        IF salon_oldest IS NOT NULL AND (oldest_due_at IS NULL OR salon_oldest < oldest_due_at) THEN
            oldest_due_at := salon_oldest;
        END IF;
    END LOOP;
    PERFORM set_config('app.tenant_id', callers_salon, true);
    RETURN NEXT;
END
$$;

REVOKE ALL ON FUNCTION outbox_backlog(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION outbox_backlog(timestamptz) TO "${application_role}";
