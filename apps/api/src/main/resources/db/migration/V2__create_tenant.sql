-- The tenant registry: one row per salon. A platform table (docs/architecture/multi-tenancy.md,
-- "Salons and their hostnames"): it has no tenant_id, and the application's role cannot read it.
-- The application learns which salon a hostname names only through tenant_by_slug() below.
--
-- The application_role placeholder is the role the API connects as (spring.datasource.username).
-- It is created by the environment before the API starts, never by a migration.

CREATE TABLE tenant (
    id         uuid        NOT NULL,
    slug       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_pkey PRIMARY KEY (id),
    -- The nil UUID stands for "no salon" inside the application; it can never be a salon.
    CONSTRAINT tenant_id_not_nil CHECK (id <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT tenant_slug_key UNIQUE (slug),
    -- 3 to 30 characters of lowercase letters, digits and hyphens, beginning and ending with a
    -- letter or digit. PostgreSQL compares regular-expression ranges by code point, so [a-z] holds
    -- no uppercase or accented letter whatever the database's collation.
    CONSTRAINT tenant_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$'),
    -- The platform's own hostnames, which can never be a salon.
    CONSTRAINT tenant_slug_not_reserved CHECK (slug NOT IN (
        'admin', 'api', 'app', 'apps', 'assets', 'auth', 'cdn', 'console', 'docs', 'help', 'login',
        'mail', 'staging', 'static', 'status', 'support', 'www')),
    CONSTRAINT tenant_status_known CHECK (status IN ('active', 'suspended'))
);

REVOKE ALL ON TABLE tenant FROM PUBLIC;

-- The one way the application's role reads the registry: the id and status of one slug, or no row.
-- It runs with the owner's rights, so the role needs no grant on the table itself; search_path is
-- pinned so that nothing the caller creates can stand in for the table or an operator.
CREATE FUNCTION tenant_by_slug(requested_slug text)
    RETURNS TABLE (id uuid, status text)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id, t.status FROM public.tenant t WHERE t.slug = requested_slug
$$;

REVOKE ALL ON FUNCTION tenant_by_slug(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenant_by_slug(text) TO "${application_role}";
