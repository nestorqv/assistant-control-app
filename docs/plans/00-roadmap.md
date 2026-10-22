# Control de Asistencia - Roadmap

Attendance clock-in/out system for small companies in Costa Rica.
Blueprint: `Prototipo-Control-Asistencia.pdf` (employee PWA screen + admin web panel).

## Confirmed decisions

- Monorepo: `backend/` (Spring Boot 3.5.16, Maven, Flyway, Java 21 bytecode, package `cr.attendance`) and `frontend/` (React + Vite 8 + TypeScript, vite-plugin-pwa, react-router 8, Vitest 5).
- Spring Boot 3.5 OSS support ended 2026-06-30. Staying on 3.5.16 for now; **migration to Spring Boot 4.x is required before Stage 5**.
- Node 24 LTS (frontend `engines.node >=24.15.0`, `engine-strict=true`).
- Database: PostgreSQL 16 installed natively on Windows (`winget install PostgreSQL.PostgreSQL.16`), running as the Windows service `postgresql-x64-16`. **No Docker anywhere.** Connection via env vars `DB_URL`, `DB_USER`, `DB_PASSWORD`; committed `backend/.env.example`, real `backend/.env` gitignored.
- Docs (README, plans, CHANGELOG) in English; UI text in Spanish and English (switchable, default Spanish); technical names (code, schema, API, JWT) in English.
- The local database `asistencia` and role `asistencia_app` keep their Spanish names. The PWA manifest stays Spanish.
- Git: repository initialized; commits only when the user asks. `claude-agents-setup.zip` is gitignored.
- Tests: zonky embedded-postgres (`io.zonky.test:embedded-database-spring-test`) instead of Testcontainers.
- Photos: local disk (`uploads/`, gitignored) behind a `StorageService` interface.
- Login: company code + employee code + PIN.

## Stages

### Stage 0 - Foundation
Git repo, `.gitignore`, `README.md`, `CHANGELOG.md`, dev DB creation script, backend skeleton (`GET /api/health`, Spring Security with only health public, Flyway baseline, embedded-Postgres context test), frontend skeleton (Vite React TS, PWA manifest, routes `/` and `/admin`, `/api` dev proxy, health check page, Vitest).
Plan: `docs/plans/stage-0-foundation.md`.

### Stage 1 - Data model + auth
- Tables: `company`, `branch`, `employee`, `work_schedule`, `time_entry`, `evidence`, `adjustment`.
- Additions: `company.code`, `employee.code`, `time_entry.client_uuid`, `employee.consent_at`, `company.time_zone` (default `America/Costa_Rica`).
- JWT login with BCrypt-hashed PIN, lockout after 5 failures, roles `EMPLOYEE` / `ADMIN`.
- Tenant scoping by `company_id` taken from the token.

### Stage 1b - English naming + bilingual text
All technical names renamed to English (root package `cr.attendance`, `marca` -> `time_entry`), and user-facing text available in Spanish and English: backend `messages*.properties` selected by `Accept-Language`, frontend language switcher. Plan: `docs/plans/stage-1b-english-naming-i18n.md`.

### Stage 2 - Check-in API
- Provisional names, to be finalized in the Stage 2 plan: `GET /api/time`, `GET /api/time-entries/today`, `POST /api/time-entries` (multipart) using server time.
- Haversine geofence -> `VALID` / `PENDING_REVIEW`.
- Order validation (`clock-in` before `clock-out`), `client_uuid` idempotency.
- `LocalDiskStorageService`.
- Lateness computed from `work_schedule` + `grace_minutes`.

### Stage 3 - Employee PWA
- Three screens per mockup (Antes de marcar / En turno / Jornada completa).
- PIN keypad login.
- Selfie via `getUserMedia`, compressed to ~1024 px JPEG quality 0.7.
- Geolocation.
- IndexedDB offline queue flushed on reconnect.
- Consent screen.
- HTTPS in dev via `@vitejs/plugin-basic-ssl`.

### Stage 4 - Admin panel
- Dashboard KPIs + employee table.
- Evidence viewer.
- Review queue writing `adjustment` rows.
- CRUD branches / employees / work schedules.
- Excel export with Apache POI.

### Stage 5 - Hardening + deploy
- **Prerequisite: migrate the backend from Spring Boot 3.5.x to 4.x** (3.5 is out of OSS support). Re-check zonky embedded-database-spring-test compatibility with the target 4.x line.
- OWASP review.
- Photo retention job.
- VPS deploy with native PostgreSQL + Caddy HTTPS.
- Backups.
- Secrets in environment variables.

### Later
Tablet kiosk mode, QR check-in, S3 storage, multiple shifts / breaks.

## Glossary (ES -> EN)

| Spanish | English |
|---|---|
| asistencia | attendance |
| empresa | company |
| sucursal | branch |
| empleado | employee |
| horario | work_schedule / `WorkSchedule` |
| marca | time_entry / `TimeEntry` (overrides the inventory's `punch`) |
| evidencia | evidence |
| ajuste | adjustment |
| consentimiento | consent |
| puesto | job_title |
| rol | role |
| codigo | code |

Full mapping: `docs/naming-inventory.md`.
