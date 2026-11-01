-- F04-S03, owned by staff (decision 087): each branch's weekly opening hours, its review of each published
-- holiday year and its choice for each holiday, and its special closures. Every table is created with the
-- whole checklist of docs/architecture/multi-tenancy.md, "Adding a table", including branch_id: the branch
-- is branding's, held here as an id with no key into branding's tables and checked through SalonBranches
-- when it is written (decisions 073 and 083). Dates are the salon's local dates and times its local times,
-- never instants; the salon's time zone is applied when they are used.

-- --- weekly opening hours: one row per branch, from its first save ---------------------------------------

-- A day is closed when both its times are null. Open, it closes after it opens, both on a quarter hour.
CREATE TABLE staff_opening_hours (
    tenant_id     uuid        NOT NULL,
    id            uuid        NOT NULL,
    branch_id     uuid        NOT NULL,
    version       bigint      NOT NULL,
    monday_opens     time,
    monday_closes    time,
    tuesday_opens    time,
    tuesday_closes   time,
    wednesday_opens  time,
    wednesday_closes time,
    thursday_opens   time,
    thursday_closes  time,
    friday_opens     time,
    friday_closes    time,
    saturday_opens   time,
    saturday_closes  time,
    sunday_opens     time,
    sunday_closes    time,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT staff_opening_hours_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_opening_hours_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_opening_hours_one_per_branch UNIQUE (tenant_id, branch_id),
    CONSTRAINT staff_opening_hours_version CHECK (version >= 1),
    CONSTRAINT staff_opening_hours_monday CHECK ((monday_opens IS NULL) = (monday_closes IS NULL)
        AND (monday_opens IS NULL OR (monday_closes > monday_opens
            AND EXTRACT(EPOCH FROM monday_opens)::integer % 900 = 0
            AND EXTRACT(EPOCH FROM monday_closes)::integer % 900 = 0))),
    CONSTRAINT staff_opening_hours_tuesday CHECK ((tuesday_opens IS NULL) = (tuesday_closes IS NULL)
        AND (tuesday_opens IS NULL OR (tuesday_closes > tuesday_opens
            AND EXTRACT(EPOCH FROM tuesday_opens)::integer % 900 = 0
            AND EXTRACT(EPOCH FROM tuesday_closes)::integer % 900 = 0))),
    CONSTRAINT staff_opening_hours_wednesday CHECK ((wednesday_opens IS NULL) = (wednesday_closes IS NULL)
        AND (wednesday_opens IS NULL OR (wednesday_closes > wednesday_opens
            AND EXTRACT(EPOCH FROM wednesday_opens)::integer % 900 = 0
            AND EXTRACT(EPOCH FROM wednesday_closes)::integer % 900 = 0))),
    CONSTRAINT staff_opening_hours_thursday CHECK ((thursday_opens IS NULL) = (thursday_closes IS NULL)
        AND (thursday_opens IS NULL OR (thursday_closes > thursday_opens
            AND EXTRACT(EPOCH FROM thursday_opens)::integer % 900 = 0
            AND EXTRACT(EPOCH FROM thursday_closes)::integer % 900 = 0))),
    CONSTRAINT staff_opening_hours_friday CHECK ((friday_opens IS NULL) = (friday_closes IS NULL)
        AND (friday_opens IS NULL OR (friday_closes > friday_opens
            AND EXTRACT(EPOCH FROM friday_opens)::integer % 900 = 0
            AND EXTRACT(EPOCH FROM friday_closes)::integer % 900 = 0))),
    CONSTRAINT staff_opening_hours_saturday CHECK ((saturday_opens IS NULL) = (saturday_closes IS NULL)
        AND (saturday_opens IS NULL OR (saturday_closes > saturday_opens
            AND EXTRACT(EPOCH FROM saturday_opens)::integer % 900 = 0
            AND EXTRACT(EPOCH FROM saturday_closes)::integer % 900 = 0))),
    CONSTRAINT staff_opening_hours_sunday CHECK ((sunday_opens IS NULL) = (sunday_closes IS NULL)
        AND (sunday_opens IS NULL OR (sunday_closes > sunday_opens
            AND EXTRACT(EPOCH FROM sunday_opens)::integer % 900 = 0
            AND EXTRACT(EPOCH FROM sunday_closes)::integer % 900 = 0)))
);

-- --- holiday review: one row per branch and published year, from its first acceptance -------------------

CREATE TABLE staff_holiday_review (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    year        integer     NOT NULL,
    version     bigint      NOT NULL,
    accepted_at timestamptz NOT NULL,
    accepted_by uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT staff_holiday_review_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_holiday_review_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_holiday_review_one_per_year UNIQUE (tenant_id, branch_id, year),
    CONSTRAINT staff_holiday_review_year CHECK (year BETWEEN 2000 AND 2100),
    CONSTRAINT staff_holiday_review_version CHECK (version >= 1)
);

-- A choice for one holiday, made against the revision of that day it names. It applies while the published
-- day still carries that revision; a later revision that changes the day puts it back to review, and a
-- withdrawn day's choice is kept (for the audit) but applies to nothing.
CREATE TABLE staff_holiday_choice (
    tenant_id    uuid        NOT NULL,
    id           uuid        NOT NULL,
    branch_id    uuid        NOT NULL,
    review_id    uuid        NOT NULL,
    holiday_date date        NOT NULL,
    choice       text        NOT NULL,
    opens        time,
    closes       time,
    day_revision integer     NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT staff_holiday_choice_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_holiday_choice_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_holiday_choice_review_fkey FOREIGN KEY (tenant_id, review_id)
        REFERENCES staff_holiday_review (tenant_id, id),
    CONSTRAINT staff_holiday_choice_one_per_date UNIQUE (tenant_id, branch_id, holiday_date),
    CONSTRAINT staff_holiday_choice_kind CHECK (choice IN ('closed', 'open', 'special')),
    CONSTRAINT staff_holiday_choice_revision CHECK (day_revision >= 1),
    CONSTRAINT staff_holiday_choice_times CHECK (
        (choice = 'special') = (opens IS NOT NULL) AND (opens IS NULL) = (closes IS NULL)
        AND (opens IS NULL OR (closes > opens
            AND EXTRACT(EPOCH FROM opens)::integer % 900 = 0
            AND EXTRACT(EPOCH FROM closes)::integer % 900 = 0)))
);
CREATE INDEX staff_holiday_choice_review ON staff_holiday_choice (tenant_id, review_id);

-- --- special closures: a whole day, at most one per branch and date -------------------------------------

-- The reason only the salon's team sees: 1 to 60 characters with no surrounding space, or null.
CREATE TABLE staff_closure (
    tenant_id    uuid        NOT NULL,
    id           uuid        NOT NULL,
    branch_id    uuid        NOT NULL,
    closure_date date        NOT NULL,
    reason       text,
    created_by   uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT staff_closure_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_closure_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_closure_one_per_date UNIQUE (tenant_id, branch_id, closure_date),
    CONSTRAINT staff_closure_reason CHECK (
        reason IS NULL OR (reason = btrim(reason) AND reason <> '' AND char_length(reason) <= 60))
);

-- --- row-level security and grants ----------------------------------------------------------------------

ALTER TABLE staff_opening_hours ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_opening_hours FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_opening_hours
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_holiday_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_holiday_review FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_holiday_review
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_holiday_choice ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_holiday_choice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_holiday_choice
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_closure ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_closure FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_closure
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

REVOKE ALL ON TABLE staff_opening_hours FROM PUBLIC;
REVOKE ALL ON TABLE staff_holiday_review FROM PUBLIC;
REVOKE ALL ON TABLE staff_holiday_choice FROM PUBLIC;
REVOKE ALL ON TABLE staff_closure FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE staff_opening_hours TO "${application_role}";
GRANT SELECT, INSERT, UPDATE ON TABLE staff_holiday_review TO "${application_role}";
GRANT SELECT, INSERT, UPDATE ON TABLE staff_holiday_choice TO "${application_role}";
-- Reopening a day removes its closure.
GRANT SELECT, INSERT, DELETE ON TABLE staff_closure TO "${application_role}";

-- --- retention: staff's eraser ---------------------------------------------------------------------------

-- As V15's erasers: only after the salon's retention has passed, one salon and one table at a time, in
-- StaffTenantData's order, which removes the choices before the reviews they belong to.
CREATE FUNCTION staff_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz, max_rows integer)
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

REVOKE ALL ON FUNCTION staff_erase_salon_rows(uuid, text, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION staff_erase_salon_rows(uuid, text, timestamptz, integer) TO "${platform_role}";
