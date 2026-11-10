-- BUG-F03-009, PLT-06: a deleted salon's registry row is a tombstone and keeps none of the salon's profile or
-- its Owner's contact. It keeps the id, slug, status, lifecycle instants (created, closed, delete and deleted
-- at), plan, country, currency, time zone, default language, access state and version: what the retired slug,
-- the invoices, the payments and the platform audit still refer to. It loses the salon's name, contact email,
-- contact phone, address and the Owner's email, which are all nullable.
--
-- The registry's lifecycle guard clears them whenever a row is, or becomes, deleted, whoever writes, so the
-- platform role needs no update right on them and the previous application version's deletion is scrubbed too.

CREATE OR REPLACE FUNCTION tenant_lifecycle_guard()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('closed', 'deleted') THEN
            RAISE EXCEPTION 'a closed salon''s registry row is kept as its tombstone'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.slug <> OLD.slug THEN
        RAISE EXCEPTION 'a salon''s slug never changes' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.status = 'closed' AND NEW.status NOT IN ('closed', 'deleted') THEN
        RAISE EXCEPTION 'closure is irreversible' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.status = 'deleted' AND NEW.status <> 'deleted' THEN
        RAISE EXCEPTION 'a deleted salon stays deleted' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.closed_at IS NOT NULL AND (NEW.closed_at IS DISTINCT FROM OLD.closed_at
            OR NEW.delete_at IS DISTINCT FROM OLD.delete_at) THEN
        RAISE EXCEPTION 'a closure''s instants never change' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'a deletion''s instant never changes' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.status = 'deleted' THEN
        NEW.name := NULL;
        NEW.contact_email := NULL;
        NEW.contact_phone := NULL;
        NEW.address := NULL;
        NEW.owner_email := NULL;
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION tenant_lifecycle_guard() FROM PUBLIC;

-- Tombstones left before this migration.
UPDATE tenant
   SET name = NULL, contact_email = NULL, contact_phone = NULL, address = NULL, owner_email = NULL
 WHERE status = 'deleted';

ALTER TABLE tenant
    ADD CONSTRAINT tenant_tombstone_holds_no_profile CHECK (status <> 'deleted' OR (
        name IS NULL AND contact_email IS NULL AND contact_phone IS NULL AND address IS NULL
        AND owner_email IS NULL));
