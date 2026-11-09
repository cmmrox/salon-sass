-- F07-S01, owned by booking: holds and bookings in one table with a state, and the history of every change of
-- state (BKG-04, BKG-05, BKG-06, BKG-14, BKG-16). Each table is created with the whole checklist of
-- docs/architecture/multi-tenancy.md, "Adding a table", including branch_id: the branch is branding's, held here
-- as an id with no key into branding's tables and checked through SalonBranches when a booking is written
-- (decisions 073 and 083). The professional, the service, its terms version, the variant and the booking rules
-- version are other modules' rows, held as ids (decision 089, CAT-04).
--
-- The guarantee (BKG-05, NFR-05): the time a booking occupies its professional is a multirange of instants, and
-- an exclusion constraint refuses two live rows of one salon and professional whose ranges overlap, however many
-- transactions insert at once. Ranges are half-open, so two bookings that only touch are both kept. A processing
-- segment (decision 074, F17) is left out of the range, so it occupies no professional. Live is a state of the row
-- itself: a hold whose expiry has passed is still 'held' until something marks it 'expired', and the application
-- does that, by its own clock, in the transaction that is about to need the time (docs/engineering/backend.md,
-- "Booking integrity"), so an expired hold never blocks a new booking even before the job has cleared it.

-- btree_gist gives the uuid columns the equality a GiST exclusion constraint needs beside the ranges. It is a
-- trusted extension, so the database's owner, which runs the migrations, may create it.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE booking_appointment (
    tenant_id            uuid             NOT NULL,
    id                   uuid             NOT NULL,
    branch_id            uuid             NOT NULL,
    -- Six characters of an alphabet with nothing that reads like another (decision 108(d)), shown as K7M-4QX.
    reference            text             NOT NULL,
    -- 'released' and 'expired' end a hold; such a row is never shown again and occupies nothing.
    state                text             NOT NULL,
    member_id            uuid             NOT NULL,
    -- Names as they were when it was booked, so a list needs no read of another module per row.
    member_name          text             NOT NULL,
    service_id           uuid             NOT NULL,
    service_terms_id     uuid             NOT NULL,
    service_name         text             NOT NULL,
    variant_id           uuid,
    variant_name         text,
    rules_version_id     uuid             NOT NULL,
    starts_at            timestamptz      NOT NULL,
    -- The end of the last segment.
    ends_at              timestamptz      NOT NULL,
    -- The service's segments as booked, in order, as the catalogue's terms keep them: each kind (active,
    -- processing or buffer) and its minutes.
    segment_kinds        text[]           NOT NULL,
    segment_minutes      integer[]        NOT NULL,
    occupied             tstzmultirange   NOT NULL,
    price_minor          bigint           NOT NULL,
    currency             text             NOT NULL,
    customer_name        text             NOT NULL,
    -- E.164 (decision 084).
    customer_mobile      text             NOT NULL,
    channel              text             NOT NULL,
    hold_expires_at      timestamptz,
    idempotency_key      uuid             NOT NULL,
    request_fingerprint  text             NOT NULL,
    created_by           uuid             NOT NULL,
    version              bigint           NOT NULL,
    created_at           timestamptz      NOT NULL,
    updated_at           timestamptz      NOT NULL,
    CONSTRAINT booking_appointment_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT booking_appointment_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    -- BKG-14: unique within the salon; two salons may hold the same reference.
    CONSTRAINT booking_appointment_reference UNIQUE (tenant_id, reference),
    -- A repeated request with the same key finds the booking it made.
    CONSTRAINT booking_appointment_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT booking_appointment_no_overlap EXCLUDE USING gist (
        tenant_id WITH =,
        member_id WITH =,
        occupied WITH &&)
        WHERE (state IN ('held', 'pending_payment', 'confirmed', 'arrived', 'in_service', 'completed')),
    CONSTRAINT booking_appointment_reference_format CHECK (reference ~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$'),
    CONSTRAINT booking_appointment_state CHECK (state IN ('held', 'pending_payment', 'confirmed', 'arrived',
        'in_service', 'completed', 'cancelled', 'no_show', 'released', 'expired')),
    CONSTRAINT booking_appointment_times CHECK (ends_at > starts_at),
    CONSTRAINT booking_appointment_occupied CHECK (NOT isempty(occupied)
        AND lower(occupied) >= starts_at AND upper(occupied) <= ends_at),
    CONSTRAINT booking_appointment_segments CHECK (cardinality(segment_kinds) >= 1
        AND cardinality(segment_kinds) = cardinality(segment_minutes)
        AND segment_kinds <@ ARRAY['active', 'processing', 'buffer']::text[]
        AND 0 < ALL (segment_minutes)),
    CONSTRAINT booking_appointment_variant CHECK ((variant_id IS NULL) = (variant_name IS NULL)),
    CONSTRAINT booking_appointment_price CHECK (price_minor >= 0),
    CONSTRAINT booking_appointment_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT booking_appointment_customer_name CHECK (
        customer_name = btrim(customer_name) AND char_length(customer_name) BETWEEN 2 AND 60),
    CONSTRAINT booking_appointment_customer_mobile CHECK (customer_mobile ~ '^\+[1-9][0-9]{6,14}$'),
    -- BKG-16; customer_app and shared_app_link are written by F15 only.
    CONSTRAINT booking_appointment_channel CHECK (channel IN ('customer_app', 'staff_web', 'shared_app_link',
        'instagram', 'google', 'whatsapp', 'phone', 'front_desk', 'walk_in')),
    CONSTRAINT booking_appointment_hold_expiry CHECK (state NOT IN ('held', 'pending_payment') OR hold_expires_at IS NOT NULL),
    CONSTRAINT booking_appointment_version CHECK (version >= 0)
);

CREATE INDEX booking_appointment_branch_start ON booking_appointment (tenant_id, branch_id, starts_at);
CREATE INDEX booking_appointment_member_start ON booking_appointment (tenant_id, member_id, starts_at);
CREATE INDEX booking_appointment_service_start ON booking_appointment (tenant_id, service_id, starts_at);
CREATE INDEX booking_appointment_held ON booking_appointment (tenant_id, hold_expires_at) WHERE state = 'held';

ALTER TABLE booking_appointment ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_appointment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON booking_appointment
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- A booking changes state and is never removed by the application: an ended hold is kept, with its history.
REVOKE ALL ON TABLE booking_appointment FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE booking_appointment TO "${application_role}";

-- --- history ---------------------------------------------------------------------------------------------------

-- One row per change of state, placing the hold included (from_state null): who (a staff account, or the system),
-- when by the application's clock, and the reason and note of a cancellation (BKG-06, decision 075).
CREATE TABLE booking_state_change (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    booking_id  uuid        NOT NULL,
    -- 1 for the placing of the hold, then one more for each change, in the order they were made.
    position    integer     NOT NULL,
    from_state  text,
    to_state    text        NOT NULL,
    changed_at  timestamptz NOT NULL,
    actor_kind  text        NOT NULL,
    actor_id    uuid,
    reason      text,
    note        text,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT booking_state_change_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT booking_state_change_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT booking_state_change_booking_fkey FOREIGN KEY (tenant_id, booking_id)
        REFERENCES booking_appointment (tenant_id, id),
    CONSTRAINT booking_state_change_position UNIQUE (tenant_id, booking_id, position),
    CONSTRAINT booking_state_change_position_positive CHECK (position >= 1),
    CONSTRAINT booking_state_change_states CHECK (
        (from_state IS NULL OR from_state IN ('held', 'pending_payment', 'confirmed', 'arrived', 'in_service',
            'completed', 'cancelled', 'no_show', 'released', 'expired'))
        AND to_state IN ('held', 'pending_payment', 'confirmed', 'arrived', 'in_service', 'completed', 'cancelled',
            'no_show', 'released', 'expired')),
    CONSTRAINT booking_state_change_actor CHECK (
        (actor_kind = 'staff' AND actor_id IS NOT NULL) OR (actor_kind = 'system' AND actor_id IS NULL)),
    CONSTRAINT booking_state_change_reason CHECK (
        reason IS NULL OR reason IN ('customer_asked', 'salon_unable', 'booked_by_mistake', 'other')),
    CONSTRAINT booking_state_change_note CHECK (note IS NULL OR (reason IS NOT NULL AND char_length(note) <= 200)),
    CONSTRAINT booking_state_change_written_once CHECK (updated_at = created_at)
);

ALTER TABLE booking_state_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_state_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON booking_state_change
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

REVOKE ALL ON TABLE booking_state_change FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE booking_state_change TO "${application_role}";

CREATE FUNCTION booking_state_change_refuse_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'a booking''s history never changes' USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER booking_state_change_immutable
    BEFORE UPDATE ON booking_state_change
    FOR EACH ROW EXECUTE FUNCTION booking_state_change_refuse_update();

-- --- booking's eraser learns the new tables ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION booking_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz, max_rows integer)
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
        WHEN 'booking_state_change' THEN
            DELETE FROM public.booking_state_change r WHERE r.ctid IN (SELECT x.ctid
                FROM public.booking_state_change x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'booking_appointment' THEN
            DELETE FROM public.booking_appointment r WHERE r.ctid IN (SELECT x.ctid
                FROM public.booking_appointment x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'booking_rules_version' THEN
            DELETE FROM public.booking_rules_version r WHERE r.ctid IN (SELECT x.ctid
                FROM public.booking_rules_version x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        ELSE
            RAISE EXCEPTION 'not a booking table' USING ERRCODE = 'insufficient_privilege';
    END CASE;
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;
