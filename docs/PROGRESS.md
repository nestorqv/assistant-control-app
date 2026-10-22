# Progress tracker

Historic checklist of every stage and step. Mark `[x]` and add the completion date (YYYY-MM-DD) when a step is done.
Each stage follows the CLAUDE.md workflow: plan → (architect / designer) → code → tests → (security audit).
Roadmap: [plans/00-roadmap.md](plans/00-roadmap.md)

## Status

| Stage | Status | Started | Completed |
|-------|--------|---------|-----------|
| 0 - Foundation | Done | 2026-10-13 | 2026-10-20 |
| 1 - Data model + auth | Done | 2026-10-21 | 2026-10-22 |
| 1b - English naming + EN/ES | Done | 2026-10-22 | 2026-10-22 |
| 2 - Check-in API | Not started | | |
| 3 - Employee PWA | Not started | | |
| 4 - Admin panel | Not started | | |
| 5 - Hardening + deploy | Not started | | |

## Stage 0 - Foundation

Plan: [plans/stage-0-foundation.md](plans/stage-0-foundation.md)

- [x] Roadmap written (`docs/plans/00-roadmap.md`) - 2026-10-13
- [x] Stage 0 plan written - 2026-10-13
- [x] Open questions answered (Spring Boot 3.5.16, Node 24 LTS, package `cr.asistencia`, English docs) - 2026-10-13
- [x] PostgreSQL 16 installed natively (service `postgresql-x64-16`) - 2026-10-13
- [x] Node 24 LTS installed (v24.20.0, npm 11.19.0) - 2026-10-13
- [x] Plan updated for the answers above - 2026-10-13
- [x] Repo setup: `git init`, `.gitignore`, `.gitattributes`, `README.md`, `CHANGELOG.md` - 2026-10-13
- [x] Dev database script (`scripts/db/`) created - 2026-10-13
- [x] Dev database script run by user + `backend/.env` created (`postgres` password reset first via temporary `trust` in pg_hba.conf, reverted) - 2026-10-20
- [x] Backend skeleton: Spring Boot 3.5.16, `GET /api/health`, Security, Flyway baseline - 2026-10-13
- [x] Frontend skeleton: Vite React TS, PWA manifest + icons, routes `/` and `/admin`, `/api` proxy - 2026-10-13
- [x] Coder's checks pass: backend 3 tests (embedded Postgres), frontend build + lint + 3 tests - 2026-10-13
- [x] Tester review: backend 10 tests + frontend 20 tests pass, lint + build clean, no bugs found - 2026-10-13
- [x] Backend connects to real local DB `asistencia`; Flyway applied V1 - 2026-10-20
- [x] Initial commit `99c27c7` pushed to github.com/nestorqv/assistant-control-app (branch `main`) - 2026-10-21
- [x] End-to-end check: frontend shows "Servidor en línea (UP)" through the proxy (run from the user's own terminal; Claude's sandboxed shell blocks Tomcat's local socket) - 2026-10-20

## Stage 1 - Data model + auth

- [x] Stage plan written (`docs/plans/stage-1-data-model-auth.md`) - 2026-10-21
- [x] Open questions answered (token 8 h employee / 6 h admin, 8-digit PIN, rest as recommended) - 2026-10-21
- [x] Plan updated for the answers (8-digit PIN policy incl. date/sequence rejection) - 2026-10-21
- [x] ER diagram (architect): `docs/diagrams/stage-1-er.md` - 2026-10-21
- [x] Plan amended after architect review (tenant convention, constant-time login, `ajuste` columns, per-tenant `client_uuid`, `empresa.activo`, no package cycle) - 2026-10-21
- [x] Flyway V2 schema: empresa, sucursal, empleado, horario, marca, evidencia, ajuste - 2026-10-21
- [x] JPA entities + repositories with tenant scoping - 2026-10-21
- [x] Login endpoint (company code + employee code + PIN), BCrypt, JWT - 2026-10-21
- [x] Lockout after 5 failed attempts; roles EMPLEADO / ADMIN; per-IP rate limit - 2026-10-21
- [x] Endpoints `/api/me`, `/api/me/consentimiento`, `/api/admin/empleados[/{id}]` - 2026-10-21
- [x] Dev seed data (profile `dev`) - 2026-10-21
- [x] Coder's checks pass: backend 119 tests (10 Stage 0 + 109 Stage 1) - 2026-10-21
- [x] Tester review: 251 tests (132 new), 3 failing `knownBug_*` tests exposing 2 bugs - 2026-10-21
- [x] Security audit: 0 Critical/High, 3 Medium, 7 Low, 8 Info (`securityAudit-report/stage-1-auth.md`) - 2026-10-21
- [x] Audit decisions agreed: fix M-1 + L-2/L-3/L-4/L-5 now; M-2/M-3 direction set for Stage 5 - 2026-10-21
- [x] Bug fixes: BUG 1 `horario` times shifted by JVM time zone; BUG 2 malformed-JSON 400 without `codigo` - 2026-10-22
- [x] Audit fixes: M-1 lockout race, L-2 require `exp`/`iat`, L-3 weak JWT secret rejection, L-4 no input echo in 400, L-5 seeder only when `dev` explicitly active; public endpoints ignore stale bearer tokens - 2026-10-22
- [x] Tests pass after fixes: 278 run, 0 failures (re-verified independently) - 2026-10-22
- [x] User added `JWT_SECRET` (48 bytes) + `DEV_SEED_PIN` to `backend/.env`; app started with `dev` profile - 2026-10-22
- [x] End-to-end check on the running server: health, 401 without/garbage token, employee login + `/api/me`, 403 on admin path for employee, admin login + list of 7 employees, wrong PIN 401 then reset - 2026-10-22
- [x] Stage 1 committed (`5f50477`) and pushed to `origin/main` - 2026-10-22
- [x] Security audit (see above) - 2026-10-21

## Stage 1b - English naming + bilingual text (EN/ES)

Branch: `stage1-2`. Inventory: [naming-inventory.md](naming-inventory.md)

- [x] Spanish naming inventory written (`docs/naming-inventory.md`) - 2026-10-22
- [x] Decisions: all technical names in English; `marca` → `time_entry` / `TimeEntry` / package `timeentry`; user-readable text in EN + ES with a language switcher (`messages_es.properties` / `messages_en.properties`) - 2026-10-22
- [x] Decision: keep local DB `asistencia` and role `asistencia_app` - 2026-10-22
- [x] Plan written (`docs/plans/stage-1b-english-naming-i18n.md`) - 2026-10-22
- [x] Open questions answered (manifest Spanish, `INVALID_PIN` now, `distance_meters`, add user-event) - 2026-10-22
- [x] Backend rename: packages (`cr.attendance`), classes, methods, fields, DB schema (V2 edited in place, `status` widened to 20), enums, API, error codes, JWT claims, config keys - 2026-10-22
- [x] Backend i18n: `MessageSource` + `Accept-Language` (default es), `INVALID_PIN` - 2026-10-22
- [x] Frontend i18n: EN/ES resources + accessible language switcher - 2026-10-22
- [x] Docs updated (diagram, README, roadmap, Stage 1 plan, inventory status) + `scripts/db/reset-dev-schema.sql` - 2026-10-22
- [x] Coder's checks pass: backend 335 tests, frontend lint + build + 52 tests - 2026-10-22
- [x] Tester review: no bugs, no unexpected Spanish leftovers; backend 398 tests, frontend 83 tests, lint + build clean - 2026-10-22
- [x] User reset local dev DB; end-to-end check on the running server: English login fields and JSON, `/api/me`, 403/200 on `/api/admin/employees`, `INVALID_CREDENTIALS` with EN/ES detail and `Content-Language`, old Spanish fields 400 and old URLs 404, frontend proxy OK; user verified the language switcher in the browser - 2026-10-22
- [ ] Committed and pushed to `stage1-2`

## Stage 2 - Check-in API

- [ ] Stage plan written + open questions answered
- [ ] `GET /api/tiempo` (server time)
- [ ] `GET /api/marcas/hoy`
- [ ] `POST /api/marcas` with order validation and `client_uuid` idempotency
- [ ] Haversine geofence → VALIDA / POR_REVISAR
- [ ] `StorageService` + `LocalDiskStorageService`, authenticated photo endpoint
- [ ] Lateness from horario + tolerancia
- [ ] Tests pass
- [ ] Security audit

## Stage 3 - Employee PWA

- [ ] Stage plan written + open questions answered
- [ ] UI design (designer)
- [ ] Login screen with PIN keypad
- [ ] Consent screen (Ley 8968)
- [ ] Main screen, 3 states (antes de marcar / en turno / jornada completa)
- [ ] Selfie capture + on-device compression
- [ ] Geolocation + distance display
- [ ] IndexedDB offline queue, flushed on reconnect
- [ ] HTTPS in dev; tested on a real phone
- [ ] Tests pass

## Stage 4 - Admin panel

- [ ] Stage plan written + open questions answered
- [ ] UI design (designer)
- [ ] Dashboard: KPI cards + employee table
- [ ] Evidence viewer
- [ ] Review queue with `ajuste` audit rows
- [ ] CRUD sucursales / empleados / horarios
- [ ] Excel export (Apache POI)
- [ ] Tests pass

## Stage 5 - Hardening + deploy

- [ ] Stage plan written + open questions answered
- [ ] Full OWASP security audit
- [ ] Stage 1 audit M-2: rate-limit by empresa+empleado and IP network block (/64 IPv6); trust `X-Forwarded-For` only from Caddy
- [ ] Stage 1 audit M-3: replace hard 15-min lock with growing delays (1 s → ~1 min) + admin alert
- [ ] Photo retention job
- [ ] VPS deploy: native PostgreSQL + Caddy HTTPS
- [ ] Backups (database + uploads)
- [ ] Secrets only in environment variables
- [ ] Tests pass

## Later (post-MVP)

- [ ] Tablet kiosk mode
- [ ] QR check-in
- [ ] S3 / R2 storage
- [ ] Multiple shifts / breaks per day
