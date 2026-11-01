-- F06-S01, owned by platform: a request for a staff seat that waited behind a plan move read "no plan" and so was not
-- limited, leaving the salon over its new plan. The hole has been there since F03-S02 for staff accounts.
--
-- V14's salon_entitlements() took the lock and read the plan in one statement, FOR SHARE OF t over a LEFT JOIN of plan.
-- After waiting for a move to commit, PostgreSQL rechecks only the locked row in READ COMMITTED: it has the new
-- tenant.plan_id but the plan row it joined before waiting, so the join no longer matches and the plan comes back NULL,
-- which the caller reads as a salon without a plan.
--
-- The lock and the read are now two statements. It stays VOLATILE, on purpose: that gives each statement its own
-- snapshot, so the read sees what the move committed. A STABLE function would reuse the caller's snapshot and read the
-- old plan instead. The signature, the owner and the grants are unchanged, and a salon genuinely without a plan still
-- answers a row of nulls, which limits nothing.
CREATE OR REPLACE FUNCTION salon_entitlements()
    RETURNS TABLE (plan_id uuid, plan_key text, plan_name text, staff_seats integer, branches integer,
                   message_allowance integer, custom_domain boolean, dedicated_app boolean)
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    salon uuid := NULLIF(pg_catalog.current_setting('app.tenant_id', true), '')::uuid;
BEGIN
    PERFORM 1 FROM public.tenant t WHERE t.id = salon FOR SHARE OF t;
    RETURN QUERY
        SELECT p.id, p.key, p.name, p.staff_seats, p.branches, p.message_allowance, p.custom_domain, p.dedicated_app
          FROM public.tenant t LEFT JOIN public.plan p ON p.id = t.plan_id
         WHERE t.id = salon;
END
$$;
