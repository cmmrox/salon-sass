-- F06-S02, owned by staff (decisions 073, 087, 102): each team member's weekly working hours with breaks, and their
-- changes for single dates. Every table follows docs/architecture/multi-tenancy.md, "Adding a table", including
-- branch_id: the branch is branding's, held here as an id with no key into branding's tables (the default branch in
-- R1). Dates are the salon's local dates and times its local times, never instants, as opening hours are.
--
-- A week is copied once from the salon's opening hours when a person is added, and is not linked to them afterwards
-- (decision 102). No existing member gains a row: a member with no schedule row reads as having no working days, at
-- version 0, which also keeps this migration safe while the previous version of the application is running.

-- --- a person's schedule: its own version, so a save never conflicts with a profile edit (decision 102(f)) --------

CREATE TABLE staff_member_schedule (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    member_id   uuid        NOT NULL,
    version     bigint      NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT staff_member_schedule_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_member_schedule_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_member_schedule_member_fkey FOREIGN KEY (tenant_id, member_id)
        REFERENCES staff_team_member (tenant_id, id),
    CONSTRAINT staff_member_schedule_once UNIQUE (tenant_id, member_id),
    CONSTRAINT staff_member_schedule_version CHECK (version >= 1)
);

-- --- the week: seven rows a member, the times null on a day they do not work -------------------------------------

CREATE TABLE staff_member_weekday (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    member_id   uuid        NOT NULL,
    -- ISO: 1 is Monday, 7 is Sunday.
    weekday     smallint    NOT NULL,
    starts      time,
    finishes    time,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT staff_member_weekday_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_member_weekday_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_member_weekday_member_fkey FOREIGN KEY (tenant_id, member_id)
        REFERENCES staff_team_member (tenant_id, id),
    CONSTRAINT staff_member_weekday_once UNIQUE (tenant_id, member_id, weekday),
    CONSTRAINT staff_member_weekday_day CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT staff_member_weekday_times CHECK ((starts IS NULL) = (finishes IS NULL)
        AND (starts IS NULL OR (finishes > starts
            AND EXTRACT(EPOCH FROM starts)::integer % 900 = 0
            AND EXTRACT(EPOCH FROM finishes)::integer % 900 = 0)))
);

CREATE TABLE staff_member_weekday_break (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    member_id   uuid        NOT NULL,
    weekday_id  uuid        NOT NULL,
    starts      time        NOT NULL,
    finishes    time        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT staff_member_weekday_break_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_member_weekday_break_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_member_weekday_break_member_fkey FOREIGN KEY (tenant_id, member_id)
        REFERENCES staff_team_member (tenant_id, id),
    CONSTRAINT staff_member_weekday_break_day_fkey FOREIGN KEY (tenant_id, weekday_id)
        REFERENCES staff_member_weekday (tenant_id, id),
    CONSTRAINT staff_member_weekday_break_once UNIQUE (tenant_id, weekday_id, starts),
    CONSTRAINT staff_member_weekday_break_times CHECK (finishes > starts
        AND EXTRACT(EPOCH FROM starts)::integer % 900 = 0
        AND EXTRACT(EPOCH FROM finishes)::integer % 900 = 0)
);

CREATE INDEX staff_member_weekday_break_member ON staff_member_weekday_break (tenant_id, member_id);

-- --- a change for one date, which replaces the week's times and breaks on that date only --------------------------

-- Working with both times, or not working with neither. The unique key keeps one change a date under simultaneous
-- requests. The note is for the team only, and never written to the audit trail or a log.
CREATE TABLE staff_member_date_change (
    tenant_id    uuid        NOT NULL,
    id           uuid        NOT NULL,
    branch_id    uuid        NOT NULL,
    member_id    uuid        NOT NULL,
    change_date  date        NOT NULL,
    starts       time,
    finishes     time,
    note         text,
    -- identity's staff account, with no key across modules.
    created_by   uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT staff_member_date_change_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_member_date_change_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_member_date_change_member_fkey FOREIGN KEY (tenant_id, member_id)
        REFERENCES staff_team_member (tenant_id, id),
    CONSTRAINT staff_member_date_change_one_per_date UNIQUE (tenant_id, member_id, change_date),
    CONSTRAINT staff_member_date_change_times CHECK ((starts IS NULL) = (finishes IS NULL)
        AND (starts IS NULL OR (finishes > starts
            AND EXTRACT(EPOCH FROM starts)::integer % 900 = 0
            AND EXTRACT(EPOCH FROM finishes)::integer % 900 = 0))),
    CONSTRAINT staff_member_date_change_note CHECK (
        note IS NULL OR (note = btrim(note) AND char_length(note) BETWEEN 1 AND 60))
);

CREATE TABLE staff_member_date_change_break (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    member_id   uuid        NOT NULL,
    change_id   uuid        NOT NULL,
    starts      time        NOT NULL,
    finishes    time        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT staff_member_date_change_break_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_member_date_change_break_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_member_date_change_break_member_fkey FOREIGN KEY (tenant_id, member_id)
        REFERENCES staff_team_member (tenant_id, id),
    CONSTRAINT staff_member_date_change_break_change_fkey FOREIGN KEY (tenant_id, change_id)
        REFERENCES staff_member_date_change (tenant_id, id),
    CONSTRAINT staff_member_date_change_break_once UNIQUE (tenant_id, change_id, starts),
    CONSTRAINT staff_member_date_change_break_times CHECK (finishes > starts
        AND EXTRACT(EPOCH FROM starts)::integer % 900 = 0
        AND EXTRACT(EPOCH FROM finishes)::integer % 900 = 0)
);

-- --- isolation ------------------------------------------------------------------------------------------------

ALTER TABLE staff_member_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_member_schedule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_member_schedule
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_member_weekday ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_member_weekday FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_member_weekday
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_member_weekday_break ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_member_weekday_break FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_member_weekday_break
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_member_date_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_member_date_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_member_date_change
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_member_date_change_break ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_member_date_change_break FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_member_date_change_break
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- grants: only what the application does -------------------------------------------------------------------

REVOKE ALL ON TABLE staff_member_schedule FROM PUBLIC;
REVOKE ALL ON TABLE staff_member_weekday FROM PUBLIC;
REVOKE ALL ON TABLE staff_member_weekday_break FROM PUBLIC;
REVOKE ALL ON TABLE staff_member_date_change FROM PUBLIC;
REVOKE ALL ON TABLE staff_member_date_change_break FROM PUBLIC;
-- A save moves the version on.
GRANT SELECT, INSERT, UPDATE ON TABLE staff_member_schedule TO "${application_role}";
-- A save replaces the week's rows.
GRANT SELECT, INSERT, DELETE ON TABLE staff_member_weekday TO "${application_role}";
GRANT SELECT, INSERT, DELETE ON TABLE staff_member_weekday_break TO "${application_role}";
-- A date change is added or removed, never changed.
GRANT SELECT, INSERT, DELETE ON TABLE staff_member_date_change TO "${application_role}";
GRANT SELECT, INSERT, DELETE ON TABLE staff_member_date_change_break TO "${application_role}";

-- --- staff's eraser learns the new tables -----------------------------------------------------------------------

CREATE OR REPLACE FUNCTION staff_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz, max_rows integer)
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
        WHEN 'staff_member_date_change_break' THEN
            DELETE FROM public.staff_member_date_change_break r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_date_change_break x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_member_date_change' THEN
            DELETE FROM public.staff_member_date_change r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_date_change x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_member_weekday_break' THEN
            DELETE FROM public.staff_member_weekday_break r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_weekday_break x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_member_weekday' THEN
            DELETE FROM public.staff_member_weekday r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_weekday x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_member_schedule' THEN
            DELETE FROM public.staff_member_schedule r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_schedule x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_member_category' THEN
            DELETE FROM public.staff_member_category r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_category x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_member_service' THEN
            DELETE FROM public.staff_member_service r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_service x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_team_member' THEN
            DELETE FROM public.staff_team_member r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_team_member x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_member_photo' THEN
            DELETE FROM public.staff_member_photo r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_photo x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_holiday_choice' THEN
            DELETE FROM public.staff_holiday_choice r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_holiday_choice x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_holiday_review' THEN
            DELETE FROM public.staff_holiday_review r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_holiday_review x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_closure' THEN
            DELETE FROM public.staff_closure r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_closure x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_opening_hours' THEN
            DELETE FROM public.staff_opening_hours r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_opening_hours x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        ELSE
            RAISE EXCEPTION 'not a staff table' USING ERRCODE = 'insufficient_privilege';
    END CASE;
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;
