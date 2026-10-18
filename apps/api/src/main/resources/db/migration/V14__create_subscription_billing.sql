-- F03-S02: subscriptions, invoices and entitlements (docs/architecture/operator-console.md,
-- "Subscriptions, invoices and entitlements"). Every new table is a platform table: it names its salon
-- in salon_id (never tenant_id, which marks a tenant table under row-level security), the ordinary
-- tenant role (application_role) receives no grant on it, and the platform runtime role
-- (platform_role) receives only the operations billing performs. Invoices and payments are platform
-- records kept after a salon is closed and deleted.
--
-- Every time is the application's clock, written by the application; nothing defaults to now().

-- The registry's access state, owned by billing: 'read_only' while an invoice is unpaid 14 days after
-- its due instant, otherwise 'full'. It is separate from status (active/suspended, owned by the
-- lifecycle), which takes precedence over it. The version guards operator changes to a salon (plan
-- moves now, lifecycle actions in F03-S03); billing's own access changes do not touch it.
ALTER TABLE tenant
    ADD COLUMN access_state text   NOT NULL DEFAULT 'full',
    ADD COLUMN version      bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT tenant_access_state_known CHECK (access_state IN ('full', 'read_only')),
    ADD CONSTRAINT tenant_version_counted CHECK (version >= 0);

GRANT UPDATE (plan_id, access_state, version, updated_at) ON TABLE tenant TO "${platform_role}";

-- The registry reads carry the access state, read on every request; nothing caches it. Their return
-- types cannot change in place, so they are dropped and created again in this transaction. The
-- previous version of the application selects its columns by name, which still works.
DROP FUNCTION tenant_by_slug(text);

CREATE FUNCTION tenant_by_slug(requested_slug text)
    RETURNS TABLE (id uuid, status text, time_zone text, access_state text)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id, t.status, t.time_zone, t.access_state FROM public.tenant t WHERE t.slug = requested_slug
$$;

REVOKE ALL ON FUNCTION tenant_by_slug(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenant_by_slug(text) TO "${application_role}";

DROP FUNCTION tenant_by_id(uuid);

CREATE FUNCTION tenant_by_id(requested_id uuid)
    RETURNS TABLE (id uuid, slug text, status text, time_zone text, access_state text)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id, t.slug, t.status, t.time_zone, t.access_state FROM public.tenant t WHERE t.id = requested_id
$$;

REVOKE ALL ON FUNCTION tenant_by_id(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenant_by_id(uuid) TO "${application_role}";

-- One subscription per salon. Unbilled salons (the synthetic salons created before this stage) have
-- none. The amount is an operator-entered monthly amount in minor units of the subscription's
-- currency (the salon's, or the one entered when a salon without a currency is billed).
CREATE TABLE subscription (
    id              uuid        NOT NULL,
    salon_id        uuid        NOT NULL,
    state           text        NOT NULL,
    monthly_amount  bigint      NOT NULL,
    currency        text        NOT NULL,
    payment_method  text        NOT NULL,
    started_at      timestamptz NOT NULL,
    trial_ends_at   timestamptz NOT NULL,
    -- The trial-end instant as a local date and time in the salon's time zone: every monthly invoice
    -- is issued at this local time on the anchored day (the month's last day for a later anchor).
    anchor_local    timestamp   NOT NULL,
    invoices_issued integer     NOT NULL DEFAULT 0,
    next_invoice_at timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT subscription_pkey PRIMARY KEY (id),
    CONSTRAINT subscription_salon_key UNIQUE (salon_id),
    CONSTRAINT subscription_salon_fkey FOREIGN KEY (salon_id) REFERENCES tenant (id),
    CONSTRAINT subscription_state_known CHECK (state IN ('trialing', 'active', 'past_due', 'read_only')),
    CONSTRAINT subscription_amount_bounded CHECK (monthly_amount BETWEEN 0 AND 100000000),
    CONSTRAINT subscription_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT subscription_method_known CHECK (payment_method IN ('card', 'bank_transfer')),
    CONSTRAINT subscription_trial_after_start CHECK (trial_ends_at > started_at),
    CONSTRAINT subscription_invoices_counted CHECK (invoices_issued >= 0),
    CONSTRAINT subscription_version_counted CHECK (version >= 0)
);
CREATE INDEX subscription_next_invoice_idx ON subscription (next_invoice_at);

-- The platform-wide invoice number: one row, advanced under its row lock in the issuing
-- transaction, so a rolled-back issue reuses its number and numbers have no gaps.
CREATE TABLE invoice_number (
    id          boolean NOT NULL DEFAULT true,
    last_number bigint  NOT NULL,
    CONSTRAINT invoice_number_pkey PRIMARY KEY (id),
    CONSTRAINT invoice_number_single CHECK (id),
    CONSTRAINT invoice_number_counted CHECK (last_number >= 0)
);
INSERT INTO invoice_number (id, last_number) VALUES (true, 0);

-- An issued invoice. Its plan, period, amount, currency, issue and due instants never change; only
-- its status moves once from open to paid.
CREATE TABLE invoice (
    id              uuid        NOT NULL,
    number          bigint      NOT NULL,
    salon_id        uuid        NOT NULL,
    subscription_id uuid        NOT NULL,
    plan_id         uuid,
    plan_key        text,
    plan_name       text,
    period_index    integer     NOT NULL,
    period_start    timestamptz NOT NULL,
    period_end      timestamptz NOT NULL,
    amount          bigint      NOT NULL,
    currency        text        NOT NULL,
    issued_at       timestamptz NOT NULL,
    due_at          timestamptz NOT NULL,
    status          text        NOT NULL,
    paid_at         timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT invoice_pkey PRIMARY KEY (id),
    CONSTRAINT invoice_number_key UNIQUE (number),
    CONSTRAINT invoice_period_key UNIQUE (subscription_id, period_start),
    CONSTRAINT invoice_period_index_key UNIQUE (subscription_id, period_index),
    CONSTRAINT invoice_salon_fkey FOREIGN KEY (salon_id) REFERENCES tenant (id),
    CONSTRAINT invoice_subscription_fkey FOREIGN KEY (subscription_id) REFERENCES subscription (id),
    CONSTRAINT invoice_plan_fkey FOREIGN KEY (plan_id) REFERENCES plan (id),
    CONSTRAINT invoice_number_positive CHECK (number > 0),
    CONSTRAINT invoice_period_ordered CHECK (period_end > period_start),
    CONSTRAINT invoice_period_index_counted CHECK (period_index >= 0),
    CONSTRAINT invoice_amount_bounded CHECK (amount BETWEEN 0 AND 100000000),
    CONSTRAINT invoice_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT invoice_due_after_issue CHECK (due_at > issued_at),
    CONSTRAINT invoice_status_known CHECK (status IN ('open', 'paid')),
    CONSTRAINT invoice_paid_has_time CHECK ((status = 'paid') = (paid_at IS NOT NULL))
);
CREATE INDEX invoice_salon_idx ON invoice (salon_id, issued_at DESC, number DESC);
CREATE INDEX invoice_open_idx ON invoice (subscription_id, due_at) WHERE status = 'open';

CREATE FUNCTION invoice_refuse_change()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.number <> OLD.number OR NEW.salon_id <> OLD.salon_id
       OR NEW.subscription_id <> OLD.subscription_id OR NEW.plan_id IS DISTINCT FROM OLD.plan_id
       OR NEW.plan_key IS DISTINCT FROM OLD.plan_key OR NEW.plan_name IS DISTINCT FROM OLD.plan_name
       OR NEW.period_index <> OLD.period_index OR NEW.period_start <> OLD.period_start
       OR NEW.period_end <> OLD.period_end OR NEW.amount <> OLD.amount OR NEW.currency <> OLD.currency
       OR NEW.issued_at <> OLD.issued_at OR NEW.due_at <> OLD.due_at OR NEW.created_at <> OLD.created_at
       OR (OLD.status = 'paid') THEN
        RAISE EXCEPTION 'an issued invoice is never changed, except once from open to paid'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER invoice_immutable
    BEFORE UPDATE ON invoice
    FOR EACH ROW EXECUTE FUNCTION invoice_refuse_change();

-- An attempt to charge an invoice through the billing provider: at issue and on each reminder day
-- while unpaid, at most once per invoice and occasion, each with its stable idempotency key.
CREATE TABLE charge_attempt (
    id           uuid        NOT NULL,
    invoice_id   uuid        NOT NULL,
    occasion     text        NOT NULL,
    attempt_key  uuid        NOT NULL,
    status       text        NOT NULL,
    requested_at timestamptz NOT NULL,
    settled_at   timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT charge_attempt_pkey PRIMARY KEY (id),
    CONSTRAINT charge_attempt_key UNIQUE (attempt_key),
    CONSTRAINT charge_attempt_occasion_key UNIQUE (invoice_id, occasion),
    CONSTRAINT charge_attempt_invoice_fkey FOREIGN KEY (invoice_id) REFERENCES invoice (id),
    CONSTRAINT charge_attempt_occasion_known CHECK (occasion IN ('issue', 'due', 'due_3', 'due_10')),
    CONSTRAINT charge_attempt_status_known CHECK (status IN ('requested', 'succeeded', 'failed')),
    CONSTRAINT charge_attempt_settled_has_time CHECK ((status = 'requested') = (settled_at IS NULL))
);

-- The one payment of an invoice: payments are exact, so one pays it in full and a second can never
-- be recorded (unique invoice). A card payment names its provider event, unique across events.
CREATE TABLE invoice_payment (
    id                uuid        NOT NULL,
    invoice_id        uuid        NOT NULL,
    method            text        NOT NULL,
    reference         text,
    provider_event_id text,
    amount            bigint      NOT NULL,
    received_on       date,
    paid_at           timestamptz NOT NULL,
    recorded_by       uuid,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT invoice_payment_pkey PRIMARY KEY (id),
    CONSTRAINT invoice_payment_invoice_key UNIQUE (invoice_id),
    CONSTRAINT invoice_payment_event_key UNIQUE (provider_event_id),
    CONSTRAINT invoice_payment_invoice_fkey FOREIGN KEY (invoice_id) REFERENCES invoice (id),
    CONSTRAINT invoice_payment_operator_fkey FOREIGN KEY (recorded_by) REFERENCES operator_user (id),
    CONSTRAINT invoice_payment_method_known CHECK (method IN ('card', 'bank_transfer')),
    CONSTRAINT invoice_payment_card_shape CHECK (method <> 'card' OR (provider_event_id IS NOT NULL
        AND reference IS NULL AND received_on IS NULL AND recorded_by IS NULL)),
    CONSTRAINT invoice_payment_transfer_shape CHECK (method <> 'bank_transfer' OR (provider_event_id IS NULL
        AND reference IS NOT NULL AND received_on IS NOT NULL AND recorded_by IS NOT NULL)),
    CONSTRAINT invoice_payment_reference_bounded CHECK (reference IS NULL OR (reference = btrim(reference)
        AND reference <> '' AND length(reference) <= 120)),
    CONSTRAINT invoice_payment_amount_bounded CHECK (amount BETWEEN 0 AND 100000000),
    CONSTRAINT invoice_payment_unchanged CHECK (updated_at = created_at)
);

-- Every distinct provider event received with a valid signature, and what it did. A repeated event
-- id is acknowledged without effect; an event for an unknown or already-paid invoice is recorded here
-- without changing anything.
CREATE TABLE provider_event (
    event_id    text        NOT NULL,
    invoice_id  uuid,
    attempt_key uuid,
    outcome     text        NOT NULL,
    occurred_at timestamptz NOT NULL,
    result      text        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT provider_event_pkey PRIMARY KEY (event_id),
    CONSTRAINT provider_event_id_format CHECK (event_id ~ '^[A-Za-z0-9_.:-]{1,100}$'),
    CONSTRAINT provider_event_outcome_known CHECK (outcome IN ('success', 'failure')),
    CONSTRAINT provider_event_result_known CHECK (result IN ('paid', 'failed', 'already_paid', 'unknown')),
    CONSTRAINT provider_event_unchanged CHECK (updated_at = created_at)
);

-- Each reminder or notice to the salon's Owner contact, once per invoice and kind (a failed-charge
-- notice once per charge attempt), however often the job runs. The message is null when the salon
-- has no Owner contact (a legacy salon): the reminder is recorded and nothing is sent.
CREATE TABLE billing_reminder (
    id                uuid        NOT NULL,
    invoice_id        uuid        NOT NULL,
    kind              text        NOT NULL,
    charge_attempt_id uuid,
    message_id        uuid,
    sent_at           timestamptz NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT billing_reminder_pkey PRIMARY KEY (id),
    CONSTRAINT billing_reminder_invoice_fkey FOREIGN KEY (invoice_id) REFERENCES invoice (id),
    CONSTRAINT billing_reminder_attempt_fkey FOREIGN KEY (charge_attempt_id) REFERENCES charge_attempt (id),
    CONSTRAINT billing_reminder_kind_known CHECK (kind IN ('failed_charge', 'due', 'due_3', 'due_10', 'read_only')),
    CONSTRAINT billing_reminder_attempt_shape CHECK ((kind = 'failed_charge') = (charge_attempt_id IS NOT NULL)),
    CONSTRAINT billing_reminder_unchanged CHECK (updated_at = created_at)
);
CREATE UNIQUE INDEX billing_reminder_once ON billing_reminder (invoice_id, kind) WHERE kind <> 'failed_charge';
CREATE UNIQUE INDEX billing_reminder_attempt_once ON billing_reminder (charge_attempt_id) WHERE kind = 'failed_charge';

-- Idempotent operator billing actions keep their receipts with salon and plan creation.
ALTER TABLE provisioning_receipt DROP CONSTRAINT provisioning_receipt_action_known;
ALTER TABLE provisioning_receipt ADD CONSTRAINT provisioning_receipt_action_known
    CHECK (action IN ('plan.create', 'salon.create', 'subscription.start', 'payment.record'));

REVOKE ALL ON TABLE subscription, invoice_number, invoice, charge_attempt, invoice_payment, provider_event,
    billing_reminder FROM PUBLIC;
REVOKE ALL ON FUNCTION invoice_refuse_change() FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON TABLE subscription TO "${platform_role}";
GRANT SELECT, UPDATE ON TABLE invoice_number TO "${platform_role}";
GRANT SELECT, INSERT, UPDATE ON TABLE invoice TO "${platform_role}";
GRANT SELECT, INSERT, UPDATE ON TABLE charge_attempt TO "${platform_role}";
GRANT SELECT, INSERT ON TABLE invoice_payment TO "${platform_role}";
GRANT SELECT, INSERT ON TABLE provider_event TO "${platform_role}";
GRANT SELECT, INSERT ON TABLE billing_reminder TO "${platform_role}";

-- The entitlements facade's read for the salon of the calling tenant transaction (the salon set in
-- app.tenant_id, and no other): its plan's limits and features, or a row of nulls when it has no
-- plan. The salon's registry row is locked FOR SHARE until the caller's transaction ends, so a
-- concurrent plan move (which updates that row) waits for a staff creation that read the old limits,
-- and a staff creation that starts after a move reads the new ones.
CREATE FUNCTION salon_entitlements()
    RETURNS TABLE (plan_id uuid, plan_key text, plan_name text, staff_seats integer, branches integer,
                   message_allowance integer, custom_domain boolean, dedicated_app boolean)
    LANGUAGE sql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p.id, p.key, p.name, p.staff_seats, p.branches, p.message_allowance, p.custom_domain, p.dedicated_app
      FROM public.tenant t LEFT JOIN public.plan p ON p.id = t.plan_id
     WHERE t.id = NULLIF(pg_catalog.current_setting('app.tenant_id', true), '')::uuid
       FOR SHARE OF t
$$;

REVOKE ALL ON FUNCTION salon_entitlements() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION salon_entitlements() TO "${application_role}";

-- Owned by identity: the number of active staff accounts of one salon, the usage platform compares
-- with a plan's staff-seat limit before a plan move. A count and nothing else. It sets that salon for
-- row-level security inside the function (which stays forced) and puts the caller's setting back.
CREATE FUNCTION identity_active_staff_count(target_tenant uuid)
    RETURNS bigint
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
    counted  bigint;
BEGIN
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    SELECT count(*) INTO counted FROM public.identity_staff s WHERE s.tenant_id = target_tenant AND s.active;
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN counted;
END
$$;

REVOKE ALL ON FUNCTION identity_active_staff_count(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_active_staff_count(uuid) TO "${platform_role}";

-- Owned by the outbox: how billing, on the platform connection, writes a message for one salon in its
-- own platform transaction (an invoice, its charge attempt and their messages commit together). Only
-- the billing channels are accepted; the message is pending and due at the given instant. It sets
-- that salon for row-level security inside the function and puts the caller's setting back.
CREATE FUNCTION outbox_enqueue_for_salon(target_tenant uuid, message_id uuid, message_channel text,
        message_payload jsonb, message_trace_parent text, at_time timestamptz)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    previous text := pg_catalog.current_setting('app.tenant_id', true);
BEGIN
    IF message_channel NOT IN ('email', 'billing-charge') THEN
        RAISE EXCEPTION 'only billing channels are written from the platform connection'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant::text, true);
    INSERT INTO public.outbox_message (tenant_id, id, channel, payload, status, attempts, next_attempt_at,
            trace_parent, created_at, updated_at)
        VALUES (target_tenant, message_id, message_channel, message_payload, 'pending', 0, at_time,
            message_trace_parent, at_time, at_time);
    PERFORM pg_catalog.set_config('app.tenant_id', coalesce(previous, ''), true);
END
$$;

REVOKE ALL ON FUNCTION outbox_enqueue_for_salon(uuid, uuid, text, jsonb, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION outbox_enqueue_for_salon(uuid, uuid, text, jsonb, text, timestamptz) TO "${platform_role}";
