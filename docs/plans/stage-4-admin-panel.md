# Stage 4 - Admin panel

Roadmap: `docs/plans/00-roadmap.md` (Stage 4). Earlier plans, read with their RESOLVED sections and Amendments taking precedence:
- `docs/plans/stage-1-data-model-auth.md`
- `docs/plans/stage-1b-english-naming-i18n.md`
- `docs/plans/stage-2-time-entry-api.md` (plus its "Audit remediation (2026-10-23)")
- `docs/plans/stage-3-employee-pwa.md`

Diagrams: `docs/diagrams/stage-1-er.md`, `docs/diagrams/stage-2-time-entry.md`. Audits with items deferred to this stage: `securityAudit-report/stage-1-auth.md` (M-3, I-1, I-2), `stage-2-time-entry.md` (L-4, L-6, I-3) and `stage-3-employee-pwa.md` (I-4). Blueprint: `docs/prototype/Prototipo-Control-Asistencia.pdf`, page 2 ("Panel del administrador").

Branch: `stage1-2`. Status: PLANNED 2026-10-28. Open Questions are pending (last section). No code has been written.

## Summary

Stage 4 adds the admin web panel that page 2 of the blueprint shows, plus the management screens and the payroll export. The plan splits it into three sub-stages, each sized for one coder pass:

- **4a: dashboard, evidence and review.** This is the core.
  - Migration V4.
  - The `/admin/*` shell, lazy-loaded.
  - The daily dashboard: date picker, 4 KPI cards, and an employee table with day-status chips.
  - The evidence viewer: photo, distance, a map link and the adjustment history.
  - The review queue with approve / reject / correct.
  - Admin-created entries (missing clock-out, broken camera, lockout).
  - Every change writes `adjustment` rows, which are append-only and enforced by a trigger.
  - Photo access is audited and throttled (Stage 2 L-6, moved forward).
- **4b: management.** Branches (name, lat/lng, radius, active), employees (create with a generated PIN shown once, edit, role, branch, activate/deactivate, PIN reset, unlock) and weekly work schedules. Each admin action is written to an append-only `audit_log`.
- **4c: Excel export.** `GET /api/admin/reports/excel?from&to[&branchId]` with Apache POI. It has four sheets: Summary, Detail, Adjustments and Info. Hours are payroll-oriented: approved hours are kept separate from pending hours. Text cells are protected against formula injection.

Three core rules:
1. **`server_time` is never changed.** V4 adds `time_entry.effective_time`, which equals `server_time` until an admin corrects it.
2. **Every computation an admin or payroll sees reads `effective_time`.** That covers worked minutes, lateness, the dashboard, the export and the PWA's worked time.
3. **The Stage 2 sequence rules keep using the receipt time.** These are the open-shift window and the 16 h rule.

When an admin corrects a CLOCK_IN, `late_minutes` is recomputed from the corrected time.

Everything reuses the established patterns:
- tenant-scoped `Repository<T, Long>` methods with `companyId` taken from the token
- load-then-mutate for updates (Stage 1 I-2)
- ProblemDetail with stable English `code`s and EN/ES texts
- the injected `Clock`
- the company time zone for every date
- zonky + MockMvc tests

A second authorization layer, `@EnableMethodSecurity` with `@PreAuthorize("hasRole('ADMIN')")` on every admin controller, closes Stage 1 I-1.

On the frontend:
- The admin code lives in `src/admin/` and is built as its own `admin` chunk, which is **excluded from the service worker precache**. Its EN/ES text lives in `src/i18n/adminResources.ts`. So the employee PWA bundle and its precache do not grow.
- The admin UI is desktop-first, works on tablets, follows WCAG 2.2 AA, and keeps the strict CSP and Trusted Types.
- Photos and the `.xlsx` file are fetched with the bearer token as blobs.

**Recommended order: 4a, then 4b, then 4c.**

## Context: what exists today

| Area | State after Stage 3 |
|---|---|
| Schema | V1-V3. These tables already exist: `adjustment (company_id, time_entry_id, admin_id, reason, field, old_value, new_value, created_at)`, `time_entry.status` with `REJECTED` reserved, `ix_time_entry_company_work_date`, and the partial `ix_time_entry_pending_review (company_id)`. `branch` has no `active` column. `time_entry.source` is one of `MOBILE`/`TABLET`/`QR`. |
| Admin API | `GET /api/admin/employees`, `GET /api/admin/employees/{id}` (`EmployeeSummary`, read-only) and `GET /api/admin/time-entries/{id}/photo`. URL rule `/api/admin/** -> hasRole("ADMIN")`. No method security. |
| Entities | Flat entities with no setters. `TimeEntry.withEvidence(...)` is used only at creation. `Adjustment` has a full constructor, and `AdjustmentRepository` has `save` plus scoped finds. |
| Rules | `ShiftResolver` (open shift, 16 h, `SHIFT_TOO_LONG`), `LatenessCalculator`, `ReviewPolicy`, `TodayService` (`workedMinutes` from `server_time`), and `PhotoService` (same 404 for every miss, no audit). |
| Auth | Login is company code + employee code + PIN. ADMIN tokens last 6 h with no refresh. Every request re-checks `active`, `token_version` and the role in the database. `PinPolicy` exists, and `EmployeeRepository.incrementTokenVersion` exists. |
| Seed | `DEMO` with 6 employees (1001-1006) and admin 9001, who has no branch and so cannot clock in. Mon-Fri schedules with a 5 min grace. |
| Frontend | React 19 + react-router 8. `/admin` renders a lazy `AdminHome` placeholder. `apiRequest` supports GET/POST only. Session: token in `sessionStorage`, identity (including `role`) in IndexedDB. `LoginPage` always goes to `/consent` or `/`. `i18n/resources.ts` is a single typed file. The bundle budget test counts **all** JS and CSS and the whole precache. |

## Verified against official sources (2026-10-28, WebFetch / WebSearch)

| Item | Finding | Consequence |
|---|---|---|
| Apache POI downloads + changes page | **6.0.0** was released 2026-10-22 and requires JDK 17. It removes deprecated APIs and changes `DataFormatter` and SXSSF row-count behaviour. The latest 5.x is **5.5.1** (2025-11-30, bug fixes plus dependency security updates, JDK 8+). 5.3.0+ deletes SXSSF temp files on `close()`. | Default **`org.apache.poi:poi-ooxml:5.5.1`**: stable, and a 6-day-old major is avoided (Open Question 12). Both run on Java 21. POI is not Boot-managed, so the version goes in a `<poi.version>` property. |
| POI security (web search) | CVE-2025-31672 (duplicate zip entry names when *reading* OOXML) is fixed in 5.4.0+. No CVE found for 5.5.1. | We only **write** workbooks and never parse uploaded files, so the parser attack surface is unused. The Stage 5 dependency scan covers POI and its transitive `commons-compress` / `xmlbeans`. |
| POI `CellStyle` javadoc | `setQuotePrefixed(boolean)` exists: "Turn on or off 'Quote Prefix' or '123 Prefix' for the style." | The formula-injection guard uses a quote-prefixed text style. No visible apostrophe is added, and the cell stays text even after editing in Excel. |
| OWASP CSV Injection | Trigger characters are `=`, `+`, `-`, `@`, tab, CR and LF, plus the full-width `＝ ＋ － ＠`. Mitigations are to prefix the cell or quote it. Quote-based fixes can break after a save/reopen in Excel. | `ExcelText.sanitize`: strip XML-illegal control characters, always write `setCellValue(String)` (never `setCellFormula`), and apply the quote-prefixed style to any text whose first character is a trigger. A test scans every cell. |
| Vite 8 build options | `build.rollupOptions` is a **deprecated alias** of `build.rolldownOptions`. `cssCodeSplit` defaults to `true`, so CSS imported by lazy chunks stays in separate files. | Use `build.rolldownOptions`. Admin CSS goes in the admin chunk's CSS file. |
| Rolldown `output.codeSplitting` | `codeSplitting: { groups: [{ name, test }] }` groups modules into a named chunk. `manualChunks` still exists. | `groups: [{ name: 'admin', test: /[\\/]src[\\/](admin[\\/]\|i18n[\\/]adminResources)/ }]` produces `assets/admin-<hash>.js`. The coder confirms the option against the installed Vite/Rolldown version and falls back to `manualChunks` if needed. |
| Costa Rica Código de Trabajo, arts. 136-139 (web search, secondary sources) | Ordinary day 8 h / 48 h per week; night 6 h / 36 h; mixed 7 h / 42 h; a 10 h day can be agreed in some cases; overtime is paid at +50%. Sources differ on details, and a reform is pending. | **The export does not classify overtime.** It gives worked minutes, scheduled minutes and "minutes beyond schedule" as information only (Open Question 9). |

Not verified online (stable, well known; the coder re-checks while implementing):
- `@EnableMethodSecurity` / `@PreAuthorize` in Spring Security 6.5, which is Boot-managed. This is the lambda DSL and is Security 7-compatible.
- The workbox-build `globIgnores` option through vite-plugin-pwa 2.0.
- PostgreSQL `BEFORE UPDATE OR DELETE` / `BEFORE TRUNCATE` triggers with `RAISE EXCEPTION`.
- `XSSFWorkbook` in-memory limits.

## Scope and sub-stages

| Sub-stage | Contents | Migration | Depends on |
|---|---|---|---|
| **4a** dashboard + evidence + review | V4; `effective_time` semantics; `/admin/*` shell; admin login landing; dashboard API + UI; evidence detail + viewer; review queue + approve/reject/correct; admin-created entries; `adjustment` + `audit_log` append-only; photo access audit + limiter; method security; PWA shows the effective time; demo-day seed | **V4** (all Stage 4 schema, including `branch.active`, so the data model is settled once for the architect) | Stage 3 |
| **4b** management | Branch CRUD + activate/deactivate; employee create (generated PIN), edit, role, branch, activate/deactivate, PIN reset, unlock, lock indicator; weekly schedule editor; `audit_log` rows for each | none | 4a (shell, audit_log, method security) |
| **4c** export | Apache POI `.xlsx` (Summary, Detail, Adjustments, Info), localized headers, injection guard, limiter, audit row; Reports page | none | 4a (worked-time rules) |

**Why this order:** 4a carries the risky decisions: the V4 schema, correction semantics and the KPI definitions. It also gives the demo the user sees on page 2. 4b is plain CRUD on a settled model. 4c reads the same worked-time rules as the dashboard, so it comes last and reuses `DayAssembler`.

**Out of scope** (later stages or "Later"):
- tablet kiosk / `TABLET` source capture
- QR
- multiple shifts per day
- legal overtime classification
- salary amounts
- the SICERE file format
- company settings (name, time zone)
- consent text versioning (Stage 5)
- photo retention job (Stage 5)
- lockout alerting (Stage 5; M-3 gets the unlock + indicator here)
- Stage 3 I-4 `photoSource=camera|file` (needs PWA + API changes; noted for Stage 5)
- bulk approve (see Edge Cases)

## Decisions

### Admin login, routing and the admin's own clocking

- **One login page.** Admins log in exactly like employees: company code + employee code + PIN, and the server returns `role = ADMIN`.
  - `LoginPage` sends an ADMIN to `next` when it is a safe internal path (it must start with `/admin` and must not start with `//`), otherwise to `/admin`.
  - It sends an EMPLOYEE to `/consent` or `/` as today.
- **Consent.** An ADMIN is **not** sent to `/consent` after login. Consent is only needed to clock in, and `RequireSession` on `/` still asks for it when the admin opens their own clock screen.
- **`RequireAdmin` guard on `/admin/*`:**
  - no identity, or no usable token: go to `/login?next=<path>`
  - `identity.role !== 'ADMIN'`: go to `/`
  - The server is the real control (URL rule + `@PreAuthorize` + per-request DB role check). The guard only avoids rendering a panel that would get 403s.
- **Admin token (6 h, no refresh).** A 401 on any admin call sets `needsReauth` (existing `markNeedsReauth`).
  - `AdminReauthDialog` opens over the current page: codes read-only, plus the `PinKeypad` (reused).
  - Form state on the page is kept. After re-auth the user repeats the action, so the request is never auto-replayed: admin writes are not idempotent.
  - "Not you?" goes to logout.
- **The admin's own clocking.** An ADMIN with a branch clocks in from `/` like anyone else (Stage 2). The admin header has a "Mi marca / My clock" link to `/`.
  - 9001 in the seed has no branch, so the employee screen shows `NO_BRANCH_ASSIGNED` with the existing `/admin` link.
  - In 4b an admin can give themselves a branch.
  - Admins with a branch appear on the dashboard like any employee. Admins without a branch are not listed: they cannot have entries.
- **Desktop-first.** The layout targets 1280 px and must work down to 768 px (tablet, as the mockup says).
  - Below 768 px the table becomes a stacked list. That is usable on a phone, but not optimized.
  - Top header: company name, branch filter, "Mi marca", language, logout.
  - Left nav, which collapses to a top tab bar below 1024 px: Dashboard, Review (with a pending count badge), Employees, Branches, Reports.

### Time semantics: effective time, corrections, lateness, worked minutes

- **`server_time`** stays the immutable receipt time and the legal evidence of when the server received the entry. **`effective_time`** (new, NOT NULL) is the time that counts.
  - Every insert sets `effective_time = server_time`.
  - Admin corrections change only `effective_time`.
  - Admin-created entries get `server_time = now` (when the admin acted) and `effective_time` = the time the admin entered.
- **Readers of `effective_time`:**
  - dashboard
  - review queue
  - evidence detail
  - export
  - `TodayService.workedMinutes`
  - the new `EntrySummary.effectiveTime`, which the PWA shows
  - `LatenessCalculator` on correction
- **Readers of `server_time`:** `ShiftResolver` (open shift, 16 h window, `SHIFT_TOO_LONG`), idempotency and replay, the photo storage month, and `skew_seconds`. A correction never re-opens or closes a shift.
- **`work_date` is never changed by a correction.** It is the shift's identity: the unique index and the pairing depend on it. An entry on the wrong day is rejected, and the right one is created on the correct `work_date`.
- **Correction window** (`CorrectionRules`, a pure function, unit-tested). The admin types a company-local date-time `YYYY-MM-DDTHH:mm`, and the server converts it with `ZoneId.of(company.time_zone)` (`atZone`; in a DST overlap the earlier offset is used, and Costa Rica has no DST). The result must be:
  - not later than `now + 1 min` (no future times)
  - for a CLOCK_IN: between `work_date 00:00 local - 12 h` and `work_date 24:00 local` (this covers shifts that start just before midnight)
  - for a CLOCK_OUT: strictly after the pair's CLOCK_IN `effective_time`, and at most `app.admin.max-shift-hours` (default **24 h**) after it
  - a CLOCK_IN correction must stay before an existing CLOCK_OUT's `effective_time`
  - Any violation gives 400 `INVALID_ENTRY_TIME`.
- **Lateness after a correction.** When a CLOCK_IN's `effective_time` changes, `late_minutes` is recomputed with `LatenessCalculator.lateMinutes(effectiveTime, workDate, zone, scheduleNow)`.
  - It uses the **current** schedule for that ISO weekday. Schedules are not versioned; this is documented.
  - The change of `late_minutes` gets its own `adjustment` row (`field = late_minutes`).
- **Worked minutes for a day:** `floor((out.effective_time - in.effective_time) / 60 s)` when both exist and neither is `REJECTED`; otherwise `null`.
  - While a shift is in progress on the selected date (today only), the dashboard shows "en turno" and no number. `workedMinutes` is null.
  - **Approved minutes** (payroll; Stage 2 L-4 recommendation 3) count only when **both** entries are `VALID`, either originally or after approval.
  - **Pending minutes** are days where at least one entry is `PENDING_REVIEW`.
  - Rejected days count 0 and are listed.
- **Status transitions** (entry level):

| Action | Allowed from | Result | Adjustment rows |
|---|---|---|---|
| Approve | `PENDING_REVIEW`, `REJECTED` (undo) | `VALID` | `status` |
| Reject | `PENDING_REVIEW`, `VALID` (spot check, L-4) | `REJECTED` | `status` |
| Correct time | any | `effective_time` changed. The status becomes `VALID` if it wasn't (correcting means the admin reviewed it). | `effective_time`, plus `late_minutes` (CLOCK_IN, if changed), plus `status` (if changed) |
| Create entry | (no entry of that type for `(employee, work_date)`) | new `VALID` row, `source = ADMIN` | `entry` (old NULL, new e.g. `CLOCK_OUT 2026-10-07T17:00-06:00`) |

  - `review_reasons` are **kept** after approval, so the export can show "approved, was OUTSIDE_GEOFENCE".
  - A no-op (correcting to the same minute, or approving a `VALID` entry) gives 409 `INVALID_STATUS_CHANGE` and writes nothing.
- **A reason is required for every action**: trimmed, 5-500 characters. The UI offers quick-pick presets in the admin's language, for example "Verificado con la foto", "Autorizado por la jefatura" or "Olvidó marcar la salida", plus free text. The stored text is whatever was submitted.
- **Concurrency:**
  - Every write request carries `expectedStatus` and, for corrections, `expectedEffectiveTime`.
  - The service locks the `time_entry` row (`PESSIMISTIC_WRITE` through a new scoped repository method) with the same `SET LOCAL lock_timeout` as `TimeEntryWriter`, then compares. A mismatch gives 409 `ENTRY_CHANGED`, and the UI reloads the item.
  - Create-entry locks the **employee row**, the same lock as the PWA POST, so it serializes with the employee's own clocking. The unique index `(employee_id, work_date, type)` is the backstop and maps to 409 `ENTRY_ALREADY_EXISTS`.

### Dashboard: day status and KPIs (mockup page 2)

`GET /api/admin/dashboard?date=YYYY-MM-DD[&branchId=]`. `date` defaults to "today" in the company zone. A future date is allowed (all rows show `NOT_YET_DUE` / `DAY_OFF`).

**Rows.** One row per employee who is active with a branch, plus any employee (even inactive, or moved since) with an entry on `date`.
- The branch filter matches the entry's `branch_id` when an entry exists, otherwise the employee's current branch.
- Rows are sorted by name.
- There is no pagination; the response is capped at `app.admin.max-dashboard-rows` (default 1000), see Pagination.

**`DayStatus`** (server-computed by `DayStatusResolver`, so the dashboard and the export agree). The first match wins:

| # | Condition | `DayStatus` | ES chip | EN chip |
|---|---|---|---|---|
| 1 | an entry of the day is `REJECTED` | `REJECTED` | Rechazada | Rejected |
| 2 | an entry is `PENDING_REVIEW` with `OUTSIDE_GEOFENCE` | `OUTSIDE_AREA` | Fuera del área | Outside the area |
| 3 | an entry is `PENDING_REVIEW` (other reasons) | `NEEDS_REVIEW` | Por revisar | Needs review |
| 4 | CLOCK_IN without CLOCK_OUT and (the date is before today, or the CLOCK_IN is older than `max-shift-duration`) | `MISSING_CLOCK_OUT` | Sin salida | No clock-out |
| 5 | CLOCK_IN and CLOCK_OUT | `COMPLETE` | Completo | Complete |
| 6 | CLOCK_IN in progress, `late_minutes > 0` | `LATE` | Tarde {n} min | Late {n} min |
| 7 | CLOCK_IN in progress, `late_minutes = 0` | `ON_TIME` | A tiempo | On time |
| 8 | CLOCK_IN in progress, `late_minutes` null (no schedule) | `UNSCHEDULED` | Sin horario | Unscheduled |
| 9 | no CLOCK_IN, scheduled, `date = today` and now < start + grace | `NOT_YET_DUE` | Aún no inicia | Not started yet |
| 10 | no CLOCK_IN, scheduled | `NOT_CLOCKED` | Sin marcar | Not clocked |
| 11 | no CLOCK_IN, not scheduled | `DAY_OFF` | Libre | Day off |

- A `COMPLETE` (or reviewed) day that was late keeps `lateMinutes` in the row. The table shows it as a secondary line, "Tarde 12 min", under the chip.
- Chips always carry text, never color alone.
- The colors follow the mockup:
  - teal: on time / complete
  - amber: late
  - violet: outside area / needs review
  - red-brown: not clocked / rejected / no clock-out
  - grey: day off / not yet due
- Each color is AA-checked by the designer.

**KPIs**, computed over the rows after the branch filter:

| Card | Value | Definition |
|---|---|---|
| Presentes / Present | `present` "de / of" `expected` | `present` = rows with a non-REJECTED CLOCK_IN. `expected` = rows that are scheduled that day **or** present (`DAY_OFF` rows are excluded). |
| Llegadas tardías / Late arrivals | `late` | non-REJECTED CLOCK_INs with `late_minutes > 0` (in progress, complete or under review) |
| Sin marcar / Not clocked | `notClocked` | rows with `NOT_CLOCKED`. If `notYetDue > 0`, a subtitle "+{n} aún no inician". |
| Por revisar / To review | `toReview` | `PENDING_REVIEW` entries with `work_date = date`. Subtitle: the most frequent reason with its count, for example "1 fuera del área" (from `toReviewByReason`). The card links to the review queue filtered to that date. |

The mockup example (12 people: María on time, José 17 min late, Ana, Luis outside at 420 m, Karla complete 8 h 04, Diego not clocked, …) gives **9 de 12 / 2 / 3 / 1 fuera del área**. This is the 4a integration test fixture and the demo seed.

**Columns** (mockup):
- Empleado: initials avatar, name, job title
- Entrada / Salida: `effective_time` as HH:mm in the company zone; a small "editada / edited" marker when adjusted
- Horas: "en turno" / "8 h 04" / "—"
- Evidencia (a button that opens the evidence viewer):
  - "Foto + GPS"
  - "GPS a {n} m" when outside the area
  - "Solo foto" with no GPS
  - "Registro manual" for `source = ADMIN`
  - "Tablet local" for `TABLET`, reserved
  - "—"
- Estado: chip plus secondary late line

**Header:**
- "{Company} · {Branch or Todas las sucursales}"
- "Asistencia de hoy" or "Asistencia del {date}"
- a date `<input type="date">` labelled "Fecha"
- "Exportar a Excel", which in 4a/4b links to the Reports page with the date pre-filled and in 4c downloads that single day directly

**Refresh.** The dashboard reloads when the date or branch changes, on a "Actualizar" button, on `visibilitychange`, and every 60 s while the page is visible and the date is today. It never prefetches photos.

### Review queue

`GET /api/admin/review-queue?status=PENDING_REVIEW&from&to&employeeId&page&size`:
- `status` defaults to `PENDING_REVIEW`; `REJECTED` is also allowed, to undo.
- `from`/`to` default to the last 31 days in the company zone.
- Sorted oldest `work_date` first, then `effective_time`.
- Paged: `page` (0-based), `size` default 25, max 100.

Each item has:
- the employee (id, code, name, job title)
- the entry: type, `effective_time`, `server_time`, `device_time`, `skew_seconds`, `synced_late`, reasons, distance, accuracy, `hasPhoto`, `source`
- the other entry of the same `work_date` (summary or null), so the admin sees the pair
- `lateMinutes`

UI:
- a list with reason chips and an "Evidencia" button
- three actions per item: **Aprobar**, **Rechazar**, **Corregir hora**
- each action opens `ReviewActionDialog`: reason presets + free text; the corrected time is a `type="datetime-local"` input pre-filled with the current effective time in the company zone
- after success, the item leaves the list and the nav badge count updates
- the nav badge reads `GET /api/admin/review-queue/count`, which counts every `PENDING_REVIEW` entry of the company

### Evidence viewer

`GET /api/admin/time-entries/{id}` returns the full detail for one entry. **This is the only endpoint that returns the exact coordinates** of an entry.
- **Fields:** employee, type, `workDate`, `source`, `serverTime`, `effectiveTime`, `deviceTime`, `skewSeconds`, `syncedLate`, `status`, `reviewReasons`, `distanceMeters`, `gpsAccuracyMeters`, `insideGeofence`, `lateMinutes`, `hasPhoto`.
- **Location:** `location: {latitude, longitude} | null`.
- **Branch:** `branch: {id, name, latitude, longitude, radiusMeters}`, the current branch values, labelled "radio actual".
- **History:** `adjustments: [{action, field, oldValue, newValue, reason, adminName, createdAt}]`.

`EvidenceDialog` (native `<dialog>`, existing `ModalDialog`):
1. **Photo.** Loaded only when the dialog opens: `apiBlob('/api/admin/time-entries/{id}/photo')` gives an object URL in an `<img alt="Selfie de {name}, {entrada|salida} {time}">`. The URL is revoked on close or unmount. CSP `img-src 'self' blob:` already allows this. A missing photo shows "Sin foto" (404 handled).
2. **Facts:** distance vs radius ("420 m del local · radio 100 m"), accuracy, the review reasons translated through the existing `reviewReason.*` keys, device time and skew ("Hora del teléfono 07:55 · 3 min de diferencia"), and "Enviada con retraso" when relevant.
3. **Map link** (Open Question 6, default OpenStreetMap): "Ver en el mapa" → `https://www.openstreetmap.org/?mlat={lat}&mlon={lng}#map=18/{lat}/{lng}` with `target="_blank" rel="noopener noreferrer"`.
   - It is never embedded: no third-party iframe, script or tile. CSP, `connect-src` and `img-src` are unchanged.
   - The coordinates are rounded to 6 decimals.
   - A second link shows the branch's position for comparison.
4. **Adjustment history** (read-only list) and, when the user is allowed, the same three actions as the queue.

### Admin-created entries

`POST /api/admin/time-entries` `{employeeId, type, workDate, localTime: 'YYYY-MM-DDTHH:mm', reason}`. The UI offers it from a dashboard row ("Registrar entrada/salida" on `NOT_CLOCKED` / `MISSING_CLOCK_OUT` rows) and from the evidence viewer of a lone CLOCK_IN.
- The employee must belong to the company (404 otherwise). It may be inactive (historical fix).
- The employee must have a branch now (409 `NO_BRANCH_ASSIGNED`). `branch_id` is that branch.
- CLOCK_OUT requires a CLOCK_IN on that `work_date` (409 `CLOCK_IN_REQUIRED`).
- Duplicates give 409 `ENTRY_ALREADY_EXISTS`.
- Times follow the correction window; `work_date` may not be in the future (400 `INVALID_ENTRY_TIME`).
- Row values:
  - `source = ADMIN`, `status = VALID`, `review_reasons` empty
  - `server_time = now`, `effective_time` = the converted local time
  - `client_uuid` = server-side `UUID.randomUUID()`
  - `device_time`, lat and lng NULL
  - **no evidence rows**
  - `late_minutes` computed for CLOCK_IN
- One `adjustment` row (`CREATE_ENTRY`, `field = entry`).
- **Today's CLOCK_IN created by an admin** (for example, the employee's camera is broken) has `server_time = now`, so `ShiftResolver` sees an open shift and the employee can clock out from the PWA normally.

### Management (4b)

**Branches.**
- Fields: name (1-120, unique per company), latitude/longitude (finite, in range, dot decimals), radius 10-5000 m, active.
- The form accepts a pasted "9.9325, -84.0796" (Google Maps "copy coordinates") and splits it client-side.
- Editing the location or radius affects **future** entries only; stored distances and reasons stay.
- **Deactivate** is allowed only when no active employee is assigned (409 `BRANCH_IN_USE`). An inactive branch can't be assigned (409 `BRANCH_INACTIVE`).
- There is no delete: entries reference branches, and FKs are `RESTRICT`.

**Employees.** The list shows code, name, job title, role, branch, active, consent given (yes/no), **locked** (`locked_until > now`, with "until HH:mm"), and has-schedule. It has client-side search and filters (active/inactive/locked).

| Action | Rules |
|---|---|
| Create | code `^[A-Z0-9-]{1,20}$` (uppercased, unique per company → 409 `EMPLOYEE_CODE_TAKEN`), name 1-120, job title 0-80 (both trimmed; control characters rejected), role, branch (required for EMPLOYEE → 400 `BRANCH_REQUIRED`; it must be active and in this company). The server **generates** the PIN (Open Question 7) and returns it **once**. |
| Edit | name, job title, branch, role. **The code is immutable**, because changing it would silently change the login. A role change bumps `token_version`. |
| Deactivate / Activate | Deactivate bumps `token_version`. Login and the per-request check already reject inactive users. An admin cannot deactivate or demote **themselves** (409 `SELF_CHANGE_NOT_ALLOWED`). The company must keep at least one active ADMIN (409 `LAST_ADMIN`). |
| Reset PIN | Generates a new PIN, shown once, and sets `pin_hash`. Bumps `token_version` (all sessions end). Clears `failed_attempts` and `locked_until`. Allowed on oneself, with a warning that it signs the admin out. |
| Unlock | `failed_attempts = 0`, `locked_until = NULL` (Stage 1 M-3 item 1). |

**PIN generation** (`auth/PinGenerator`):
- `SecureRandom` (non-blocking default), 8 digits with leading zeros allowed
- loop until `PinPolicy.isValid`
- never logged, never written to `audit_log`, never stored in plain text
- the response has `Cache-Control: no-store`

`PinRevealDialog` shows the PIN in 4+4 monospace groups with "Copiar" (`navigator.clipboard.writeText`, best effort) and "No se volverá a mostrar". The value is dropped from React state when the dialog closes. There is no self-service PIN change yet (noted for later).

**Schedules.**
- The editor is a 7-row week grid on the employee detail page, one row per ISO weekday, with names from `Intl`.
- Each row has: works this day (checkbox), start, end (an "end < start" hint shows "turno nocturno / overnight"), grace 0-240.
- `PUT` replaces the whole week in one transaction. Unchecked days delete their `work_schedule` row. Schedule rows are configuration, not evidence, so a hard delete is allowed.
- Stored `late_minutes` of past entries never change.
- "Copiar horario de…" pre-fills the grid from another employee, client-side only.

### Audit trail

- **`adjustment`** holds changes to a time entry: approve, reject, correct and create.
  - V4 adds `action` and CHECKs on `action`/`field`/reason length, plus an index `(company_id, created_at)`.
  - **Append-only:** `AdjustmentRepository` keeps `save` plus finds, with no update or delete methods, and a V4 trigger raises on `UPDATE`, `DELETE` and `TRUNCATE`.
  - One user action writes 1-3 rows in one transaction, sharing `reason` and `created_at`.
- **`audit_log`** (new, append-only, same trigger) holds every other admin action.
  - Columns: `company_id`, `actor_id` (composite FK to `employee`), `action`, `target_type`, `target_id`, `details` (≤ 500 characters, **ids and field names only**: no PIN, no names, no coordinates) and `created_at`.
  - Actions (Java enum `AuditAction`; the DB checks only the format, so new actions need no migration): `PHOTO_VIEW`, `REPORT_EXPORT`, `EMPLOYEE_CREATE`, `EMPLOYEE_UPDATE`, `EMPLOYEE_ROLE_CHANGE`, `EMPLOYEE_ACTIVATE`, `EMPLOYEE_DEACTIVATE`, `PIN_RESET`, `EMPLOYEE_UNLOCK`, `BRANCH_CREATE`, `BRANCH_UPDATE`, `BRANCH_ACTIVATE`, `BRANCH_DEACTIVATE`, `SCHEDULE_UPDATE`.
- **Photo access (Stage 2 L-6, Open Question 5, default: do it now).**
  - Every successful `GET /api/admin/time-entries/{id}/photo` inserts `PHOTO_VIEW` (`target TIME_ENTRY`, id) and logs INFO `photo_access actor=… entry=… company=…` (ids only).
  - A per-admin `FixedWindowRateLimiter` allows **300 per 10 min**; over that gives 429 `TOO_MANY_ATTEMPTS` + `Retry-After`.
  - The employee's own-photo route is not audited (it is their own data).
  - The Stage 5 item shrinks to "alerting on unusual volume".
- **No audit UI in Stage 4**, apart from the per-entry adjustment history. The export's Adjustments sheet covers the payroll period. An `audit_log` viewer is a "Later" item, and the table is queryable by the operator.

### Pagination and limits

| Endpoint | Strategy |
|---|---|
| Dashboard | Unpaged. The cap is `app.admin.max-dashboard-rows: 1000`; above it the response is 400 `REPORT_TOO_LARGE` and the UI asks for a branch filter. Small companies are the target. |
| Review queue | `page`/`size` (max 100) with `totalItems`. |
| Employees, branches | Unpaged; cap 1000 / 200. Client-side search. |
| Adjustment history | Per entry, unpaged (a handful of rows). |
| Excel | `from..to` ≤ **62 days** inclusive (Open Question 10; two monthly payrolls) → else 400 `DATE_RANGE_TOO_LARGE`. Detail rows ≤ 20,000 → else 400 `REPORT_TOO_LARGE`. |

`page`/`size` outside the allowed range give 400 `INVALID_REQUEST` (no echo).

### Time zone

- Every date parameter (`date`, `from`, `to`, `workDate`) is a company-local `LocalDate`.
- Every local time input is converted with `company.time_zone`.
- Every time in a response is an ISO `Instant`, and the frontend formats it with `Intl` in the zone from the identity (`timeZone`), with `hourCycle: 'h23'` as in Stage 3.
- The admin's PC zone is never used. "Today" for defaults comes from the server: dashboard `date`, review `to`, report `to`.
- The `.xlsx` holds local date-times (Excel has no zones), and the Info sheet states the zone.

### Excel export (4c)

`GET /api/admin/reports/excel?from=YYYY-MM-DD&to=YYYY-MM-DD[&branchId=]`.
- **Content type:** `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.
- **Content-Disposition:** `attachment; filename="asistencia_{COMPANYCODE}_{from}_{to}.xlsx"`, ASCII only, with the code already matching `[A-Z0-9-]`.
- **Cache-Control:** `no-store`.
- **Built in memory** with `XSSFWorkbook`: at most 20,000 detail rows, a few MB. This avoids the SXSSF temp files on disk, which would be personal data outside the managed store (the Stage 2 L-2 lesson). The workbook is written to the response stream and closed in `finally`.
- **Limits:** a per-admin limiter of 10 per 10 min (429), and an `audit_log` `REPORT_EXPORT` row with `details from=…,to=…,branchId=…`.
- **Headers** come from `messages*.properties` (`report.*`) in the request locale (`Accept-Language`, default es).

| Sheet (ES / EN) | One row per | Columns |
|---|---|---|
| Resumen / Summary | employee with any scheduled day or entry in the range | Código, Nombre, Puesto, Sucursal, Días trabajados (complete, approved), Horas aprobadas (decimal, `0.00`), Horas aprobadas (`[h]:mm`), Horas pendientes de revisión (decimal), Días con marca rechazada, Llegadas tardías, Minutos tarde (sum), Salidas faltantes, Ausencias (scheduled days with no clock-in, past days only), Minutos fuera de horario (informational, Open Question 9) |
| Detalle / Detail | employee × `work_date` that has a schedule or an entry | Fecha (date cell, `dd/mm/yyyy`), Día (weekday name), Código, Nombre, Sucursal, Horario inicio, Horario fin, Entrada (time cell `HH:mm`), Salida, Minutos trabajados, Horas (decimal), Estado del día (localized `DayStatus`), Minutos tarde, Estado entrada, Estado salida, Motivos de revisión (localized, comma-separated), Ajustada (Sí/No), Evidencia (Foto + GPS / Solo foto / Registro manual), Distancia entrada (m), Distancia salida (m) |
| Ajustes / Adjustments | adjustment row whose entry's `work_date` is in the range | Fecha y hora, Empleado (código + nombre), Fecha de jornada, Marca (Entrada/Salida), Acción, Campo, Valor anterior, Valor nuevo (instants formatted local `dd/mm/yyyy HH:mm`), Administrador, Motivo, Autoaprobado (Sí when `admin_id = employee_id`, Open Question 4) |
| Info | — | Empresa, Código, Período, Sucursal filter, Zona horaria, Generado (local), Generado por (admin name), plus a legend: approved vs pending hours, rejected excluded, `effective_time` used, overtime not classified |

- Numbers are numeric cells: hours as minutes/60 rounded to 2 decimals, and `[h]:mm` as minutes/1440 with that format, so Excel sums them.
- Header row bold, frozen, with an auto-filter. Column widths are fixed by the coder, not `autoSizeColumn` (slow, and it needs fonts).
- **No coordinates and no storage keys in the export** (Stage 2 L-5 exploit scenario). There are no photos either.
- **Injection guard (`ExcelText`).** Every free-text value goes through it: names, job titles, branch names, reasons, admin names and old/new values.
  1. Remove characters that are illegal in XML 1.0 (`\x00-\x08\x0B\x0C\x0E-\x1F`) and lone surrogates.
  2. Truncate to 32,767 characters, the Excel cell limit.
  3. `setCellValue(String)`, so the cell is never a formula.
  4. If the first character is one of `= + - @ \t \r \n ＝ ＋ － ＠`, use the shared quote-prefixed text style.

  The workbook never calls `setCellFormula` and never creates hyperlinks.

### Demo seed (4a; Open Question 11)

`DevDataSeeder` (dev profile only) becomes "ensure" rather than "create once":
- **Missing employees.** The demo employees 1001-1006 and 9001 are created if missing, plus **1007-1012**, so the company has 12 people as in the mockup "9 de 12". Use plain Costa Rican names, Mon-Fri 08:00-17:00 with 5 min grace (one 06:00-14:00).
- **Existing rows** are never changed. Existing PINs are never reset; new ones get the `DEV_SEED_PIN` hash.
- **Opt-in demo day:** `DEV_SEED_DEMO_DAY=true` → `app.seed.demo-day: true`. At startup, if no DEMO entries exist for the company-local **today**, it inserts today's entries matching page 2:

| Employee | Entries |
|---|---|
| María 1001 | in 07:58, VALID, Foto + GPS |
| José 1002 | in 08:17, VALID, late 17 |
| Ana 1003 | in 07:52, `source TABLET`, VALID |
| Luis 1004 | in 08:01, PENDING_REVIEW `OUTSIDE_GEOFENCE`, 420 m |
| Karla 1005 | 06:00-14:04, VALID, complete 8 h 04 |
| Diego 1006 | none |
| 1007-1010 | 4 more present (one late) |
| 1011, 1012 | none |

  - The result is 9 present of 12 expected, 2 late, 3 not clocked, 1 to review.
  - Each MOBILE entry gets a generated placeholder JPEG: a solid color with initials, made with Java2D, stored through `StorageService`, with a PHOTO evidence row and a GPS row.
  - `server_time = effective_time` = today at those local times. Future times are allowed when the seed runs before 08:17; it is dev only.
- **Why opt-in:** demo entries would stop the user from testing María's real clock-in that day. The README explains it. Logging: counts only.

## Data model changes

### `V4__admin_panel.sql` (4a; V1-V3 untouched)

```sql
-- Stage 4: admin review. server_time stays the immutable receipt time; effective_time is the time that
-- counts for worked time, lateness and payroll (equal to server_time until an admin corrects it).
ALTER TABLE time_entry ADD COLUMN effective_time timestamptz NULL;
UPDATE time_entry SET effective_time = server_time WHERE effective_time IS NULL;
ALTER TABLE time_entry ALTER COLUMN effective_time SET NOT NULL;

-- Admin-created entries (missing clock-out, broken camera, lockout).
ALTER TABLE time_entry DROP CONSTRAINT ck_time_entry_source;
ALTER TABLE time_entry ADD CONSTRAINT ck_time_entry_source
    CHECK (source IN ('MOBILE', 'TABLET', 'QR', 'ADMIN'));

-- Review queue: pending entries of one company, by work date.
DROP INDEX ix_time_entry_pending_review;
CREATE INDEX ix_time_entry_pending_review ON time_entry (company_id, work_date) WHERE status = 'PENDING_REVIEW';

-- adjustment: what kind of admin action wrote the row; constrained field names.
ALTER TABLE adjustment ADD COLUMN action varchar(20) NULL;
UPDATE adjustment SET action = 'CORRECT_TIME' WHERE action IS NULL;   -- no rows expected before Stage 4
ALTER TABLE adjustment ALTER COLUMN action SET NOT NULL;
ALTER TABLE adjustment
    ADD CONSTRAINT ck_adjustment_action CHECK (action IN ('APPROVE', 'REJECT', 'CORRECT_TIME', 'CREATE_ENTRY')),
    ADD CONSTRAINT ck_adjustment_field CHECK (field IN ('status', 'effective_time', 'late_minutes', 'entry')),
    ADD CONSTRAINT ck_adjustment_reason_length CHECK (char_length(reason) <= 500);
CREATE INDEX ix_adjustment_company_created ON adjustment (company_id, created_at);

-- Branches can be retired without deleting history.
ALTER TABLE branch ADD COLUMN active boolean NOT NULL DEFAULT true;

-- Every other admin action (photo views, exports, employee/branch/schedule changes). Ids only in details.
CREATE TABLE audit_log (
    id           bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    company_id   bigint       NOT NULL,
    actor_id     bigint       NOT NULL,
    action       varchar(40)  NOT NULL,
    target_type  varchar(20)  NOT NULL,
    target_id    bigint       NULL,
    details      varchar(500) NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_audit_log_actor_company FOREIGN KEY (actor_id, company_id)
        REFERENCES employee (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_log_action CHECK (action ~ '^[A-Z_]{3,40}$'),
    CONSTRAINT ck_audit_log_target_type
        CHECK (target_type IN ('TIME_ENTRY', 'EMPLOYEE', 'BRANCH', 'WORK_SCHEDULE', 'REPORT'))
);
CREATE INDEX ix_audit_log_company_created ON audit_log (company_id, created_at);

-- Audit rows are append-only (defense in depth; the repositories expose no update/delete either).
CREATE FUNCTION forbid_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'append-only table: %', TG_TABLE_NAME;
END
$$;
CREATE TRIGGER tr_adjustment_append_only BEFORE UPDATE OR DELETE ON adjustment
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_change();
CREATE TRIGGER tr_adjustment_no_truncate BEFORE TRUNCATE ON adjustment
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_change();
CREATE TRIGGER tr_audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_change();
CREATE TRIGGER tr_audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_change();
```

Notes:
- **Constraint and index counts for `SchemaEnglishNamingTest`.**
  - Constraints: `ck_time_entry_source` is replaced; add `ck_adjustment_action`, `ck_adjustment_field`, `ck_adjustment_reason_length`, `audit_log_pkey`, `fk_audit_log_actor_company`, `ck_audit_log_action` and `ck_audit_log_target_type`.
  - Indexes: `ix_time_entry_pending_review` is recreated; add `ix_adjustment_company_created` and `ix_audit_log_company_created`.
  - The coder recounts against `pg_constraint` and adds a check that the 4 `tr_*` triggers exist (`pg_trigger`).
- **Trigger owner.** The app role owns the tables (Flyway runs as it), so the trigger is defense in depth against application bugs and casual SQL, not against the DB owner. Stage 5 can split the migration and runtime roles.
- **Tests that clean tables.** If any existing test deletes from or truncates `adjustment`, it must stop doing so. The coder greps the tests. Tests already use random tenants.
- **Entities:**
  - `TimeEntry` gets `effectiveTime`, set in the constructor (= `serverTime`), plus intention-revealing mutators: `correctEffectiveTime(Instant, Integer lateMinutes)`, `changeStatus(TimeEntryStatus)`.
  - `Branch` gets `active` + `rename/relocate/activate/deactivate`.
  - `Employee` gets `updateProfile(name, jobTitle)`, `assignBranch`, `changeRole`, `activate`/`deactivate`, `resetPin(hash)`, `unlock()`. `token_version` is bumped through the existing atomic query.
  - `Adjustment` gets `action`.
  - New `AuditLogEntry` + `AuditLogRepository` (`save` + `findAllByCompanyId…` only).
  - `TimeEntrySource` gets `ADMIN`; new enums `AdjustmentAction`, `AuditAction`, `DayStatus`.
- **ER diagram:** the architect updates it (hand-off).

## API

All paths are under `/api/admin/**`: `hasRole("ADMIN")` at the URL layer **and** `@PreAuthorize("hasRole('ADMIN')")` on each controller class. `companyId` and the actor come only from `EmployeePrincipal`. Another tenant's id gives 404. JSON request records are explicit, there is no entity binding, and unknown properties are ignored without effect.

### 4a

| Method + path | Body / params | Response |
|---|---|---|
| `GET /api/admin/dashboard` | `date?`, `branchId?` | `DashboardResponse` |
| `GET /api/admin/review-queue` | `status?`, `from?`, `to?`, `employeeId?`, `page?`, `size?` | `Page<ReviewItem>` |
| `GET /api/admin/review-queue/count` | — | `{ pending: number }` |
| `GET /api/admin/time-entries/{id}` | — | `AdminEntryDetail` |
| `POST /api/admin/time-entries/{id}/approve` | `{reason, expectedStatus}` | 200 `AdminEntryDetail` |
| `POST /api/admin/time-entries/{id}/reject` | `{reason, expectedStatus}` | 200 `AdminEntryDetail` |
| `POST /api/admin/time-entries/{id}/correct` | `{reason, expectedStatus, expectedEffectiveTime, localTime}` | 200 `AdminEntryDetail` |
| `POST /api/admin/time-entries` | `{employeeId, type, workDate, localTime, reason}` | 201 `AdminEntryDetail` |
| `GET /api/admin/time-entries/{id}/photo` (existing) | — | unchanged, plus audit and the limiter |
| `GET /api/admin/branches` (needed by the dashboard filter in 4a) | — | `[{id, name, latitude, longitude, radiusMeters, active, activeEmployees}]` |
| `GET /api/time-entries/today` (existing, employee) | — | `EntrySummary` gains `effectiveTime` and `adjusted`; `workedMinutes` uses effective times |

Shapes (hand-written TS types mirror them):

```ts
type DayStatus = 'REJECTED' | 'OUTSIDE_AREA' | 'NEEDS_REVIEW' | 'MISSING_CLOCK_OUT' | 'COMPLETE' | 'LATE'
  | 'ON_TIME' | 'UNSCHEDULED' | 'NOT_YET_DUE' | 'NOT_CLOCKED' | 'DAY_OFF'
type TimeEntrySource = 'MOBILE' | 'TABLET' | 'QR' | 'ADMIN'
type AdjustmentAction = 'APPROVE' | 'REJECT' | 'CORRECT_TIME' | 'CREATE_ENTRY'
type AdminEntrySummary = { id: number; type: TimeEntryType; effectiveTime: string; serverTime: string;
  status: TimeEntryStatus; reviewReasons: ReviewReason[]; source: TimeEntrySource; distanceMeters: number | null;
  insideGeofence: boolean | null; hasPhoto: boolean; hasGps: boolean; adjusted: boolean }
type DashboardRow = { employee: { id: number; code: string; name: string; jobTitle: string | null; active: boolean };
  branchName: string | null; clockIn: AdminEntrySummary | null; clockOut: AdminEntrySummary | null;
  workedMinutes: number | null; inProgress: boolean; lateMinutes: number | null; dayStatus: DayStatus;
  schedule: { startTime: string; endTime: string; graceMinutes: number; overnight: boolean } | null }
type DashboardResponse = { date: string; today: string; timeZone: string; companyName: string; branchId: number | null;
  kpis: { present: number; expected: number; late: number; notClocked: number; notYetDue: number; toReview: number;
          toReviewByReason: Partial<Record<ReviewReason, number>> };
  rows: DashboardRow[]; generatedAt: string }
type ReviewItem = { employee: { id: number; code: string; name: string; jobTitle: string | null }; workDate: string;
  entry: AdminEntrySummary & { deviceTime: string | null; skewSeconds: number | null; syncedLate: boolean;
                               gpsAccuracyMeters: number | null; lateMinutes: number | null };
  pair: AdminEntrySummary | null }
type Page<T> = { items: T[]; page: number; size: number; totalItems: number }
type AdminEntryDetail = ReviewItem['entry'] & { workDate: string; employee: ReviewItem['employee'];
  location: { latitude: number; longitude: number } | null;
  branch: { id: number; name: string; latitude: number; longitude: number; radiusMeters: number };
  pair: AdminEntrySummary | null;
  adjustments: { id: number; action: AdjustmentAction; field: string; oldValue: string | null; newValue: string;
                 reason: string; adminName: string; createdAt: string }[] }
```

- `adjusted` = at least one adjustment exists, from one batched `EXISTS` query per page.
- `hasGps` = lat/lng present.
- `old_value` / `new_value` are stored as plain text: ISO instants with offset, status names, integers, or the `entry` description. The API returns them raw, and the UI formats instants and status names.
- **`client_uuid` is never exposed** in admin APIs (Stage 2 I-3).

### 4b

| Method + path | Body | Response |
|---|---|---|
| `POST /api/admin/branches` | `{name, latitude, longitude, radiusMeters}` | 201 branch |
| `PUT /api/admin/branches/{id}` | same | 200 branch |
| `POST /api/admin/branches/{id}/deactivate` / `activate` | — | 200 branch |
| `GET /api/admin/employees` (existing, extended) | — | `AdminEmployeeView[]` = `EmployeeSummary` + `branchName`, `consentGiven`, `locked`, `lockedUntil`, `hasSchedule` (still no `pinHash`, `failedAttempts` or `tokenVersion`) |
| `GET /api/admin/employees/{id}` (extended) | — | `AdminEmployeeView` |
| `POST /api/admin/employees` | `{code, name, jobTitle?, role, branchId?}` | 201 `{employee, pin}` with `Cache-Control: no-store` |
| `PUT /api/admin/employees/{id}` | `{name, jobTitle?, role, branchId?}` | 200 |
| `POST /api/admin/employees/{id}/deactivate` / `activate` | — | 200 |
| `POST /api/admin/employees/{id}/pin-reset` | — | 200 `{pin}` with `no-store` |
| `POST /api/admin/employees/{id}/unlock` | — | 200 |
| `GET /api/admin/employees/{id}/schedule` | — | `[{dayOfWeek, startTime, endTime, graceMinutes}]` |
| `PUT /api/admin/employees/{id}/schedule` | the same array (0-7 items, unique `dayOfWeek` 1-7, `HH:mm`, start ≠ end, grace 0-240) | 200 |

Per-admin limiter on `pin-reset` and on employee create: 30 per 10 min (429).

### 4c

| Method + path | Params | Response |
|---|---|---|
| `GET /api/admin/reports/excel` | `from`, `to`, `branchId?` | 200 `.xlsx` (headers as in Decisions) |

### New error codes (stable English `code`, EN/ES `title`/`detail` in all 3 bundles; no arguments, no apostrophes)

| Code | Status | EN title / detail | ES title / detail |
|---|---|---|---|
| `ENTRY_CHANGED` | 409 | Entry changed / Someone else changed this entry. Reload and try again. | Marca modificada / Otra persona modificó esta marca. Recargue e intente de nuevo. |
| `INVALID_STATUS_CHANGE` | 409 | Change not allowed / This entry is already in that state or cannot change that way. | Cambio no permitido / La marca ya está en ese estado o no puede cambiar de esa forma. |
| `ENTRY_ALREADY_EXISTS` | 409 | Entry already exists / This employee already has that entry for the selected day. | La marca ya existe / Este empleado ya tiene esa marca para el día seleccionado. |
| `CLOCK_IN_REQUIRED` | 409 | Clock-in required / Register the clock-in for that day before the clock-out. | Falta la entrada / Registre la entrada de ese día antes de la salida. |
| `INVALID_ENTRY_TIME` | 400 | Invalid time / The time is in the future, outside the work day or not after the clock-in. | Hora inválida / La hora está en el futuro, fuera de la jornada o no es posterior a la entrada. |
| `EMPLOYEE_CODE_TAKEN` | 409 | Code in use / Another employee of this company already uses that code. | Código en uso / Otro empleado de esta empresa ya usa ese código. |
| `BRANCH_NAME_TAKEN` | 409 | Name in use / Another branch already has that name. | Nombre en uso / Otra sucursal ya tiene ese nombre. |
| `BRANCH_REQUIRED` | 400 | Branch required / Employees must have a branch. | Sucursal requerida / Los empleados deben tener una sucursal. |
| `BRANCH_INACTIVE` | 409 | Inactive branch / The selected branch is inactive. | Sucursal inactiva / La sucursal seleccionada está inactiva. |
| `BRANCH_IN_USE` | 409 | Branch in use / Move or deactivate its employees before deactivating the branch. | Sucursal en uso / Traslade o desactive a sus empleados antes de desactivar la sucursal. |
| `LAST_ADMIN` | 409 | Last administrator / The company must keep at least one active administrator. | Último administrador / La empresa debe conservar al menos un administrador activo. |
| `SELF_CHANGE_NOT_ALLOWED` | 409 | Not allowed on yourself / You cannot deactivate yourself or remove your own administrator role. | No permitido sobre usted / No puede desactivarse ni quitarse el rol de administrador. |
| `DATE_RANGE_TOO_LARGE` | 400 | Range too large / Choose a period of at most 62 days, with the start before the end. | Rango demasiado grande / Elija un período de máximo 62 días, con el inicio antes del fin. |
| `REPORT_TOO_LARGE` | 400 | Too much data / Narrow the period or choose a branch. | Demasiados datos / Reduzca el período o elija una sucursal. |

That makes 14 codes, which means 28 keys per bundle. `MessageBundleParityTest` covers them automatically. Reused codes: `INVALID_REQUEST` (Bean Validation, reason length, bad dates and coordinates), `NOT_FOUND`, `NO_BRANCH_ASSIGNED`, `TOO_MANY_ATTEMPTS`, `SERVER_BUSY` (lock timeout). The frontend `ERROR_CODE_KEYS` and `resources.ts` get the 14 `error.*` keys. They stay in the main resources (about 1 KB gzip) because `contract.test.ts` checks the map against `ErrorCode.java`.

**Data-integrity mapping.** The admin services catch `DataIntegrityViolationException` outside the transaction (the Stage 2 pattern) and map by constraint/index name:
- `uq_employee_company_code` gives `EMPLOYEE_CODE_TAKEN`
- `uq_branch_company_name` gives `BRANCH_NAME_TAKEN`
- `uq_time_entry_employee_work_date_type` gives `ENTRY_ALREADY_EXISTS`
- `ck_employee_branch_role` gives `BRANCH_REQUIRED`
- `fk_*_company` gives 404

## Frontend

### Structure (`frontend/src/`)

```
admin/
  AdminApp.tsx            lazy entry: <Routes> for /admin/*, AdminLayout, RequireAdmin, AdminReauthDialog, error boundary
  AdminLayout.tsx, AdminNav.tsx, RequireAdmin.tsx, AdminReauthDialog.tsx, ChunkErrorBoundary.tsx
  adminI18n.ts            useAdminI18n(): same language store and interpolation as useI18n, typed AdminMessageKey
  api/adminTypes.ts, api/adminApi.ts   typed calls (dashboard, review, entries, branches, employees, schedule, report)
  hooks/useAdminQuery.ts  tiny load/reload/error state (no library), AbortController on unmount
  format.ts               formatHoursMinutes, initials, toLocalInput/fromLocalInput (company zone), osmUrl
  pages/  DashboardPage, ReviewQueuePage, EmployeesPage, EmployeeDetailPage (4b), BranchesPage (4b), ReportsPage (4c)
  components/ KpiCards, AttendanceTable, DayStatusChip, EvidenceButton, EvidenceDialog, ReviewActionDialog,
              CreateEntryDialog, ReasonField (presets + text), Pagination, DateField, BranchFilter,
              EmployeeForm, PinRevealDialog, ScheduleEditor, BranchForm (4b)
  admin.css               imported only by AdminApp; designer tokens reused
i18n/adminResources.ts    es/en admin strings (typed like resources.ts)
```

- `App.tsx`: `<Route path="/admin/*" element={<Suspense><AdminApp/></Suspense>}>` replaces `AdminHome`. `pages/AdminHome.tsx` is deleted.
  - `HealthStatus` moves into the admin layout footer ("Servidor en línea").
  - **Keep this route outside `RequireSession`**: `RequireAdmin` does its own check, and the consent gate must not apply.
- `api/client.ts`:
  - `method` gains `'PUT' | 'DELETE'`
  - new `apiBlob(path, {timeoutMs})` returns `{blob, filename}`; it parses `Content-Disposition` with an ASCII-safe regex and falls back to a default name. It uses the same auth, 401 handling and problem mapping.
- `LoginPage`: role-based landing + `next` validation (Decisions).
- `api/types.ts`: `EntrySummary.effectiveTime`, `adjusted`.
- `EmployeeHome` / `EntryTimes` show `effectiveTime` (fall back to `serverTime` for an old snapshot) and a small "ajustada por tu administrador / adjusted by your administrator" note when `adjusted`.
- **Downloads:** `URL.createObjectURL(blob)`, then a hidden `<a download>` created with `document.createElement`, clicked and removed, then `revokeObjectURL` on the next tick. These are not Trusted Types sinks. No `window.open` of blob URLs.

### Bundle, precache and CSS (employee PWA must not grow)

- **Chunking.** `vite.config.ts` adds `build.rolldownOptions.output.codeSplitting.groups = [{ name: 'admin', test: /[\\/]src[\\/](admin[\\/]|i18n[\\/]adminResources)/ }]`, so the admin code is `assets/admin-<hash>.js` (+ `admin-<hash>.css` via `cssCodeSplit`).
- **Precache.** `workbox.globIgnores: ['**/admin-*.js', '**/admin-*.css']`, so the phones never download the admin panel. The admin panel needs the network anyway.
- **Offline or chunk-load failure.** `ChunkErrorBoundary` shows "No se pudo cargar el panel. Revise su conexión." with "Reintentar" (reload).
- **`bundleBudget.test.ts` (modify):**
  - the employee JS (all `.js` **except** `admin-*`) stays ≤ 130 KB gzip
  - the entry chunk stays ≤ 110 KB
  - employee CSS (excluding `admin-*`) stays ≤ 10 KB
  - **admin JS ≤ 80 KB gzip** and **admin CSS ≤ 10 KB gzip** (new budgets; the coder measures and reports first)
  - the precache stays ≤ 600 KB **and contains no `admin-` file**
  - the "lazy chunks" test expects `admin-` (instead of `AdminHome-`) and `SelfieCapture-`
- **`pwaConfig.test.ts`:** asserts the `globIgnores`.
- **No new runtime dependencies:**
  - native `<input type="date">` / `datetime-local`
  - `Intl` for weekday names and formats
  - plain `<table>`
  - no chart, grid or map library

### i18n

- `i18n/adminResources.ts` exports `adminEs`, `adminEn: Record<AdminMessageKey, string>`. `useAdminI18n()` reuses `getLanguage`/`subscribe` and the same `{name}` interpolation.
- **Tests:**
  - `resources.test.ts` gets a parity block for the admin resources (same `SAME_IN_BOTH` rules)
  - `noHardcodedText.test.ts` covers `src/admin/**`
  - its glob sanity test expects `admin/pages/DashboardPage.tsx`
  - `SPANISH_WORDS` gains `Presentes|tardías|Sin marcar|revisar|Aprobar|Rechazar|Sucursal|Exportar|Horario`
- **Key groups**, with ES texts from the mockup; the designer finalizes them:

| Group | Contents |
|---|---|
| `admin.nav.*` | dashboard, review, employees, branches, reports, myClock, skipToContent |
| `admin.dashboard.*` | titleToday "Asistencia de hoy", titleDate, date "Fecha", export "Exportar a Excel", refresh, allBranches, empty, rowsCapped |
| `admin.kpi.*` | present "Presentes", ofTotal "de {n}", late "Llegadas tardías", notClocked "Sin marcar", notYetDue, toReview "Por revisar", toReviewReason |
| `admin.table.*` | caption, employee, clockIn "Entrada", clockOut "Salida", hours "Horas", evidence "Evidencia", status "Estado", inProgress "en turno", edited |
| `admin.dayStatus.*` | 11 values, LATE with `{minutes}` |
| `admin.evidence.*` | photoAndGps "Foto + GPS", gpsAt "GPS a {meters} m", photoOnly, manual "Registro manual", tablet "Tablet local", none, title, noPhoto, distance, radius, accuracy, deviceTime, skew, mapLink, branchMapLink, history |
| `admin.review.*` | title, empty, approve, reject, correct, create, reason, reasonHint, newTime, pair, count |
| `admin.reasonPreset.*` | 6 presets |
| `admin.adjustmentAction.*`, `admin.adjustmentField.*` | |
| `admin.employees.*`, `admin.employee.*` | form labels, locked "Bloqueado hasta {time}", consent, actions, confirms |
| `admin.pin.*` | title, warning "No se volverá a mostrar", copy, copied |
| `admin.schedule.*` | worksDay, start, end, grace, overnight, copyFrom |
| `admin.branches.*` | name, lat, lng, radius, paste hint, inactive |
| `admin.reports.*` | from, to, branch, download, rangeHint |
| `admin.reauth.*` | |
| `admin.chunkError` | |

Plus `error.*` for the 14 new codes in `resources.ts`.

### Accessibility (WCAG 2.2 AA, desktop)

- Landmarks: one `<h1>` per page, focused on navigation (`tabIndex={-1}`, the Stage 3 pattern), a skip link, `<nav aria-label>`.
- The attendance table: `<table>` with a `<caption>` (visually hidden, giving date and branch), `th scope="col"`, and the employee name as `th scope="row"`. The evidence cell is a `<button>` with an accessible name ("Ver evidencia de María Rojas, entrada 07:58").
- KPI cards: a `<ul>` of `<li>`s, each with its label and value read together ("Presentes: 9 de 12").
- Chips: text, never color only. The designer verifies contrast.
- Dialogs use the native `<dialog>` via `ModalDialog` (focus trap, Esc, focus returned to the opener). Reason fields have visible labels; errors use `role="alert"` with `aria-describedby`.
- Each page has one polite `role="status"` region for results ("Marca aprobada", "Exportación lista") and for loading state changes.
- Native date and time inputs with labels and a visible format hint. Keyboard: the full flow works without a mouse. Targets are ≥ 24 px (AA) and ≥ 44 px on tablets via a media query.
- `prefers-reduced-motion` is respected. `lang` comes from the existing i18n.

### CSP and Trusted Types

Unchanged. The admin code adds:
- no inline scripts or styles, and no `style=` attributes (classes only, as in Stage 3)
- no `dangerouslySetInnerHTML` (lint rule)
- no third-party origins
- blob URLs only for `img` and downloads
- external links only to `openstreetmap.org` via `<a>`, which needs no CSP directive

`Referrer-Policy: no-referrer` already strips the panel URL from those navigations.

## Security

1. **Two authorization layers.** URL rule + `@EnableMethodSecurity` (in `SecurityConfig` or a new `MethodSecurityConfig`) with a class-level `@PreAuthorize("hasRole('ADMIN')")` on every admin controller, including the existing `AdminEmployeeController` and `AdminTimeEntryPhotoController`. A reflection test fails if any `@RestController` mapped under `/api/admin` lacks it (Stage 1 I-1).
2. **Tenant isolation.**
   - Every lookup is `findByIdAndCompanyId(id, principal.companyId())` (or a scoped query), followed by mutating the managed entity. No entity is ever built from a request DTO carrying an id (Stage 1 I-2).
   - `employeeId`/`branchId` inside request bodies are loaded the same way, so another tenant's id gives 404. The composite FKs are the backstop.
   - New repositories are added to `TenantRepositoryConventionTest`.
3. **Privilege changes take effect immediately.** A role change, deactivation or PIN reset bumps `token_version`, and the per-request re-check already reads the role from the DB. Guards: no self-deactivation or self-demotion, and at least one active admin, checked **under a lock on the company's admin rows** (`SELECT … FOR UPDATE` on the active admins) so two admins can't demote each other at the same time.
4. **PIN handling.** `SecureRandom`, `PinPolicy`, BCrypt with the configured strength. The PIN is shown once and is never logged, audited, cached (`no-store`) or kept in React state after the dialog closes. The frontend test scans the stores (`sessionStorage`, `localStorage`, IndexedDB) for the PIN string after the dialog closes.
5. **Audit and accountability (Ley 8968).** Append-only `adjustment` + `audit_log` (repository + DB trigger), photo views audited and throttled, exports audited. Logs carry ids only.
6. **Personal data minimization.**
   - Exact coordinates only in the single-entry detail.
   - No coordinates, storage keys or `client_uuid` in lists or the export.
   - Photos fetched on demand, never prefetched or cached (`no-store` already set; the SW has no runtime caching).
   - The map link goes to OSM only when clicked (Open Question 6).
7. **Input validation.**
   - Bean Validation on every request record. Reason 5-500 characters; names 1-120; job title ≤ 80; codes by regex.
   - Text fields reject any control character (`\p{Cc}`, including tab and newline).
   - lat/lng finite and in range with dot decimals; radius 10-5000; times `HH:mm`; grace 0-240; ISO day 1-7 unique; dates ISO and within ±5 years of today.
   - Every 400 uses the existing no-echo handler.
8. **Output encoding.**
   - React escaping only, no HTML rendering of reasons.
   - Excel: `ExcelText` injection guard, no formulas or hyperlinks.
   - The filename is ASCII from validated parts.
9. **Rate limits** (per admin, `FixedWindowRateLimiter`, 429 + `Retry-After`): photo 300 / 10 min, export 10 / 10 min, PIN reset + create 30 / 10 min. Configured in `app.admin.rate-limit.*`.
10. **Concurrency.** Row locks + `expected*` checks give 409 `ENTRY_CHANGED`. The employee row lock serializes admin-created entries with PWA posts. `lock_timeout` gives 503 `SERVER_BUSY`.
11. **Business-logic fraud (Stage 2 L-4).**
    - Payroll totals count approved time only; pending hours are shown separately.
    - `VALID` entries can be rejected after a spot check, and every row's evidence is one click away.
    - Self-approval is visible (Open Question 4).
    - Optional new review reasons (Open Question 8).
12. **Session on shared admin PCs.** The token is in `sessionStorage` (gone when the browser is closed) and logout bumps `token_version`. An idle auto-lock is noted as "Later".
13. **Excel library.** POI only writes. There is no file upload anywhere in Stage 4.

## Requirements Checklist

### Planning
- [x] Read the roadmap, the Stage 1/1b/2/3 plans with their resolutions and amendments, the ER and Stage 2 diagrams, V1-V3, the backend packages, the three audits, PROGRESS and the frontend
- [x] Mockup page 2 read (PDF rendered as text and image)
- [x] Verified Apache POI versions and security, the `CellStyle.setQuotePrefixed` API, OWASP CSV injection, Vite 8 `rolldownOptions`, Rolldown `codeSplitting`, and the Costa Rica working-hours rules
- [x] Open Questions answered by the user and applied to this plan
- [x] Architect: `docs/diagrams/stage-4-admin-panel.md` (data model changes and new layers: required)
- [x] Designer: `docs/design/stage-4-admin-panel.md` (UI: required)

### 4a - Dashboard, evidence, review
- [x] V4 migration exactly as above; `ddl-auto=validate` passes; V1-V3 untouched
- [x] `effective_time` written on every insert; corrections change only it; `server_time` never changes
- [x] `TodayService.workedMinutes` and `EntrySummary.effectiveTime`/`adjusted`; the PWA shows the effective time
- [x] Dashboard API: `DayStatus` priority table, KPIs (present/expected, late, not clocked + not yet due, to review + by reason), branch filter, company-zone date, row cap
- [x] Dashboard UI: date picker, 4 KPI cards, table with chips, hours, evidence button, refresh rules
- [x] Evidence detail API (coordinates only here) and viewer (photo blob on demand and revoked, facts, Google Maps link, adjustment history)
- [x] Review queue (paged, filters, pair, count badge) with approve / reject / correct, required reason with presets, `expected*` concurrency checks
- [x] Admin-created entries (`source ADMIN`, no evidence, rules, employee lock)
- [x] `late_minutes` recomputed on a CLOCK_IN correction and audited
- [x] `adjustment` rows for every change; append-only (repository + trigger)
- [x] `audit_log` table; `PHOTO_VIEW` audit + per-admin photo limiter (L-6)
- [x] `@EnableMethodSecurity` + `@PreAuthorize` on all admin controllers + reflection test (I-1)
- [x] Admin shell: lazy `admin` chunk, `RequireAdmin`, admin landing after login, `next` validation, `AdminReauthDialog`, chunk-error boundary, "Mi marca" link
- [x] Employee bundle and precache within budget; admin chunk excluded from the precache; new admin budgets
- [x] 14 new error codes (those used in 4a at least) in backend bundles + frontend map
- [x] Demo seed: employees 1007-1012 ensured; opt-in `DEV_SEED_DEMO_DAY` reproduces page 2
- [x] Optional per Open Question 8: `NO_GPS_ACCURACY` / `CLOCK_OUT_AFTER_SCHEDULE` review reasons

### 4b - Management
- [ ] Branches: list, create, edit, activate/deactivate (`BRANCH_IN_USE`), paste-coordinates helper
- [ ] Employees: extended list (locked, consent, schedule), create with a generated PIN shown once, edit (code immutable), role change, activate/deactivate, PIN reset, unlock; `token_version` bumps; last-admin and self guards
- [ ] Schedules: weekly editor, replace-week API, overnight hint, copy from another employee
- [ ] `audit_log` rows for every management action; no PIN anywhere

### 4c - Export
- [ ] `GET /api/admin/reports/excel` with POI (version per Open Question 12), 4 sheets, localized headers, numeric hours, approved vs pending split
- [ ] Injection guard + no formulas or hyperlinks; no coordinates or keys
- [ ] Range ≤ 62 days, row cap, limiter, `REPORT_EXPORT` audit, `no-store`, safe filename
- [ ] Reports page + dashboard "Exportar a Excel" for the selected day

### Cross-cutting
- [ ] Every user-visible string in ES and EN (backend bundles, `resources.ts`, `adminResources.ts`); i18n tests extended and green
- [ ] WCAG 2.2 AA per the Accessibility section
- [ ] All backend and frontend tests green: `.\mvnw.cmd clean test`; `npm test`, `npm run lint`, `npm run build`, `npm test`
- [ ] README: "Admin panel" section (login, screens, corrections model, demo-day seed, export)
- [ ] Security audit after 4a and after 4c (or once after 4c; see hand-off)
- [ ] Main session updates `CHANGELOG.md` and `docs/PROGRESS.md` (not the planner)

## Implementation Steps

Paths are relative to `backend/` or `frontend/` as named; `docs/` paths are repo-root relative. After each step, `.\mvnw.cmd clean test` (backend) and `npm test && npm run lint && npm run build && npm test` (frontend) stay green.

### Step 0 - Hand-offs before coding
1. **Architect:** `docs/diagrams/stage-4-admin-panel.md` (see hand-off). Short "Stage 4 changes" note at the top of `docs/diagrams/stage-1-er.md`.
2. **Designer:** `docs/design/stage-4-admin-panel.md` (see hand-off). The designer may also author `src/admin/components/*` and `admin.css`.

### Sub-stage 4a

**Backend:**
3. `pom.xml`: no change in 4a.
4. `src/main/resources/db/migration/V4__admin_panel.sql` (new): exactly the SQL above.
5. **Entities and enums** (modify / new):
   - `timeentry/TimeEntry.java` (`effectiveTime`, mutators)
   - `timeentry/TimeEntrySource.java` (+`ADMIN`)
   - `timeentry/Adjustment.java` (+`action`)
   - `timeentry/AdjustmentAction.java` (new)
   - `company/Branch.java` (+`active`, mutators)
   - `common/AuditAction.java`, `common/AuditTargetType.java` (new)
   - `audit/AuditLogEntry.java`, `audit/AuditLogRepository.java`, `audit/AuditService.java` (new package `cr.attendance.audit`; depends on `common` only)
6. **Repositories** (all scoped):
   - `TimeEntryRepository`:
     - `findAllByCompanyIdAndWorkDate(companyId, workDate)`
     - `findAllByCompanyIdAndWorkDateBetween(...)`
     - a pending page query `findPage…ByCompanyIdAndStatusAndWorkDateBetween(companyId, …, Pageable)`
     - `countByCompanyIdAndStatus`
     - `@Lock(PESSIMISTIC_WRITE) findWithLockByIdAndCompanyId`
     - `findByCompanyIdAndEmployeeIdAndWorkDateAndType`
   - `AdjustmentRepository`:
     - `findAllByCompanyIdAndTimeEntryIdOrderByCreatedAt`
     - `existsBy…` / a batched `findTimeEntryIdsWithAdjustments(companyId, ids)`
     - `findAllByCompanyIdAndTimeEntryIdIn`
   - `EvidenceRepository`: batched `findAllByCompanyIdAndTimeEntryIdInAndType`
   - `EmployeeRepository`: `findAllByCompanyIdAndIdIn`
   - `WorkScheduleRepository`: `findAllByCompanyIdAndDayOfWeek`, `findAllByCompanyIdAndEmployeeIdIn`
   - Update `tenancy/TenantRepositoryConventionTest` with `AuditLogRepository`.
7. `TimeEntryWriter.java` (modify): set `effectiveTime = now` on insert (constructor). `TodayService` / `TodayResponse.EntrySummary` (modify): `effectiveTime`, `adjusted`, worked minutes from effective times.
8. **Pure rules (new, unit-tested):**
   - `admin/DayStatusResolver.java`
   - `admin/KpiCalculator.java`
   - `admin/WorkedTime.java` (worked, approved, pending)
   - `admin/CorrectionRules.java`
   - `admin/DayAssembler.java`, which builds per-employee day records from batched rows and is shared by the dashboard and the export
   - New package `cr.attendance.admin`, which depends on `timeentry`, `employee`, `company`, `audit` and `common`. Nothing depends on `admin`.
9. **Services and controllers (new):**
   - `admin/AdminDashboardService` + `AdminDashboardController` (`GET /api/admin/dashboard`)
   - `admin/ReviewService` (approve / reject / correct / create; locks, `expected*` checks, adjustment rows, lateness recompute, integrity mapping) + `AdminTimeEntryController` (detail, actions, create)
   - `admin/ReviewQueueController` (queue + count)
   - `admin/AdminBranchController` (GET only in 4a)
   - DTO records per the API section
10. `timeentry/AdminTimeEntryPhotoController` / `PhotoService` (modify): `@PreAuthorize`; after a successful load, `auditService.record(PHOTO_VIEW, …)` + INFO line; per-admin limiter (`AdminRateLimits` config bean, keys `companyId:employeeId`).
11. **Method security:** `config/SecurityConfig.java` (modify) + `@EnableMethodSecurity`; `@PreAuthorize` on `AdminEmployeeController` too. `AccessDeniedException` from method security must still give the plain 403 (no body), the same as the URL layer. The coder verifies that `ApiExceptionHandler`'s catch-all does not turn it into a 500: add `@ExceptionHandler(AccessDeniedException.class)` → empty 403, or rethrow.
12. **Errors:** `common/ErrorCode.java` (+14 codes; only the ones used must exist by the end of 4a, but adding all now keeps the bundles in one change), and `messages.properties` / `_es` / `_en` (+28 keys each).
13. `application.yml`: `app.admin.max-dashboard-rows: 1000`, `app.admin.max-shift-hours: 24`, `app.admin.rate-limit.photo: {max-attempts: 300, window: 10m}`, `export: {10, 10m}`, `account: {30, 10m}`; `AdminProperties` record. Test config raises the limits (limiter tests override).
14. **Seed:** `seed/DevDataSeeder.java`, `seed/SeedProperties.java` (modify): ensure employees 1007-1012; `demoDay` flag; `seed/DemoDayEntries.java` (new; placeholder JPEG via Java2D → `StorageService`). `application-dev.yml`: `app.seed.demo-day: ${DEV_SEED_DEMO_DAY:false}`. `.env.example`: `DEV_SEED_DEMO_DAY=` (empty, commented).
15. **Open Question 8 (if yes):** `timeentry/ReviewReason.java` (+`NO_GPS_ACCURACY`, `CLOCK_OUT_AFTER_SCHEDULE`), `ReviewPolicy` + `TimeEntryWriter` (schedule end + `app.time-entry.after-schedule-tolerance: 60m`, overnight-aware), the PWA `reviewReason.*` keys, and the contract test.

**Frontend:**
16. `api/client.ts` (PUT/DELETE, `apiBlob`), `api/types.ts` (`effectiveTime`, `adjusted`, `TimeEntrySource`), `test/fixtures.ts`.
17. `vite.config.ts` (`rolldownOptions` codeSplitting group, `globIgnores`); `bundleBudget.test.ts`, `pwaConfig.test.ts` (modify).
18. `i18n/adminResources.ts` + `admin/adminI18n.ts` (new); `i18n/resources.ts` (+`error.*`, and `reviewReason.*` if Open Question 8); `errors/errorMessages.ts`; i18n tests.
19. `admin/*` shell: `AdminApp`, `AdminLayout`, `AdminNav` (review badge), `RequireAdmin`, `AdminReauthDialog`, `ChunkErrorBoundary`. `App.tsx` route `/admin/*`; delete `pages/AdminHome.tsx`; `LoginPage.tsx` admin landing + `next`.
20. `admin/pages/DashboardPage.tsx` + components (`KpiCards`, `AttendanceTable`, `DayStatusChip`, `EvidenceButton`, `DateField`, `BranchFilter`).
21. `admin/components/EvidenceDialog.tsx`, `ReviewActionDialog.tsx`, `ReasonField.tsx`, `CreateEntryDialog.tsx`; `admin/pages/ReviewQueuePage.tsx`, `Pagination.tsx`.
22. `components/EntryTimes.tsx` / `EntryResult.tsx` (modify): effective time + adjusted note; `i18n` keys `home.adjusted`.
23. `admin/admin.css` (designer tokens; no inline styles).

**Docs:**
24. README "Admin panel" (4a part); `.env.example` note.

### Sub-stage 4b
25. **Backend:**
    - `admin/AdminBranchService` + controller (POST/PUT/activate/deactivate; `BRANCH_IN_USE` check; audit)
    - `admin/AdminEmployeeService` (create/update/role/activate/deactivate/pin-reset/unlock; last-admin lock query; self guard; `token_version` bump via `incrementTokenVersion`; audit). `employee/AdminEmployeeController` becomes the 4b employee controller, or it moves into `admin` and keeps the same paths (the coder decides; tests unchanged).
    - `auth/PinGenerator` (new)
    - `employee/EmployeeQueryService` / `EmployeeSummary` → `AdminEmployeeView`
    - `admin/AdminScheduleService` + endpoints (`WorkScheduleRepository.deleteAllByCompanyIdAndEmployeeId`, `@Modifying`)
    - `EmployeeRepository`: `findAllByCompanyIdAndRoleAndActiveTrue` with `@Lock` (last-admin guard), `unlock(id, companyId)`, `updatePinHash(id, companyId, hash)` as atomic `@Modifying` queries or entity mutators (the coder keeps one style per field)
26. **Frontend:** `admin/pages/BranchesPage.tsx`, `EmployeesPage.tsx`, `EmployeeDetailPage.tsx`; `components/BranchForm`, `EmployeeForm`, `PinRevealDialog`, `ScheduleEditor`, `ConfirmDialog` reuse.

### Sub-stage 4c
27. `pom.xml`: `<poi.version>5.5.1</poi.version>` + `org.apache.poi:poi-ooxml:${poi.version}` (Open Question 12). Check `mvn dependency:tree` for conflicts (log4j-api is bridged by Boot's `log4j-to-slf4j`; no Log4j Core).
28. **Report code:**
    - `report/ReportService` (uses `DayAssembler`, `WorkedTime`)
    - `report/ExcelReportWriter` (XSSF, 4 sheets, styles, `ExcelText`)
    - `report/ExcelText` (sanitizer)
    - `report/ReportController` (`GET /api/admin/reports/excel`, limiter, audit, headers)
    - `report.*` keys in the 3 bundles
    - New package `cr.attendance.report` → `admin`, `audit`, `common`.
29. **Frontend:** `admin/pages/ReportsPage.tsx` (from/to/branch, client-side range check, `apiBlob` download); dashboard "Exportar a Excel" downloads the selected day.
30. README: export section.

### Verification (each sub-stage)
31. `cd backend; .\mvnw.cmd clean test`. Report the totals (632 before Stage 4).
32. `cd frontend; npm test; npm run lint; npm run build; npm test`. Report employee/admin JS and CSS gzip sizes and the precache size.

### Manual check (user's terminal, after 4a and again after 4c)
1. `backend\.env`: `DEV_SEED_DEMO_DAY=true`. `cd backend; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"`. Expect "Migrating schema … to version 4" and the dev seed log with counts only.
2. `cd frontend; npm run dev`, then open `http://localhost:5173/login` and sign in as `DEMO` / `9001` (PIN typed at the prompt). Expect `/admin` with "Asistencia de hoy", **9 de 12 / 2 / 3 / 1 fuera del área**, and the 12 rows with the mockup chips.
3. Open Luis's "GPS a 420 m": photo, distance, OSM link. Approve with a preset reason. The KPI "Por revisar" drops to 0 and the history shows the row.
4. Correct José's entrada to 08:03. The chip becomes "A tiempo" and "Tarde" disappears. In SQL, `server_time` is unchanged, `effective_time` is 08:03 and `late_minutes` is 0, with 3 adjustment rows.
5. Register a salida for María at 17:00 (reason "Olvidó marcar la salida"). The row becomes "Completo · 9 h 02".
6. Run `psql` as `asistencia_app`: `UPDATE adjustment SET reason='x';` gives the error "append-only table".
7. EN switch: every admin text is in English.
8. Sign in as `1001` on `/`: María's Entrada still shows 07:58, and the Salida 17:00 shows the "ajustada" note.
9. Run `npm run build; npm run preview`: the admin panel works under the production CSP (DevTools console shows no CSP/TT violation), and `dist/sw.js` has no `admin-` URL.
10. **4b:** create an employee (PIN shown once) and log in with it in a private window. Reset the PIN; the old window gets the re-auth dialog. Lock an account with 5 wrong PINs, then unlock it from the panel.
11. **4c:** export the current month, open it in Excel/LibreOffice and check the totals. Rename an employee `=1+1` beforehand: the cell shows `=1+1` as text.

## Tests

### Backend (`src/test/java/cr/attendance/...`)

**4a:**
- **`db/SchemaV4Test`:**
  - backfill `effective_time = server_time`; NOT NULL
  - `source ADMIN` accepted, `KIOSK` rejected
  - `ck_adjustment_action` / `field` / reason length
  - `branch.active` default true
  - `audit_log` cross-tenant actor rejected (composite FK)
  - `UPDATE`, `DELETE`, `TRUNCATE` on `adjustment` and `audit_log` raise; `INSERT` works
  - the 4 triggers exist
- **`db/SchemaMigrationTest`, `SchemaEnglishNamingTest`** (modify): versions 1-4, counts, new names, the enum round-trip loop includes `ADMIN`, `AdjustmentAction`, `AuditAction`.
- **`admin/DayStatusResolverTest`:**
  - each priority row and their conflicts (REJECTED beats PENDING, PENDING beats COMPLETE, …)
  - `NOT_YET_DUE` boundary at start + grace (the minute before and at it)
  - past date → `NOT_CLOCKED`
  - overnight schedule
  - missing clock-out after 16 h vs in progress at 15 h 59
- **`admin/KpiCalculatorTest`:** the mockup set gives 9/12/2/3/1 and `{OUTSIDE_GEOFENCE: 1}`; a DAY_OFF employee who clocked in counts in `expected`; a REJECTED clock-in is not present.
- **`admin/WorkedTimeTest`:** effective vs server time; approved only when both are VALID; pending; rejected gives null/0.
- **`admin/CorrectionRulesTest`:** future (+1 min boundary); CLOCK_IN window (-12 h, 24:00); CLOCK_OUT ≤ CLOCK_IN; > 24 h; CLOCK_IN after CLOCK_OUT; local-time conversion in `America/Costa_Rica` and a DST zone (gap and overlap).
- **`admin/AdminDashboardIntegrationTest`:**
  - mockup fixture → KPIs and statuses
  - default date = company today, with a test at 23:30 CR (05:30 UTC next day)
  - `branchId` filter, including an employee moved since
  - an inactive employee with an entry is listed
  - another tenant is never visible
  - an EMPLOYEE token gives 403
  - `date=2026-13-01` gives 400 with no echo
  - row cap gives `REPORT_TOO_LARGE` (property override)
- **`admin/ReviewActionsIntegrationTest`:**
  - approve gives VALID + 1 row (`APPROVE`, `status`, `PENDING_REVIEW`→`VALID`, admin id, reason)
  - reject from VALID
  - approve from REJECTED (undo)
  - approving a VALID entry gives 409 `INVALID_STATUS_CHANGE` with no rows
  - correcting a CLOCK_IN gives 3 rows, `late_minutes` recomputed, and `server_time` unchanged
  - correcting a CLOCK_OUT gives worked minutes in the dashboard **and** in `GET /api/time-entries/today` for that employee
  - a wrong `expectedStatus` / `expectedEffectiveTime` gives 409 `ENTRY_CHANGED`
  - 2 threads approving the same entry: one 200 and one 409
  - reason blank, 4 or 501 characters gives 400
  - another tenant's id gives 404
  - an EMPLOYEE token gives 403
  - the response never contains `clientUuid`
- **`admin/AdminCreateEntryIntegrationTest`:**
  - CLOCK_OUT for a forgotten shift gives `COMPLETE`, `source ADMIN`, no evidence, a `CREATE_ENTRY` row
  - CLOCK_OUT without CLOCK_IN gives 409 `CLOCK_IN_REQUIRED`
  - a duplicate gives 409 `ENTRY_ALREADY_EXISTS`
  - today's CLOCK_IN by the admin, then the employee's PWA `POST` CLOCK_OUT, gives 201
  - another tenant's employee gives 404
  - an employee without a branch (an ADMIN) gives 409 `NO_BRANCH_ASSIGNED`
  - a future time gives 400
  - concurrent creation with the employee's own POST: exactly one CLOCK_IN row
- **`admin/ReviewQueueIntegrationTest`:** order, paging (`totalItems`), `size=101` gives 400, `REJECTED` filter, date range, `employeeId` filter, the pair included, the count endpoint, tenant isolation.
- **`admin/AdminEvidenceIntegrationTest`:** detail with coordinates and the adjustment history (admin names); a `PHOTO_VIEW` audit row per successful photo GET and none on 404; limiter (`max-attempts=3`) gives 429 + `Retry-After` on the 4th; another admin is unaffected.
- **`config/AdminMethodSecurityTest`:** reflection over `@RestController`s with an `/api/admin` mapping (class or method) asserts `@PreAuthorize`; a parameterized MockMvc test hits every admin route with an EMPLOYEE token and expects 403 with an empty body.
- **`seed/DevSeedEnabledTest` / `DevSeedContentTest`** (modify): 12 employees ensured on an existing DEMO without touching existing PINs; `demo-day=true` gives today's KPIs 9/12/2/3/1, and a second run adds nothing; the output has counts only.
- **Existing suites stay green:** `TodayStateIntegrationTest` gets the `effectiveTime` assertion; `TenantRepositoryConventionTest`, `MessageBundleParityTest`, the photo access tests (+ audit side effect) and every auth test.

**4b:**
- **`admin/AdminBranchIntegrationTest`:**
  - create / update
  - validation: NaN, 91, -181, a comma decimal, radius 9 and 5001, a blank name, control characters
  - a duplicate name gives 409
  - deactivate with an active employee gives 409 `BRANCH_IN_USE`, then OK after moving them
  - assigning an inactive branch gives 409
  - a moved location changes only the next clock-in's distance
  - audit rows
- **`admin/AdminEmployeeIntegrationTest`:**
  - create gives 201 with an 8-digit PIN that passes `PinPolicy`; logging in with it gives 200; `Cache-Control: no-store`
  - the PIN is absent from the captured log output and from `audit_log.details`
  - a duplicate code (also in lowercase) gives 409
  - EMPLOYEE without a branch gives 400 `BRANCH_REQUIRED`
  - another tenant's branch gives 404
  - extra JSON fields (`companyId`, `pinHash`, `tokenVersion`, `active`) have no effect
  - update; the code is immutable (the field is ignored)
  - a role change makes the old token 401
  - demoting or deactivating the last admin gives 409 `LAST_ADMIN`; concurrent demotion of two admins by each other leaves one admin
  - self-deactivation gives 409
  - deactivate makes the token 401 and login fail; activate restores login
  - PIN reset: the old PIN fails, the new one works, the token is 401, the lock is cleared
  - unlock after 5 failures, then login 200
  - the list shows `locked` and `consentGiven`
  - an EMPLOYEE token gives 403
- **`admin/AdminScheduleIntegrationTest`:** replace the week; remove a day; overnight accepted; day 0/8, a duplicate day, start = end and grace 241 give 400; stored `late_minutes` unchanged; another tenant gives 404; audit row.
- **`auth/PinGeneratorTest`:** 10,000 generated PINs all valid, 8 characters, digits only, with leading zeros possible; generated with a seeded `Random` for determinism through an injected source.

**4c:**
- **`report/ExcelTextTest`:** every trigger character (including full-width, tab, CR and LF) gives the quote-prefixed flag; control characters are stripped; lone surrogates are removed; 40,000 characters are truncated; plain text, numbers as text and empty input are untouched.
- **`report/ReportIntegrationTest`:**
  - headers (content type, attachment filename, `no-store`)
  - parse with `XSSFWorkbook`: 4 sheet names in ES and in EN (`Accept-Language: en`)
  - Summary approved vs pending hours for the mockup data plus a correction
  - Detail rows per employee-day, rejected excluded from approved
  - local dates: an entry at 23:30 CR on the last day is included
  - the Adjustments sheet has the self-approval flag
  - **injection:** an employee named `=HYPERLINK("http://x","y")` and a reason starting with `@` give string cells with `getQuotePrefixed()`; a scan of every cell in every sheet finds no `CellType.FORMULA` and no hyperlinks
  - no latitude, longitude or storage key strings anywhere in the workbook
  - 63 days give 400 `DATE_RANGE_TOO_LARGE`; `from > to` gives 400; the row cap gives `REPORT_TOO_LARGE`
  - limiter 429
  - `REPORT_EXPORT` audit row
  - another tenant's data is absent; an EMPLOYEE token gives 403

### Frontend (Vitest + Testing Library + jsdom)

- `api/client.test.ts` (modify): PUT/DELETE; `apiBlob` returns the blob and filename; 401 marks re-auth; a problem body is mapped.
- `api/contract.test.ts` (modify): Java enums `DayStatus`, `TimeEntrySource`, `AdjustmentAction` (and new `ReviewReason`s) vs the TS unions and translation maps; fixtures for the admin DTOs.
- `admin/RequireAdmin.test.tsx` + `App.routes.test.tsx` (modify):
  - no session gives `/login?next=/admin/review`
  - EMPLOYEE gives `/`
  - ADMIN renders the panel
  - login as ADMIN lands on `/admin`
  - `next=//evil.example` / `next=https://…` / `next=/` give `/admin`
  - an ADMIN with `consentPending` is not sent to `/consent`
  - `/admin` chunk failure (mock `import()` rejecting) shows the retry message
- `admin/pages/DashboardPage.test.tsx`:
  - the mockup fixture renders "9 de 12", "2", "3", "1 fuera del área"
  - every `DayStatus` chip in ES and EN
  - "en turno", "8 h 04"
  - the date change refetches with `date=`
  - the default is the server `today`
  - the branch filter
  - refresh on `visibilitychange`; 60 s polling only for today (fake timers)
  - empty and error states
  - the table has a caption, `th scope`, and row headers
  - the evidence button's accessible name
- `admin/components/EvidenceDialog.test.tsx`:
  - no photo request before opening
  - an object URL is created when it opens and revoked on close or unmount (spies)
  - 404 gives "Sin foto"
  - the OSM link `href` with 6 decimals has `rel` containing `noopener` and `noreferrer`
  - the adjustment history is formatted in the company zone
- `admin/pages/ReviewQueuePage.test.tsx`:
  - the list, paging and pair
  - approve with a preset sends `{reason, expectedStatus}`
  - reject needs ≥ 5 characters (button disabled, plus an error)
  - correct converts `datetime-local` in the **company** zone even with a different stubbed browser zone
  - 409 `ENTRY_CHANGED` shows the message and reloads
  - the badge count updates
  - focus returns to the next item
- `admin/components/CreateEntryDialog.test.tsx`: payload; `CLOCK_IN_REQUIRED` message.
- **4b:**
  - `EmployeesPage.test.tsx` / `EmployeeDetailPage.test.tsx`:
    - create shows `PinRevealDialog` with 4+4 groups
    - closing clears it: the PIN text is absent from the DOM, `sessionStorage`, `localStorage` and IndexedDB
    - reset PIN needs a confirm
    - unlock
    - deactivate needs a confirm
    - the self-deactivate button is disabled for one's own row
    - `LAST_ADMIN` message
    - code field uppercased and validated
  - `BranchesPage.test.tsx`: paste "9.9325, -84.0796" fills both fields; a comma decimal is rejected; radius bounds; `BRANCH_IN_USE` message.
  - `ScheduleEditor.test.tsx`: week grid with `Intl` weekday names ES/EN; overnight hint; copy-from; payload.
- **4c:** `ReportsPage.test.tsx`: > 62 days is blocked client-side; the download calls `apiBlob`, creates an `<a download>` with the server filename and revokes it; 429 message.
- `pages/EmployeeHome.test.tsx` (modify): the effective time is shown with the adjusted note.
- `i18n/resources.test.ts`, `noHardcodedText.test.ts` (modify) cover the admin resources and files.
- `bundleBudget.test.ts`, `pwaConfig.test.ts` (modify) per the Frontend section.
- **Accessibility** checks inside the page tests: one `h1` focused on navigation, one `role="status"` per page, dialog focus return, `role="alert"` on errors, labelled inputs.

## Hand-off to the architect (before coding)

`docs/diagrams/stage-4-admin-panel.md`:
1. **ER delta (V4):**
   - `time_entry.effective_time`, `source` + `ADMIN`
   - `adjustment.action` + CHECKs + index
   - `branch.active`
   - `audit_log` with its composite FK
   - the append-only triggers
   - the recreated partial index
2. **Package diagram:** new `admin`, `audit` and `report`. Arrows:
   - `admin` → `timeentry`, `employee`, `company`, `audit`, `common`
   - `report` → `admin`, `audit`, `common`
   - `audit` → `common`
   - no cycles; nothing depends on `admin` or `report`
   - `auth.PinGenerator` used by `admin`
3. **Sequence, correct a CLOCK_IN:** controller → `ReviewService` → lock `time_entry` → `expected*` check → `CorrectionRules` → `LatenessCalculator` → mutate → 1-3 `adjustment` inserts → commit; the 409 / 400 / 503 branches.
4. **Sequence, admin-created CLOCK_OUT** vs a concurrent PWA POST (the employee row lock, the unique-index backstop).
5. **Sequence, dashboard:** batched queries (employees, schedules for the weekday, entries for `work_date`, photo evidence, adjustment existence) → `DayAssembler` → `DayStatusResolver` → `KpiCalculator`.
6. **State diagram, entry status:** VALID / PENDING_REVIEW / REJECTED with the allowed transitions and the actions that cause them.
7. **Frontend module diagram:** `App` → lazy `admin/AdminApp` (separate chunk, not precached) → pages → components → `admin/api` → `api/client` → `auth/session`; `adminResources` only in the admin chunk.
8. **Review** the `effective_time` vs `server_time` split and the KPI definitions, and list any gaps as amendments (the Stage 1/2 practice).

## Hand-off to the designer (before coding)

`docs/design/stage-4-admin-panel.md`, with ES and EN copy keys for everything:
1. **Admin shell:** header (company, branch filter, "Mi marca", language, logout), left nav with the review badge (≥ 1024 px) and top tabs (768-1023 px), skip link, chunk-error and loading states, re-auth dialog.
2. **Dashboard** exactly per mockup page 2:
   - KPI cards (including the "+n aún no inician" and "1 fuera del área" subtitles)
   - table columns, avatar initials and chip palette for the 11 `DayStatus` values with **measured AA contrast**
   - the "editada" marker and the secondary late line
   - stacked rows below 768 px
   - empty, loading and error states
3. **Evidence dialog:** photo frame (portrait 3:4, max 480 px), facts layout, map links, history list, action buttons.
4. **Review queue:** item card or row, pair display, the three action dialogs (reason presets + free text, `datetime-local` with the company zone shown), success and 409 states.
5. **Create-entry dialog.**
6. **4b:** employees list + filters, employee detail (profile form, role, branch, status actions, lock indicator, consent indicator), PIN reveal dialog, confirm dialogs, schedule week grid, branches list + form (paste helper).
7. **4c:** reports page (range, branch, download states).
8. **Tokens:** reuse the Stage 3 tokens (`#0F5F56` teal, `#F2F2F0` background, text `#1C1F1E`); add amber, violet and red-brown chip pairs as in the mockup; table density; desktop type scale; focus ring.
9. **A11y notes per screen** (focus after each action, live-region texts, table semantics).
10. Register: **"usted"** for admin copy (desktop, business tone; the backend bundles already use "usted"), unlike the PWA's "tú". The designer confirms.

## Hand-off to the security auditor (after 4a, and again after 4c)

Focus on:
- **Authorization:** both layers on every admin route, the method-security 403 body, `RequireAdmin` being cosmetic only, and the safety of the `next` parameter.
- **Tenant isolation:**
  - every id in paths **and bodies** (`employeeId`, `branchId`)
  - batched `IN` queries carrying `companyId`
  - the review queue and dashboard queries
  - audit rows with a cross-tenant actor
- **Mass assignment** on employee and branch requests (`companyId`, `pinHash`, `tokenVersion`, `active`, `code`).
- **Audit integrity:** append-only through the repository and the trigger, rows written in the same transaction as the change, no PIN or personal text in `audit_log.details`, `PHOTO_VIEW` coverage and limiter.
- **Corrections:** `server_time` immutability; the `effective_time` window; lateness recompute; worked-time and payroll rules (L-4: can a VALID fraud still pass unnoticed? spot-check path); self-approval visibility.
- **Concurrency:** `ENTRY_CHANGED`, the employee lock on create, last-admin race.
- **PIN lifecycle:** generation entropy, the policy, no-store, never logged, client-side memory wipe after the reveal, `token_version` bumps on reset, role change and deactivate.
- **Data minimization:** coordinates only in the detail; OSM link leakage (Referrer-Policy); photos never cached or prefetched; no `client_uuid` or keys exposed.
- **Excel:** formula and DDE injection (including full-width triggers), hyperlink absence, XML-illegal characters, filename header injection, memory bound, limiter, audit, POI version and transitive dependency CVEs.
- **Frontend:** CSP/TT still clean with the admin chunk; blob URL lifetimes; no admin chunk in the SW precache; `dangerouslySetInnerHTML` absent.
- **Earlier follow-ups:** Stage 1 M-3 (unlock + indicator done; alerting still Stage 5), I-1, I-2; Stage 2 L-4, L-6, I-3; Stage 3 I-4 (still deferred).

## Edge Cases to Handle

- **Company-local "today" vs the admin PC.** The defaults come from the server; the browser zone is never used for dates. Tested with a stubbed browser zone.
- **Entry at 23:30 CR** (05:30 UTC the next day). It belongs to its `work_date`, appears on that day's dashboard and in an export ending that day.
- **Overnight shift** (22:00-06:00). It appears on the start date; "en turno" while open. At 06:00 the next morning, the previous day is `COMPLETE` and the new day is `NOT_YET_DUE`.
- **Forgotten clock-out.** `MISSING_CLOCK_OUT` after 16 h or on past dates. The admin creates the CLOCK_OUT; the export lists it under missing clock-outs until fixed.
- **CLOCK_OUT after 16 h (`SHIFT_TOO_LONG`).** It is PENDING_REVIEW, so it appears in the queue. Approving it counts the long day; correcting it shortens it.
- **Admin creates today's CLOCK_IN, then the employee clocks out from the PWA.** This works because the open-shift check uses `server_time = now`.
- **Admin creates a CLOCK_IN while the employee's PWA POST is in flight.** The employee row lock serializes them: one wins and the other gets 409 (`ENTRY_ALREADY_EXISTS` for the admin, `ALREADY_CLOCKED_IN` for the PWA, whose outbox shows its failure card).
- **Correcting a CLOCK_IN past its CLOCK_OUT.** Rejected (`INVALID_ENTRY_TIME`). The admin corrects the CLOCK_OUT first.
- **Rejecting the CLOCK_IN of a complete day.** Worked time is null and approved time 0; the row is `REJECTED`; the CLOCK_OUT keeps its own status.
- **Rejecting a CLOCK_IN does not let the employee clock in again** (unique index includes REJECTED, Stage 2). The admin creates nothing new for that day; the correction path is to undo, approve and correct.
- **Approving an entry with `SYNCED_LATE`.** The status changes, the time does not. The admin should correct the time to `device_time` when appropriate; the dialog shows device time and skew and offers "Usar hora del teléfono" to pre-fill the correction.
- **Schedule changed after the fact.** Stored lateness stays; only a CLOCK_IN correction recomputes it with the current schedule (documented in the Info sheet legend).
- **Employee moved to another branch.** Past entries keep their `branch_id`; the dashboard filter uses the entry's branch for that day.
- **Inactive employee with history.** Listed on days with entries (marked inactive), excluded from expected and not-clocked counts otherwise, and included in exports for the days they have data.
- **Employee without a schedule who clocks in.** `UNSCHEDULED`; counted as present and expected; no lateness.
- **Weekend / day off.** `DAY_OFF` rows are sorted last and not counted.
- **Before the shift starts today.** `NOT_YET_DUE`, not "Sin marcar"; the KPI subtitle explains it.
- **Very small or empty company.** KPIs "0 de 0"; empty state with a link to Employees (4b).
- **Large company (> 1000 rows).** `REPORT_TOO_LARGE` with "choose a branch"; the review queue is paged anyway.
- **Two admins reviewing the same entry.** The second gets `ENTRY_CHANGED` and a reload.
- **Admin token expires mid-form.** The re-auth dialog keeps the page; the user re-submits (no auto replay of writes).
- **Admin deactivated or demoted while using the panel.** The next request gets 401 (token_version) or 403; the panel shows "sesión terminada" and goes to login.
- **Last admin locked out by the lockout (Stage 1 M-3).** Another admin can unlock; with a single admin, auto-unlock after 15 min still applies. Documented; the Stage 5 runbook gets an operator SQL for emergencies.
- **Admin resets their own PIN.** Allowed, with a warning; the dialog shows the new PIN; then all their sessions end and they log in again.
- **A role change to ADMIN for someone currently clocked in.** No effect on entries; their next request has the new role.
- **Deactivating a branch with only inactive employees.** Allowed. Reactivation is allowed.
- **Coordinates pasted with a comma decimal** ("9,9325") are rejected with a hint; "9.9325, -84.0796" is split on the comma plus space.
- **Branch at (0,0) or a typo in the hemisphere.** Allowed by the ranges; the form shows an OSM preview link so the admin can check before saving.
- **Photo missing on disk / purged by retention.** "Sin foto" in the viewer; the export still lists the entry.
- **`source = ADMIN` entries have no photo or GPS.** Evidence shows "Registro manual" with the reason, and they never need review.
- **Self-approval by a single-admin company.** Allowed (Open Question 4) and flagged in the Adjustments sheet.
- **Bulk approval** (for example, a branch whose geofence was misconfigured for a week). Out of scope; the admin fixes the branch (4b) and approves one by one. Noted as "Later" ("Aprobar seleccionadas").
- **Excel opened in LibreOffice.** The quote prefix and the `[h]:mm` format are supported (manual check).
- **Export in English for a Spanish payroll clerk.** The headers follow the UI language; the Info sheet states the language.
- **Export period in the future.** Allowed; rows show `NOT_YET_DUE`/`DAY_OFF` and no absences (absences are counted for past days only).
- **Clock skew between the admin PC and the server.** Irrelevant: all times are server instants formatted in the company zone.
- **Chunk load failure after a deploy** (old hashed admin chunk gone). The error boundary offers a reload.
- **Service worker on the admin PC.** The shell is precached but the admin chunk is not; offline gives the boundary message, not a broken page.
- **Accent and casing in search** ("jose" finds "José"). The client-side search uses `localeCompare` / `normalize('NFD')` without diacritics.

## Open Questions: RESOLVED (2026-10-28, user)

This section overrides anything earlier in this plan that conflicts with it.

- **Q1-Q5, Q7-Q13:** the recommended defaults are adopted (split 4a → 4b → 4c with a commit each and security audits after 4a and 4c; `effective_time` beside an unchanged `server_time`; employees see corrected times with a note; admin self-approval allowed but marked "Autoaprobado"; photo-view logging + 300/10 min limit in 4a; system-generated PINs shown once; `NO_GPS_ACCURACY` and `CLOCK_OUT_AFTER_SCHEDULE` in 4a; overtime informational only; 62-day export limit; demo seed 1007-1012 + opt-in `DEV_SEED_DEMO_DAY`; Apache POI 5.5.1; no `national_id` column).
- **Q6, map link: Google Maps**, not OpenStreetMap. "Ver en el mapa / View on map" → `https://www.google.com/maps/search/?api=1&query={lat},{lng}` as a plain `<a target="_blank" rel="noopener noreferrer">`, opened only when clicked; nothing embedded, no Maps JavaScript API, no API key, no CSP change. The list/table never shows coordinates; the single-entry evidence view shows distance from the branch, GPS accuracy (±N m) and the exact coordinates next to the link. Replace every OpenStreetMap reference above (Decisions item 3, CSP section, tests) with this.
- **Q14, register: "usted" everywhere**, in the admin panel **and in the employee PWA**. 4a includes converting the existing Stage 3 Spanish PWA texts in `frontend/src/i18n/resources.ts` (and the consent text, design spec notes and any test assertions on Spanish strings) from "tú" to "usted" (e.g. "ajustada por su administrador", "¿No es usted?" stays). Backend `messages_es.properties` already uses "usted"; verify and fix any "tú" left. Add a test that flags common "tú" forms (only unambiguous forms, e.g. `\btú\b`, `\btu\b`, `\btienes\b`, `\bpuedes\b`, `\bquieres\b`, `\bdebes\b`, `\bte\b`) in Spanish resources.

## Amendments (2026-10-29)

- **Maximum shift for admin corrections and admin-created clock-outs = 16 h**, the same `app.time-entry.max-shift-duration` the employee app uses (Stage 2 user decision). There is no `app.admin.max-shift-hours` and no 24 h limit; this replaces line items in Decisions (CLOCK_OUT window), Implementation step 13 and the `CorrectionRulesTest` bullet. Architect note 8 flagged the 16 h / 24 h conflict; the main session chose 16 h for consistency.

### From code review #1 (2026-10-30, adopted by the user)

- **"En turno" (`inProgress`) and stale clock-ins.** A CLOCK_IN without a CLOCK_OUT is `inProgress` only while `now <= in.effectiveTime + maxShift` (16 h). Reviewer's suggested condition: `!date.isBefore(today) && now <= in.effectiveTime + maxShift`. Applied with one adjustment so overnight shifts still work: the `!date.isBefore(today)` part is dropped, because a 22:00 clock-in on day D viewed at 00:30 on D+1 has a past date but must still show "en turno". After the 16 h window the row becomes `MISSING_CLOCK_OUT` and offers "Registrar salida"; inside it, no "Registrar salida" is offered. The same rule applies in `DayAssembler` (`inProgress`), `DayStatusResolver` (`MISSING_CLOCK_OUT`) and `frontend/src/admin/rows.ts`. Tests: 22:00 → 00:30 next day (en turno, no clock-out action); past day with a pending clock-in older than 16 h (missing clock-out, not "en turno"); today, clock-in 3 h ago (en turno).
- **`workDate` bounds for admin-created entries.** `POST /api/admin/time-entries` rejects a `workDate` before today minus 5 years (and after today) with 400, the same rule `AdminDashboardService` already applies to the dashboard date. The review queue's `from`/`to` get the same bounds. Tests for 1900-01-01, today − 5 years (boundary) and tomorrow.
- **Null values in adjustment rows.** `old_value`/`new_value` store SQL `NULL`, never the text `"null"`: `oldLate == null ? null : String.valueOf(oldLate)`, and the same for `newLate` and any other nullable field. Test: correcting a clock-in with no computed lateness stores `old_value IS NULL`.
- **Exception, `new_value` (user decision 2026-10-30):** `adjustment.new_value` stays NOT NULL (V2 constraint, no schema change). An unknown new value is stored as an empty string. The evidence history (and the 4c Adjustments sheet) shows an empty value, a NULL old value and any legacy `"null"` as "—", with an accessible label "sin valor / no value".

### From the Stage 4a security audit (2026-11-02, adopted by the user): do in 4b

Source: `securityAudit-report/stage-4a-admin-panel.md`.
- **L-1, invisible characters in reasons.** `ReviewRequests.java` reason validation also rejects format characters (`\p{Cf}`: bidi overrides, zero-width spaces), with the same localized 400. Apply the same rule to the new 4b free-text fields (branch, employee and schedule names, PIN-reset reason). Tests: U+202E and U+200B give 400; ordinary accented Spanish text passes.
- **L-2, location exposure on entry detail.** `GET /api/admin/time-entries/{id}` (exact GPS) gets a per-admin rate limit like the photo view (same bucket style, configurable) and writes an `audit_log` row `LOCATION_VIEW` (V5 migration widens the action check if needed). Tests: an audit row per view, 429 over the limit, other tenant 404 with no audit row.
- **L-4, no rate limit on admin write actions.** approve / reject / correct / create (and the 4b CRUD writes) share a per-admin write limit (configurable, e.g. 120 per 10 minutes) returning 429 `RATE_LIMITED` with `Retry-After`. Tests: over the limit gives 429 and writes nothing; the limit is per admin.
- **Deferred to Stage 5:** L-3 (separate migration DB user; runtime user limited to SELECT/INSERT on `adjustment` and `audit_log`) and L-5 (dev-tool `npm audit` advisories; run the non-breaking `npm audit fix`, never downgrade `vite-plugin-pwa`).

## Open Questions (original)

Each has a recommended default. Answer "as recommended" to accept all.

1. **Split into 4a → 4b → 4c, in that order?**
   - **Recommended: yes.** Each sub-stage is a coder + tester pass with its own commit, and the security audit runs after 4a and after 4c.
   - Alternative: one pass. It is very large and gives a long time without green checkpoints.
2. **Correction model.**
   - **Recommended: A.** Keep `server_time` immutable and add `effective_time`; payroll, lateness and the dashboard use `effective_time`. The original receipt time stays as evidence, and the `adjustment` rows explain the difference.
   - Alternative B: overwrite `server_time` and keep only the old value in `adjustment`. That means less code, but the receipt fact is lost from the row and the sequence rules would see admin-edited times.
3. **Should the employee see corrected times in the PWA?**
   - **Recommended: yes.** Show the effective time with a small "ajustada por tu administrador" note. It is transparent and avoids disputes.
   - Alternative: the PWA keeps showing the received time, and corrections are visible only to admins.
4. **May an admin approve, reject or correct their own entries?**
   - **Recommended: allowed** (small companies often have one admin who also clocks), but visible: a "Autoaprobado" column in the export and the history shows it.
   - Alternative: forbid it (409) unless no other active admin exists.
5. **Photo access logging (Stage 2 L-6) now, not in Stage 5?**
   - **Recommended: yes, in 4a.** A `PHOTO_VIEW` row in the new `audit_log` table plus a per-admin limit of 300 photos per 10 min. Stage 5 only adds alerting.
   - Alternative: a log line only now, with the DB audit in Stage 5.
6. **Map link in the evidence viewer.**
   - **Recommended: an OpenStreetMap link** (opens in a new tab only when clicked; nothing embedded), plus the exact coordinates shown in the detail.
   - Alternatives: a Google Maps link, or no link (distance and coordinates only, nothing leaves the app).
7. **PINs for new employees and resets.**
   - **Recommended: system-generated** random 8-digit PIN (passes `PinPolicy`), shown once to the admin, who hands it over.
   - Alternative: the admin types the PIN (validated by `PinPolicy`). People pick guessable numbers more often.
8. **Stage 2 audit L-4 extras: add two review reasons now?**
   - The reasons:
     - `NO_GPS_ACCURACY`: coordinates sent without accuracy, or accuracy < 1 m. This is a scripted-client signal; the PWA always sends accuracy.
     - `CLOCK_OUT_AFTER_SCHEDULE`: clock-out more than 60 min after the scheduled end, configurable and overnight-aware.
   - **Recommended: yes**, in 4a. More honest payroll; long days get a look.
   - Alternative: defer them to Stage 5 and rely on approved-only totals.
9. **Overtime in the Excel export.**
   - **Recommended:** do **not** classify legal overtime (day, night and mixed rules, +50%). Export worked minutes, scheduled minutes and "minutes beyond schedule" as information, and let payroll decide.
   - Alternative: compute overtime per Código de Trabajo art. 136-139. This needs rules per shift type, which is a bigger, legally sensitive feature.
10. **Maximum export period.**
    - **Recommended: 62 days** (covers a monthly payroll plus margin).
    - Alternative: 31 days, or 1 year with streaming (SXSSF temp files on disk).
11. **Demo seed for the mockup.**
    - **Recommended: yes.** Ensure 12 demo employees (adds 1007-1012), plus an opt-in `DEV_SEED_DEMO_DAY=true` that inserts today's mockup entries (María 07:58, José 08:17 late 17, Ana tablet, Luis 420 m outside, Karla 06:00-14:04, …) with placeholder photos.
    - Alternative: no demo entries; you create them by clocking in.
12. **Apache POI version.**
    - **Recommended: 5.5.1** (stable since 2025-11).
    - Alternative: **6.0.0** (released 2026-10-22, JDK 17+); move to it with the Spring Boot 4 migration in Stage 5.
13. **Employee code and national ID (cédula) for payroll.**
    - **Recommended:** no new column. The export uses the employee code, which may hold the cédula (the format `1-1234-5678` fits the code rule).
    - Alternative: add an optional `employee.national_id` column (more personal data to protect).
14. **Admin copy register.**
    - **Recommended: "usted"** in the admin panel (business and desktop tone, matching the backend messages), while the PWA keeps "tú".
    - Alternative: "tú" everywhere.
