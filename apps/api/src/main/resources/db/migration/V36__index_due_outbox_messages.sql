-- Platform review M3: outbox_due, outbox_due_events and outbox_backlog visited every salon on every poll of every
-- instance (setting it, then asking its rows), so an idle platform's cost grew with its number of salons. They now
-- read one small index of what is waiting: one row per pending or in-flight message, holding its salon's id, its
-- id, its channel or event type, its state and when it is next due. Nothing of a message's content, as the
-- functions themselves have always returned only ids, counts and times.
--
-- The index is a platform table (no tenant_id, like scheduled_job), owned by shared, which the application's role
-- cannot reach: a trigger keeps it, in the same transaction as each change of a message, so it is exact whichever
-- version wrote the message. A row is keyed by its message, so two transactions touch the same row only when they
-- change the same message, which already locks it. The trigger function runs with its owner's rights to write the
-- index, pins its search path, and nobody may call it.
--
-- The functions keep their signatures and their answers, so the previous version, still running during the
-- deployment, uses them unchanged.

CREATE TABLE shared_outbox_due (
    -- The message's salon. No foreign key: only tenant_id may refer to the registry across modules, and a row
    -- goes with its message, which has one.
    salon_id   uuid        NOT NULL,
    message_id uuid        NOT NULL,
    channel    text        NOT NULL,
    -- An event's type; null for a provider's message.
    event_type text,
    -- 'pending' (due at its next attempt) or 'sending' (due again when its lease runs out).
    status     text        NOT NULL,
    due_at     timestamptz NOT NULL,
    CONSTRAINT shared_outbox_due_pkey PRIMARY KEY (salon_id, message_id),
    CONSTRAINT shared_outbox_due_status_known CHECK (status IN ('pending', 'sending'))
);

CREATE INDEX shared_outbox_due_deliveries_idx ON shared_outbox_due (channel, due_at) WHERE event_type IS NULL;
CREATE INDEX shared_outbox_due_events_idx ON shared_outbox_due (event_type, due_at) WHERE event_type IS NOT NULL;

REVOKE ALL ON TABLE shared_outbox_due FROM PUBLIC;

CREATE FUNCTION shared_outbox_due_track()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM public.shared_outbox_due WHERE salon_id = OLD.tenant_id AND message_id = OLD.id;
        RETURN NULL;
    END IF;
    IF NEW.status IN ('pending', 'sending') THEN
        INSERT INTO public.shared_outbox_due (salon_id, message_id, channel, event_type, status, due_at)
            VALUES (NEW.tenant_id, NEW.id, NEW.channel, NEW.event_type, NEW.status,
                    CASE WHEN NEW.status = 'pending' THEN NEW.next_attempt_at ELSE NEW.lease_until END)
            ON CONFLICT (salon_id, message_id) DO UPDATE
               SET status = EXCLUDED.status, due_at = EXCLUDED.due_at;
    ELSIF TG_OP = 'UPDATE' THEN
        DELETE FROM public.shared_outbox_due WHERE salon_id = NEW.tenant_id AND message_id = NEW.id;
    END IF;
    RETURN NULL;
END
$$;

REVOKE ALL ON FUNCTION shared_outbox_due_track() FROM PUBLIC;

CREATE TRIGGER outbox_message_due_track
    AFTER INSERT OR UPDATE OF status, next_attempt_at, lease_until OR DELETE ON outbox_message
    FOR EACH ROW EXECUTE FUNCTION shared_outbox_due_track();

-- The messages already waiting. The trigger above already holds the table, so nothing is missed between the two.
-- Row-level security is forced, so the owner sees one salon at a time.
DO $$
DECLARE
    salon uuid;
BEGIN
    FOR salon IN SELECT t.id FROM tenant t LOOP
        PERFORM set_config('app.tenant_id', salon::text, true);
        INSERT INTO shared_outbox_due (salon_id, message_id, channel, event_type, status, due_at)
            SELECT m.tenant_id, m.id, m.channel, m.event_type, m.status,
                   CASE WHEN m.status = 'pending' THEN m.next_attempt_at ELSE m.lease_until END
              FROM outbox_message m
             WHERE m.tenant_id = salon AND m.status IN ('pending', 'sending')
            ON CONFLICT (salon_id, message_id) DO NOTHING;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- What is due across salons, as before (V6, V15): the ids of due provider messages on the given channels and of
-- their salons, at most per_salon for one salon and max_count in all, an active salon's on any channel and a
-- closed salon's on 'platform-email' only. Each salon's first due messages come before any salon's next ones, so
-- one salon's backlog cannot hold back the others.
CREATE OR REPLACE FUNCTION outbox_due(at_time timestamptz, channels text[], per_salon integer, max_count integer)
    RETURNS TABLE (message_id uuid, tenant_id uuid)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT due.message_id, due.salon_id FROM (
        SELECT d.message_id, d.salon_id, d.due_at,
               row_number() OVER (PARTITION BY d.salon_id ORDER BY d.due_at, d.message_id) AS place
          FROM public.shared_outbox_due d
          JOIN public.tenant t ON t.id = d.salon_id
         WHERE d.event_type IS NULL AND d.channel = ANY (channels) AND d.due_at <= at_time
           AND (t.status = 'active' OR (t.status = 'closed' AND d.channel = 'platform-email'))
    ) due
     WHERE due.place <= per_salon
     ORDER BY due.place, due.due_at, due.message_id
     LIMIT greatest(max_count, 0)
$$;

-- What events are due across salons, as before (V17): of the given types, of active salons only.
CREATE OR REPLACE FUNCTION outbox_due_events(at_time timestamptz, event_types text[], per_salon integer, max_count integer)
    RETURNS TABLE (message_id uuid, tenant_id uuid)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT due.message_id, due.salon_id FROM (
        SELECT d.message_id, d.salon_id, d.due_at,
               row_number() OVER (PARTITION BY d.salon_id ORDER BY d.due_at, d.message_id) AS place
          FROM public.shared_outbox_due d
          JOIN public.tenant t ON t.id = d.salon_id
         WHERE d.event_type = ANY (event_types) AND d.due_at <= at_time AND t.status = 'active'
    ) due
     WHERE due.place <= per_salon
     ORDER BY due.place, due.due_at, due.message_id
     LIMIT greatest(max_count, 0)
$$;

-- The backlog for the previous version's meters, as before (V6): every waiting message of every salon.
CREATE OR REPLACE FUNCTION outbox_backlog(at_time timestamptz)
    RETURNS TABLE (waiting bigint, oldest_due_at timestamptz)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT count(*), min(d.due_at) FILTER (WHERE d.due_at <= at_time) FROM public.shared_outbox_due d
$$;

-- The backlog of an instance (V35): pending events of a type it has no subscriber for are counted apart.
CREATE OR REPLACE FUNCTION outbox_backlog(at_time timestamptz, event_types text[])
    RETURNS TABLE (waiting bigint, oldest_due_at timestamptz, unsubscribed bigint)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT count(*) FILTER (WHERE NOT w.unheard),
           min(w.due_at) FILTER (WHERE NOT w.unheard AND w.due_at <= at_time),
           count(*) FILTER (WHERE w.unheard)
      FROM (SELECT d.due_at,
                   d.status = 'pending' AND d.event_type IS NOT NULL AND NOT (d.event_type = ANY (event_types))
                       AS unheard
              FROM public.shared_outbox_due d) w
$$;
