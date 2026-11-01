-- F05-S01, owned by branding (decision 083; the lead's decision while building F05-S01): a salon's default
-- branch id, for another module's SQL that must name the branch while the branch is visible only in the
-- caller's own transaction (the catalogue's default categories, written in the platform transaction that
-- provisions the salon). It returns the id and nothing else of the branch.
--
-- Not SECURITY DEFINER and not executable by PUBLIC: only the owning role runs it (migrations, the local
-- seed, and the owner's own SECURITY DEFINER functions). Row-level security is forced on branding_branch,
-- so it answers only for the salon the caller has set in app.tenant_id: asked for another salon, it
-- returns null, exactly as for a salon with no branch.
CREATE FUNCTION branding_default_branch_id(target_tenant uuid)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT b.id FROM public.branding_branch b WHERE b.tenant_id = target_tenant AND b.is_default
$$;

REVOKE ALL ON FUNCTION branding_default_branch_id(uuid) FROM PUBLIC;
