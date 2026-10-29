-- Durable events between modules (CR-021 decision 3; F23-S05). An event is an outbox message of kind
-- 'event', written in the publisher's transaction and delivered by the same dispatcher to every
-- subscriber of its type, each in its own transaction with the event's salon set.
--
-- Additive, so that the previous version keeps running against it: the new columns have a default or
-- are nullable, and a provider message written without them is a 'delivery' as before. An event's
-- channel is 'event', which no provider delivers, so a previous version's dispatcher, which asks
-- outbox_due() for its providers' channels only, never claims one; this version asks outbox_due() the same
-- way and outbox_due_events() for the event types it knows.

ALTER TABLE outbox_message ADD COLUMN kind text NOT NULL DEFAULT 'delivery';
-- The event's type name with its version ('identity.CustomerDataErased.v1'); null for a delivery.
ALTER TABLE outbox_message ADD COLUMN event_type text;

ALTER TABLE outbox_message ADD CONSTRAINT outbox_message_kind_known CHECK (kind IN ('delivery', 'event'));
ALTER TABLE outbox_message ADD CONSTRAINT outbox_message_event_has_type CHECK ((kind = 'event') = (event_type IS NOT NULL));
ALTER TABLE outbox_message ADD CONSTRAINT outbox_message_event_channel CHECK ((kind = 'event') = (channel = 'event'));

-- What events are due across salons, for the dispatcher: the ids of due events of the types the asking
-- instance knows, and of their salons, nothing else, as outbox_due() does for provider messages. An event of a
-- type the instance's build does not declare is never returned, so it stays pending, unclaimed and with no
-- attempt counted, for an instance that knows it: during a rolling deployment an older instance never marks a
-- newer event delivered with nothing done (decision 064). Only an active salon's events are due; a suspended
-- salon's wait, and a closed salon's are never delivered. It sets each salon in turn and puts its caller's back.
CREATE FUNCTION outbox_due_events(at_time timestamptz, event_types text[], per_salon integer, max_count integer)
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
                   AND m.kind = 'event' AND m.event_type = ANY (event_types)
                UNION ALL
                SELECT m.id, m.tenant_id, m.lease_until FROM public.outbox_message m
                 WHERE m.tenant_id = salon AND m.status = 'sending' AND m.lease_until <= at_time
                   AND m.kind = 'event' AND m.event_type = ANY (event_types)
            ) due
            ORDER BY due.due_at, due.id
            LIMIT least(per_salon, remaining);
        GET DIAGNOSTICS found = ROW_COUNT;
        remaining := remaining - found;
    END LOOP;
    PERFORM set_config('app.tenant_id', callers_salon, true);
END
$$;

REVOKE ALL ON FUNCTION outbox_due_events(timestamptz, text[], integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION outbox_due_events(timestamptz, text[], integer, integer) TO "${application_role}";

-- Which subscriber has processed which event, per salon. A subscriber's change and this record commit in
-- one transaction, so a repeated delivery (a lease that ran out before the outcome was recorded, or a
-- second dispatcher) finds the record and does nothing. A tenant table, created with the whole checklist
-- in docs/architecture/multi-tenancy.md. The record goes with its event: when the outbox's retention, a
-- privacy erasure or a closed salon's deletion removes the event, its records go too.
CREATE TABLE shared_processed_event (
    tenant_id  uuid        NOT NULL,
    event_id   uuid        NOT NULL,
    -- The subscriber's stable name, as it registers itself ('identity.local-message-capture').
    subscriber text        NOT NULL,
    -- When the subscriber's change committed. A record is never changed, so updated_at is created_at.
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT shared_processed_event_pkey PRIMARY KEY (tenant_id, event_id, subscriber),
    CONSTRAINT shared_processed_event_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT shared_processed_event_event_fkey FOREIGN KEY (tenant_id, event_id)
        REFERENCES outbox_message (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT shared_processed_event_subscriber_present CHECK (btrim(subscriber) <> ''),
    CONSTRAINT shared_processed_event_unchanged CHECK (updated_at = created_at)
);

ALTER TABLE shared_processed_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE shared_processed_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shared_processed_event
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- The dispatcher records and reads; nothing updates a record, and its removal follows its event.
REVOKE ALL ON TABLE shared_processed_event FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE shared_processed_event TO "${application_role}";

-- A closed salon's deletion erases the outbox's tables through the outbox's own guarded eraser, now two
-- tables: the processed records first, then the messages (TenantDataModule's order). Unchanged otherwise.
CREATE OR REPLACE FUNCTION outbox_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz,
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
    IF table_name NOT IN ('outbox_message', 'shared_processed_event') THEN
        RAISE EXCEPTION 'not an outbox table' USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    IF table_name = 'shared_processed_event' THEN
        DELETE FROM public.shared_processed_event r WHERE r.ctid IN (SELECT x.ctid FROM public.shared_processed_event x
            WHERE x.tenant_id = target_tenant LIMIT max_rows);
    ELSE
        DELETE FROM public.outbox_message r WHERE r.ctid IN (SELECT x.ctid FROM public.outbox_message x
            WHERE x.tenant_id = target_tenant LIMIT max_rows);
    END IF;
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;
