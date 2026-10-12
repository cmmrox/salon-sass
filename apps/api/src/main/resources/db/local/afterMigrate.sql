-- The local seed: the three synthetic salons of docs/qa/test-plan.md, with their fixed ids, slugs,
-- names and colours. A Flyway callback in a location only the `local` profile adds
-- (application-local.yaml), so no other environment ever runs it. It runs after every migrate,
-- leaves no entry in flyway_schema_history, and changes nothing that already exists: running it
-- again neither duplicates a salon nor overwrites one.
--
-- Each salon's brand row is written with that salon set for the statement, so the seed passes
-- row-level security like any other writer, whichever role runs the migrations.
DO $$
DECLARE
    salon record;
BEGIN
    FOR salon IN
        SELECT * FROM (VALUES
            ('a1a1a1a1-0000-4000-8000-000000000001'::uuid, 'alpha', 'Alpha Studio', '#D6086A'),
            ('b2b2b2b2-0000-4000-8000-000000000002'::uuid, 'beta',
                'Beta Hair, Beauty & Wellness Lounge of Colombo Seven', '#1F2937'),
            ('c3c3c3c3-0000-4000-8000-000000000003'::uuid, 'gamma', 'Gamma', '#FDE68A')
        ) AS synthetic (id, slug, display_name, brand_colour)
    LOOP
        INSERT INTO tenant (id, slug, status) VALUES (salon.id, salon.slug, 'active')
            ON CONFLICT DO NOTHING;
        PERFORM set_config('app.tenant_id', salon.id::text, true);
        INSERT INTO salon_brand (tenant_id, display_name, brand_colour)
            VALUES (salon.id, salon.display_name, salon.brand_colour)
            ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
