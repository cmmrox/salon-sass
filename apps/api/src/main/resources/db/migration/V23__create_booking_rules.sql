-- F04-S04, owned by booking (decision 089): the salon's booking rules, one immutable row per save. The
-- current rules are a branch's newest version; a booking (F07) keeps the id of the version it was made under,
-- so no later save rewrites its terms. Created with the whole checklist of docs/architecture/multi-tenancy.md,
-- "Adding a table", including branch_id: the branch is branding's, held here as an id with no key into
-- branding's tables and checked through SalonBranches when a version is written (decisions 073 and 083).
-- Amounts are bigint minor units in the version's currency, the salon's when it was saved (LKR when unset).

CREATE TABLE booking_rules_version (
    tenant_id                  uuid        NOT NULL,
    id                         uuid        NOT NULL,
    branch_id                  uuid        NOT NULL,
    version                    bigint      NOT NULL,
    slot_interval_minutes      integer     NOT NULL,
    minimum_notice_minutes     integer     NOT NULL,
    booking_horizon_days       integer     NOT NULL,
    deposit_kind               text        NOT NULL,
    deposit_percent            integer,
    deposit_cap_minor          bigint,
    deposit_amount_minor       bigint,
    currency                   text        NOT NULL,
    free_cancellation_hours    integer     NOT NULL,
    late_cancellation_kind     text        NOT NULL,
    late_cancellation_percent  integer,
    no_show_kind               text        NOT NULL,
    no_show_percent            integer,
    -- 0 is not online (no time limit); 1 or 2 times; null is any number of times.
    reschedule_limit           integer,
    reschedule_until_hours     integer,
    -- The rules this save changed from the version before it (every rule for a branch's first version).
    changed_fields             text[]      NOT NULL,
    created_by                 uuid        NOT NULL,
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL,
    CONSTRAINT booking_rules_version_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT booking_rules_version_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    -- Two saves made from the same version both try the next number: exactly one is kept.
    CONSTRAINT booking_rules_version_number UNIQUE (tenant_id, branch_id, version),
    CONSTRAINT booking_rules_version_positive CHECK (version >= 1),
    CONSTRAINT booking_rules_version_interval CHECK (slot_interval_minutes IN (10, 15, 20, 30)),
    CONSTRAINT booking_rules_version_notice CHECK (minimum_notice_minutes IN (0, 30, 60, 120, 240, 1440)),
    CONSTRAINT booking_rules_version_horizon CHECK (booking_horizon_days IN (14, 30, 60, 90, 180)),
    CONSTRAINT booking_rules_version_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT booking_rules_version_deposit CHECK (
        (deposit_kind = 'none'
            AND deposit_percent IS NULL AND deposit_cap_minor IS NULL AND deposit_amount_minor IS NULL)
        OR (deposit_kind = 'percentage'
            AND deposit_percent BETWEEN 1 AND 100 AND deposit_cap_minor > 0 AND deposit_amount_minor IS NULL)
        OR (deposit_kind = 'amount'
            AND deposit_percent IS NULL AND deposit_cap_minor IS NULL AND deposit_amount_minor > 0)),
    CONSTRAINT booking_rules_version_free_cancellation CHECK (free_cancellation_hours IN (2, 6, 12, 24, 48)),
    CONSTRAINT booking_rules_version_late_cancellation CHECK (
        (late_cancellation_kind IN ('nothing', 'deposit') AND late_cancellation_percent IS NULL)
        OR (late_cancellation_kind = 'share' AND late_cancellation_percent BETWEEN 1 AND 100)),
    CONSTRAINT booking_rules_version_no_show CHECK (
        (no_show_kind = 'deposit' AND no_show_percent IS NULL)
        OR (no_show_kind = 'share' AND no_show_percent BETWEEN 1 AND 100)),
    CONSTRAINT booking_rules_version_reschedule CHECK (
        (reschedule_limit IS NOT NULL AND reschedule_limit = 0 AND reschedule_until_hours IS NULL)
        OR ((reschedule_limit IS NULL OR reschedule_limit IN (1, 2))
            AND reschedule_until_hours IS NOT NULL AND reschedule_until_hours IN (2, 6, 12, 24))),
    CONSTRAINT booking_rules_version_written_once CHECK (updated_at = created_at)
);

ALTER TABLE booking_rules_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_rules_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON booking_rules_version
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- A version is written once and read: no update or delete for the application role.
REVOKE ALL ON TABLE booking_rules_version FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE booking_rules_version TO "${application_role}";

-- And no role changes one: an update is refused whoever runs it. Only booking's eraser removes rows.
CREATE FUNCTION booking_rules_version_refuse_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'a booking rules version never changes' USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER booking_rules_version_immutable
    BEFORE UPDATE ON booking_rules_version
    FOR EACH ROW EXECUTE FUNCTION booking_rules_version_refuse_update();

-- --- retention: booking's eraser -------------------------------------------------------------------------

-- As V15's erasers: only after the salon's retention has passed, one salon and one table at a time.
CREATE FUNCTION booking_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz, max_rows integer)
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

REVOKE ALL ON FUNCTION booking_erase_salon_rows(uuid, text, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION booking_erase_salon_rows(uuid, text, timestamptz, integer) TO "${platform_role}";
