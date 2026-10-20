# Changelog

## [2026-10-13]

### Added

- Stage 0 project foundation (`docs/plans/stage-0-foundation.md`).
- Git repository (branch `main`, no commits), `.gitignore`, `.gitattributes`, English `README.md`.
- Dev database scripts: `scripts/db/create-dev-db.sql` and `create-dev-db.ps1` (role `asistencia_app`, database `asistencia`; password supplied at run time).
- Backend `backend/`: Spring Boot 3.5.16 (Maven wrapper 3.9.14, Java 21 bytecode), web, data-jpa, validation, security, actuator, Flyway (+ PostgreSQL module), PostgreSQL driver.
  - Public `GET /api/health`; stateless security chain returning 401 for everything else.
  - Flyway `V1__baseline.sql` (no-op).
  - Datasource from `DB_URL` / `DB_USER` / `DB_PASSWORD` with optional `backend/.env` (`backend/.env.example` provided).
  - Integration test on zonky embedded PostgreSQL 16.15 (embedded-database-spring-test 2.8.0, embedded-postgres 2.2.2).
- Frontend `frontend/`: Vite 8.3.4, React 19.3.0, TypeScript 6.0.3, react-router 8.4.0 (routes `/`, `/admin`, `*`), vite-plugin-pwa 2.0.0 (Spanish manifest, placeholder "CA" icons, `/api` excluded from SW navigation fallback), `/api` dev and preview proxy, `HealthStatus` component.
- Frontend tests: Vitest 5.0.4, jsdom 30.1.2, @testing-library/react 16.3.3, @testing-library/dom 10.4.2; lint with oxlint 1.88.0 (create-vite default).
- `engines.node >=24.15.0` and `engine-strict=true` for the frontend.
- Stage 0 test coverage: backend `SecurityAndActuatorTest` (public health, 401 on other paths and actuator endpoints, stateless responses, UTC `Clock`); frontend tests for `HealthStatus` states, routes, PWA manifest/service worker and dev proxy. Totals: backend 10, frontend 20.
- `docs/PROGRESS.md` stage/step tracker.
