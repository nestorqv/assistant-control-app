# Changelog

## [2026-10-21]

### Added

- Stage 1 data model and authentication (`docs/plans/stage-1-data-model-auth.md`).
- Flyway `V2__initial_schema.sql`: `empresa` (with `codigo`, `zona_horaria`, `activo`), `sucursal`, `empleado` (lockout state and `token_version`), `horario`, `marca` (`UNIQUE (empresa_id, client_uuid)`), `evidencia`, `ajuste` (`campo`, `valor_anterior` nullable, `valor_nuevo`). bigint identity ids, `timestamptz`, CHECK constraints, `empresa_id` on every tenant table with composite foreign keys, `ON DELETE RESTRICT`.
- Flat JPA entities and tenant-scoped repositories (bare `Repository`, every method takes `empresaId`).
- `POST /api/auth/login`: 8-digit PIN (`PinPolicy`), BCrypt (strength 12, 4 in tests), uniform 401 with one BCrypt comparison on every path, lockout (5 failures, 15 minutes), per-IP limiter (20 per 5 minutes, 429 with `Retry-After`).
- JWT (HS256) through the Spring Security OAuth2 Resource Server; `JWT_SECRET` (base64, at least 32 bytes) fails fast at startup; TTL 8 h employee, 6 h admin; per-request database re-check of employee, company, `activo` and `token_version`; role taken from the database.
- `GET /api/me`, `POST /api/me/consentimiento`, `GET /api/admin/empleados` and `GET /api/admin/empleados/{id}` (404 across tenants).
- Dev seed under profile `dev` (company `DEMO`, employees 1001-1006, admin 9001, PIN from `DEV_SEED_PIN`).
- `ProblemDetail` error bodies with a `codigo` property.
- Dependencies: `spring-boot-starter-oauth2-resource-server`, `caffeine`.
- Backend tests: 119 in total (Stage 0: 10, Stage 1: 109), all on embedded PostgreSQL.
- Tester's extra Stage 1 coverage (lockout, login hardening, JWT, tenancy, schema, seed, time zone) plus the regression tests for the fixes below; the backend suite now totals 278 tests, all passing.
- `backend/.env.example` placeholders `JWT_SECRET` and `DEV_SEED_PIN`; README sections for configuration, dev data and authentication.

### Fixed

- `horario` times (`hora_entrada`, `hora_salida`) were shifted by the JVM time zone (06:00 stored as 12:00 in `America/Costa_Rica`) because of `hibernate.jdbc.time_zone: UTC`. `Horario` now maps both `LocalTime` fields with `@JdbcType(LocalTimeJdbcType.class)`, so the wall-clock time is stored as written; `timestamptz` columns are unchanged and still store the right instant.
- Every error response is now a `ProblemDetail` with a stable `codigo`: `ApiExceptionHandler` overrides `handleExceptionInternal`, so unreadable or malformed JSON (400), type mismatch (400), missing parameter (400), 405, 406 and 415 are covered. Bodies use fixed messages and a constant `instance`, so they never echo the submitted value, the URL or an exception message (audit L-4).
- Lockout race (audit M-1): `registrarExito` now only updates when the lock is not active; if 0 rows change, the login fails with the generic 401 and no token. A correct PIN that raced a concurrent lock can no longer log in or clear the lock.
- JWT validation requires `exp` and `iat` besides `iss` and HS256 (audit L-2). The default claim converter invented `iat` from `exp`, so it is dropped when the token has none.
- `JWT_SECRET` is rejected at startup if it is the committed test secret (outside the `test` profile) or has fewer than 8 distinct byte values; the error message never contains the secret (audit L-3).
- The dev seeder runs only when the `dev` profile is explicitly active and fails startup if `prod` is also active (audit L-5).
- `GET /api/health` and `/actuator/health` no longer return 401 when the client sends a stale or invalid bearer token; the bearer resolver ignores the Authorization header on public endpoints.

### Changed

- `SecurityConfig`: OAuth2 resource server with a custom converter, `POST /api/auth/login` public, `/api/admin/**` requires `ROLE_ADMIN`, plain 401/403 kept.
- Existing tests use the shared `@IntegrationTest` annotation (test profile); `SecurityAndActuatorTest` gained login and admin checks.
- `docs/diagrams/stage-1-er.md` updated to the final design (amendments 3 to 6).
- `AsistenciaApplication` enables `@ConfigurationPropertiesScan`.

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
