-- The application's database role in the local environment: what the API connects as. Run by
-- infra/scripts (lib/common.sh, ensure_application_role) through psql in the salon-postgres
-- container, as the owning role, every time PostgreSQL or the API is started. Running it again
-- changes nothing but the password, so it works on a new volume and on one that already has data.
--
-- The role can log in and nothing more: it is not a superuser, cannot bypass row-level security,
-- cannot create roles or databases, and owns nothing. What it may read is granted by the
-- migrations (apps/api/src/main/resources/db/migration), table by table.
--
-- The platform runtime role (F03-S01) is created the same way: the operator console's separately
-- configured role. Its grants come from the migrations, platform table by platform table.
--
-- psql variables: app_user, app_password, platform_user, platform_password (local-only values from
-- .env.example), db_name.
\set ON_ERROR_STOP on

SELECT format('CREATE ROLE %I', :'app_user')
 WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'app_user')
\gexec

ALTER ROLE :"app_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS INHERIT
    PASSWORD :'app_password';

GRANT CONNECT ON DATABASE :"db_name" TO :"app_user";

SELECT format('CREATE ROLE %I', :'platform_user')
 WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'platform_user')
\gexec

ALTER ROLE :"platform_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS INHERIT
    PASSWORD :'platform_password';

GRANT CONNECT ON DATABASE :"db_name" TO :"platform_user";
