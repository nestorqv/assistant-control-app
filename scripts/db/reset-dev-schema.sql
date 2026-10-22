-- Drops the app schema objects so Flyway re-applies V1/V2 on next start. Dev only.
-- Needed once after Stage 1b, because V2__initial_schema.sql was edited in place (Flyway checksum changes).
-- Run as the app role (it owns the tables):
--   psql -U asistencia_app -h localhost -d asistencia -v ON_ERROR_STOP=1 -f scripts/db/reset-dev-schema.sql
DROP TABLE IF EXISTS
    adjustment, evidence, time_entry, work_schedule, employee, branch, company,
    ajuste, evidencia, marca, horario, empleado, sucursal, empresa,
    flyway_schema_history
CASCADE;
