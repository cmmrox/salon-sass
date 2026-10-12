-- A salon's time zone (BKG-15, decision 029): an IANA name, carried by the tenant context, in which
-- the salon's times are shown. Added so that the previous version of the application keeps
-- working against the migrated database: the column has a default, and tenant_by_slug keeps its
-- first two columns, which are all that version reads.

-- Whether a name is a time zone that both the database and the application know: an IANA name in
-- the database's own time zone list ('UTC', or 'Area/Location' and the like). Abbreviations and
-- the database's private names ('posixrules', 'localtime') are refused.
CREATE FUNCTION is_iana_time_zone(zone text)
    RETURNS boolean
    LANGUAGE sql
    STABLE
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT zone ~ '^(UTC|[A-Z][A-Za-z_]+(/[A-Za-z0-9_+-]+){1,2})$'
       AND EXISTS (SELECT 1 FROM pg_catalog.pg_timezone_names n WHERE n.name = zone)
$$;

REVOKE ALL ON FUNCTION is_iana_time_zone(text) FROM PUBLIC;

ALTER TABLE tenant
    ADD COLUMN time_zone text NOT NULL DEFAULT 'Asia/Colombo'
        CONSTRAINT tenant_time_zone_known CHECK (is_iana_time_zone(time_zone));

-- The one way the application learns a hostname's salon: now with its time zone. The return type
-- of a function cannot be changed in place, so it is dropped and created again in this migration's
-- transaction; the previous version selects id and status by name, which still works.
DROP FUNCTION tenant_by_slug(text);

CREATE FUNCTION tenant_by_slug(requested_slug text)
    RETURNS TABLE (id uuid, status text, time_zone text)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id, t.status, t.time_zone FROM public.tenant t WHERE t.slug = requested_slug
$$;

REVOKE ALL ON FUNCTION tenant_by_slug(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenant_by_slug(text) TO "${application_role}";

-- Background work (the outbox dispatcher, recurring jobs) holds a salon's id, never a hostname, and
-- sets the salon for each unit of work (T-7). This is how it learns that salon's slug, status and
-- time zone, one id at a time.
CREATE FUNCTION tenant_by_id(requested_id uuid)
    RETURNS TABLE (id uuid, slug text, status text, time_zone text)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id, t.slug, t.status, t.time_zone FROM public.tenant t WHERE t.id = requested_id
$$;

REVOKE ALL ON FUNCTION tenant_by_id(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenant_by_id(uuid) TO "${application_role}";

-- The ids of the active salons, and nothing else: what a recurring job over salon data iterates.
CREATE FUNCTION active_tenant_ids()
    RETURNS TABLE (id uuid)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id FROM public.tenant t WHERE t.status = 'active' ORDER BY t.id
$$;

REVOKE ALL ON FUNCTION active_tenant_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION active_tenant_ids() TO "${application_role}";
