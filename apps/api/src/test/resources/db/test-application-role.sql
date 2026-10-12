-- The application's role in every test container (TestPostgres.APP_USER), created as an environment
-- creates it: it can log in, and nothing more. Grants on tables come from the migrations.
CREATE ROLE salon_app LOGIN PASSWORD 'salon-app-test' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
