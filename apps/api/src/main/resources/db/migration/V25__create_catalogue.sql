-- F05-S01, owned by catalogue (decisions 073, 074, 083, 093): the salon's categories, services, their terms
-- versions and service photos. Every table follows docs/architecture/multi-tenancy.md, "Adding a table",
-- including branch_id: the branch is branding's, held here as an id with no key into branding's tables and
-- checked through SalonBranches when a row is written (the default branch in R1).
--
-- A service's price, time and buffer live on its terms versions, never on the service row (CAT-04): each
-- change of terms inserts a new immutable version and points the service at it, so a booking (F07) that
-- keeps a version id keeps its terms. Time is stored as ordered segments (decision 074). F05-S02 adds
-- variants inside a terms version, each with its own price and segments, without moving these rows.

-- --- the category list: one row per branch, whose version guards the order --------------------------------

CREATE TABLE catalogue_category_list (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    -- Raised by every add, delete and move, so that a move made from a stale list is refused.
    version     bigint      NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT catalogue_category_list_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT catalogue_category_list_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT catalogue_category_list_branch UNIQUE (tenant_id, branch_id),
    CONSTRAINT catalogue_category_list_version CHECK (version >= 0)
);

-- --- categories ----------------------------------------------------------------------------------------

CREATE TABLE catalogue_category (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    name        text        NOT NULL,
    -- The salon's order (CAT-02). A move swaps two positions in one transaction, so the uniqueness is
    -- checked when the transaction commits.
    position    integer     NOT NULL,
    version     bigint      NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT catalogue_category_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT catalogue_category_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT catalogue_category_position UNIQUE (tenant_id, branch_id, position) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT catalogue_category_name CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 30),
    CONSTRAINT catalogue_category_position_positive CHECK (position >= 1),
    CONSTRAINT catalogue_category_version CHECK (version >= 0)
);

-- A name is unique in the salon's branch regardless of case.
CREATE UNIQUE INDEX catalogue_category_name_key ON catalogue_category (tenant_id, branch_id, lower(name));

-- --- photos ---------------------------------------------------------------------------------------------

-- A service's photo (decision 093): checked by decoding, cropped to its centre square and re-encoded as a
-- JPEG of at most 800 x 800, so nothing of the uploaded file but its picture is kept. Served by its id.
CREATE TABLE catalogue_photo (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    media_type  text        NOT NULL,
    width       integer     NOT NULL,
    height      integer     NOT NULL,
    bytes       bytea       NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT catalogue_photo_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT catalogue_photo_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT catalogue_photo_media_type CHECK (media_type = 'image/jpeg'),
    CONSTRAINT catalogue_photo_size CHECK (width BETWEEN 1 AND 800 AND height BETWEEN 1 AND 800)
);

-- --- services --------------------------------------------------------------------------------------------

-- A deleted service is a tombstone (deleted_at set): it leaves every list and frees its name, while its
-- terms versions stay readable for the bookings that keep them. A tombstone holds no category and no photo,
-- so neither stops a category or a photo being removed.
CREATE TABLE catalogue_service (
    tenant_id         uuid        NOT NULL,
    id                uuid        NOT NULL,
    branch_id         uuid        NOT NULL,
    category_id       uuid,
    name              text        NOT NULL,
    description       text,
    bookable_online   boolean     NOT NULL,
    photo_id          uuid,
    -- The newest terms version. No foreign key: a service and its first version are written together and
    -- each would wait for the other; the terms version's own key names its service.
    current_terms_id  uuid        NOT NULL,
    version           bigint      NOT NULL,
    deleted_at        timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT catalogue_service_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT catalogue_service_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT catalogue_service_category_fkey FOREIGN KEY (tenant_id, category_id)
        REFERENCES catalogue_category (tenant_id, id),
    CONSTRAINT catalogue_service_photo_fkey FOREIGN KEY (tenant_id, photo_id)
        REFERENCES catalogue_photo (tenant_id, id),
    CONSTRAINT catalogue_service_name CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 40),
    CONSTRAINT catalogue_service_description CHECK (description IS NULL OR char_length(description) BETWEEN 1 AND 90),
    CONSTRAINT catalogue_service_live CHECK (
        (deleted_at IS NULL AND category_id IS NOT NULL)
        OR (deleted_at IS NOT NULL AND category_id IS NULL AND photo_id IS NULL AND NOT bookable_online)),
    CONSTRAINT catalogue_service_version CHECK (version >= 0)
);

-- A live service's name is unique in the salon's branch regardless of case; a deleted one frees it.
CREATE UNIQUE INDEX catalogue_service_name_key ON catalogue_service (tenant_id, branch_id, lower(name))
    WHERE deleted_at IS NULL;
CREATE INDEX catalogue_service_category ON catalogue_service (tenant_id, category_id);
CREATE INDEX catalogue_service_branch ON catalogue_service (tenant_id, branch_id) WHERE deleted_at IS NULL;

-- --- terms versions -----------------------------------------------------------------------------------------

-- One version of a service's terms, written once and never changed (CAT-04). Its time is ordered segments
-- (decision 074): the two arrays are read together, position by position. R1 writes an active segment and
-- then a buffer segment when the buffer is above zero; processing segments arrive with F17.
CREATE TABLE catalogue_terms_version (
    tenant_id        uuid        NOT NULL,
    id               uuid        NOT NULL,
    branch_id        uuid        NOT NULL,
    service_id       uuid        NOT NULL,
    number           bigint      NOT NULL,
    price_minor      bigint      NOT NULL,
    currency         text        NOT NULL,
    segment_kinds    text[]      NOT NULL,
    segment_minutes  integer[]   NOT NULL,
    created_by       uuid        NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT catalogue_terms_version_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT catalogue_terms_version_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT catalogue_terms_version_service_fkey FOREIGN KEY (tenant_id, service_id)
        REFERENCES catalogue_service (tenant_id, id),
    -- Two saves made from the same service version both try the next number: exactly one is kept.
    CONSTRAINT catalogue_terms_version_number UNIQUE (tenant_id, service_id, number),
    CONSTRAINT catalogue_terms_version_positive CHECK (number >= 1),
    CONSTRAINT catalogue_terms_version_price CHECK (price_minor > 0),
    CONSTRAINT catalogue_terms_version_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT catalogue_terms_version_segments CHECK (
        cardinality(segment_kinds) >= 1
        AND cardinality(segment_kinds) = cardinality(segment_minutes)
        AND segment_kinds <@ ARRAY['active', 'processing', 'buffer']::text[]
        AND 0 < ALL (segment_minutes)),
    CONSTRAINT catalogue_terms_version_written_once CHECK (updated_at = created_at)
);

-- --- isolation ----------------------------------------------------------------------------------------------

ALTER TABLE catalogue_category_list ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue_category_list FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalogue_category_list
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE catalogue_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue_category FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalogue_category
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE catalogue_photo ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue_photo FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalogue_photo
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE catalogue_service ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue_service FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalogue_service
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE catalogue_terms_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue_terms_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalogue_terms_version
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- grants: only what the application does -------------------------------------------------------------

REVOKE ALL ON TABLE catalogue_category_list FROM PUBLIC;
REVOKE ALL ON TABLE catalogue_category FROM PUBLIC;
REVOKE ALL ON TABLE catalogue_photo FROM PUBLIC;
REVOKE ALL ON TABLE catalogue_service FROM PUBLIC;
REVOKE ALL ON TABLE catalogue_terms_version FROM PUBLIC;
-- The list row is created with the salon's default categories, never by the application.
GRANT SELECT, UPDATE ON TABLE catalogue_category_list TO "${application_role}";
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE catalogue_category TO "${application_role}";
-- A photo is replaced by a new one, never changed.
GRANT SELECT, INSERT, DELETE ON TABLE catalogue_photo TO "${application_role}";
-- A service is never removed: deleting it makes it a tombstone.
GRANT SELECT, INSERT, UPDATE ON TABLE catalogue_service TO "${application_role}";
-- A terms version is written once and read.
GRANT SELECT, INSERT ON TABLE catalogue_terms_version TO "${application_role}";

-- And no role changes a terms version: an update is refused whoever runs it. Only catalogue's eraser removes rows.
CREATE FUNCTION catalogue_terms_version_refuse_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'a terms version never changes' USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER catalogue_terms_version_immutable
    BEFORE UPDATE ON catalogue_terms_version
    FOR EACH ROW EXECUTE FUNCTION catalogue_terms_version_refuse_update();

-- --- the default categories (decision 092, 093) ------------------------------------------------------------

-- Gives one salon the prototype's ten categories, in order, on its default branch, with its category list.
-- A salon that already has its list (it was given them before, and may since have renamed, reordered or
-- deleted some) is left unchanged, so running it twice is the same as running it once. Called by the
-- platform transaction that provisions a salon (catalogue's SalonBootstrapStep), by the migration below for
-- the salons that existed before it, and by the local seed. Sets that one salon for row-level security and
-- puts the caller's setting back.
CREATE FUNCTION catalogue_add_default_categories(target_tenant uuid, at_time timestamptz)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
    branch   uuid;
    names    text[] := ARRAY['Cuts & styling', 'Colour', 'Facials & skin', 'Nails', 'Brows & lashes', 'Waxing',
                             'Makeup & draping', 'Massage & spa', 'Bridal', 'Consultations'];
    i        integer;
BEGIN
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    branch := public.branding_default_branch_id(target_tenant);
    IF branch IS NULL THEN
        RAISE EXCEPTION 'a salon is given its categories only once it has its default branch'
            USING ERRCODE = 'no_data_found';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.catalogue_category_list l
                    WHERE l.tenant_id = target_tenant AND l.branch_id = branch) THEN
        INSERT INTO public.catalogue_category_list (tenant_id, id, branch_id, version, created_at, updated_at)
            VALUES (target_tenant, pg_catalog.gen_random_uuid(), branch, 0, at_time, at_time);
        FOR i IN 1 .. pg_catalog.array_length(names, 1) LOOP
            INSERT INTO public.catalogue_category
                    (tenant_id, id, branch_id, name, position, version, created_at, updated_at)
                VALUES (target_tenant, pg_catalog.gen_random_uuid(), branch, names[i], i, 0, at_time, at_time);
        END LOOP;
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
END
$$;

REVOKE ALL ON FUNCTION catalogue_add_default_categories(uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION catalogue_add_default_categories(uuid, timestamptz) TO "${platform_role}";

-- Every existing salon that has its default branch gets its categories once (decision 093). A deleted salon
-- is a tombstone with no data, so it gets none.
DO $$
DECLARE
    salon record;
BEGIN
    FOR salon IN SELECT t.id FROM tenant t WHERE t.status <> 'deleted' ORDER BY t.id
    LOOP
        PERFORM set_config('app.tenant_id', salon.id::text, true);
        IF branding_default_branch_id(salon.id) IS NOT NULL THEN
            PERFORM catalogue_add_default_categories(salon.id, now());
        END IF;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- --- retention: catalogue's eraser -------------------------------------------------------------------------

-- As V15's erasers: only after the salon's retention has passed, one salon and one table at a time, in
-- CatalogueTenantData's order (versions before their services, services before their categories and photos).
CREATE FUNCTION catalogue_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz,
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
        WHEN 'catalogue_terms_version' THEN
            DELETE FROM public.catalogue_terms_version r WHERE r.ctid IN (SELECT x.ctid
                FROM public.catalogue_terms_version x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'catalogue_service' THEN
            DELETE FROM public.catalogue_service r WHERE r.ctid IN (SELECT x.ctid
                FROM public.catalogue_service x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'catalogue_photo' THEN
            DELETE FROM public.catalogue_photo r WHERE r.ctid IN (SELECT x.ctid
                FROM public.catalogue_photo x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'catalogue_category' THEN
            DELETE FROM public.catalogue_category r WHERE r.ctid IN (SELECT x.ctid
                FROM public.catalogue_category x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'catalogue_category_list' THEN
            DELETE FROM public.catalogue_category_list r WHERE r.ctid IN (SELECT x.ctid
                FROM public.catalogue_category_list x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        ELSE
            RAISE EXCEPTION 'not a catalogue table' USING ERRCODE = 'insufficient_privilege';
    END CASE;
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;

REVOKE ALL ON FUNCTION catalogue_erase_salon_rows(uuid, text, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION catalogue_erase_salon_rows(uuid, text, timestamptz, integer) TO "${platform_role}";
