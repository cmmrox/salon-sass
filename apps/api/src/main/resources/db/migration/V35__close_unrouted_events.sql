-- Decision 119: an instance claims only the events whose type has a subscriber in its own build, so an event
-- nobody subscribes to stays pending, unattempted, for a build that does. Left at that, such events would
-- accumulate for ever (booking.BookingConfirmed.v1 has no subscriber until F11). After a window that covers a
-- rolling deployment and a short rollback (salon.outbox.unrouted-after, seven days), the outbox's housekeeping
-- closes each one, salon by salon, as 'unrouted': a terminal state of its own, never attempted. An unrouted
-- event is removed as a delivered message is, a retention after it was closed.
--
-- Additive, so the previous version keeps running against it: it never writes 'unrouted', and neither of its
-- functions changes.

ALTER TABLE outbox_message DROP CONSTRAINT outbox_message_status_known;
ALTER TABLE outbox_message ADD CONSTRAINT outbox_message_status_known
    CHECK (status IN ('pending', 'sending', 'sent', 'dead', 'cancelled', 'unrouted'));
ALTER TABLE outbox_message ADD CONSTRAINT outbox_message_unrouted_is_an_event
    CHECK (status <> 'unrouted' OR kind = 'event');

CREATE INDEX outbox_message_unrouted_idx ON outbox_message (tenant_id, updated_at) WHERE status = 'unrouted';

-- For the meters of an instance: messages waiting (pending or being delivered) across every salon, apart from
-- pending events of a type this instance has no subscriber for, which are counted on their own
-- (unsubscribed), so that events waiting for another build never look like a stuck dispatcher; and when the
-- oldest of the waiting ones that is due became due. Counts and a time only. It sets each salon in turn and
-- puts its caller's back, as outbox_backlog(timestamptz) does, which the previous version still calls.
CREATE FUNCTION outbox_backlog(at_time timestamptz, event_types text[])
    RETURNS TABLE (waiting bigint, oldest_due_at timestamptz, unsubscribed bigint)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    callers_salon      text := coalesce(current_setting('app.tenant_id', true), '');
    salon              uuid;
    salon_waiting      bigint;
    salon_unsubscribed bigint;
    salon_oldest       timestamptz;
BEGIN
    waiting := 0;
    unsubscribed := 0;
    oldest_due_at := NULL;
    FOR salon IN SELECT t.id FROM public.tenant t LOOP
        PERFORM set_config('app.tenant_id', salon::text, true);
        SELECT count(*) FILTER (WHERE NOT unheard), count(*) FILTER (WHERE unheard),
               min(due_at) FILTER (WHERE NOT unheard AND due_at <= at_time)
          INTO salon_waiting, salon_unsubscribed, salon_oldest
          FROM (SELECT m.status = 'pending' AND m.kind = 'event'
                       AND NOT (m.event_type = ANY (event_types)) AS unheard,
                       CASE WHEN m.status = 'pending' THEN m.next_attempt_at ELSE m.lease_until END AS due_at
                  FROM public.outbox_message m
                 WHERE m.tenant_id = salon AND m.status IN ('pending', 'sending')) waiting_messages;
        waiting := waiting + salon_waiting;
        unsubscribed := unsubscribed + salon_unsubscribed;
        IF salon_oldest IS NOT NULL AND (oldest_due_at IS NULL OR salon_oldest < oldest_due_at) THEN
            oldest_due_at := salon_oldest;
        END IF;
    END LOOP;
    PERFORM set_config('app.tenant_id', callers_salon, true);
    RETURN NEXT;
END
$$;

REVOKE ALL ON FUNCTION outbox_backlog(timestamptz, text[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION outbox_backlog(timestamptz, text[]) TO "${application_role}";
