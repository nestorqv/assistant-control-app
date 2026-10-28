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
| 2 - Check-in API | Done | 2026-10-22 | 2026-10-26 |
| 3 - Employee PWA | Done (real-phone check deferred) | 2026-10-26 | 2026-10-28 |
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
- [x] Committed (`e952bec`) and pushed to `stage1-2` - 2026-10-22

## Stage 2 - Check-in API

- [x] Stage plan written (`docs/plans/stage-2-time-entry-api.md`) - 2026-10-22
- [x] Open questions answered (work date = clock-in date, max shift 16 h; lateness counted from scheduled start; add `deviceSentAt` + GPS accuracy; selfie REQUIRED) - 2026-10-22
- [x] Plan updated for the answers (`PHOTO_REQUIRED`, replay without photo returns original, validation order fixed) - 2026-10-22
- [x] Diagrams (architect): `docs/diagrams/stage-2-time-entry.md` - 2026-10-22
- [x] Plan amended after architect review (rate limit before validation, no `common`↔`auth` cycle, consent/branch pre-check before photo work, no stuck state from 16 h rule → `SHIFT_TOO_LONG`) - 2026-10-22
- [x] Flyway V3 migration (`work_date`, GPS accuracy, skew, lateness, review reasons; one pair per employee per work date) - 2026-10-23
- [x] `GET /api/time` (server time) - 2026-10-23
- [x] `GET /api/time-entries/today` - 2026-10-23
- [x] `POST /api/time-entries` with photo required, order validation and `client_uuid` idempotency - 2026-10-23
- [x] Haversine geofence → VALID / PENDING_REVIEW (+ review reasons) - 2026-10-23
- [x] `StorageService` + `LocalDiskStorageService` (magic bytes, 2 MB cap, re-encode/EXIF strip), authenticated photo endpoints - 2026-10-23
- [x] Lateness from work_schedule + grace_minutes (company time zone) - 2026-10-23
- [x] Dev seed location overrides (`DEV_SEED_LAT/LNG/RADIUS`) - 2026-10-23
- [x] Coder's checks pass: backend 554 tests, 0 failures, 2 skipped (OS-dependent symlink/read-only) - 2026-10-23
- [x] Tester review: 55 tests added, backend 608 (0 failures, 3 skipped); no functional bugs; 1 low plan-order deviation (429 after 415, disabled `knownBug_` test); all 11 coder deviations judged acceptable - 2026-10-23
- [x] Security audit: 0 Critical/High, 2 Medium, 6 Low, 7 Info (`securityAudit-report/stage-2-time-entry.md`) - 2026-10-23
- [x] Audit fixes agreed: fix M-1, L-1, L-2, L-3, L-5 now (after tester); L-4 → Stage 4 admin review; L-6 → Stage 5 - 2026-10-23
- [x] **Note (M-2):** user chose to keep local JDK 25.0.1 for now (about 4 security updates behind). Must update the JDK and pin a patched version for production — tracked in Stage 5 - 2026-10-23
- [x] Audit fixes applied: M-1 (header pre-check, 2560 px / 6 MP, decode semaphore → 503 `SERVER_BUSY`), L-1 (lazy multipart + rate-limit filter before parsing; 429 before 415), L-2 (private multipart temp dir), L-3 (real PostgreSQL `lock_timeout` → 503), L-5 (absolute storage root + static-location guard). Backend 626 tests, 0 failures, 2 skipped - 2026-10-26
- [x] Independent re-run of the full suite: 626 run, 0 failures, 0 errors, 2 skipped - 2026-10-26
- [x] End-to-end check on the user's running server (V3 auto-migrated): consent 403, `PHOTO_REQUIRED` EN/ES, fake photo `INVALID_PHOTO`, 3.5 MB → 413 on real Tomcat, VALID clock-in, replay with/without photo → same entry, `ALREADY_CLOCKED_IN`, today states, photo download headers + no metadata, other employee 404, admin route 200/403, outside-geofence clock-out → PENDING_REVIEW, no temp leftovers, `uploads/` ignored by git - 2026-10-26
- [x] Committed and pushed to `stage1-2` - 2026-10-26

## Stage 3 - Employee PWA

- [x] Stage plan written (`docs/plans/stage-3-employee-pwa.md`) - 2026-10-26
- [x] Open questions answered (token in sessionStorage; distance shown only after clocking, no backend change; offline clocking without session allowed; consent v1: 12 months retention, requests to company admin, pending legal review) - 2026-10-26
- [x] Plan updated for the answers (no branch coordinates; readiness chip before, server verdict after; consent v1 text) - 2026-10-26
- [x] UI design (designer): `docs/design/stage-3-employee-pwa.md` — tokens with verified AA contrast, wireframes for all screens/states, component inventory, ES/EN key tables, a11y notes; section 8 lists 7 plan conflicts (default: adopt the spec's resolution) - 2026-10-26
- [x] Coder started on Stage 3 (plan + design spec; spec section 8 resolutions adopted) - 2026-10-27
- [x] Backend: `POST /api/auth/logout` (bump `token_version`) + `LogoutIntegrationTest` - 2026-10-27
- [x] Login screen with PIN keypad - 2026-10-27
- [x] Consent screen (Ley 8968, v1 `2026-10-v1`) - 2026-10-27
- [x] Main screen, 3 states (antes de marcar / en turno / jornada completa) - 2026-10-27
- [x] Selfie capture + on-device compression (baseline JPEG) - 2026-10-27
- [x] Geolocation readiness chip + server verdict/distance after clocking - 2026-10-27
- [x] IndexedDB offline queue, flushed on reconnect; re-auth dialog keeps queued entries - 2026-10-27
- [x] PWA update prompt, strict CSP + Trusted Types, `dev:phone` HTTPS script, README phone steps - 2026-10-27
- [x] Coder's checks pass (re-verified): frontend lint + build + 532 tests (42 files); backend 632 tests, 0 failures, 2 skipped - 2026-10-27
- [x] Tester review: +34 tests (frontend 566 in 43 files, backend 632); fixed `Retry-After: 0` hot loop and "Jornada completa" persisting past local midnight - 2026-10-27
- [x] Security audit: `securityAudit-report/stage-3-employee-pwa.md` — 0 Critical, 0 High, 1 Medium (previous employee's results kept in memory after logout/switch), 4 Low, 6 Info - 2026-10-27
- [x] Security fixes (M-1, L-1, L-2, L-4, I-1, I-6; L-3 documented; flush-guard race closed) — re-verified: frontend lint + build + 595 tests (44 files) twice; `dist/sw.js` has no `importScripts` - 2026-10-28
- [ ] Consent text: mention 7-day on-device retention of unsent entries (pending legal review)
- [x] Manual test by user in desktop Chrome (phone emulation, DevTools GPS sensor, webcam selfie, offline queue, ES/EN, logout, location blocked): all passed - 2026-10-28
- [ ] Real-phone check (camera, real GPS, install + cold-start offline, SW under Trusted Types) — deferred: LAN access to this PC is blocked by the corporate endpoint security; do it via USB port-forward or in Stage 5 on the real HTTPS server
- [x] Commit + push to `stage1-2` - 2026-10-28

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
- [ ] Stage 2 audit M-2: update JDK 25 (local is 25.0.1, kept by user decision 2026-10-23) and pin a patched JDK for production
- [ ] Stage 2 audit L-6: log and rate-limit photo access
- [ ] Spring Boot 3.5 → 4.x migration (3.5 OSS support ended 2026-06-30)
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
