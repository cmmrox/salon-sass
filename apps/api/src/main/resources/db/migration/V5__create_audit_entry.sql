-- The audit trail (NFR-10): one append-only row per sensitive action of a salon. A tenant table,
-- created with the whole checklist in docs/architecture/multi-tenancy.md, "Adding a table".
-- Every time in it is the application's clock, written by the application; nothing defaults to
-- the database's now().

CREATE TABLE audit_entry (
    tenant_id     uuid        NOT NULL,
    id            uuid        NOT NULL,
    -- Who acted: a kind ('staff', 'customer', 'operator', 'system') and that actor's id.
    actor_kind    text        NOT NULL,
    actor_id      text        NOT NULL,
    -- What was done, as a stable dotted name ('probe.created', 'booking.refunded').
    action        text        NOT NULL,
    -- What it was done to.
    subject_kind  text        NOT NULL,
    subject_id    text        NOT NULL,
    reason        text,
    -- The values before and after, with every value under a key that names a secret removed.
    before_values jsonb,
    after_values  jsonb,
    -- The trace of the request or the work that wrote it, when there was one.
    trace_id      text,
    -- When the action happened. An entry is never changed, so updated_at is always created_at.
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT audit_entry_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT audit_entry_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT audit_entry_actor_kind_known CHECK (actor_kind IN ('staff', 'customer', 'operator', 'system')),
    CONSTRAINT audit_entry_action_present CHECK (btrim(action) <> ''),
    CONSTRAINT audit_entry_trace_id_format CHECK (trace_id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT audit_entry_unchanged CHECK (updated_at = created_at)
);

CREATE INDEX audit_entry_subject_idx ON audit_entry (tenant_id, subject_kind, subject_id, created_at);
CREATE INDEX audit_entry_created_idx ON audit_entry (tenant_id, created_at);

ALTER TABLE audit_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_entry
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- No role may change an entry, the owner included: an update is refused here, whoever asks.
-- Removing entries after their retention (24 months, a later release) is the owner's alone; the
-- application's role has no DELETE.
CREATE FUNCTION audit_entry_refuse_change()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'an audit entry is never changed'
        USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER audit_entry_append_only
    BEFORE UPDATE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION audit_entry_refuse_change();

REVOKE ALL ON TABLE audit_entry FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE audit_entry TO "${application_role}";
