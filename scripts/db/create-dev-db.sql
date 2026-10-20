-- Creates the dev role and database. Run as the postgres superuser:
--   psql -U postgres -v ON_ERROR_STOP=1 -v app_password=YOUR_PASSWORD -f create-dev-db.sql
-- Idempotent: re-running does not change an existing role's password.

\if :{?app_password}
\else
  \echo 'ERROR: app_password is not set. Pass it with -v app_password=...'
  \quit
\endif

-- CREATE ROLE has no IF NOT EXISTS, so generate the statement only when missing.
SELECT format('CREATE ROLE asistencia_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'asistencia_app')
\gexec

-- Same pattern for the database.
SELECT 'CREATE DATABASE asistencia OWNER asistencia_app ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'asistencia')
\gexec

-- In PostgreSQL 15+ the public schema is owned by pg_database_owner, so the
-- database owner can create tables and no extra GRANT is needed.
