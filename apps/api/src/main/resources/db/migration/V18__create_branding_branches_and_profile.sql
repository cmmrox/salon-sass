-- F04-S01, owned by branding (decision 083): every salon's default branch, and the salon's business
-- profile as a draft and a published copy. The salon-wide part (description, links) is one row per
-- salon; each branch's contact details (address, map position, phone, WhatsApp) are one row per
-- branch. Each table is created with the whole checklist of docs/architecture/multi-tenancy.md,
-- "Adding a table". Other modules hold a branch as an id and check it through SalonBranches; no key
-- of theirs points here.

-- --- branches ------------------------------------------------------------------------------------

CREATE TABLE branding_branch (
    tenant_id  uuid        NOT NULL,
    id         uuid        NOT NULL,
    name       text        NOT NULL,
    is_default boolean     NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT branding_branch_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT branding_branch_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT branding_branch_name_present CHECK (btrim(name) <> '' AND char_length(name) <= 120)
);
-- At most one default branch per salon (decision 073).
CREATE UNIQUE INDEX branding_branch_one_default ON branding_branch (tenant_id) WHERE is_default;

ALTER TABLE branding_branch ENABLE ROW LEVEL SECURITY;
ALTER TABLE branding_branch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON branding_branch
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Branches are created by provisioning and by this migration only, in R1; the application reads them.
REVOKE ALL ON TABLE branding_branch FROM PUBLIC;
GRANT SELECT ON TABLE branding_branch TO "${application_role}";

-- --- the salon-wide profile: draft and published --------------------------------------------------

-- Lengths and forms are the API's rules, held here as well so that no writer can store another
-- shape: description up to 500 characters, each link an https address of up to 200, and null (never
-- blank) for "not set".
CREATE TABLE branding_profile_draft (
    tenant_id     uuid        NOT NULL,
    -- Optimistic concurrency: every save, publication and discard moves it on.
    version       bigint      NOT NULL,
    description   text,
    instagram_url text,
    facebook_url  text,
    website_url   text,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT branding_profile_draft_pkey PRIMARY KEY (tenant_id),
    CONSTRAINT branding_profile_draft_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT branding_profile_draft_version_known CHECK (version >= 0),
    CONSTRAINT branding_profile_draft_description_shape
        CHECK (description IS NULL OR (btrim(description) <> '' AND char_length(description) <= 500)),
    CONSTRAINT branding_profile_draft_links_shape CHECK (
        (instagram_url IS NULL OR (instagram_url ~ '^https://\S+$' AND char_length(instagram_url) <= 200))
        AND (facebook_url IS NULL OR (facebook_url ~ '^https://\S+$' AND char_length(facebook_url) <= 200))
        AND (website_url IS NULL OR (website_url ~ '^https://\S+$' AND char_length(website_url) <= 200)))
);

CREATE TABLE branding_profile_published (
    tenant_id     uuid        NOT NULL,
    description   text,
    instagram_url text,
    facebook_url  text,
    website_url   text,
    published_at  timestamptz NOT NULL,
    -- The staff account that published: identity's id, held as an id (no key across modules).
    published_by  uuid        NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT branding_profile_published_pkey PRIMARY KEY (tenant_id),
    CONSTRAINT branding_profile_published_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT branding_profile_published_description_shape
        CHECK (description IS NULL OR (btrim(description) <> '' AND char_length(description) <= 500)),
    CONSTRAINT branding_profile_published_links_shape CHECK (
        (instagram_url IS NULL OR (instagram_url ~ '^https://\S+$' AND char_length(instagram_url) <= 200))
        AND (facebook_url IS NULL OR (facebook_url ~ '^https://\S+$' AND char_length(facebook_url) <= 200))
        AND (website_url IS NULL OR (website_url ~ '^https://\S+$' AND char_length(website_url) <= 200)))
);

-- --- each branch's contact details: draft and published --------------------------------------------

-- Address up to 200 characters; the map position as both coordinates or neither, in range; phone
-- numbers in E.164 (decision 084).
CREATE TABLE branding_branch_contact_draft (
    tenant_id  uuid         NOT NULL,
    branch_id  uuid         NOT NULL,
    address    text,
    latitude   numeric(8,6),
    longitude  numeric(9,6),
    phone      text,
    whatsapp   text,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    CONSTRAINT branding_branch_contact_draft_pkey PRIMARY KEY (tenant_id, branch_id),
    CONSTRAINT branding_branch_contact_draft_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT branding_branch_contact_draft_branch_fkey
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branding_branch (tenant_id, id),
    CONSTRAINT branding_branch_contact_draft_address_shape
        CHECK (address IS NULL OR (btrim(address) <> '' AND char_length(address) <= 200)),
    CONSTRAINT branding_branch_contact_draft_location_shape CHECK (
        (latitude IS NULL) = (longitude IS NULL)
        AND (latitude IS NULL OR latitude BETWEEN -90 AND 90)
        AND (longitude IS NULL OR longitude BETWEEN -180 AND 180)),
    CONSTRAINT branding_branch_contact_draft_phones_shape CHECK (
        (phone IS NULL OR phone ~ '^\+[1-9][0-9]{6,14}$')
        AND (whatsapp IS NULL OR whatsapp ~ '^\+[1-9][0-9]{6,14}$'))
);

CREATE TABLE branding_branch_contact_published (
    tenant_id  uuid         NOT NULL,
    branch_id  uuid         NOT NULL,
    address    text,
    latitude   numeric(8,6),
    longitude  numeric(9,6),
    phone      text,
    whatsapp   text,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    CONSTRAINT branding_branch_contact_published_pkey PRIMARY KEY (tenant_id, branch_id),
    CONSTRAINT branding_branch_contact_published_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT branding_branch_contact_published_branch_fkey
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branding_branch (tenant_id, id),
    CONSTRAINT branding_branch_contact_published_address_shape
        CHECK (address IS NULL OR (btrim(address) <> '' AND char_length(address) <= 200)),
    CONSTRAINT branding_branch_contact_published_location_shape CHECK (
        (latitude IS NULL) = (longitude IS NULL)
        AND (latitude IS NULL OR latitude BETWEEN -90 AND 90)
        AND (longitude IS NULL OR longitude BETWEEN -180 AND 180)),
    CONSTRAINT branding_branch_contact_published_phones_shape CHECK (
        (phone IS NULL OR phone ~ '^\+[1-9][0-9]{6,14}$')
        AND (whatsapp IS NULL OR whatsapp ~ '^\+[1-9][0-9]{6,14}$'))
);

ALTER TABLE branding_profile_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE branding_profile_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON branding_profile_draft
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE branding_profile_published ENABLE ROW LEVEL SECURITY;
ALTER TABLE branding_profile_published FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON branding_profile_published
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE branding_branch_contact_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE branding_branch_contact_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON branding_branch_contact_draft
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE branding_branch_contact_published ENABLE ROW LEVEL SECURITY;
ALTER TABLE branding_branch_contact_published FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON branding_branch_contact_published
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- The drafts exist from the salon's creation and are only changed; the published copy is written at
-- the first publication and changed at every later one. Nothing here is deleted by the application.
REVOKE ALL ON TABLE branding_profile_draft FROM PUBLIC;
REVOKE ALL ON TABLE branding_profile_published FROM PUBLIC;
REVOKE ALL ON TABLE branding_branch_contact_draft FROM PUBLIC;
REVOKE ALL ON TABLE branding_branch_contact_published FROM PUBLIC;
GRANT SELECT, UPDATE ON TABLE branding_profile_draft TO "${application_role}";
GRANT SELECT, INSERT, UPDATE ON TABLE branding_profile_published TO "${application_role}";
GRANT SELECT, UPDATE ON TABLE branding_branch_contact_draft TO "${application_role}";
GRANT SELECT, INSERT, UPDATE ON TABLE branding_branch_contact_published TO "${application_role}";

-- --- a salon's default branch and its empty drafts --------------------------------------------------

-- Gives one salon its default branch, named after the salon, with an empty contact draft, and the
-- salon its empty profile draft. Changes nothing a salon already has, so running it twice for the
-- same salon is the same as running it once. Sets that one salon for row-level security and puts the
-- caller's setting back. Not SECURITY DEFINER: only its owner (migrations, the bootstrap below, the
-- local seeds) runs it.
CREATE FUNCTION branding_add_default_branch(target_tenant uuid, branch_name text, at_time timestamptz)
    RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
    branch   uuid;
BEGIN
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    SELECT b.id INTO branch FROM public.branding_branch b WHERE b.tenant_id = target_tenant AND b.is_default;
    IF branch IS NULL THEN
        branch := pg_catalog.gen_random_uuid();
        INSERT INTO public.branding_branch (tenant_id, id, name, is_default, created_at, updated_at)
            VALUES (target_tenant, branch, branch_name, true, at_time, at_time);
    END IF;
    INSERT INTO public.branding_branch_contact_draft (tenant_id, branch_id, created_at, updated_at)
        VALUES (target_tenant, branch, at_time, at_time)
        ON CONFLICT DO NOTHING;
    INSERT INTO public.branding_profile_draft (tenant_id, version, created_at, updated_at)
        VALUES (target_tenant, 0, at_time, at_time)
        ON CONFLICT DO NOTHING;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
END
$$;

REVOKE ALL ON FUNCTION branding_add_default_branch(uuid, text, timestamptz) FROM PUBLIC;

-- Every existing salon gets its default branch (decision 083). A deleted salon is a tombstone with no
-- data, so it gets none. The branch is named after the published name, or the registry's name, or the
-- slug, in that order, whichever the salon has first.
DO $$
DECLARE
    salon record;
BEGIN
    FOR salon IN
        SELECT t.id, coalesce(nullif(btrim(left(b.display_name, 120)), ''), nullif(btrim(left(t.name, 120)), ''),
                              t.slug) AS branch_name
          FROM tenant t
          LEFT JOIN LATERAL (SELECT sb.display_name FROM salon_brand sb WHERE sb.tenant_id = t.id) b ON true
         WHERE t.status <> 'deleted'
         ORDER BY t.id
    LOOP
        PERFORM branding_add_default_branch(salon.id, salon.branch_name, now());
    END LOOP;
END
$$;

-- --- provisioning: the bootstrap also creates the default branch ------------------------------------

-- As V13's, and in the same platform transaction that creates the salon, the new salon's default
-- branch and empty drafts (F04-S01 AC-1). Same refusals, same signature and grants.
CREATE OR REPLACE FUNCTION branding_bootstrap_brand(target_tenant uuid, display_name text, brand_colour text,
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
    PERFORM public.branding_add_default_branch(target_tenant, left(display_name, 120), at_time);
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
END
$$;

-- --- retention: branding's eraser covers the new tables ---------------------------------------------

-- As V15's, for every branding table; the platform job calls it in BrandTenantData's order, which
-- removes the contact rows before the branch they belong to.
CREATE OR REPLACE FUNCTION branding_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz,
        max_rows integer)
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
        WHEN 'branding_branch_contact_published' THEN
            DELETE FROM public.branding_branch_contact_published r WHERE r.ctid IN (SELECT x.ctid
                FROM public.branding_branch_contact_published x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'branding_branch_contact_draft' THEN
            DELETE FROM public.branding_branch_contact_draft r WHERE r.ctid IN (SELECT x.ctid
                FROM public.branding_branch_contact_draft x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'branding_profile_published' THEN
            DELETE FROM public.branding_profile_published r WHERE r.ctid IN (SELECT x.ctid
                FROM public.branding_profile_published x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'branding_profile_draft' THEN
            DELETE FROM public.branding_profile_draft r WHERE r.ctid IN (SELECT x.ctid
                FROM public.branding_profile_draft x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'branding_branch' THEN
            DELETE FROM public.branding_branch r WHERE r.ctid IN (SELECT x.ctid
                FROM public.branding_branch x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'salon_brand' THEN
            DELETE FROM public.salon_brand r WHERE r.ctid IN (SELECT x.ctid FROM public.salon_brand x
                WHERE x.tenant_id = target_tenant LIMIT max_rows);
        ELSE
            RAISE EXCEPTION 'not a branding table' USING ERRCODE = 'insufficient_privilege';
    END CASE;
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;
