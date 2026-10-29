-- Durable events between modules (CR-021 decision 3; F23-S05). An event is an outbox message of kind
-- 'event', written in the publisher's transaction and delivered by the same dispatcher to every
-- subscriber of its type, each in its own transaction with the event's salon set.
--
-- Additive, so that the previous version keeps running against it: the new columns have a default or
-- are nullable, and a provider message written without them is a 'delivery' as before. An event's
-- channel is 'event', which no provider delivers, so a previous version's dispatcher, which asks
-- outbox_due() for its providers' channels only, never claims one.

ALTER TABLE outbox_message ADD COLUMN kind text NOT NULL DEFAULT 'delivery';
-- The event's type name with its version ('identity.CustomerDataErased.v1'); null for a delivery.
ALTER TABLE outbox_message ADD COLUMN event_type text;

ALTER TABLE outbox_message ADD CONSTRAINT outbox_message_kind_known CHECK (kind IN ('delivery', 'event'));
ALTER TABLE outbox_message ADD CONSTRAINT outbox_message_event_has_type CHECK ((kind = 'event') = (event_type IS NOT NULL));
ALTER TABLE outbox_message ADD CONSTRAINT outbox_message_event_channel CHECK ((kind = 'event') = (channel = 'event'));

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
