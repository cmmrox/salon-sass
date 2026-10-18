-- The two database roles of any environment other than a developer's machine (T-3), and the
-- database's ownership. Run by the database's administrator, through psql, before the first
-- migration: infra/scripts/stack-up.sh runs it in the stack, every time it starts; on a real host the
-- administrator runs it once. Running it again changes nothing and does not fail.
--
--   * The owning role runs the migrations and owns the database, its public schema and so every
--     table. It is not a superuser, cannot create roles or databases and cannot bypass row-level
--     security, which is forced on every salon's table and so holds for it too.
--   * The application's role is what the API connects as. It logs in and does nothing else: not a
--     superuser, no BYPASSRLS, owns nothing. What it may read and write is granted table by table
--     by the migrations (apps/api/src/main/resources/db/migration). The API refuses to start on a
--     role that could get round row-level security.
--
-- A role's password is set when the role is created and never changed by a later run; to rotate
-- one, the administrator runs ALTER ROLE ... PASSWORD.
--
-- Inputs, read from the environment so that no password is ever on a command line:
--   SALON_DB_NAME, SALON_DB_USER, SALON_DB_PASSWORD, SALON_DB_APP_USER, SALON_DB_APP_PASSWORD,
--   SALON_DB_PLATFORM_USER, SALON_DB_PLATFORM_PASSWORD (F03-S01: the operator console's platform runtime
--   role, a third role that logs in and owns nothing; the migrations grant it the platform tables only)
\set ON_ERROR_STOP on
\set QUIET on

\getenv db_name SALON_DB_NAME
\getenv owner_user SALON_DB_USER
\getenv owner_password SALON_DB_PASSWORD
\getenv app_user SALON_DB_APP_USER
\getenv app_password SALON_DB_APP_PASSWORD
\getenv platform_user SALON_DB_PLATFORM_USER
\getenv platform_password SALON_DB_PLATFORM_PASSWORD

\if :{?db_name}
\else
  DO $$BEGIN RAISE EXCEPTION 'SALON_DB_NAME is not set'; END$$;
\endif
\if :{?owner_user}
\else
  DO $$BEGIN RAISE EXCEPTION 'SALON_DB_USER is not set'; END$$;
\endif
\if :{?owner_password}
\else
  DO $$BEGIN RAISE EXCEPTION 'SALON_DB_PASSWORD is not set'; END$$;
\endif
\if :{?app_user}
\else
  DO $$BEGIN RAISE EXCEPTION 'SALON_DB_APP_USER is not set'; END$$;
\endif
\if :{?app_password}
\else
  DO $$BEGIN RAISE EXCEPTION 'SALON_DB_APP_PASSWORD is not set'; END$$;
\endif

\if :{?platform_user}
\else
  DO $$BEGIN RAISE EXCEPTION 'SALON_DB_PLATFORM_USER is not set'; END$$;
\endif
\if :{?platform_password}
\else
  DO $$BEGIN RAISE EXCEPTION 'SALON_DB_PLATFORM_PASSWORD is not set'; END$$;
\endif

SELECT :'platform_user' IN (:'owner_user', :'app_user') AS platform_shared \gset
\if :platform_shared
  DO $$BEGIN RAISE EXCEPTION 'SALON_DB_PLATFORM_USER must name a third role, not the owning or the application role'; END$$;
\endif

SELECT :'owner_user' = :'app_user' AS same_role \gset
\if :same_role
  DO $$BEGIN RAISE EXCEPTION 'SALON_DB_USER and SALON_DB_APP_USER must name two different roles'; END$$;
\endif

-- Each role is created once, with its password.
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'owner_user', :'owner_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'owner_user')
\gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'app_user')
\gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'platform_user', :'platform_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'platform_user')
\gexec

-- What each role may do, held on every run, so that a role made some other way is brought into line.
ALTER ROLE :"owner_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS INHERIT;
ALTER ROLE :"app_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS INHERIT;
ALTER ROLE :"platform_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS INHERIT;

-- The owning role owns the database, and with it the public schema (owned by pg_database_owner).
ALTER DATABASE :"db_name" OWNER TO :"owner_user";

-- Only these two roles (and the administrator) may connect.
REVOKE CONNECT, TEMPORARY ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO :"app_user";
GRANT CONNECT ON DATABASE :"db_name" TO :"platform_user";
