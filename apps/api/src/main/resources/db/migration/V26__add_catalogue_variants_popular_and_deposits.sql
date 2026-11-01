-- F05-S02, owned by catalogue (decisions 074, 092, 095): a service's variants and its own deposit, both part of
-- its immutable terms; the salon's Popular choice and the Popular setting. Every new table follows
-- docs/architecture/multi-tenancy.md, "Adding a table", including branch_id (held as an id, checked through
-- branding's SalonBranches when a row is written).
--
-- Works while F05-S01's application still runs: the new columns have defaults that say what F05-S01 meant (no
-- variants, the salon's deposit rule, not popular), and the new tables are only read by the new code.

-- --- a terms version's variant set name and deposit ----------------------------------------------------------

-- Adding a column with a constant default rewrites no row, so the trigger that refuses an update of a terms
-- version is not fired and every existing version keeps exactly the terms it was made with: no variants and the
-- salon's deposit rule.
ALTER TABLE catalogue_terms_version
    ADD COLUMN variant_set_name text,
    ADD COLUMN deposit_kind text NOT NULL DEFAULT 'salon_rule',
    ADD COLUMN deposit_percent integer,
    ADD COLUMN deposit_amount_minor bigint;

ALTER TABLE catalogue_terms_version
    ADD CONSTRAINT catalogue_terms_version_variant_set_name CHECK (
        variant_set_name IS NULL
        OR (variant_set_name = btrim(variant_set_name) AND char_length(variant_set_name) BETWEEN 2 AND 30)),
    ADD CONSTRAINT catalogue_terms_version_deposit CHECK (
        (deposit_kind IN ('salon_rule', 'none') AND deposit_percent IS NULL AND deposit_amount_minor IS NULL)
        OR (deposit_kind = 'percentage' AND deposit_percent BETWEEN 1 AND 100 AND deposit_amount_minor IS NULL)
        OR (deposit_kind = 'amount' AND deposit_percent IS NULL AND deposit_amount_minor > 0
            AND deposit_amount_minor <= price_minor));

-- --- variants -----------------------------------------------------------------------------------------------

-- One variant of one terms version (CAT-03), written with its version and never changed. variant_id is the
-- variant's stable id, the same in every version of the service while the variant exists, so a booking names it.
-- A version with variants holds the lowest price and the shortest time of them itself (decision 095), so
-- F05-S01's readers keep working. Its time is ordered segments, as a version's are.
CREATE TABLE catalogue_terms_variant (
    tenant_id        uuid        NOT NULL,
    id               uuid        NOT NULL,
    branch_id        uuid        NOT NULL,
    terms_version_id uuid        NOT NULL,
    variant_id       uuid        NOT NULL,
    position         integer     NOT NULL,
    name             text        NOT NULL,
    price_minor      bigint      NOT NULL,
    segment_kinds    text[]      NOT NULL,
    segment_minutes  integer[]   NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT catalogue_terms_variant_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT catalogue_terms_variant_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT catalogue_terms_variant_version_fkey FOREIGN KEY (tenant_id, terms_version_id)
        REFERENCES catalogue_terms_version (tenant_id, id),
    CONSTRAINT catalogue_terms_variant_position UNIQUE (tenant_id, terms_version_id, position),
    CONSTRAINT catalogue_terms_variant_stable_id UNIQUE (tenant_id, terms_version_id, variant_id),
    CONSTRAINT catalogue_terms_variant_position_range CHECK (position BETWEEN 1 AND 10),
    CONSTRAINT catalogue_terms_variant_name CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 30),
    CONSTRAINT catalogue_terms_variant_price CHECK (price_minor > 0),
    CONSTRAINT catalogue_terms_variant_segments CHECK (
        cardinality(segment_kinds) >= 1
        AND cardinality(segment_kinds) = cardinality(segment_minutes)
        AND segment_kinds <@ ARRAY['active', 'processing', 'buffer']::text[]
        AND 0 < ALL (segment_minutes)),
    CONSTRAINT catalogue_terms_variant_written_once CHECK (updated_at = created_at)
);

-- A variant's name is unique in its set regardless of case.
CREATE UNIQUE INDEX catalogue_terms_variant_name_key
    ON catalogue_terms_variant (tenant_id, terms_version_id, lower(name));
-- Every variant a variant id has had, across versions.
CREATE INDEX catalogue_terms_variant_variant ON catalogue_terms_variant (tenant_id, variant_id);

-- --- Popular ------------------------------------------------------------------------------------------------

-- Whether the salon chose the service for Popular (CAT-06). Not part of its terms: choosing makes no version. A
-- deleted service is chosen by no one.
ALTER TABLE catalogue_service ADD COLUMN popular boolean NOT NULL DEFAULT false;
ALTER TABLE catalogue_service
    ADD CONSTRAINT catalogue_service_popular_live CHECK (deleted_at IS NULL OR NOT popular);
CREATE INDEX catalogue_service_popular ON catalogue_service (tenant_id, branch_id)
    WHERE deleted_at IS NULL AND popular;

-- How the branch's Popular list is made: the services the salon chose, or the most booked of the last 90 days.
-- One row per branch, created by the application the first time it is needed (INSERT ... ON CONFLICT DO NOTHING,
-- so two first uses both wait for the one row). Its version guards the setting; a choice of a Popular service
-- locks the row, so that two choices made at once of a sixth service keep exactly one.
CREATE TABLE catalogue_popular_setting (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    mode        text        NOT NULL,
    version     bigint      NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT catalogue_popular_setting_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT catalogue_popular_setting_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT catalogue_popular_setting_branch UNIQUE (tenant_id, branch_id),
    CONSTRAINT catalogue_popular_setting_mode CHECK (mode IN ('chosen', 'most_booked')),
    CONSTRAINT catalogue_popular_setting_version CHECK (version >= 0)
);

-- --- isolation ----------------------------------------------------------------------------------------------

ALTER TABLE catalogue_terms_variant ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue_terms_variant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalogue_terms_variant
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE catalogue_popular_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue_popular_setting FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalogue_popular_setting
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- grants: only what the application does -------------------------------------------------------------

REVOKE ALL ON TABLE catalogue_terms_variant FROM PUBLIC;
REVOKE ALL ON TABLE catalogue_popular_setting FROM PUBLIC;
-- A variant is written once with its version and read.
GRANT SELECT, INSERT ON TABLE catalogue_terms_variant TO "${application_role}";
-- The setting is created on first use and changed; never removed by the application.
GRANT SELECT, INSERT, UPDATE ON TABLE catalogue_popular_setting TO "${application_role}";

-- And no role changes a variant: an update is refused whoever runs it, as for its terms version.
CREATE TRIGGER catalogue_terms_variant_immutable
    BEFORE UPDATE ON catalogue_terms_variant
    FOR EACH ROW EXECUTE FUNCTION catalogue_terms_version_refuse_update();

-- --- retention: catalogue's eraser, with the two new tables ------------------------------------------------

-- As V25's, in CatalogueTenantData's order: variants before their terms versions, and the Popular setting with
-- the category list.
CREATE OR REPLACE FUNCTION catalogue_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz,
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
        WHEN 'catalogue_terms_variant' THEN
            DELETE FROM public.catalogue_terms_variant r WHERE r.ctid IN (SELECT x.ctid
                FROM public.catalogue_terms_variant x WHERE x.tenant_id = target_tenant LIMIT max_rows);
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
        WHEN 'catalogue_popular_setting' THEN
            DELETE FROM public.catalogue_popular_setting r WHERE r.ctid IN (SELECT x.ctid
                FROM public.catalogue_popular_setting x WHERE x.tenant_id = target_tenant LIMIT max_rows);
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
