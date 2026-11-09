-- The local seed: the three synthetic salons of docs/qa/test-plan.md, with their fixed ids, slugs,
-- names and colours. A Flyway callback in a location only the `local` profile adds
-- (application-local.yaml), so no other environment ever runs it. It runs after every migrate,
-- leaves no entry in flyway_schema_history, and changes nothing that already exists: running it
-- again neither duplicates a salon nor overwrites one.
--
-- One exception, for time zones (F01-S04): a synthetic salon that still has the column's default,
-- because it was seeded before the column existed, is given its own. alpha and beta are in
-- Asia/Colombo, which is the default; gamma is in Europe/London (decision 029).
--
-- Each salon's brand row is written with that salon set for the statement, so the seed passes
-- row-level security like any other writer, whichever role runs the migrations. Each salon also has
-- its default branch and empty profile drafts (F04-S01) and its brand draft (F04-S02), added by
-- branding's own functions, which leave a salon that already has them unchanged, and the default categories
-- (F05-S01), added by catalogue's own function, which leaves a salon that already has them unchanged, and
-- saved booking rules (F07-S01) when it has none.
DO $$
DECLARE
    salon record;
BEGIN
    FOR salon IN
        SELECT * FROM (VALUES
            ('a1a1a1a1-0000-4000-8000-000000000001'::uuid, 'alpha', 'Alpha Studio', '#D6086A', 'Asia/Colombo'),
            ('b2b2b2b2-0000-4000-8000-000000000002'::uuid, 'beta',
                'Beta Hair, Beauty & Wellness Lounge of Colombo Seven', '#1F2937', 'Asia/Colombo'),
            ('c3c3c3c3-0000-4000-8000-000000000003'::uuid, 'gamma', 'Gamma', '#FDE68A', 'Europe/London')
        ) AS synthetic (id, slug, display_name, brand_colour, time_zone)
    LOOP
        INSERT INTO tenant (id, slug, status, time_zone) VALUES (salon.id, salon.slug, 'active', salon.time_zone)
            ON CONFLICT DO NOTHING;
        UPDATE tenant SET time_zone = salon.time_zone, updated_at = now()
         WHERE id = salon.id AND time_zone = 'Asia/Colombo' AND time_zone <> salon.time_zone;
        PERFORM set_config('app.tenant_id', salon.id::text, true);
        INSERT INTO salon_brand (tenant_id, display_name, brand_colour)
            VALUES (salon.id, salon.display_name, salon.brand_colour)
            ON CONFLICT DO NOTHING;
        PERFORM branding_add_default_branch(salon.id, salon.display_name, now());
        PERFORM branding_add_brand_draft(salon.id, now());
        PERFORM catalogue_add_default_categories(salon.id, now());
        -- Booking rules (F07-S01), so bookings can be made locally: a first version for a salon that has never
        -- saved any, with a fixed id per salon, and nothing for one that has. Its author is no staff account.
        INSERT INTO booking_rules_version (tenant_id, id, branch_id, version, slot_interval_minutes,
                minimum_notice_minutes, booking_horizon_days, deposit_kind, currency, free_cancellation_hours,
                late_cancellation_kind, no_show_kind, reschedule_limit, reschedule_until_hours, changed_fields,
                created_by, created_at, updated_at)
            SELECT salon.id, md5(salon.id::text || ':booking-rules')::uuid, branding_default_branch_id(salon.id), 1,
                   15, 0, 60, 'none', coalesce(salon_currency(), 'LKR'), 24, 'nothing', 'deposit', 1, 24,
                   ARRAY['slotIntervalMinutes', 'minimumNoticeMinutes', 'bookingHorizonDays', 'deposit',
                         'freeCancellationHours', 'lateCancellation', 'noShow', 'reschedule'],
                   '00000000-0000-4000-8000-000000000000'::uuid, now(), now()
             WHERE NOT EXISTS (SELECT 1 FROM booking_rules_version r WHERE r.tenant_id = salon.id);
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
