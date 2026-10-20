# Progress tracker

Historic checklist of every stage and step. Mark `[x]` and add the completion date (YYYY-MM-DD) when a step is done.
Each stage follows the CLAUDE.md workflow: plan → (architect / designer) → code → tests → (security audit).
Roadmap: [plans/00-roadmap.md](plans/00-roadmap.md)

## Status

| Stage | Status | Started | Completed |
|-------|--------|---------|-----------|
| 0 - Foundation | Done | 2026-10-13 | 2026-10-20 |
| 1 - Data model + auth | Not started | | |
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
- [x] End-to-end check: frontend shows "Servidor en línea (UP)" through the proxy (run from the user's own terminal; Claude's sandboxed shell blocks Tomcat's local socket) - 2026-10-20

## Stage 1 - Data model + auth

- [ ] Stage plan written + open questions answered
- [ ] ER diagram (architect)
- [ ] Flyway V1 schema: empresa, sucursal, empleado, horario, marca, evidencia, ajuste
- [ ] JPA entities + repositories with tenant scoping
- [ ] Login endpoint (company code + employee code + PIN), BCrypt, JWT
- [ ] Lockout after 5 failed attempts; roles EMPLEADO / ADMIN
- [ ] Dev seed data
- [ ] Tests pass
- [ ] Security audit

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
