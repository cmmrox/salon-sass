-- The first tenant-owned table: a salon's published display name and brand colour, one row per
-- salon. Created with the whole checklist in docs/architecture/multi-tenancy.md, "Adding a table".

CREATE TABLE salon_brand (
    tenant_id    uuid        NOT NULL,
    display_name text        NOT NULL,
    brand_colour text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    -- One row per salon: the key is the tenant itself, so it starts with tenant_id.
    CONSTRAINT salon_brand_pkey PRIMARY KEY (tenant_id),
    CONSTRAINT salon_brand_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT salon_brand_display_name_present CHECK (btrim(display_name) <> ''),
    -- #RRGGBB, upper case: one spelling per colour.
    CONSTRAINT salon_brand_colour_format CHECK (brand_colour ~ '^#[0-9A-F]{6}$')
);

-- Row-level security, forced so that it holds for the table's owner as well. With no tenant set
-- for the transaction the setting is null, or the empty string once a transaction on the same
-- connection has set and released it; NULLIF makes both mean "no salon", so no row is visible and
-- nothing can be written.
ALTER TABLE salon_brand ENABLE ROW LEVEL SECURITY;
ALTER TABLE salon_brand FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON salon_brand
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- The application only reads a salon's brand in this release. Editing arrives with F04.
REVOKE ALL ON TABLE salon_brand FROM PUBLIC;
GRANT SELECT ON TABLE salon_brand TO "${application_role}";
