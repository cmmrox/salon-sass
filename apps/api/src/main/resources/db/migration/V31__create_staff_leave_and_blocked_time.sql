-- F06-S03, owned by staff (decisions 099, 104): a team member's leave, as whole days, and their blocked time, as part of
-- one day. Every table follows docs/architecture/multi-tenancy.md, "Adding a table", including branch_id: the branch is
-- branding's, held here as an id with no key into branding's tables. Dates are the salon's local dates and times its
-- local times, never instants.
--
-- Two of one person's leaves may not overlap, nor two of their blocks, and each has a limit. Adding either holds the
-- member's row first, which keeps those rules under simultaneous requests and stops one being added to someone being
-- removed, so neither rule needs a constraint here. A booking's flag is worked out when read and is stored nowhere
-- (decision 104(c)). A removed member's rows stay, unreadable by any route, until salon erasure.

-- --- leave: whole days. Its type and note are private: only a caller with team.manage receives them, and neither is
-- ever written to the audit trail or a log (decision 104(a)) -------------------------------------------------------

CREATE TABLE staff_member_leave (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    member_id   uuid        NOT NULL,
    first_day   date        NOT NULL,
    last_day    date        NOT NULL,
    leave_type  text        NOT NULL,
    note        text,
    -- identity's staff account, with no key across modules.
    created_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT staff_member_leave_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_member_leave_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_member_leave_member_fkey FOREIGN KEY (tenant_id, member_id)
        REFERENCES staff_team_member (tenant_id, id),
    CONSTRAINT staff_member_leave_days CHECK (last_day >= first_day),
    CONSTRAINT staff_member_leave_type CHECK (leave_type IN ('annual', 'sick', 'personal', 'other')),
    CONSTRAINT staff_member_leave_note CHECK (
        note IS NULL OR (note = btrim(note) AND char_length(note) BETWEEN 1 AND 60))
);

-- A person's leave that has not ended, which is what the overlap rule, the limit, the list and the card all read.
CREATE INDEX staff_member_leave_member ON staff_member_leave (tenant_id, member_id, last_day);

-- --- blocked time: part of one date, with a label ---------------------------------------------------------------

CREATE TABLE staff_member_blocked_time (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    member_id   uuid        NOT NULL,
    block_date  date        NOT NULL,
    starts      time        NOT NULL,
    finishes    time        NOT NULL,
    label       text        NOT NULL,
    -- identity's staff account, with no key across modules.
    created_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT staff_member_blocked_time_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_member_blocked_time_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_member_blocked_time_member_fkey FOREIGN KEY (tenant_id, member_id)
        REFERENCES staff_team_member (tenant_id, id),
    CONSTRAINT staff_member_blocked_time_times CHECK (finishes > starts
        AND EXTRACT(EPOCH FROM starts)::integer % 900 = 0
        AND EXTRACT(EPOCH FROM finishes)::integer % 900 = 0),
    CONSTRAINT staff_member_blocked_time_label CHECK (label = btrim(label) AND char_length(label) BETWEEN 2 AND 40)
);

CREATE INDEX staff_member_blocked_time_member ON staff_member_blocked_time (tenant_id, member_id, block_date);

-- --- isolation ------------------------------------------------------------------------------------------------

ALTER TABLE staff_member_leave ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_member_leave FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_member_leave
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_member_blocked_time ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_member_blocked_time FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_member_blocked_time
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- grants: only what the application does: each is added or removed, never changed ----------------------------

REVOKE ALL ON TABLE staff_member_leave FROM PUBLIC;
REVOKE ALL ON TABLE staff_member_blocked_time FROM PUBLIC;
GRANT SELECT, INSERT, DELETE ON TABLE staff_member_leave TO "${application_role}";
GRANT SELECT, INSERT, DELETE ON TABLE staff_member_blocked_time TO "${application_role}";

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
        WHEN 'staff_member_leave' THEN
            DELETE FROM public.staff_member_leave r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_leave x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_member_blocked_time' THEN
            DELETE FROM public.staff_member_blocked_time r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_blocked_time x WHERE x.tenant_id = target_tenant LIMIT max_rows);
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
