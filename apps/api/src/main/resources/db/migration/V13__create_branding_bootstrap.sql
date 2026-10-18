-- F03-S01, owned by branding: the minimal published name and neutral colour a new salon needs for
-- its workspace hostname to resolve. Platform calls it through branding's public provisioning
-- facade, on the platform connection, in the transaction that creates the salon. Not a brand editor.

-- Inserts the brand row of a salon created in the calling transaction. Refuses any other salon and
-- a second bootstrap, sets that one salon for row-level security inside the function and puts the
-- caller's setting back before it returns.
CREATE FUNCTION branding_bootstrap_brand(target_tenant uuid, display_name text, brand_colour text,
        at_time timestamptz)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := current_setting('app.tenant_id', true);
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.tenant t
                    WHERE t.id = target_tenant AND t.xmin = pg_catalog.pg_current_xact_id()::xid) THEN
        RAISE EXCEPTION 'a brand is bootstrapped only for a salon created in this transaction'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    IF EXISTS (SELECT 1 FROM public.salon_brand b WHERE b.tenant_id = target_tenant) THEN
        RAISE EXCEPTION 'this salon already has a brand'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    INSERT INTO public.salon_brand (tenant_id, display_name, brand_colour, created_at, updated_at)
        VALUES (target_tenant, display_name, brand_colour, at_time, at_time);
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
END
$$;

REVOKE ALL ON FUNCTION branding_bootstrap_brand(uuid, text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION branding_bootstrap_brand(uuid, text, text, timestamptz) TO "${platform_role}";
