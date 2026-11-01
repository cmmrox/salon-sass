-- F05-S03, owned by catalogue (decisions 092, 095): the salon's tax settings, one immutable row per save, and the
-- products the front desk sells. Both follow docs/architecture/multi-tenancy.md, "Adding a table", including
-- branch_id (held as an id, checked through branding's SalonBranches when a row is written).
--
-- Works while F05-S02's application still runs: two new tables, read only by the new code, and the eraser
-- replaced with the same tables plus the two new ones.

-- --- tax settings ---------------------------------------------------------------------------------------

-- One version of a branch's tax settings (CAT-05): written once by a save and never changed, the newest current.
-- A booking or a sale (F07, F08, F10) keeps the id of the version it was made under. Each version copies the VAT
-- name and rate and the service charge's name from configuration (salon.catalogue.tax.*) as they were when it was
-- saved, so a later change of configuration never rewrites the tax of an existing booking or sale. The
-- registration number and the rate are held only while their part is on.
CREATE TABLE catalogue_tax_version (
    tenant_id                   uuid        NOT NULL,
    id                          uuid        NOT NULL,
    branch_id                   uuid        NOT NULL,
    version                     bigint      NOT NULL,
    vat_name                    text        NOT NULL,
    vat_rate_percent            integer     NOT NULL,
    vat_registered              boolean     NOT NULL,
    vat_registration_number     text,
    service_charge_name         text        NOT NULL,
    service_charge_on           boolean     NOT NULL,
    service_charge_percent      integer,
    service_charge_on_products  boolean     NOT NULL,
    -- The settings this save changed from the version before it (every setting for a branch's first version).
    changed_fields              text[]      NOT NULL,
    created_by                  uuid        NOT NULL,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    CONSTRAINT catalogue_tax_version_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT catalogue_tax_version_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    -- Two saves made from the same version both try the next number: exactly one is kept.
    CONSTRAINT catalogue_tax_version_number UNIQUE (tenant_id, branch_id, version),
    CONSTRAINT catalogue_tax_version_positive CHECK (version >= 1),
    CONSTRAINT catalogue_tax_version_vat_name CHECK (btrim(vat_name) <> ''),
    CONSTRAINT catalogue_tax_version_vat_rate CHECK (vat_rate_percent BETWEEN 0 AND 100),
    CONSTRAINT catalogue_tax_version_vat CHECK (
        (vat_registered AND vat_registration_number ~ '^[A-Za-z0-9-]{1,20}$')
        OR (NOT vat_registered AND vat_registration_number IS NULL)),
    CONSTRAINT catalogue_tax_version_service_charge_name CHECK (btrim(service_charge_name) <> ''),
    CONSTRAINT catalogue_tax_version_service_charge CHECK (
        (service_charge_on AND service_charge_percent BETWEEN 1 AND 100)
        OR (NOT service_charge_on AND service_charge_percent IS NULL AND NOT service_charge_on_products)),
    CONSTRAINT catalogue_tax_version_written_once CHECK (updated_at = created_at)
);

-- --- products -------------------------------------------------------------------------------------------

-- One product the salon sells at the front desk (INV-01), current only: a sale (F10) copies its name, price and VAT
-- choice, so a change or a delete never rewrites a past sale. Stock is not tracked (INV-02). Its name is unique in
-- the branch regardless of case; a deleted product's row is removed, which frees its name.
CREATE TABLE catalogue_product (
    tenant_id         uuid        NOT NULL,
    id                uuid        NOT NULL,
    branch_id         uuid        NOT NULL,
    name              text        NOT NULL,
    price_minor       bigint      NOT NULL,
    currency          text        NOT NULL,
    charges_vat       boolean     NOT NULL,
    sold_at_checkout  boolean     NOT NULL,
    version           bigint      NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT catalogue_product_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT catalogue_product_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT catalogue_product_name CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 40),
    CONSTRAINT catalogue_product_price CHECK (price_minor > 0),
    CONSTRAINT catalogue_product_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT catalogue_product_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX catalogue_product_name_key ON catalogue_product (tenant_id, branch_id, lower(name));

-- --- isolation ----------------------------------------------------------------------------------------------

ALTER TABLE catalogue_tax_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue_tax_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalogue_tax_version
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE catalogue_product ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue_product FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalogue_product
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- grants: only what the application does -------------------------------------------------------------

REVOKE ALL ON TABLE catalogue_tax_version FROM PUBLIC;
REVOKE ALL ON TABLE catalogue_product FROM PUBLIC;
-- A version is written once and read: no update or delete for the application role.
GRANT SELECT, INSERT ON TABLE catalogue_tax_version TO "${application_role}";
-- A product is added, changed and deleted.
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE catalogue_product TO "${application_role}";

-- And no role changes a tax version: an update is refused whoever runs it. Only catalogue's eraser removes rows.
CREATE FUNCTION catalogue_tax_version_refuse_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'a tax settings version never changes' USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER catalogue_tax_version_immutable
    BEFORE UPDATE ON catalogue_tax_version
    FOR EACH ROW EXECUTE FUNCTION catalogue_tax_version_refuse_update();

-- --- retention: catalogue's eraser, with the two new tables ------------------------------------------------

-- As V26's, in CatalogueTenantData's order, with the tax versions and the products at the end.
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
        WHEN 'catalogue_tax_version' THEN
            DELETE FROM public.catalogue_tax_version r WHERE r.ctid IN (SELECT x.ctid
                FROM public.catalogue_tax_version x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'catalogue_product' THEN
            DELETE FROM public.catalogue_product r WHERE r.ctid IN (SELECT x.ctid
                FROM public.catalogue_product x WHERE x.tenant_id = target_tenant LIMIT max_rows);
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
