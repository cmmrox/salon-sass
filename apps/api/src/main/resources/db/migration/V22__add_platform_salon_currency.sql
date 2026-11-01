-- F04-S04, owned by platform: the currency of the salon set in the calling tenant transaction, for a module
-- that keeps amounts in the salon's currency (booking's rules, decision 089). The tenant role has no grant on
-- the registry; as tenant_by_slug() and salon_entitlements() (decision 090's pattern), this narrow function
-- is its one path to the column. It answers for the salon in app.tenant_id and no other, and null when that
-- salon has no currency or no salon is set; platform's SalonCurrency reads null as LKR.
CREATE FUNCTION salon_currency()
    RETURNS text
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.currency FROM public.tenant t
     WHERE t.id = NULLIF(pg_catalog.current_setting('app.tenant_id', true), '')::uuid
$$;

REVOKE ALL ON FUNCTION salon_currency() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION salon_currency() TO "${application_role}";
