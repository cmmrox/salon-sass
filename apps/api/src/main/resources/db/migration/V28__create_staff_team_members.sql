-- F06-S01, owned by staff (decisions 073, 099, 100): the salon's team members, the whole categories and single
-- services each performs, and their photos. Every table follows docs/architecture/multi-tenancy.md, "Adding a
-- table", including branch_id: the branch is branding's, held here as an id with no key into branding's tables
-- and checked through SalonBranches when a row is written (the default branch in R1; the team is salon-wide).
--
-- A team member is not a staff account (decision 100). The account is identity's, held here as an id with no key
-- across modules; categories and services are catalogue's, held the same way, and a read intersects them with what
-- catalogue lists, so a deleted service simply stops being performed and needs no change here. No salon gains a
-- member from this migration.

-- --- photos ---------------------------------------------------------------------------------------------------

-- A member's photo, already checked by content and re-encoded: a JPEG the API made itself (decision 086's rules).
CREATE TABLE staff_member_photo (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    media_type  text        NOT NULL,
    width       integer     NOT NULL,
    height      integer     NOT NULL,
    bytes       bytea       NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT staff_member_photo_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_member_photo_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_member_photo_media_type CHECK (media_type = 'image/jpeg'),
    CONSTRAINT staff_member_photo_size CHECK (width BETWEEN 1 AND 800 AND height BETWEEN 1 AND 800),
    CONSTRAINT staff_member_photo_bytes CHECK (octet_length(bytes) > 0)
);

-- --- team members ---------------------------------------------------------------------------------------------

CREATE TABLE staff_team_member (
    tenant_id           uuid        NOT NULL,
    id                  uuid        NOT NULL,
    branch_id           uuid        NOT NULL,
    name                text        NOT NULL,
    title               text,
    highlights          text,
    bio                 text,
    shown_online        boolean     NOT NULL,
    photo_id            uuid,
    -- identity's staff account, with no key across modules (decision 100).
    sign_in_account_id  uuid,
    -- A removed member is a tombstone: its id and name stay for past bookings (decision 100).
    removed_at          timestamptz,
    version             bigint      NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT staff_team_member_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_team_member_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_team_member_photo_fkey FOREIGN KEY (tenant_id, photo_id)
        REFERENCES staff_member_photo (tenant_id, id),
    CONSTRAINT staff_team_member_name CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 40),
    CONSTRAINT staff_team_member_title CHECK (
        title IS NULL OR (title = btrim(title) AND char_length(title) BETWEEN 1 AND 40)),
    CONSTRAINT staff_team_member_highlights CHECK (
        highlights IS NULL OR (highlights = btrim(highlights) AND char_length(highlights) BETWEEN 1 AND 60)),
    CONSTRAINT staff_team_member_bio CHECK (
        bio IS NULL OR (bio = btrim(bio) AND char_length(bio) BETWEEN 1 AND 140)),
    CONSTRAINT staff_team_member_removed CHECK (
        removed_at IS NULL
        OR (sign_in_account_id IS NULL AND photo_id IS NULL AND NOT shown_online)),
    CONSTRAINT staff_team_member_version CHECK (version >= 0)
);

-- An account belongs to at most one member at a time: the database refuses a double link under simultaneous saves.
CREATE UNIQUE INDEX staff_team_member_account_key ON staff_team_member (tenant_id, sign_in_account_id)
    WHERE removed_at IS NULL AND sign_in_account_id IS NOT NULL;
CREATE INDEX staff_team_member_branch ON staff_team_member (tenant_id, branch_id) WHERE removed_at IS NULL;

-- --- what a member performs: whole categories, and single services --------------------------------------------

-- A whole category: every service in it, and any added to it later.
CREATE TABLE staff_member_category (
    tenant_id    uuid        NOT NULL,
    id           uuid        NOT NULL,
    branch_id    uuid        NOT NULL,
    member_id    uuid        NOT NULL,
    category_id  uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT staff_member_category_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_member_category_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_member_category_member_fkey FOREIGN KEY (tenant_id, member_id)
        REFERENCES staff_team_member (tenant_id, id),
    CONSTRAINT staff_member_category_once UNIQUE (tenant_id, member_id, category_id)
);

-- A single service: that service and no other of its category.
CREATE TABLE staff_member_service (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    branch_id   uuid        NOT NULL,
    member_id   uuid        NOT NULL,
    service_id  uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT staff_member_service_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT staff_member_service_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT staff_member_service_member_fkey FOREIGN KEY (tenant_id, member_id)
        REFERENCES staff_team_member (tenant_id, id),
    CONSTRAINT staff_member_service_once UNIQUE (tenant_id, member_id, service_id)
);

-- --- isolation ------------------------------------------------------------------------------------------------

ALTER TABLE staff_member_photo ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_member_photo FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_member_photo
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_team_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_team_member FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_team_member
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_member_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_member_category FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_member_category
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE staff_member_service ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_member_service FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_member_service
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- --- grants: only what the application does -------------------------------------------------------------------

REVOKE ALL ON TABLE staff_member_photo FROM PUBLIC;
REVOKE ALL ON TABLE staff_team_member FROM PUBLIC;
REVOKE ALL ON TABLE staff_member_category FROM PUBLIC;
REVOKE ALL ON TABLE staff_member_service FROM PUBLIC;
-- A photo is replaced by a new one, never changed.
GRANT SELECT, INSERT, DELETE ON TABLE staff_member_photo TO "${application_role}";
-- A member is never deleted: removing them makes a tombstone.
GRANT SELECT, INSERT, UPDATE ON TABLE staff_team_member TO "${application_role}";
-- A save replaces the rows.
GRANT SELECT, INSERT, DELETE ON TABLE staff_member_category TO "${application_role}";
GRANT SELECT, INSERT, DELETE ON TABLE staff_member_service TO "${application_role}";

-- --- the count platform reads (decision 100) --------------------------------------------------------------------

-- How many active members of one salon have no linked account: what they add to the salon's seats, beside its
-- active accounts. Platform reads it for usage and the plan move, on its own connection, in the transaction that
-- holds the salon's registry row; a count and nothing else of the salon's team. Sets that one salon for row-level
-- security and puts the caller's setting back.
CREATE FUNCTION staff_active_unlinked_member_count(target_tenant uuid)
    RETURNS bigint
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
    members  bigint;
BEGIN
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    SELECT count(*) INTO members FROM public.staff_team_member m
     WHERE m.tenant_id = target_tenant AND m.removed_at IS NULL AND m.sign_in_account_id IS NULL;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN members;
END
$$;

REVOKE ALL ON FUNCTION staff_active_unlinked_member_count(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION staff_active_unlinked_member_count(uuid) TO "${platform_role}";

-- --- staff's eraser learns the new tables -----------------------------------------------------------------------

CREATE OR REPLACE FUNCTION staff_erase_salon_rows(target_tenant uuid, table_name text, at_time timestamptz, max_rows integer)
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
        WHEN 'staff_member_category' THEN
            DELETE FROM public.staff_member_category r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_category x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_member_service' THEN
            DELETE FROM public.staff_member_service r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_service x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_team_member' THEN
            DELETE FROM public.staff_team_member r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_team_member x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_member_photo' THEN
            DELETE FROM public.staff_member_photo r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_member_photo x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_holiday_choice' THEN
            DELETE FROM public.staff_holiday_choice r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_holiday_choice x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_holiday_review' THEN
            DELETE FROM public.staff_holiday_review r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_holiday_review x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_closure' THEN
            DELETE FROM public.staff_closure r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_closure x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        WHEN 'staff_opening_hours' THEN
            DELETE FROM public.staff_opening_hours r WHERE r.ctid IN (SELECT x.ctid
                FROM public.staff_opening_hours x WHERE x.tenant_id = target_tenant LIMIT max_rows);
        ELSE
            RAISE EXCEPTION 'not a staff table' USING ERRCODE = 'insufficient_privilege';
    END CASE;
    GET DIAGNOSTICS erased = ROW_COUNT;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN erased;
END
$$;
