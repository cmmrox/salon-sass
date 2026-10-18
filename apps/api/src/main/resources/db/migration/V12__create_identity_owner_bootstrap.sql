-- F03-S01, owned by identity: the one way a salon's first Owner comes into existence outside the
-- local fixtures (docs/architecture/operator-console.md, "Platform database boundary and atomic
-- bootstrap"). Platform calls it through identity's public provisioning facade, on the platform
-- connection, inside the same transaction that creates the salon; it never writes identity's tables.

-- The Owner's display name, given at provisioning. Nullable: existing staff stay as they were.
ALTER TABLE identity_staff ADD COLUMN name text;
ALTER TABLE identity_staff ADD CONSTRAINT staff_name_present
    CHECK (name IS NULL OR (name = btrim(name) AND name <> '' AND length(name) <= 120));

-- Inserts the active first Owner of a salon created in the calling transaction, with no enrolled
-- MFA secret and no session, so the Owner's first salon sign-in must enroll MFA. It refuses a salon
-- that was not created by the calling transaction or that already has staff (a second bootstrap),
-- sets that one salon for row-level security inside the function (which stays forced; nothing is
-- bypassed) and puts the caller's setting back before it returns.
CREATE FUNCTION identity_bootstrap_owner(
        target_tenant uuid, owner_id uuid, owner_name text, owner_email text, owner_password_hash text,
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
        RAISE EXCEPTION 'an Owner is bootstrapped only for a salon created in this transaction'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    IF EXISTS (SELECT 1 FROM public.identity_staff s WHERE s.tenant_id = target_tenant) THEN
        RAISE EXCEPTION 'this salon already has staff'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    INSERT INTO public.identity_staff (id, tenant_id, created_at, updated_at, email, mobile, password_hash, role,
            active, professional_grant, mfa_secret, version, name)
        VALUES (owner_id, target_tenant, at_time, at_time, owner_email, NULL, owner_password_hash, 'OWNER',
            true, false, NULL, 0, owner_name);
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
END
$$;

REVOKE ALL ON FUNCTION identity_bootstrap_owner(uuid, uuid, text, text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_bootstrap_owner(uuid, uuid, text, text, text, timestamptz) TO "${platform_role}";
