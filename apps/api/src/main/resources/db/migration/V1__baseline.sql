-- Baseline. It creates nothing: its purpose is to start the migration history, so that every
-- table the product ever has arrives through a numbered migration recorded in
-- flyway_schema_history. The first tables (the tenant registry) come with V2.
--
-- Migrations are append-only: never edit this file. See docs/engineering/backend.md.
SELECT 1;
