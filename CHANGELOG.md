# Changelog

## [2026-10-27]

### Added

- Stage 3 employee PWA (`docs/plans/stage-3-employee-pwa.md`, design in `docs/design/stage-3-employee-pwa.md`): login with an 8-digit PIN keypad, the Ley 8968 consent screen (text version `2026-10-v1`, pending legal review), and the main screen with the single clock button, server clock, status line, worked time, entry times, review badges and late minutes (EN/ES).
- Selfie capture (front camera with a file-input fallback, compressed on the phone to about 1024 px baseline JPEG with a header check), best-fix GPS within 20 s, readiness chip before sending and the server's geofence result after. The phone never computes a distance.
- IndexedDB outbox (`idb`): every clock action is queued and sent strictly FIFO, one request at a time, with `deviceSentAt` set at send time and the same `clientUuid` on retries (200 and 201 both succeed), backoff, `Retry-After`, a Web Lock against two tabs, at most 8 unsent entries and a 5 MB free-space check. Final 4xx errors become failure cards and drop the photo at once. Cold open offline with a stored identity can still queue entries.
- Session handling: token in `sessionStorage`, non-secret identity in IndexedDB, PIN never stored; PIN re-auth dialog on 401 or expiry (resends with the same `clientUuid`); sign-out always confirms, warns about unsent entries and wipes the device; one identity per device.
- Server clock with a monotonic base, suspend/clock-change detection and "(aprox.)" after an offline reload; dates and times in the company time zone.
- `POST /api/auth/logout` (bumps `token_version`, ends every session of that employee) with `LogoutIntegrationTest` (6 tests).
- PWA update prompt (`registerType: 'prompt'`, never during a capture or a flush), no caching of API responses or photos.
- Security headers in `frontend/securityHeaders.ts` (strict CSP, `Permissions-Policy`, `Referrer-Policy`, `nosniff`, COOP) enforced by `vite preview`; dev gets the same without the CSP; Trusted Types `default` policy that only allows `/sw.js`; lint bans `react/no-danger`, `no-eval`, `no-new-func`.
- `npm run dev:phone` and `npm run preview:phone` (HTTPS on the LAN with `@vitejs/plugin-basic-ssl`).
- Frontend tests: 532 now (API, storage, flush, session, clock, capture, pages, accessibility, drift guards against the Java enums, bundle budget, CSP and built-output checks). New dev dependencies: `@vitejs/plugin-basic-ssl`, `fake-indexeddb`, `workbox-window`; new dependency: `idb`.
- README: "Employee PWA", "Security headers", "Testing on a phone", the logout endpoint and the new scripts.

### Fixed

- Stage 3 audit M-1 (`securityAudit-report/stage-3-employee-pwa.md`): `OutboxProvider` drops its per-session state (items, last results, last delivered entry, network flag) and cancels its backoff timer whenever the signed-in employee changes (logout, "not you", sign-in as someone else), so the next person never sees or hears the previous employee's result. A POST that finishes after the person signed out is discarded, `useToday` ignores and never saves a response for a different employee, and an account switch clears the previous employee's IndexedDB snapshot. The per-page announcer is already disposed with the page.
- Audit L-2: the local wipe retries each step (3 attempts), logs failures without personal data, returns whether it completed and sets `attendance.wipePending`; the next start repeats the wipe and restores no identity until it succeeds. The flush re-checks before every send that the item's employee (and company code, when stored) matches the identity the token belongs to, and otherwise sends nothing. The backoff timer of the previous session is cancelled on sign-out.
- Audit L-4: unsent items older than 7 days (`OUTBOX_MAX_AGE_MS`) are expired at app open and before each flush: photo and coordinates are dropped, the item becomes a failure card with its own EN/ES message. Final failures now drop the coordinates as well as the photo.
- Audit I-1: `inlineWorkboxRuntime: true`, so `sw.js` has no `importScripts` and works under Trusted Types with the CSP unchanged.
- Flush guard race (tester): a `flushOnce` call that arrives while a run is finishing now asks the run to look at the queue once more instead of returning idle, so an entry enqueued at that moment is not left waiting.
- `Retry-After: 0` no longer causes a hot loop: the retry wait is at least 1 second.
- "Jornada completa" no longer stays on screen after local midnight in the company time zone (a network answer is also refreshed against the local date).

### Security

- Audit L-1: signing out, "¿No es usted?" and signing in as another employee no longer delete anyone's unsent entries. They stay on the phone, isolated by employee, and are sent when that employee signs in again (or expire after 7 days). The sign-out and switch dialogs (EN/ES) now say so and are no longer destructive.
- Audit I-6: `workbox-window` moved from `devDependencies` to `dependencies` (it ships in the bundle, so `npm audit --omit=dev` covers it).
- Audit L-3: README "Testing on a phone" warns that `dev:phone` exposes the dev stack to the LAN and recommends USB port forwarding to `localhost` (`adb reverse`). The rate limiter is unchanged.

### Added (tests)

- 29 new frontend tests (595 in total): session isolation across logout and account switch, an in-flight POST after sign-out, per-employee queue retention, the stale backoff timer, send-time identity checks, the flush guard race, the 7-day expiry (storage, flush and app open), coordinates dropped on failure, wipe retry, flag and logging, and the `pwaConfig` checks for the inlined runtime and `workbox-window`. Existing sign-out, switch and PIN dialog tests now expect that entries are kept.

### Changed

- The sign-out and employee-switch dialogs use the default (non-destructive) style; the "Cerrar sesión y borrar" / "Continuar y borrar" actions are now "Cerrar sesión" / "Continuar". `signOut()` and `performLogout()` return whether the wipe completed.
- `/` is the employee app behind a session guard (`/login`, `/consent`); `HealthStatus` moved from the employee screen to `/admin`, which is now lazy-loaded.
- `AppHeader` shows a sign-out button when an identity exists; layout tokens, focus rings and reduced-motion rules in `index.css` (WCAG 2.2 AA).
- `vite.config.ts` is a function of the mode; `assetsInlineLimit: 0`; the PWA options are exported for tests and use the new workbox settings (`globPatterns`, `navigateFallback`, `cleanupOutdatedCaches`, no `runtimeCaching`).
- The i18n no-hardcoded-text test now also scans `.ts` modules; the JSX text pattern still applies to `.tsx` only.

## [2026-10-23]

### Fixed

- Stage 2 audit M-1 (`securityAudit-report/stage-2-time-entry.md`): photo decoding is bounded. Header-only pre-check refuses 16-bit and interlaced PNG and progressive JPEG (the PWA sends baseline JPEG); limits lowered to 2560 px per side and 6 MP (`app.storage.max-input-dimension`, `max-input-pixels`); a process-wide decode semaphore (`max-concurrent-decodes`, default 3) returns 503 `SERVER_BUSY` (EN/ES, `Retry-After`) when saturated.
- Audit L-1: `spring.servlet.multipart.resolve-lazily: true`, and the per-employee POST rate limiter moved from an interceptor to a filter in the Spring Security chain, so 429 comes before 415/400 and before multipart parsing.
- Audit L-2: multipart uploads are spooled to a private temp directory (`<storage root>/.upload-tmp` or `app.storage.temp-dir`), emptied at startup. The photo file is deleted only on rollback; an unknown commit outcome keeps it. The orphan-file sweeper stays in Stage 5.
- Audit L-3: the employee row-lock wait is really bounded on PostgreSQL (`lock_timeout` set per transaction from `app.time-entry.lock-timeout`, default 5s); a timeout returns 503 `SERVER_BUSY`.
- Audit L-5: the storage root is resolved to an absolute normalized path at startup, and startup fails if it is inside a static-resource location (`src/main/resources`, `webapp`, `static`, `public`, `META-INF/resources`, `target/classes`, configured `file:` locations).
- Deferred: M-2 (JDK update, user decision, Stage 5), L-4 (Stage 4), L-6 (Stage 5).

## [2026-10-22]

### Added

- Stage 2 check-in API (`docs/plans/stage-2-time-entry-api.md`): `GET /api/time`, `GET /api/time-entries/today`, `POST /api/time-entries` (multipart; 201 created, 200 idempotent replay), `GET /api/time-entries/{id}/photo` (own photos) and `GET /api/admin/time-entries/{id}/photo` (admin, own company; other tenants get 404).
- Stage 2 rules: company-local `work_date` (overnight shifts inherit the CLOCK_IN date), 16 h shift window (`SHIFT_TOO_LONG` review instead of rejection; a forgotten earlier day never blocks today), Haversine geofence, `deviceSentAt`-based late-sync and clock-skew review reasons, GPS accuracy, lateness stored at CLOCK_IN, mandatory selfie (`PHOTO_REQUIRED`), consent and branch checks, per-employee row lock, and a per-employee POST rate limit that runs before validation.
- Stage 2 photos: new `storage` package (`StorageService`, `LocalDiskStorageService`, `PhotoProcessor`): magic-byte check (JPEG/PNG), 2 MB cap, dimension guard, server-side re-encode to JPEG without EXIF, keys `{companyId}/{yyyy-MM}/{uuid}.jpg` under `UPLOADS_DIR` (default `backend/uploads`), path-traversal and symlink guards, file deleted on rollback.
- Stage 2 schema: Flyway `V3__time_entry_checkin.sql` (`work_date`, `skew_seconds`, `late_minutes`, `gps_accuracy_meters`, `review_reasons`, unique `(employee_id, work_date, type)`, evidence constraints; 45 constraints and 7 `ix_*` indexes in total).
- Stage 2 errors (EN/ES): `CONSENT_REQUIRED`, `NO_BRANCH_ASSIGNED`, `ALREADY_CLOCKED_IN`, `NOT_CLOCKED_IN`, `SHIFT_ALREADY_COMPLETED`, `CLIENT_UUID_CONFLICT`, `PHOTO_REQUIRED`, `INVALID_PHOTO`, `PAYLOAD_TOO_LARGE`; malformed multipart gives 400 `INVALID_REQUEST`; unexpected errors give a fixed 500 `INTERNAL_ERROR` body.
- Stage 2 dev seed: optional `DEV_SEED_LAT`, `DEV_SEED_LNG`, `DEV_SEED_RADIUS` (also update an already-seeded branch). `.env.example` has `UPLOADS_DIR` and these keys, empty.
- Stage 2 tests: pure rules, storage, photo processing, and MockMvc integration tests with a shared mutable clock.

### Changed

- Stage 2: `TooManyAttemptsException`, `InvalidCredentialsException` and `InvalidPinException` moved from `auth` to `common`, so `common` no longer depends on feature packages (guarded by `CommonPackageDependencyTest`).
- Stage 2: `TimeEntry` gained the V3 columns and a constructor with `workDate`; `SeedProperties` gained the optional branch location; `TestData` helpers and the schema tests were updated for `work_date` and the new constraint counts.
- Stage 2: multipart limits (`max-file-size` 3MB, `max-request-size` 4MB), Tomcat `max-part-count` 10 and `max-swallow-size` 5MB.

### Changed

- Stage 1b (`docs/plans/stage-1b-english-naming-i18n.md`): every technical name is now English. Root package `cr.asistencia` -> `cr.attendance` (`AttendanceApplication`, Maven `attendance-backend`); subpackages `employee`, `company`, `timeentry`. Mapping: `docs/naming-inventory.md`.
- Database (`V2__initial_schema.sql`, edited in place because it was never deployed): tables `company`, `branch`, `employee`, `work_schedule`, `time_entry` (was `marca`), `evidence`, `adjustment`, with English columns, constraints and indexes. Stored enum values are `EMPLOYEE`, `CLOCK_IN`/`CLOCK_OUT`, `MOBILE`, `VALID`/`PENDING_REVIEW`/`REJECTED`, `PHOTO`. `time_entry.status` is `varchar(20)` and `distance_meters` replaces `distancia_m`. The database `asistencia` and role `asistencia_app` keep their names.
- API: `POST /api/me/consent`, `GET /api/admin/employees[/{id}]`; JSON fields `companyCode`, `employeeCode`, `expiresAt`, `employee`, `company`, `code`, `name`, `jobTitle`, `role`, `consentPending`, `active`, `branchId`, `timeZone`.
- JWT claims `companyId` and `role`, issuer `attendance-backend`, authority `ROLE_EMPLOYEE`. Config keys `max-attempts`, `duration`, `window`, `ttl-employee`.
- The PWA manifest stays Spanish (`es-CR`).
- Backend tests: 335 (278 existing, renamed, plus 57 new). Frontend tests: 52.

### Added

- Bilingual (Spanish/English) API errors: `messages.properties` (Spanish base), `messages_es.properties`, `messages_en.properties`; the language comes from `Accept-Language` (supported `es`, `en`; default `es`, also for `*` and invalid headers); responses carry `Content-Language`. `ErrorCode` enum; `code` stays English and stable.
- `InvalidPinException`, mapped to 400 `INVALID_PIN` (localized).
- Frontend i18n without new runtime dependencies: typed `es`/`en` resources, `I18nProvider`, accessible `LanguageSwitcher` in a shared header (native buttons, `aria-pressed`, polite announcement), choice saved in `localStorage`, `<html lang>` and the page title kept in sync, `Accept-Language` sent on every API call through `apiFetch`.
- `@testing-library/user-event` (dev dependency) for keyboard tests.
- `scripts/db/reset-dev-schema.sql` to reset the dev schema once after the in-place `V2` edit (see README).
- README section "Languages".
- Stage 1b test coverage: English schema naming and enum CHECK constraints, rename regression tests (legacy `empresaId`/`rol` tokens and old URLs rejected), `Accept-Language` edge cases and localized 429. Frontend tests cover the provider, the no-hardcoded-text scan and app-level language switching. Totals: backend 398, frontend 83.

### Removed

- Spanish API error codes (`CREDENCIALES_INVALIDAS`, ...), JSON field names, URLs and JWT claim names. Tokens issued before this change (`empresaId`/`rol` claims) are rejected with 401; users log in again.

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
