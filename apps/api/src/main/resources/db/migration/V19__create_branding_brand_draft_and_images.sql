-- F04-S02, owned by branding: the brand as a draft and a published copy, and the brand's images
-- (decision 086). salon_brand stays the published brand everything already reads; it gains nullable
-- columns, so the running version keeps working while this migrates. The draft is a new table with the
-- version, on F04-S01's pattern. Each new table carries the whole checklist of
-- docs/architecture/multi-tenancy.md, "Adding a table".

-- --- images ---------------------------------------------------------------------------------------

-- Each uploaded logo or cover, already checked by content, decoded and re-encoded by the API: a PNG logo
-- of at most 512 x 512 or a JPEG cover at most 1600 wide. `unused_since` is when the image stopped being
-- used by the draft and the published brand (or when it was uploaded, unused); null while it is used. The
-- cleanup job removes an image unused for 24 hours.
CREATE TABLE branding_image (
    tenant_id    uuid        NOT NULL,
    id           uuid        NOT NULL,
    kind         text        NOT NULL,
    media_type   text        NOT NULL,
    width        integer     NOT NULL,
    height       integer     NOT NULL,
    bytes        bytea       NOT NULL,
    unused_since timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT branding_image_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT branding_image_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT branding_image_kind_known CHECK (kind IN ('logo', 'cover')),
    CONSTRAINT branding_image_media_type_known CHECK (
        (kind = 'logo' AND media_type = 'image/png') OR (kind = 'cover' AND media_type = 'image/jpeg')),
    CONSTRAINT branding_image_size_known CHECK (
        width BETWEEN 1 AND 1600 AND height BETWEEN 1 AND 1600
        AND (kind <> 'logo' OR (width = height AND width <= 512))),
    CONSTRAINT branding_image_bytes_bounded CHECK (octet_length(bytes) BETWEEN 1 AND 5242880)
);
-- The cleanup job's question, per salon: which images have been unused since before a time.
CREATE INDEX branding_image_unused_idx ON branding_image (tenant_id, unused_since) WHERE unused_since IS NOT NULL;

ALTER TABLE branding_image ENABLE ROW LEVEL SECURITY;
ALTER TABLE branding_image FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON branding_image
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Uploaded, marked used or unused as the draft and the published brand change, and removed by cleanup.
REVOKE ALL ON TABLE branding_image FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE branding_image TO "${application_role}";

-- --- the published brand gains its new parts -------------------------------------------------------

ALTER TABLE salon_brand
    ADD COLUMN tagline        text,
    ADD COLUMN logo_image_id  uuid,
    ADD COLUMN cover_image_id uuid,
    ADD COLUMN published_at   timestamptz,
    -- The staff account that published: identity's id, held as an id (no key across modules).
    ADD COLUMN published_by   uuid,
    ADD CONSTRAINT salon_brand_tagline_shape
        CHECK (tagline IS NULL OR (btrim(tagline) <> '' AND char_length(tagline) <= 80)),
    ADD CONSTRAINT salon_brand_logo_fkey
        FOREIGN KEY (tenant_id, logo_image_id) REFERENCES branding_image (tenant_id, id),
    ADD CONSTRAINT salon_brand_cover_fkey
        FOREIGN KEY (tenant_id, cover_image_id) REFERENCES branding_image (tenant_id, id),
    ADD CONSTRAINT salon_brand_published_shape CHECK ((published_at IS NULL) = (published_by IS NULL));

-- Publishing copies the draft over the published brand: the application now changes salon_brand, which
-- it only read until F04. It still never adds or removes one; provisioning does.
GRANT UPDATE ON TABLE salon_brand TO "${application_role}";

-- --- the brand draft ---------------------------------------------------------------------------------

-- One per salon, from its creation; only the salon's settings staff see it until one of them publishes.
-- The name is checked here as the published one is (non-blank, at most the provisioning limit); the API
-- holds a new name to 60 characters.
CREATE TABLE branding_brand_draft (
    tenant_id      uuid        NOT NULL,
    -- Optimistic concurrency, as the profile draft's: every save, reset, publication and discard moves it on.
    version        bigint      NOT NULL,
    display_name   text        NOT NULL,
    tagline        text,
    brand_colour   text        NOT NULL,
    logo_image_id  uuid,
    cover_image_id uuid,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT branding_brand_draft_pkey PRIMARY KEY (tenant_id),
    CONSTRAINT branding_brand_draft_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT branding_brand_draft_version_known CHECK (version >= 0),
    CONSTRAINT branding_brand_draft_name_present
        CHECK (btrim(display_name) <> '' AND char_length(display_name) <= 120),
    CONSTRAINT branding_brand_draft_tagline_shape
        CHECK (tagline IS NULL OR (btrim(tagline) <> '' AND char_length(tagline) <= 80)),
    CONSTRAINT branding_brand_draft_colour_format CHECK (brand_colour ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT branding_brand_draft_logo_fkey
        FOREIGN KEY (tenant_id, logo_image_id) REFERENCES branding_image (tenant_id, id),
    CONSTRAINT branding_brand_draft_cover_fkey
        FOREIGN KEY (tenant_id, cover_image_id) REFERENCES branding_image (tenant_id, id)
);

ALTER TABLE branding_brand_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE branding_brand_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON branding_brand_draft
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

REVOKE ALL ON TABLE branding_brand_draft FROM PUBLIC;
GRANT SELECT, UPDATE ON TABLE branding_brand_draft TO "${application_role}";

-- --- a salon's brand draft, equal to its published brand ------------------------------------------------

-- Gives one salon its brand draft, a copy of its published brand. A salon with no published brand gets
-- none, and one that already has a draft keeps it, so running it twice is running it once. Sets that one
-- salon for row-level security and puts the caller's setting back. Not SECURITY DEFINER: only its owner
-- (migrations, the bootstrap below, the local seeds) runs it.
CREATE FUNCTION branding_add_brand_draft(target_tenant uuid, at_time timestamptz)
    RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
BEGIN
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    INSERT INTO public.branding_brand_draft (tenant_id, version, display_name, tagline, brand_colour,
                                             logo_image_id, cover_image_id, created_at, updated_at)
        SELECT b.tenant_id, 0, b.display_name, b.tagline, b.brand_colour, b.logo_image_id, b.cover_image_id,
               at_time, at_time
          FROM public.salon_brand b
         WHERE b.tenant_id = target_tenant
        ON CONFLICT DO NOTHING;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
END
$$;

REVOKE ALL ON FUNCTION branding_add_brand_draft(uuid, timestamptz) FROM PUBLIC;

-- Every existing salon with a published brand gets its draft. A deleted salon has no brand left.
DO $$
DECLARE
    salon record;
BEGIN
    FOR salon IN SELECT t.id FROM tenant t WHERE t.status <> 'deleted' ORDER BY t.id
    LOOP
        PERFORM branding_add_brand_draft(salon.id, now());
    END LOOP;
END
$$;

-- --- provisioning: the bootstrap also creates the brand draft ----------------------------------------

-- As V18's, and in the same platform transaction that creates the salon, the brand draft (F04-S02).
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
    PERFORM public.branding_add_brand_draft(target_tenant, at_time);
    PERFORM public.branding_add_default_branch(target_tenant, left(display_name, 120), at_time);
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
END
$$;

-- --- retention: branding's eraser covers the new tables ---------------------------------------------

-- As V18's, for every branding table, in BrandTenantData's order: the drafts and the published brand
-- before the images they refer to.
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
        WHEN 'branding_brand_draft' THEN
            DELETE FROM public.branding_brand_draft r WHERE r.ctid IN (SELECT x.ctid
                FROM public.branding_brand_draft x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'salon_brand' THEN
            DELETE FROM public.salon_brand r WHERE r.ctid IN (SELECT x.ctid FROM public.salon_brand x
                WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'branding_image' THEN
            DELETE FROM public.branding_image r WHERE r.ctid IN (SELECT x.ctid
                FROM public.branding_image x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        ELSE
            RAISE EXCEPTION 'not a branding table' USING ERRCODE = 'insufficient_privilege';
    END CASE;
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;
