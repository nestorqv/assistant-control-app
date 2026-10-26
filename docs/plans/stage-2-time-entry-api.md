# Stage 2 - Check-in / time-entry API

Roadmap: `docs/plans/00-roadmap.md`. Previous stages: `docs/plans/stage-1-data-model-auth.md` (with its Amendments), `docs/plans/stage-1b-english-naming-i18n.md`. Diagrams: `docs/diagrams/stage-1-er.md`. Audit: `securityAudit-report/stage-1-auth.md`. Blueprint: `docs/prototype/Prototipo-Control-Asistencia.pdf` (page 1 employee screen, page 2 admin dashboard).
Branch: `stage1-2`. Status: IMPLEMENTED 2026-10-22 (security audit and manual end-to-end check pending). Open questions RESOLVED 2026-10-22 (see the last section; where earlier text conflicts, the resolution wins).

## Summary

Stage 2 adds four employee endpoints and one admin endpoint to `cr.attendance.timeentry`, plus a new `cr.attendance.storage` package. They all reuse the Stage 1 patterns: tenant-scoped repositories with a `companyId` parameter, `EmployeePrincipal` from the per-request DB re-check, the injected UTC `Clock`, and `ErrorCode` + `ApiExceptionHandler` + `messages*.properties` for ProblemDetail bodies with a stable English `code`.
- `GET /api/time` returns the server time and the company's time zone.
- `GET /api/time-entries/today` returns the current shift state that drives the single button.
- `POST /api/time-entries` (multipart) records a CLOCK_IN or CLOCK_OUT. `server_time` is always `Instant.now(clock)`, and the device clock is only a review signal.
- `GET /api/time-entries/{id}/photo` lets an employee see their own photo, and `GET /api/admin/time-entries/{id}/photo` lets an admin see their company's photos.

A POST is processed in this order:
1. A per-employee rate limit.
2. Bean Validation of the form (the photo part is not part of Bean Validation).
3. A cheap idempotency lookup on `(company_id, client_uuid)`. A replay returns the original entry here, with or without a photo part.
4. The photo is **required**: a missing part gives 400 `PHOTO_REQUIRED` and nothing is written.
5. Photo processing outside any lock: a size cap, a magic-byte sniff (JPEG/PNG only), a dimension guard, then a server-side re-encode to JPEG. Re-encoding drops all EXIF, including GPS.
6. One transaction that takes a row lock on the employee and then:
   - re-checks idempotency
   - requires consent (Ley 8968) and an assigned branch
   - applies the shift-sequence rules
   - computes the Haversine distance, clock skew, late-sync flag, lateness and review reasons
   - writes the photo through `StorageService` (deleted again if the transaction rolls back)
   - inserts the `time_entry` and its `evidence` rows (PHOTO, GPS)

A new Flyway `V3__time_entry_checkin.sql` adds these columns to `time_entry`:
- `work_date`: the company-local date the shift belongs to
- `skew_seconds`, `late_minutes`, `gps_accuracy_meters`, `review_reasons`

It also adds a unique index `(employee_id, work_date, type)` as the database backstop for "one CLOCK_IN/CLOCK_OUT pair per day", evidence constraints (one row per type per entry, a storage-key format check, and a tenant prefix check) and a dashboard index. "Today" is always computed in `company.time_zone` and never in UTC or the JVM zone. A shift belongs to the date it starts, and a CLOCK_OUT closes the open CLOCK_IN for up to 16 h, which covers overnight shifts. Lateness is computed once at CLOCK_IN and stored. Tests run on zonky embedded PostgreSQL through MockMvc with a temp storage root. The user runs the real server for the manual end-to-end check.

## Verified against official sources (2026-10-22, WebFetch)

| Item | Finding | Consequence |
|---|---|---|
| `ResponseEntityExceptionHandler` (Framework 6.2 javadoc) | `handleException` routes `MaxUploadSizeExceededException` to `handleMaxUploadSizeExceededException` (since 6.1) and `MissingServletRequestPartException` to `handleMissingServletRequestPart`. Both delegate to `handleExceptionInternal`. **Plain `MultipartException` is not in the list.** | Our existing `handleExceptionInternal` override already normalizes the body. Add `case 413 -> PAYLOAD_TOO_LARGE` to `codeFor`. Add an explicit `@ExceptionHandler(MultipartException.class)` that returns 400 `INVALID_REQUEST`, otherwise a malformed multipart body falls through to the Boot error page. |
| `MaxUploadSizeExceededException` (6.2.19 source) | Implements `ErrorResponse`; `getStatusCode()` returns `HttpStatus.PAYLOAD_TOO_LARGE`. | Oversize gives **413** with `code: PAYLOAD_TOO_LARGE`. |
| `StandardMultipartHttpServletRequest.handleParseFailure` (6.2.19 source) | Container errors whose message contains "exceed/limit" + "size/length/count" become `MaxUploadSizeExceededException(-1)`. Others become `MultipartException`. `getOriginalFilename()` is **not sanitized**. | Never use the client filename or content type. Storage keys are generated server-side only. |
| `MultipartProperties` (Boot 3.5 javadoc) | `max-file-size` defaults to 1MB, `max-request-size` to 10MB, `file-size-threshold` to 0, and `location` to a temp dir. | Set all of them explicitly (Step 1). |
| `ServerProperties.Tomcat` (Boot v3.5.16 source) | `maxPartCount = 50`, `maxPartHeaderSize = 512B`, `maxSwallowSize = 2MB`, `maxHttpFormPostSize = 2MB`. | Tighten `max-part-count` to 10 (our form has 7 parts). Raise `max-swallow-size` above `max-request-size`, so an oversize upload gets a readable 413 instead of a connection reset. |
| Hibernate 6.6 `LocalDateJdbcType` (javadoc) | Exists: "handling LocalDate directly through the JDBC driver". | Map `TimeEntry.workDate` with `@JdbcType(LocalDateJdbcType.class)`. This mirrors the `LocalTimeJdbcType` fix on `WorkSchedule`, because the default DATE binding goes through `java.sql.Date` and can shift a day when `hibernate.jdbc.time_zone=UTC` and the JVM is east of UTC. Tested in Step 9. |

Not verified online (JDK built-ins, stable for years): `javax.imageio` `ImageReader.getWidth/getHeight` read only the header, so dimensions can be checked before decoding; the JDK JPEG writer rejects images with alpha, so convert to `TYPE_INT_RGB` first; `ImageIO.setUseCache(false)` avoids temp files. No new Maven dependencies are needed.

**MockMvc caveat:** `MockMultipartFile` bypasses the servlet container, so `spring.servlet.multipart.*` limits are **not enforced in MockMvc tests**. This plan therefore adds an application-level size check (`app.storage.max-photo-bytes`), which is testable in MockMvc, plus a unit test of the handler mapping for `MaxUploadSizeExceededException`, plus a manual check against the real Tomcat (Step 12).

## Decisions (final; Open Questions resolved 2026-10-22)

### Endpoints

| Method + path | Access | Purpose |
|---|---|---|
| `GET /api/time` | authenticated | `{ serverTime, epochMillis, timeZone, localDateTime }`. `serverTime` comes from `Instant.now(clock)`; `timeZone` is the caller's company zone. |
| `GET /api/time-entries/today` | authenticated | Current shift state (below). |
| `POST /api/time-entries` | authenticated, `multipart/form-data` | Record CLOCK_IN / CLOCK_OUT. **201** when created, **200** when it is an idempotent replay. |
| `GET /api/time-entries/{timeEntryId}/photo` | authenticated | Own photo only. Returns 404 if the entry belongs to another employee or company, or has no photo. |
| `GET /api/admin/time-entries/{timeEntryId}/photo` | ADMIN (existing `/api/admin/**` rule) | Any photo of the admin's company. Returns 404 for another tenant. |

`GET /api/time` stays authenticated. The PWA only needs it after login, and this avoids another `permitAll` path plus a `LoginAwareBearerTokenResolver` change. No `SecurityConfig` change is needed.

### POST form fields

| Field | Type | Rule |
|---|---|---|
| `type` | `CLOCK_IN` / `CLOCK_OUT` | required, exact enum name |
| `clientUuid` | UUID | required. The nil UUID is rejected. It is the idempotency key, generated by the PWA once per tap. |
| `deviceTime` | ISO-8601 instant with offset (`2026-10-22T13:58:00.000Z`) | required. Year 2000-2100, otherwise 400. |
| `lat`, `lng` | decimal with a dot | optional, but both or neither. Must be finite, lat in [-90, 90], lng in [-180, 180]. |
| `accuracy` | meters, decimal >= 0, <= 100000 | optional. Only meaningful with lat/lng (accuracy without lat/lng gives 400). Stored rounded in `gps_accuracy_meters`. |
| `deviceSentAt` | ISO-8601 instant | optional, sent by the PWA at transmit time. Year 2000-2100, otherwise 400. If it is earlier than `deviceTime` (a client bug), it is ignored and the single-timestamp rule applies, so a clock-in is never lost over it. |
| `photo` | file part | **required.** A missing part gives 400 `PHOTO_REQUIRED`, except for an idempotent replay (see Idempotency). An empty part gives 400 `INVALID_PHOTO`. |

`source` is always `MOBILE` for this endpoint (TABLET/QR come later). The client's part `Content-Type` and filename are ignored. Every malformed field gives 400 `INVALID_REQUEST` with no echo, through the existing `handleExceptionInternal`.

### "Today", work date and sequence rules
- `zone = ZoneId.of(company.timeZone)`, `now = Instant.now(clock)`, `localToday = now.atZone(zone).toLocalDate()`. Never use `LocalDate.now()` or the JVM zone.
- **Open shift:** the employee's latest CLOCK_IN with `server_time > now - app.time-entry.max-shift-duration` (default **16 h**) and no CLOCK_OUT with the same `work_date`.
- **CLOCK_IN:**
  - If an open shift exists, return 409 `ALREADY_CLOCKED_IN`.
  - Otherwise `work_date = localToday`.
  - If a CLOCK_IN already exists for `(employee, work_date)`, return 409 `SHIFT_ALREADY_COMPLETED` when its CLOCK_OUT exists too, else 409 `ALREADY_CLOCKED_IN`. The second case is a CLOCK_IN older than 16 h on the same local day.
- **CLOCK_OUT:**
  - If there is no open shift, return 409 `NOT_CLOCKED_IN`.
  - Otherwise `work_date = openShift.work_date`. This is the overnight rule: a 22:00 Monday CLOCK_IN closed at 06:00 Tuesday belongs to Monday.
- **One CLOCK_IN/CLOCK_OUT pair per `work_date`** (MVP; multiple shifts are "Later"). The DB unique index `(employee_id, work_date, type)` is the backstop.
- **Forgotten CLOCK_OUT:** after 16 h the shift is no longer open. That day stays incomplete (the Stage 4 admin dashboard shows "missing clock-out"), and the next day's CLOCK_IN is allowed.
- **No schedule that day:** clocking in is allowed and the entry can still be VALID. `late_minutes` is NULL, and the dashboard can show "unscheduled".

### Today state (`GET /api/time-entries/today`)
- `workDate`: the open shift's `work_date` if one exists, else `localToday`.
- `nextAction`, one of:
  - `CLOCK_OUT` if a shift is open
  - `CLOCK_IN` if no CLOCK_IN exists for `localToday` (and consent and a branch exist)
  - otherwise `NONE`
- `blockedReason`: `null`, `CONSENT_REQUIRED` or `NO_BRANCH_ASSIGNED`. When it is set, `nextAction` is `NONE`.
- `clockIn` / `clockOut` for `workDate`: `{ id, serverTime, status, reviewReasons, distanceMeters, insideGeofence, lateMinutes (clockIn only), hasPhoto }` or `null`.
- `workedMinutes`: `floor((clockOut or now) - clockIn)`, or `null`. `inProgress` is true while the shift is open.
- `missingClockOut`: true when today's CLOCK_IN has expired without a CLOCK_OUT.
- `schedule`: `{ startTime, endTime, graceMinutes, overnight }` for `workDate`'s ISO day of week, or `null`.
- `branch`: `{ name, radiusMeters }`. Coordinates are not exposed.
- `serverTime`, `timeZone`, `consentPending`.

For an overnight worker at 07:00 Tuesday after clocking out Monday's shift, `nextAction` is `CLOCK_IN`, which is correct for Tuesday 22:00. Stage 3 decides how to show "last shift done" (the PWA can keep the POST response).

### Geofence, skew and review status (exact rules)
- **Haversine**, mean Earth radius 6,371,008.8 m. `distance_meters = round(distance)` from the employee's **current** `branch` (snapshotted in `time_entry.branch_id`).
- `insideGeofence`:
  - `null` without GPS
  - otherwise true when `distance <= radius_meters` and (accuracy absent or `accuracy <= app.time-entry.max-gps-accuracy-meters`, default 100)
- `tolerance = app.time-entry.clock-skew-tolerance` (default **2 min**).
- **With `deviceSentAt`** (the normal PWA case):
  - `queueDelay = deviceSentAt - deviceTime`. Both come from the same phone clock, so the clock error cancels.
  - `skew_seconds = server_time - deviceSentAt`, signed. This is the real clock skew plus network latency.
  - `synced_late = queueDelay > tolerance`.
- **Without `deviceSentAt`** (fallback, e.g. an older client):
  - `skew_seconds = server_time - deviceTime`, signed. Positive means the device time is in the past.
  - `synced_late = skew_seconds > tolerance`. A slow phone clock can't be told apart from a late sync here.
- **Review reasons**, a stored set. The status is `PENDING_REVIEW` if the set is non-empty, else `VALID`:

| Reason | When |
|---|---|
| `NO_GPS` | `lat`/`lng` absent (denied or unavailable) |
| `OUTSIDE_GEOFENCE` | `distance > radius_meters` |
| `LOW_GPS_ACCURACY` | `accuracy > max-gps-accuracy-meters` (default 100 m) |
| `SYNCED_LATE` | `synced_late` |
| `DEVICE_CLOCK_AHEAD` | `skew_seconds < -tolerance` (device clock in the future) |

There is no `NO_PHOTO` reason: the photo is required, so an entry without one is never created. A positive skew with no queue delay (a phone clock that is just slow, online) adds no reason, because `server_time` is official anyway.

- `device_time` is **never** used as the official time, never for `work_date` and never for lateness. It is stored for the admin's review only.
- `REJECTED` is never set in Stage 2. It is reserved for Stage 4 review.

### Lateness (stored, computed at CLOCK_IN)
- `schedule = work_schedule` for `(employee, work_date.getDayOfWeek().getValue())` (ISO, 1 = Monday).
- `scheduledStart = work_date.atTime(start_time).atZone(zone).toInstant()`
- `minutesLate = floor((server_time - scheduledStart) / 60 s)`
- `late_minutes = minutesLate <= grace_minutes ? 0 : minutesLate`. It counts from the scheduled start; grace only decides whether the entry is late (resolved Open Question 2). Arriving early gives 0.
- Example: start 08:00, grace 5. 08:05:59 is on time (0), 08:06:00 is late 6, 08:12 is late 12.
- An overnight schedule (`end_time < start_time`) affects only CLOCK_OUT pairing and the `schedule.overnight` display flag. Lateness uses only `start_time`.
- **Stored, not computed on read.** A later schedule edit must not rewrite history, and the dashboard and Excel export (Stage 4) read one column. Stage 4 adjustments of a CLOCK_IN `server_time` must recompute `late_minutes` (note for the Stage 4 plan).

### Idempotency
- The key is `(company_id, client_uuid)`, the existing unique constraint.
- **Replay** means a row exists with this key, the same `employee_id` and the same `type`. The response is **200** with the original entry and `"replayed": true`. No new row and no new file are written, and the photo is not processed.
- **Same key, different employee** (in the same company) or **different type** gives 409 `CLIENT_UUID_CONFLICT`. Another employee's entry is never returned.
- **Other differing fields** on a replay (lat/lng/accuracy/deviceTime/deviceSentAt/photo) are ignored, and the first write wins. This is documented, not an error.
- **A replay without a photo part** returns the original entry (200). The original already has its photo, and the replay lookup runs before the photo-required check. This lets the PWA retry after a timeout without re-uploading. The rule is safe because a replay is only returned to the same employee with the same type, and it never creates or changes data. If the `clientUuid` does not exist yet, a missing photo gives 400 `PHOTO_REQUIRED`.
- **A replay that arrives after a later entry exists** (a CLOCK_IN replayed after the CLOCK_OUT) still returns the original CLOCK_IN with 200. The idempotency check runs before the sequence rules.
- **Concurrent duplicates.** The employee row lock (`PESSIMISTIC_WRITE` on `employee (id, company_id)`) serializes all POSTs of one employee, so the second same-key request sees the first committed row on its re-check under READ COMMITTED and returns a replay. Backstop: a `DataIntegrityViolationException` raised outside the transaction is mapped by constraint name:
  - `uq_time_entry_company_client_uuid`: a fresh lookup, then a replay (200) or 409 `CLIENT_UUID_CONFLICT`.
  - `uq_time_entry_employee_work_date_type`: 409 `ALREADY_CLOCKED_IN`.

### Offline / late sync
- The official time is the time the server receives the entry. An entry queued offline for 2 h is recorded 2 h late, flagged `SYNCED_LATE` and sent to review with `device_time` visible, and the admin corrects it in Stage 4 through `adjustment`.
- **Ordering:** the Stage 3 queue must flush **strictly FIFO, one request at a time**, and stop on the first 4xx other than 409 replay outcomes. A CLOCK_OUT synced before its CLOCK_IN gets 409 `NOT_CLOCKED_IN`. The PWA keeps it, shows "contact your administrator", and does not retry forever. The server never accepts orphan CLOCK_OUTs.
- An expired token during the flush gives 401. The PWA asks for the PIN and replays with the same `clientUuid` (idempotent).
- Both queued entries synced at once get near-identical `server_time`s and a worked time of about 0 min. Both are flagged `SYNCED_LATE` for review.

### Photos and storage
- **Interface** `StorageService`:
  - `String store(long companyId, YearMonth month, byte[] jpeg)`
  - `Optional<Resource> load(long companyId, String key)`
  - `void delete(String key)`
- **`LocalDiskStorageService`:**
  - root = `app.storage.root` (`${UPLOADS_DIR:uploads}`, relative to the working dir `backend/`), resolved to an absolute real path at startup, created if missing, checked as writable, and logged at INFO once ("Photo storage root: ...")
  - key `{companyId}/{yyyy-MM}/{random UUID}.jpg`, with `yyyy-MM` taken from `server_time` in the company zone. The UUID is server-generated, never `clientUuid` or the filename.
  - **Path safety:**
    - keys match `^[0-9]+/[0-9]{4}-[0-9]{2}/[0-9a-f-]{36}\.jpg$`
    - `load` also requires the first segment to equal the caller's `companyId`
    - `root.resolve(key).normalize()` must start with root
    - the real path must still be under the real root (blocks symlinks)
    - the DB has the same format check plus a tenant-prefix CHECK (V3)
  - **Write:** a temp file in the target dir, then `Files.move(..., ATOMIC_MOVE)`. It never overwrites.
  - The root is outside the web root: Boot serves static content only from the classpath `static/` locations, never from the working dir. `uploads/` is already gitignored (no leading slash, so `backend/uploads/` is covered).
- **Processing** (`PhotoProcessor`, before the transaction, after the replay lookup and the `PHOTO_REQUIRED` check in `TimeEntryService`):
  1. size 0 gives 400 `INVALID_PHOTO`; size > `app.storage.max-photo-bytes` (2 MB) gives 413 `PAYLOAD_TOO_LARGE`
  2. magic bytes: JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`. Anything else gives `INVALID_PHOTO`.
  3. `ImageReader` for the sniffed format only, `setInput(stream, true, true)` (ignore metadata). Check the header dimensions: each side 32..4096 px and at most 16 MP, otherwise `INVALID_PHOTO`. This guards against decompression bombs.
  4. decode, catching any `IOException`/`RuntimeException` as `INVALID_PHOTO`. CMYK JPEGs fail here, which is acceptable.
  5. draw onto a white `TYPE_INT_RGB` canvas (drops alpha), downscale so the long side is at most 1280 px
  6. write JPEG at quality 0.85 with **no metadata**
- **Re-encode, not just strip EXIF:** re-encoding removes EXIF/GPS, ICC profiles, comments and any polyglot payload in one step. The Stage 3 client already sends a canvas-encoded ~1024 px JPEG at 0.7 (no EXIF), so the extra cost is about 20-50 ms of CPU and a slight quality loss. Only a non-PWA client would ever send EXIF, and this closes that path. Storage is always `.jpg`, served as `image/jpeg`.
- **Rollback safety:** after the file is stored inside the transaction, register a `TransactionSynchronization` whose `afterCompletion(STATUS_ROLLED_BACK)` deletes the file. If the process crashes between the write and the commit, an orphan file remains (Stage 5 sweeper: files with no `evidence` row older than 24 h).
- **Serving:** `ResponseEntity<Resource>`, with:
  - `Content-Type: image/jpeg`, `Content-Length`
  - `Cache-Control: private, no-store`
  - `Content-Disposition: inline; filename="photo.jpg"`
  - `X-Content-Type-Options: nosniff` (already set by Spring Security)

  If the evidence row exists but the file is missing, return 404 and log WARN with the entry id only.
- `ImageIO.setUseCache(false)` once at startup (no ImageIO temp files on disk).

### Evidence rows
- `PHOTO` with `storage_key`, on every new entry (the photo is required).
- `GPS` with `storage_key` NULL when lat/lng were sent. The coordinates live on `time_entry`.
- At most one row per type per entry (V3 unique constraint).

### Consent, branch, roles
- **Consent required:** if `employee.consent_at IS NULL`, POST returns **403 `CONSENT_REQUIRED`** before any photo is written. This follows auditor finding I-3. Today and time stay readable. Consent versioning and revocation are deferred (Stage 3 consent screen; Stage 5 legal review).
- **Branch:**
  - An employee without a branch (only possible for ADMIN) gets **403 `NO_BRANCH_ASSIGNED`**. An ADMIN *with* a branch may clock in like anyone else.
  - The seeded admin `9001` has no branch, so he cannot clock in.
  - **`branch` has no `active` column**, so "inactive branch" cannot exist yet. Stage 4 decides whether to add one, and this stage does not.
- An inactive employee or company is already rejected with 401 by the JWT converter.

### Rate limiting and abuse
- Per-employee fixed window on POST, keyed `companyId:employeeId`: `app.time-entry.rate-limit.max-attempts: 30`, `window: 10m`. Exceeding it gives 429 `TOO_MANY_ATTEMPTS` with `Retry-After`, using the existing code and message. Replays count too. 30 leaves room for a week of queued offline entries plus retries.
- Implemented as a new `common/FixedWindowRateLimiter` (same Caffeine logic as `LoginRateLimiter`, which stays untouched).
- Worst-case disk per employee is bounded by 30 × 2 MB per 10 min before re-encode. After re-encode it is about 150 KB per photo. **Per-company disk quota and alerting go to Stage 5.**

### Privacy (Ley 8968)
- GPS stays out of the photo bytes (re-encode). Log lines contain entry id, status and reasons, never lat/lng, photo bytes or file paths beyond the key.
- Retention note for Stage 5: a job deletes photo files older than N months (configurable) and sets `evidence.storage_key = NULL`. V3 allows a PHOTO row with a NULL key for exactly this reason. Time entries themselves are legal records and are kept.

### New error codes (stable English `code`, localized `title`/`detail`)

| Code | Status | EN title / detail | ES title / detail |
|---|---|---|---|
| `CONSENT_REQUIRED` | 403 | Consent required / You must accept the photo and location notice before clocking in. | Consentimiento requerido / Debe aceptar el aviso de uso de foto y ubicación antes de marcar. |
| `NO_BRANCH_ASSIGNED` | 403 | No branch assigned / Your user has no branch assigned, so it cannot clock in. Contact your administrator. | Sin sucursal asignada / Su usuario no tiene una sucursal asignada y no puede marcar. Consulte a su administrador. |
| `ALREADY_CLOCKED_IN` | 409 | Already clocked in / You already clocked in. The next step is to clock out. | Entrada ya registrada / Ya registró su entrada. El siguiente paso es marcar la salida. |
| `NOT_CLOCKED_IN` | 409 | No clock-in found / You cannot clock out without clocking in first. | No hay entrada registrada / No puede marcar la salida sin haber marcado la entrada. |
| `SHIFT_ALREADY_COMPLETED` | 409 | Day already complete / You already clocked in and out today. | Jornada completa / Ya registró la entrada y la salida de hoy. |
| `CLIENT_UUID_CONFLICT` | 409 | Duplicate request ID / This request ID was already used for a different time entry. Try again. | Identificador repetido / Este identificador ya se usó para otra marca. Intente de nuevo. |
| `PHOTO_REQUIRED` | 400 | Photo required / A selfie is required to clock in or out. | Foto requerida / Se requiere una selfie para marcar la entrada o la salida. |
| `INVALID_PHOTO` | 400 | Invalid photo / The photo must be a JPEG or PNG image. | Foto inválida / La foto debe ser una imagen JPEG o PNG. |
| `PAYLOAD_TOO_LARGE` | 413 | File too large / The photo is larger than the allowed size. | Archivo demasiado grande / La foto supera el tamaño permitido. |

That makes 9 new codes. **Order of checks on a POST** (first failure wins):
1. 401 token
2. 429 rate limit
3. 415 / 400 `INVALID_REQUEST` (content type, form fields)
4. replay lookup (200, or 409 `CLIENT_UUID_CONFLICT`)
5. 400 `PHOTO_REQUIRED`
6. 413 `PAYLOAD_TOO_LARGE` / 400 `INVALID_PHOTO`
7. in the transaction: 403 `CONSENT_REQUIRED`, then 403 `NO_BRANCH_ASSIGNED`, then the 409 sequence codes

So a request with no photo and no consent gets 400 `PHOTO_REQUIRED`. Every failure writes nothing.

These replace the generic `INVALID_SEQUENCE` idea with three specific codes, so the PWA can show the right message. They are reused: `INVALID_REQUEST` (malformed fields, malformed multipart), `TOO_MANY_ATTEMPTS` (POST rate limit), `NOT_FOUND` (photo), `UNSUPPORTED_MEDIA_TYPE` (JSON body sent to POST), `INTERNAL_ERROR` (storage I/O failure). The messages take no arguments and contain no apostrophes. The parity test requires `en != es` per key, which holds.

### Schema: `V3__time_entry_checkin.sql` (V2 is deployed, so it is not edited)

```sql
-- Stage 2: check-in rules. work_date is the company-local date the shift belongs to
-- (computed in the app from company.time_zone; a generated column cannot read another table).
ALTER TABLE time_entry
    ADD COLUMN work_date            date         NULL,
    ADD COLUMN skew_seconds         integer      NULL,
    ADD COLUMN late_minutes         integer      NULL,
    ADD COLUMN gps_accuracy_meters  integer      NULL,
    ADD COLUMN review_reasons       varchar(200) NULL;

-- Backfill (dev rows only, if any) then enforce.
UPDATE time_entry te SET work_date = (te.server_time AT TIME ZONE c.time_zone)::date
  FROM company c WHERE c.id = te.company_id AND te.work_date IS NULL;
ALTER TABLE time_entry ALTER COLUMN work_date SET NOT NULL;

ALTER TABLE time_entry
    ADD CONSTRAINT ck_time_entry_location_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    ADD CONSTRAINT ck_time_entry_late_minutes CHECK (late_minutes IS NULL OR (type = 'CLOCK_IN' AND late_minutes >= 0)),
    ADD CONSTRAINT ck_time_entry_gps_accuracy CHECK (gps_accuracy_meters >= 0),
    ADD CONSTRAINT ck_time_entry_review_reasons CHECK (review_reasons ~ '^[A-Z_]+(,[A-Z_]+)*$');

-- One CLOCK_IN and one CLOCK_OUT per employee per work_date (backstop for the app-level lock).
CREATE UNIQUE INDEX uq_time_entry_employee_work_date_type ON time_entry (employee_id, work_date, type);
-- Stage 4 dashboard: one company, one day.
CREATE INDEX ix_time_entry_company_work_date ON time_entry (company_id, work_date);

ALTER TABLE evidence
    ADD CONSTRAINT uq_evidence_time_entry_type UNIQUE (time_entry_id, type),
    -- Only PHOTO rows carry a key; a PHOTO key may be NULL after a Stage 5 retention purge.
    ADD CONSTRAINT ck_evidence_storage_key_type CHECK (type = 'PHOTO' OR storage_key IS NULL),
    ADD CONSTRAINT ck_evidence_storage_key_format
        CHECK (storage_key ~ '^[0-9]+/[0-9]{4}-[0-9]{2}/[0-9a-f-]{36}\.jpg$'),
    ADD CONSTRAINT ck_evidence_storage_key_company
        CHECK (storage_key IS NULL OR split_part(storage_key, '/', 1) = company_id::text);
```

Notes:
- **`uq_time_entry_employee_work_date_type` is a unique index, not a constraint.** It doesn't appear in `pg_constraint`, but the violation reports the index name, which the handler maps.
- **The unique index includes REJECTED rows.** Stage 4 corrects entries through `adjustment`, not by re-clocking.
- **`skew_seconds` is an `integer`,** which holds ±68 years. The year range check on `deviceTime` keeps it in bounds.
- **The `review_reasons` CSV** maps to `Set<ReviewReason>` through a JPA `AttributeConverter`, sorted on write for stable output. `text[]` was rejected to keep Hibernate `validate` simple.
- Counts after V3 for `SchemaEnglishNamingTest`:
  - constraints: 37 + 4 (time_entry) + 4 (evidence) = **45**
  - `ix_*` indexes: 6 + 1 = **7**
  - the coder recounts against `pg_constraint`

## Requirements Checklist

### Planning (this document)
- [x] Read roadmap, Stage 1 plan + amendments, Stage 1b plan, ER diagram, Stage 1 audit, current backend
- [x] Verified multipart limits / 413 mapping / Tomcat part limits / `LocalDateJdbcType` against official docs and source
- [x] Rules defined for today/work date, sequence, idempotency, late sync, geofence, lateness, storage, consent
- [x] Open Questions answered by the user (2026-10-22) and applied to this plan

### API
- [x] `GET /api/time` returns `serverTime`, `epochMillis`, `timeZone`, `localDateTime` from the injected `Clock`
- [x] `GET /api/time-entries/today` returns the state defined above, including `nextAction`, `blockedReason`, `workedMinutes`, `schedule`, `missingClockOut`
- [x] `POST /api/time-entries` multipart with `type`, `lat`, `lng`, `accuracy`, `deviceTime`, `deviceSentAt`, `clientUuid`, `photo`; 201 created / 200 replay
- [x] Photo required: a new entry without a photo part gives 400 `PHOTO_REQUIRED` and writes nothing; a replay without a photo returns the original (200)
- [x] `deviceSentAt` used for queue delay (`SYNCED_LATE`) and skew; fallback to `deviceTime` alone when absent or earlier than `deviceTime`
- [x] `accuracy` stored in `gps_accuracy_meters`; above 100 m gives `LOW_GPS_ACCURACY`
- [x] `server_time = Instant.now(clock)`; `device_time` stored, never used as official time
- [x] Sequence rules: no CLOCK_OUT without an open CLOCK_IN, no double CLOCK_IN, one pair per `work_date`, 16 h open-shift window
- [x] Idempotency: replay returns the original (200), cross-employee or different-type reuse gives 409, concurrent duplicates give one row
- [x] `synced_late` and `skew_seconds` recorded; review reasons and status per the table
- [x] Haversine distance to the employee's branch; `distance_meters` stored; `insideGeofence` in responses
- [x] Evidence rows PHOTO (with key) and GPS
- [x] Lateness stored at CLOCK_IN from `work_schedule` + `grace_minutes` in the company zone
- [x] Photo endpoints: employee own only, admin own company only, 404 otherwise
- [x] Consent required (403 `CONSENT_REQUIRED`); no branch gives 403 `NO_BRANCH_ASSIGNED`
- [x] Per-employee POST rate limit gives 429 `TOO_MANY_ATTEMPTS` + `Retry-After`

### Storage / images
- [x] `StorageService` + `LocalDiskStorageService`, key `{companyId}/{yyyy-MM}/{uuid}.jpg`, root from `app.storage.root` / `UPLOADS_DIR`
- [x] Path-traversal and symlink guards; atomic write; delete on rollback
- [x] Magic-byte sniff (JPEG/PNG only), dimension guard, re-encode to JPEG without metadata
- [x] App-level 2 MB cap (413) + Spring multipart limits + Tomcat part count / swallow size

### Errors / i18n
- [x] 9 new `ErrorCode`s (including `PHOTO_REQUIRED`) with EN/ES `title` and `detail` in all three bundles
- [x] `codeFor(413)` gives `PAYLOAD_TOO_LARGE`; `MultipartException` gives 400 `INVALID_REQUEST`; `ApiException(status, code)` handler
- [x] No input echo in any new 4xx body

### Schema
- [x] `V3__time_entry_checkin.sql` exactly as above; V1/V2 untouched; `ddl-auto=validate` passes
- [x] `TimeEntry.workDate` mapped with `LocalDateJdbcType`

### Config / seed
- [x] `application.yml`: `spring.servlet.multipart.*`, `server.tomcat.max-part-count`, `server.tomcat.max-swallow-size`, `app.storage.*`, `app.time-entry.*`
- [x] `.env.example`: `UPLOADS_DIR=`, `DEV_SEED_LAT=`, `DEV_SEED_LNG=`, `DEV_SEED_RADIUS=` (empty, commented)
- [x] Dev seed: optional branch coordinates/radius from env, applied to a fresh seed **and** to the existing `Sucursal Centro` on every dev start when set

### Tests / docs / process
- [x] All unit, storage and integration tests in Step 9 pass with `.\mvnw.cmd clean test`; the existing 398 tests still pass (after the listed fixture updates)
- [x] Architect: `docs/diagrams/stage-2-time-entry.md` before coding
- [ ] Security audit after implementation (input handling + uploads)
- [x] README section "Check-in API"
- [ ] Main session updates `CHANGELOG.md` and `docs/PROGRESS.md` (not the planner)
- [ ] User runs the manual end-to-end steps (Step 12)

## Implementation Steps

Paths are relative to `backend/` unless they start with `docs/` or the repo root. Each step should leave `.\mvnw.cmd clean test` green.

### Step 1 - Configuration
1. **`src/main/resources/application.yml`** (modify). Add:
   ```yaml
   spring:
     servlet:
       multipart:
         max-file-size: 3MB        # hard ceiling; the app rejects > 2MB first with a localized 413
         max-request-size: 4MB
         file-size-threshold: 256KB
   server:
     tomcat:
       max-part-count: 10          # Boot default is 50; the form has 7 parts
       max-swallow-size: 5MB       # > max-request-size so oversize gets a readable 413, not a reset
   app:
     storage:
       root: ${UPLOADS_DIR:uploads}   # relative to backend/; outside the web root
       max-photo-bytes: 2MB
       max-input-dimension: 4096
       max-input-pixels: 16000000
       output-max-dimension: 1280
       jpeg-quality: 0.85
     time-entry:
       max-shift-duration: 16h
       clock-skew-tolerance: 2m
       max-gps-accuracy-meters: 100
       rate-limit:
         max-attempts: 30
         window: 10m
   ```
2. **`src/main/resources/application-dev.yml`** (modify): `app.seed.branch-latitude: ${DEV_SEED_LAT:}`, `branch-longitude: ${DEV_SEED_LNG:}`, `branch-radius-meters: ${DEV_SEED_RADIUS:}`.
3. **`src/test/resources/application-test.yml`** (modify):
   - `app.storage.root: target/test-uploads` (wiped by `mvn clean`)
   - `app.time-entry.rate-limit.max-attempts: 100000` (the limiter test overrides it)
4. **`.env.example`** (modify). Append, with empty values:
   ```
   # Photo storage directory (default: uploads, relative to backend/). Must not be inside src/ or a static folder.
   UPLOADS_DIR=
   # Dev profile only, optional: put the seeded branch at your real location to test the geofence.
   # Decimal degrees with a dot (9.9325, -84.0796); radius 10..5000 m. Applied on every dev start when set.
   DEV_SEED_LAT=
   DEV_SEED_LNG=
   DEV_SEED_RADIUS=
   ```
5. **`src/main/java/cr/attendance/storage/StorageProperties.java`** (new): `@ConfigurationProperties("app.storage") @Validated` record `(String root, DataSize maxPhotoBytes, int maxInputDimension, long maxInputPixels, int outputMaxDimension, float jpegQuality)`.
6. **`src/main/java/cr/attendance/timeentry/TimeEntryProperties.java`** (new): `@ConfigurationProperties("app.time-entry")` record `(Duration maxShiftDuration, Duration clockSkewTolerance, int maxGpsAccuracyMeters, RateLimit rateLimit)` with a nested `RateLimit(int maxAttempts, Duration window)`. Check that `AttendanceApplication` has `@ConfigurationPropertiesScan`; if it doesn't, register both classes the same way `SeedProperties` is registered.

### Step 2 - Migration, entity and existing-test fixtures
7. **`src/main/resources/db/migration/V3__time_entry_checkin.sql`** (new): exactly the SQL in Decisions.
8. **`src/main/java/cr/attendance/timeentry/TimeEntry.java`** (modify):
   - fields `workDate` (`LocalDate`, `@JdbcType(LocalDateJdbcType.class)`, `nullable=false`), `skewSeconds` (`Integer`), `lateMinutes` (`Integer`), `gpsAccuracyMeters` (`Integer`), `reviewReasons` (`Set<ReviewReason>`, `@Convert(converter = ReviewReasonsConverter.class)`, `length=200`)
   - a full constructor (or a static factory `TimeEntry.record(...)`) that sets every column, including `deviceTime`, `latitude`, `longitude`, `distanceMeters`, `syncedLate` and `status`
   - getters only, no setters (flat-entity pattern)
9. **`src/main/java/cr/attendance/timeentry/ReviewReason.java`** (new enum): `NO_GPS, OUTSIDE_GEOFENCE, LOW_GPS_ACCURACY, SYNCED_LATE, DEVICE_CLOCK_AHEAD`. There is no `NO_PHOTO`.
10. **`src/main/java/cr/attendance/timeentry/ReviewReasonsConverter.java`** (new): `AttributeConverter<Set<ReviewReason>, String>`. An empty set is stored as NULL; values are sorted by enum order and joined with `,`; parsing tolerates NULL.
11. **Existing tests, same step.** These break by design; update them:
    - `src/test/java/cr/attendance/support/TestData.java`:
      - `timeEntry(...)` inserts `work_date` (default `current_date`)
      - add an overload `timeEntry(companyId, employeeId, branchId, type, workDate)`
      - add helpers `consent(employeeId)`, `schedule(companyId, employeeId, isoDay, start, end, grace)` and `branchAt(companyId, lat, lng, radius)`
    - `db/SchemaMigrationTest.java`: versions contain `"1", "2", "3"`; direct `insert into time_entry` statements include `work_date`
    - `db/SchemaEnglishNamingTest.java`:
      - the constraint count goes from 37 to the new total, and the `EXPECTED_CONSTRAINTS` list gains the 8 V3 names
      - `EXPECTED_INDEXES` gains `ix_time_entry_company_work_date`
      - `insertTimeEntry` includes `work_date`
      - in the 18-insert loop of `everyJavaEnumValueIsStoredAndReadBackThroughTheEntities`, each insert gets its own `work_date` (`current_date - n`), otherwise the new unique index fires
      - the evidence loop inserts PHOTO with a valid key (`companyId + "/2026-10/" + UUID + ".jpg"`) and GPS/QR with NULL
    - `db/SchemaRangeConstraintsTest.java`, `db/TenantConstraintsTest.java`: direct inserts include `work_date`. Where one employee gets several entries, use distinct dates.
    - Gate: green, with only the fixtures changed.

### Step 3 - Pure domain rules (no Spring, unit-tested)
12. **`timeentry/GeoDistance.java`** (new): `static double meters(double lat1, double lng1, double lat2, double lng2)`, Haversine with R = 6,371,008.8. Clamp the intermediate `a` to [0, 1] before `asin(sqrt(a))` to avoid NaN.
13. **`timeentry/LatenessCalculator.java`** (new): `static Integer lateMinutes(Instant clockIn, LocalDate workDate, ZoneId zone, WorkSchedule scheduleOrNull)` per the rule above.
14. **`timeentry/ReviewPolicy.java`** (new): input record `(Double lat, Double lng, Double accuracy, Integer distance, int radius, Instant serverTime, Instant deviceTime, Instant deviceSentAtOrNull)` plus the properties. It computes `skewSeconds` and `queueDelaySeconds` with the `deviceSentAt` rule and its fallback. It returns `ReviewOutcome(Set<ReviewReason> reasons, boolean syncedLate, TimeEntryStatus status, Boolean insideGeofence)`.
15. **`timeentry/ShiftResolver.java`** (new): pure functions over a small list of entries:
    - `Optional<TimeEntry> openShift(List<TimeEntry> recent, Instant now, Duration max)`
    - `NextAction nextAction(...)`
    - `LocalDate workDateFor(TimeEntryType, Optional<TimeEntry> open, LocalDate localToday)`
    - `SequenceViolation check(...)`, returning the `ErrorCode` or null
16. **`timeentry/NextAction.java`** (new enum `CLOCK_IN, CLOCK_OUT, NONE`).

### Step 4 - Storage and photo processing (`cr.attendance.storage`)
17. **`StorageService.java`** (interface, signatures in Decisions).
18. **`StorageKeys.java`**:
    - `static String newKey(long companyId, YearMonth month)`
    - `static boolean isValid(String key)` (regex)
    - `static boolean belongsTo(String key, long companyId)`
19. **`LocalDiskStorageService.java`** (`@Service`):
    - constructor resolves and creates the root, checks that it is writable, logs it at INFO, and fails fast with a clear English message if it is not writable
    - `store`: temp file + atomic move
    - `load`: `isValid`, `belongsTo`, normalize + `startsWith`, `toRealPath` check, `Optional.empty()` if missing
    - `delete`: validated key, `deleteIfExists`, IOException logged at WARN
20. **`ImageFormatSniffer.java`**: `static Optional<String> sniff(byte[] head)` returns `"jpeg"`, `"png"` or empty.
21. **`PhotoProcessor.java`** (`@Component`): `ProcessedPhoto process(MultipartFile photo)` throws `ApiException`. It implements the 6 processing rules. A `null` argument is a programming error (`IllegalArgumentException`): the `PHOTO_REQUIRED` check belongs to `TimeEntryService`, because it must run after the replay lookup. Its constructor calls `ImageIO.setUseCache(false)`.
22. **`ProcessedPhoto.java`**: record `(byte[] jpeg, int width, int height)`.

### Step 5 - Errors and i18n
23. **`common/ErrorCode.java`** (modify): add the 9 codes.
24. **`common/ApiException.java`** (new): `RuntimeException` carrying `HttpStatus status` and `ErrorCode code`. Its message is the English code name only; it never carries user data.
25. **`common/ApiExceptionHandler.java`** (modify):
    - `@ExceptionHandler(ApiException.class)` returns `respond(e.status(), e.code(), null)`
    - `@ExceptionHandler(MultipartException.class)` returns 400 `INVALID_REQUEST`. `MaxUploadSizeExceededException` stays with the base class, which is a closer match, so it still reaches `handleExceptionInternal`.
    - `codeFor`: add `case 413 -> ErrorCode.PAYLOAD_TOO_LARGE`. No 403 case is needed, because the new 403s come from `ApiException`.
    - `TooManyAttemptsException` is reused for the POST limiter (same 429 + `Retry-After`)
26. **`src/main/resources/messages.properties`, `messages_es.properties`, `messages_en.properties`** (modify): 18 new keys each, with the texts from the error table. Base = Spanish. UTF-8, no BOM.
27. **`common/FixedWindowRateLimiter.java`** (new): copy of the `LoginRateLimiter` logic (`tryAcquire(String key)`, `retryAfter()`, injectable `Ticker`). Bean `timeEntryRateLimiter` built from `TimeEntryProperties` in a small `timeentry/TimeEntryConfig.java` (new).

### Step 6 - Repositories (all scoped; `TenantRepositoryConventionTest` must stay green)
28. **`timeentry/TimeEntryRepository.java`** (modify): add
    - `Optional<TimeEntry> findByCompanyIdAndClientUuid(Long companyId, UUID clientUuid)`
    - `List<TimeEntry> findAllByCompanyIdAndEmployeeIdAndWorkDateOrderByServerTime(Long companyId, Long employeeId, LocalDate workDate)`
    - `List<TimeEntry> findAllByCompanyIdAndEmployeeIdAndServerTimeAfterOrderByServerTimeDesc(Long companyId, Long employeeId, Instant after)` (open-shift window)
29. **`timeentry/EvidenceRepository.java`** (modify): `Optional<Evidence> findByCompanyIdAndTimeEntryIdAndType(Long companyId, Long timeEntryId, EvidenceType type)`.
30. **`employee/EmployeeRepository.java`** (modify): `@Lock(LockModeType.PESSIMISTIC_WRITE) @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")) Optional<Employee> findWithLockByIdAndCompanyId(Long id, Long companyId)`. Hibernate may emit `FOR UPDATE` or `FOR NO KEY UPDATE`; either serializes this employee's POSTs.
31. **`company/BranchRepository.java`** (modify): `@Transactional @Modifying` `int updateLocation(Long id, Long companyId, Double latitude, Double longitude, Integer radiusMeters)`, used only by the dev seed.

### Step 7 - Services and controllers (`cr.attendance.timeentry`)
32. **`TimeController.java`** + **`TimeResponse.java`**: `GET /api/time`.
33. **`TimeEntryForm.java`**: record bound with `@ModelAttribute`.
    - Bean Validation: `@NotNull type, clientUuid, deviceTime`; `@DateTimeFormat(iso = DATE_TIME)` on `deviceTime` and `deviceSentAt`
    - `@AssertTrue isLocationValid()`: lat/lng both or neither, `Double.isFinite`, ranges; `accuracy` finite, 0..100000, and only together with lat/lng
    - `@AssertTrue isDeviceTimeInRange()` (years 2000-2100, for both instants) and `isClientUuidNotNil()`
    - `photo` is a `MultipartFile` field **without** `@NotNull`. Presence is checked by the service after the replay lookup.
    - `toString` masks lat/lng
34. **`TimeEntryResponse.java`**: `{ id, clientUuid, type, serverTime, workDate, status, reviewReasons, syncedLate, skewSeconds, distanceMeters, radiusMeters, insideGeofence, lateMinutes, hasPhoto, replayed }`.
35. **`TodayResponse.java`**: shape per Decisions (nested `EntrySummary`, `ScheduleInfo`, `BranchInfo`).
36. **`TimeEntryWriter.java`** (`@Service`, `@Transactional`): `TimeEntryResult record(EmployeePrincipal, TimeEntryForm, ProcessedPhoto photoOrNull)`, in the transaction order from the Summary. It registers the rollback-delete synchronization right after `storage.store`.
37. **`TimeEntryService.java`** (`@Service`, **not** transactional, the orchestrator):
    1. pre-lookup replay: return the original (200) or 409 `CLIENT_UUID_CONFLICT`, whether or not a photo was sent
    2. photo part missing gives `ApiException(400, PHOTO_REQUIRED)`
    3. `photoProcessor.process`
    4. `writer.record`
    5. `catch DataIntegrityViolationException`, mapping by constraint/index name (inspect the root `PSQLException` message or Hibernate `ConstraintViolationException.getConstraintName()`) to a replay or a 409
38. **`TodayService.java`** (`@Service`, `@Transactional(readOnly = true)`): builds `TodayResponse` with `ShiftResolver`.
39. **`TimeEntryController.java`**:
    - `@PostMapping(consumes = MULTIPART_FORM_DATA_VALUE)`: rate limit (`TooManyAttemptsException`), then `service.record`, then `ResponseEntity.status(replayed ? 200 : 201)`
    - `@GetMapping("/today")`
    - `@GetMapping("/{timeEntryId}/photo")`: own entries only, `employeeId` checked
40. **`PhotoService.java`** (`@Service`, read-only): `Resource photoFor(long companyId, Long employeeIdOrNull, long timeEntryId)`. If `employeeIdOrNull` is set, the entry must belong to that employee. Every miss gives `ResourceNotFoundException`.
41. **`AdminTimeEntryPhotoController.java`**: `@RequestMapping("/api/admin/time-entries")`, `GET /{timeEntryId}/photo`.

### Step 8 - Dev seed
42. **`seed/SeedProperties.java`** (modify): add `Double branchLatitude, Double branchLongitude, Integer branchRadiusMeters`. `toString` still masks the PIN; coordinates are not secret.
43. **`seed/DevDataSeeder.java`** (modify):
    - Validate: lat/lng both set or both blank, and in range; radius 10..5000. Otherwise fail startup with an English message.
    - Fresh seed: use the values (defaults 9.9325, -84.0796, 100).
    - **Existing DEMO company:** if any value is set, call `branchRepository.updateLocation` on "Sucursal Centro" and log "Dev seed: branch location updated" without the coordinates. The user's dev DB is already seeded, and without this the env vars would do nothing.

### Step 9 - Tests (`src/test/java/cr/attendance/...`)
44. **`support/MutableClock.java`** + **`support/MutableClockConfig.java`** (new, shared). All clock-driven Stage 2 integration tests import the **same** config class, so they share one cached context and one embedded DB. **The clock may only move forward from the real now:** the JWT decoder validates `exp` against the system clock. To hit a local midnight, advance to the *next* Costa Rica midnight.
45. **`support/TestImages.java`** (new): builds JPEG and PNG bytes with ImageIO, including:
    - a PNG with alpha
    - a JPEG with an injected APP1 `Exif\0\0` segment containing a marker string
    - a header-only PNG claiming 50000×50000
    - a text file named `.jpg`
    - a truncated JPEG
46. **Unit tests:**
    - `timeentry/GeoDistanceTest`:
      - the same point gives 0
      - (0,0)-(0,1) is about 111,195 m (±1 m)
      - two San José points about 35 m apart
      - antimeridian (179.9 vs -179.9)
      - near the pole
      - no NaN for antipodal points
    - `timeentry/LatenessCalculatorTest`:
      - early, exact start, the grace boundary (5:59 → 0, 6:00 → 6)
      - no schedule gives null
      - an overnight schedule's CLOCK_IN at 21:55 gives 0
      - zone handling (the same instant in UTC vs America/Costa_Rica)
      - a DST zone sanity case (`America/New_York` spring-forward day)
    - `timeentry/ReviewPolicyTest`:
      - each reason alone; combined reasons; status VALID iff no reasons
      - tolerance boundaries (±120 s exactly vs 121 s); accuracy boundary (100 vs 101 m)
      - with `deviceSentAt`: a phone 5 min slow and sent immediately gives no reason, `skew_seconds` about 300; a 10 min queue delay gives `SYNCED_LATE`, whatever the skew; a device 5 min ahead gives `DEVICE_CLOCK_AHEAD`
      - without `deviceSentAt`: the fallback rule
      - `deviceSentAt` earlier than `deviceTime`: ignored, so the fallback rule applies
    - `timeentry/ShiftResolverTest`:
      - open shift within and after 16 h
      - overnight `work_date` inheritance
      - every `nextAction` / violation branch
      - CLOCK_IN after an expired same-day CLOCK_IN
    - `timeentry/ReviewReasonsConverterTest`: empty set ↔ NULL, sorted order, round trip
    - `storage/ImageFormatSnifferTest`: JPEG, PNG, GIF, WebP, PDF, empty, 2-byte input
    - `storage/PhotoProcessorTest`:
      - JPEG and PNG accepted, and the output starts with `FF D8 FF`
      - the EXIF marker string is absent from the output and there is no APP1 segment
      - an alpha PNG gives an RGB JPEG
      - 3000 px is downscaled to 1280
      - the 50000×50000 header is rejected **without decoding** (fast; assert time < 1 s)
      - the text file, truncated JPEG and empty part give `INVALID_PHOTO`
      - 2 MB + 1 byte gives 413 `PAYLOAD_TOO_LARGE`
    - `storage/StorageKeysTest`: valid key; `../`, `..\\`, absolute paths, URL-encoded, uppercase hex, a wrong company prefix, an extra segment and an empty string are all rejected
    - `common/FixedWindowRateLimiterTest` (fake `Ticker`)
    - `common/ApiExceptionHandlerTest` (modify):
      - `MaxUploadSizeExceededException` gives 413 `PAYLOAD_TOO_LARGE`, in es and en
      - `MultipartException` gives 400 `INVALID_REQUEST`
      - each new `ApiException` code gives the right status and localized texts
      - no echo
47. **Storage tests:** `storage/LocalDiskStorageServiceTest` (`@TempDir`, no Spring):
    - store creates `{company}/{yyyy-MM}/{uuid}.jpg` with the exact bytes and leaves no temp files behind
    - load with the wrong company gives empty
    - a traversal key gives empty and never touches files outside root
    - a symlink inside root pointing outside gives empty (skip on Windows when symlink creation isn't permitted, using `assumeTrue`)
    - delete is idempotent
    - an unwritable root fails at construction (POSIX only, `assumeTrue`)
48. **Integration tests** (`@IntegrationTest`, MockMvc multipart via `multipart("/api/time-entries").file(...).param(...)`):
    - `timeentry/TimeEndpointIntegrationTest`: 401 without a token; `serverTime` equals the mutable clock; `timeZone` `America/Costa_Rica`
    - `timeentry/TimeEntryFlowIntegrationTest` (the full happy path):
      - consent, then today shows `CLOCK_IN`
      - POST CLOCK_IN with photo + GPS inside the radius: 201 VALID, `distanceMeters` set, `insideGeofence` true, 2 evidence rows, the file exists under `target/test-uploads/{companyId}/{yyyy-MM}/`
      - today shows `CLOCK_OUT` with `inProgress`
      - after advancing the clock 9 h, POST CLOCK_OUT gives 201
      - today shows `NONE`, `workedMinutes` 540
      - a third POST CLOCK_IN gives 409 `SHIFT_ALREADY_COMPLETED`
    - `timeentry/TimeEntryIdempotencyIntegrationTest`:
      - the same request twice gives 201 then 200 `replayed:true`, with the same id, 1 row, 1 photo file on disk
      - a replay with different lat/photo returns the original values
      - the same uuid with a different type gives 409 `CLIENT_UUID_CONFLICT`
      - the same uuid from another employee in the same company gives 409 `CLIENT_UUID_CONFLICT`, and the body has no data of the first entry
      - the same uuid in **another company** creates a separate entry (201): the key is per tenant
      - a CLOCK_IN replayed after the CLOCK_OUT exists gives 200
      - **a replay without a photo part** gives 200 with the original entry, `hasPhoto` true, and still 1 file
      - a replay without a photo from another employee (same uuid) gives 409 `CLIENT_UUID_CONFLICT`, not `PHOTO_REQUIRED`
    - `timeentry/TimeEntryConcurrencyIntegrationTest`:
      - 2 to 8 threads POST the same `clientUuid` at once: exactly one 201, the rest 200, 1 row, 1 file
      - 2 threads POST CLOCK_IN with different uuids: one 201, one 409 `ALREADY_CLOCKED_IN`, 1 row
      - the DB backstop via JDBC: a second CLOCK_IN for the same `(employee, work_date)` raises `DataIntegrityViolationException` naming `uq_time_entry_employee_work_date_type`
    - `timeentry/TimeEntryValidationIntegrationTest`:
      - CLOCK_OUT first gives 409 `NOT_CLOCKED_IN`
      - a double CLOCK_IN gives 409 `ALREADY_CLOCKED_IN`
      - no consent gives 403 `CONSENT_REQUIRED`, and **no file is written**
      - ADMIN without a branch gives 403 `NO_BRANCH_ASSIGNED`; ADMIN with a branch gives 201
      - these give 400 `INVALID_REQUEST` and never echo the value: missing `type`, lowercase `clock_in`, a bad uuid, the nil uuid, a bad `deviceTime`, year 1970, lat 91, lng -181, `lat=NaN`, `Infinity`, lat without lng, `lat=9,93` (comma)
      - a JSON body gives 415
      - **no photo part** (new `clientUuid`) gives 400 `PHOTO_REQUIRED`, for both CLOCK_IN and CLOCK_OUT, with no row, no evidence and no file
      - no photo **and** no consent gives 400 `PHOTO_REQUIRED` (check order)
      - accuracy without lat/lng, a negative accuracy, `accuracy=NaN` and a `deviceSentAt` in 1970 give 400 `INVALID_REQUEST`
      - photo as text, a truncated JPEG or an empty part give 400 `INVALID_PHOTO`
      - a 2 MB + 1 byte photo gives 413 `PAYLOAD_TOO_LARGE`
      - all of these leave no row and no file
      - the `en` vs `es` texts for one 409 and for `PHOTO_REQUIRED`
    - `timeentry/TimeEntryGeofenceIntegrationTest`:
      - 35 m with a 100 m radius gives VALID, `distanceMeters` 35 ±1
      - 420 m gives PENDING_REVIEW `[OUTSIDE_GEOFENCE]`, `distanceMeters` 420 ±1 (blueprint "GPS at 420 m")
      - no GPS gives PENDING_REVIEW `[NO_GPS]` and no GPS evidence row
      - accuracy 500 gives `[LOW_GPS_ACCURACY]` and `gps_accuracy_meters` 500; accuracy 12 inside the radius gives VALID
      - with `deviceSentAt` = `deviceTime` = now - 5 min (slow phone, online) gives VALID, `synced_late=false`, `skew_seconds` 300
      - `deviceTime` = now - 10 min, `deviceSentAt` = now gives `synced_late=true`, `[SYNCED_LATE]`
      - without `deviceSentAt`: `deviceTime` = now - 10 min gives `synced_late=true`, `skew_seconds` 600, `[SYNCED_LATE]`
      - `deviceTime` = now + 10 min gives `[DEVICE_CLOCK_AHEAD]`
      - `deviceTime` = now - 60 s gives VALID
    - `timeentry/TimeEntryDayBoundaryIntegrationTest` (MutableClock):
      - CLOCK_IN at 23:59 CR local gets `work_date` D. Advance 2 min (00:01 local, D+1): today still shows D's shift open (`nextAction` `CLOCK_OUT`), and CLOCK_OUT gets `work_date` D.
      - A clean employee at 00:01 local gets `work_date` D+1 even though the UTC date is different (UTC 06:01). That proves the company zone is used, not UTC.
      - A JVM default zone of `Asia/Tokyo` during the request does not change `work_date`.
    - `timeentry/OvernightShiftIntegrationTest`:
      - Schedule Mon 22:00-06:00, grace 5. CLOCK_IN Mon 22:03 gives `late_minutes` 0. CLOCK_OUT Tue 06:02 gets `work_date` Monday, and `workedMinutes` is 479.
      - Tuesday 21:58: today shows `CLOCK_IN`, and the POST gets `work_date` Tuesday.
      - A forgotten CLOCK_OUT: after 16 h + 1 min, today shows `CLOCK_IN` for the new day and the old day has `missingClockOut`.
    - `timeentry/LatenessIntegrationTest`:
      - the stored `late_minutes` for 08:12 with start 08:00 and grace 5 is 12
      - no schedule on Saturday gives NULL and status VALID
      - editing the schedule afterwards does not change the stored value
    - `timeentry/TodayStateIntegrationTest`:
      - each `nextAction` / `blockedReason` combination
      - `schedule` for the right ISO day
      - **tenant isolation:** company B's entries never appear for company A's employee with the same employee code
    - `timeentry/TimeEntryPhotoAccessIntegrationTest`:
      - the employee gets their own photo: 200 `image/jpeg`, the bytes are the stored file, `Cache-Control` contains `no-store`
      - another employee's entry in the same company gives 404
      - another company's entry gives 404
      - an entry without a photo gives 404
      - the admin of the same company gets 200
      - the admin of another company gets 404
      - an employee calling `/api/admin/...` gets 403
      - no token gives 401
      - a non-numeric id gives 400 with no echo
      - a file deleted from disk gives 404
      - a request with `Accept: image/jpeg` and a 404 still gets a 404 status
    - `timeentry/TimeEntryRateLimitIntegrationTest` (`@TestPropertySource(properties = "app.time-entry.rate-limit.max-attempts=3")`): the 4th POST gives 429 `TOO_MANY_ATTEMPTS` + `Retry-After`, and another employee is unaffected.
    - `timeentry/WorkDateTimeZoneIntegrationTest`: like `WorkScheduleTimeZoneIntegrationTest`. Save a `TimeEntry` with `workDate` 2026-10-22 under JVM zones UTC, America/Costa_Rica, Asia/Tokyo and Pacific/Kiritimati. `work_date::text` reads back `2026-10-22` every time.
    - `db/SchemaV3ConstraintsTest`: each new CHECK and unique rejects its bad case:
      - a lat without lng
      - `late_minutes` on a CLOCK_OUT, and negative `late_minutes`
      - lowercase review reasons
      - a GPS evidence row with a key
      - a key with `..`
      - a key whose company prefix doesn't match
      - a duplicate `(time_entry_id, type)`
      - a NULL `work_date`
      - and a PHOTO row with a NULL key is accepted (retention)
    - `seed/DevSeedEnabledTest` / `DevSeedContentTest` (modify):
      - with `app.seed.branch-latitude/longitude/radius-meters` set, a fresh seed uses them
      - a second run with different values updates the existing branch
      - invalid values (lat 91, radius 5) fail with the English message
      - the output doesn't contain the PIN
49. Existing suites that must stay untouched and green: `TenantRepositoryConventionTest`, `MessageBundleParityTest` (it now covers the 18 new keys automatically), `LocaleResolutionIntegrationTest`, all auth tests.

### Step 10 - Docs
50. **`README.md`** (repo root, modify): a "Check-in API" section covering:
    - the endpoints, multipart fields and an example `curl.exe` call
    - status rules (VALID / PENDING_REVIEW reasons), the 16 h rule, idempotency semantics
    - where photos are stored (`UPLOADS_DIR`, default `backend/uploads`), "never commit uploads"
    - the `DEV_SEED_LAT/LNG/RADIUS` usage
    - the new error codes
51. **`docs/diagrams/stage-2-time-entry.md`**: architect (below).
52. `CHANGELOG.md` and `docs/PROGRESS.md`: main session only. PROGRESS already lists the Stage 2 steps. Add "V3 migration" and "Dev seed location env vars" as steps.

### Step 11 - Verification (coder and tester, in the sandbox)
53. `cd backend; .\mvnw.cmd clean test`: all green. Report the new total (398 + new).
54. Check that the `target/test-uploads` contents are created only under `{companyId}/{yyyy-MM}/`.

### Step 12 - Manual end-to-end (user's terminal; the sandbox cannot run Tomcat)
Windows PowerShell 5.1 or 7. `curl.exe` ships with Windows 10+ and handles multipart in both. PINs are typed at a prompt, never pasted into chat.

1. Optional, to test the geofence at your real location: in `backend/.env` set `DEV_SEED_LAT`, `DEV_SEED_LNG` (from Google Maps, dot decimals) and `DEV_SEED_RADIUS=100`.
2. Start the server:
   ```powershell
   cd backend
   .\mvnw.cmd clean spring-boot:run "-Dspring-boot.run.profiles=dev"
   ```
   Expect Flyway "Migrating schema ... to version 3", "Photo storage root: ...\backend\uploads", and "Dev seed already present" (plus "branch location updated" if step 1 was done).
3. Second window. Make a sample selfie and a fake one:
   ```powershell
   Add-Type -AssemblyName System.Drawing
   $bmp = New-Object System.Drawing.Bitmap 640,480
   $g = [System.Drawing.Graphics]::FromImage($bmp); $g.Clear([System.Drawing.Color]::SteelBlue); $g.Dispose()
   $jpg = "$env:TEMP\selfie.jpg"; $bmp.Save($jpg, [System.Drawing.Imaging.ImageFormat]::Jpeg); $bmp.Dispose()
   $fake = "$env:TEMP\fake.jpg"; Set-Content $fake 'not an image'
   ```
4. Log in and look at today:
   ```powershell
   $base = 'http://localhost:8080'
   $pin = Read-Host 'DEV_SEED_PIN'
   $login = Invoke-RestMethod -Method Post "$base/api/auth/login" -ContentType 'application/json' `
            -Body (@{companyCode='DEMO'; employeeCode='1001'; pin=$pin} | ConvertTo-Json)
   $auth = "Authorization: Bearer $($login.token)"
   curl.exe -s "$base/api/time" -H $auth
   curl.exe -s "$base/api/time-entries/today" -H $auth     # nextAction NONE, blockedReason CONSENT_REQUIRED (if no consent yet)
   ```
5. Before consent, a POST is refused:
   ```powershell
   $uuid1 = [guid]::NewGuid().ToString()
   $now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
   curl.exe -s -X POST "$base/api/time-entries" -H $auth -F type=CLOCK_IN -F clientUuid=$uuid1 `
     -F deviceTime=$now -F lat=9.9325 -F lng=-84.0796 -F "photo=@$jpg;type=image/jpeg"
   # 403 code CONSENT_REQUIRED (skip if 1001 already consented)
   curl.exe -s -X POST "$base/api/me/consent" -H $auth -w "%{http_code}`n"    # 204
   ```
6. Without a photo, then clock in, replay it (with and without the photo), then try a fake photo:
   ```powershell
   curl.exe -s -w "`n%{http_code}`n" -X POST "$base/api/time-entries" -H $auth -F type=CLOCK_IN `
     -F clientUuid=([guid]::NewGuid()) -F deviceTime=$now -F lat=9.9325 -F lng=-84.0796
   # 400 code PHOTO_REQUIRED; nothing written
   $sent = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
   curl.exe -s -w "`n%{http_code}`n" -X POST "$base/api/time-entries" -H $auth -F type=CLOCK_IN -F clientUuid=$uuid1 `
     -F deviceTime=$now -F deviceSentAt=$sent -F lat=9.9325 -F lng=-84.0796 -F accuracy=12 -F "photo=@$jpg;type=image/jpeg"
   # 201, status VALID (or PENDING_REVIEW OUTSIDE_GEOFENCE if the branch is at your location), distanceMeters, lateMinutes
   curl.exe -s -w "`n%{http_code}`n" -X POST "$base/api/time-entries" -H $auth -F type=CLOCK_IN -F clientUuid=$uuid1 `
     -F deviceTime=$now -F deviceSentAt=$sent -F lat=9.9325 -F lng=-84.0796 -F accuracy=12 -F "photo=@$jpg;type=image/jpeg"
   # 200, same id, "replayed": true
   curl.exe -s -w "`n%{http_code}`n" -X POST "$base/api/time-entries" -H $auth -F type=CLOCK_IN -F clientUuid=$uuid1 `
     -F deviceTime=$now
   # 200, same id, "replayed": true, hasPhoto true (a replay doesn't need the photo again)
   curl.exe -s -X POST "$base/api/time-entries" -H $auth -F type=CLOCK_IN -F clientUuid=([guid]::NewGuid()) `
     -F deviceTime=$now -F "photo=@$fake;type=image/jpeg"     # 400 INVALID_PHOTO
   curl.exe -s -X POST "$base/api/time-entries" -H $auth -F type=CLOCK_IN -F clientUuid=([guid]::NewGuid()) `
     -F deviceTime=$now -F "photo=@$jpg;type=image/jpeg"      # 409 ALREADY_CLOCKED_IN
   ```
   Photo validation runs before the transaction, so a bad photo gives 400 even when the sequence would also fail. A valid photo with a new uuid reaches the sequence rules and gets 409.
7. An oversize upload hits the real Tomcat limits:
   ```powershell
   $big = "$env:TEMP\big.jpg"; $bytes = New-Object byte[] (3500KB); $bytes[0]=0xFF; $bytes[1]=0xD8; $bytes[2]=0xFF
   [IO.File]::WriteAllBytes($big, $bytes)
   curl.exe -s -w "`n%{http_code}`n" -X POST "$base/api/time-entries" -H $auth -F type=CLOCK_OUT `
     -F clientUuid=([guid]::NewGuid()) -F deviceTime=$now -F "photo=@$big;type=image/jpeg"
   # 413 code PAYLOAD_TOO_LARGE. If curl reports a connection reset instead, note it for the auditor (max-swallow-size).
   ```
8. Clock out, then check today and the photo:
   ```powershell
   $now2 = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
   curl.exe -s -X POST "$base/api/time-entries" -H $auth -F type=CLOCK_OUT -F clientUuid=([guid]::NewGuid()) `
     -F deviceTime=$now2 -F deviceSentAt=$now2 -F lat=9.9325 -F lng=-84.0796 -F accuracy=15 -F "photo=@$jpg;type=image/jpeg"     # 201
   $today = curl.exe -s "$base/api/time-entries/today" -H $auth | ConvertFrom-Json
   $today.nextAction; $today.workedMinutes                                                  # NONE, minutes
   curl.exe -s -o "$env:TEMP\back.jpg" -w "%{http_code} %{content_type}`n" "$base/api/time-entries/$($today.clockIn.id)/photo" -H $auth
   # 200 image/jpeg; open $env:TEMP\back.jpg
   ```
9. As the admin, the same photo is visible. For another company's entry the response is 404 (needs a second company, so the tests cover it).
   ```powershell
   $adm = Invoke-RestMethod -Method Post "$base/api/auth/login" -ContentType 'application/json' `
          -Body (@{companyCode='DEMO'; employeeCode='9001'; pin=$pin} | ConvertTo-Json)
   curl.exe -s -o NUL -w "%{http_code}`n" "$base/api/admin/time-entries/$($today.clockIn.id)/photo" -H "Authorization: Bearer $($adm.token)"   # 200
   curl.exe -s -X POST "$base/api/time-entries" -H "Authorization: Bearer $($adm.token)" -F type=CLOCK_IN `
     -F clientUuid=([guid]::NewGuid()) -F deviceTime=$now2 -F "photo=@$jpg;type=image/jpeg"
   # 403 NO_BRANCH_ASSIGNED (admin 9001 has no branch; the photo is needed, otherwise PHOTO_REQUIRED comes first.
   # If 9001 has not consented yet, expect 403 CONSENT_REQUIRED first; consent with his token and retry.)
   ```
10. Check where the files landed and that EXIF is gone:
    ```powershell
    Get-ChildItem ..\backend\uploads -Recurse -File | Select-Object FullName, Length   # uploads\<companyId>\<yyyy-MM>\<uuid>.jpg
    git status --short ..\backend\uploads                                              # nothing (gitignored)
    ```
11. Re-run step 4 the next day (or after local midnight) and expect `nextAction` `CLOCK_IN` again. Report the results to the main session so it can tick PROGRESS.

## Hand-off to the architect (before coding)
Write `docs/diagrams/stage-2-time-entry.md`:
1. **Sequence: `POST /api/time-entries`**
   - Participants: PWA, security filter chain + JWT converter, `TimeEntryController`, `FixedWindowRateLimiter`, `TimeEntryService`, `PhotoProcessor`, `TimeEntryWriter`, `EmployeeRepository` (lock), `TimeEntryRepository`, `BranchRepository`, `WorkScheduleRepository`, `StorageService`/disk, PostgreSQL.
   - Show:
     - the pre-lookup replay (200 short-circuit, even without a photo part)
     - the photo-required check (400 `PHOTO_REQUIRED`)
     - photo validation before the lock (400/413)
     - the lock and the re-check
     - consent and branch (403)
     - the sequence rules (409)
     - the geofence, skew and review computation
     - `store` followed by the rollback-delete synchronization
     - the inserts and the commit
     - the `DataIntegrityViolationException` path, mapped to a replay or a 409
2. **Sequence: `GET /api/time-entries/today`**: the open-shift lookup, `work_date` choice, `nextAction`.
3. **Sequence: photo fetch**, employee vs admin, with the 404 rules.
4. **ER delta (V3):** the new `time_entry` columns, the unique index `(employee_id, work_date, type)`, `ix_time_entry_company_work_date`, and the evidence constraints. Also add a short "Stage 2 changes" note to `docs/diagrams/stage-1-er.md`, or redraw it there.
5. **Package diagram:** add `storage` (`StorageService`, `LocalDiskStorageService`, `PhotoProcessor`, `StorageKeys`) and the `timeentry` services/controllers. Arrows: `timeentry` → `storage`, `employee`, `company`, `common`; `storage` → `common` only. No cycles.
6. **State diagram:** the button: `CLOCK_IN` → `CLOCK_OUT` → `NONE`, with `blockedReason` and the 16 h expiry edge.

## Hand-off to the security-auditor (after implementation)
Focus on:
- **Uploads:**
  - magic-byte sniff vs re-encode actually applied
  - decompression bomb guard
  - no client filename or content type used anywhere
  - storage key generation and validation (traversal, symlink, tenant prefix, DB CHECKs)
  - files outside the web root
  - temp-file cleanup
  - the rollback deletion
  - ImageIO CVE exposure on the JDK in use
  - the real-Tomcat 413 behavior (step 12.7) and `max-swallow-size`
- **IDOR:** both photo endpoints and the today state, across employees and tenants; the response for a missing file
- **Idempotency abuse:** reusing another employee's `clientUuid` must not leak data. Check the 409 body and timing. Check also that the photo-less replay path can't be used to create or read anything it shouldn't.
- **Photo required:** enforced server-side before any write, for every new entry.
- **Input handling:** numeric parsing (NaN, Infinity, comma decimals), the instant range, no input echo in the new 400/409 bodies, `toString` masking of lat/lng
- **Concurrency:** the lock covers all paths; the unique-index backstop mapping is correct
- **Privacy:** consent enforced server-side before any write; no EXIF in stored files; no lat/lng in logs; the retention hook (nullable PHOTO key)
- **DoS:** the per-employee limiter, the ImageIO CPU cost per request, disk growth (Stage 5 quota)
- **Time:** the server clock is authoritative; `device_time` cannot influence `work_date`, lateness or status other than adding review reasons
- **Follow-ups from the Stage 1 report** that this stage touches: I-3 (consent), L-6 (shared-device tokens now allow clocking in as someone else; recommend `POST /api/auth/logout` in Stage 3)

## Edge Cases to Handle

- **Local midnight vs UTC midnight.** Costa Rica is UTC-6, so 18:00-23:59 local is already the next UTC day. `work_date` always comes from the company zone (test at 23:59/00:01 local).
- **JVM zone drift.** The `LocalDate` binding goes through `LocalDateJdbcType`, and the code never calls `LocalDate.now()` without a zone. Tested with the JVM set to Tokyo or Kiritimati.
- **Invalid `company.time_zone`.** `ZoneId.of` throws, giving 500 `INTERNAL_ERROR` and an ERROR log. It can't happen through the API until Stage 4 (which validates zones). Not silently defaulted.
- **Overnight shift.** The shift belongs to its start date; CLOCK_OUT inherits the CLOCK_IN's `work_date` within 16 h (resolved Open Question 1).
- **Shift starting just after midnight, CLOCK_IN just before.** For example, a 00:30 start with a CLOCK_IN at 23:58: the entry gets the previous `work_date`, whose schedule may not exist, so `late_minutes` is NULL. This is rare and documented. The admin adjusts it in Stage 4.
- **Forgotten CLOCK_OUT.** After 16 h the shift auto-expires, `missingClockOut` is shown, and the next day is unblocked.
- **A 16 h+ legitimate shift** (double shift). The CLOCK_OUT after 16 h gets 409 `NOT_CLOCKED_IN`. The admin creates the entry in Stage 4. The window is configurable.
- **Multiple shifts per day.** Out of MVP: one pair per `work_date`, enforced by the app and the unique index.
- **Employee with no schedule that day.** Clocking in is allowed, `late_minutes` is NULL, and the status can still be VALID.
- **Schedule edited after clock-in.** The stored `late_minutes` doesn't change.
- **Employee moved to another branch mid-shift.** The CLOCK_OUT is measured against the current branch and snapshotted in `branch_id`.
- **Branch radius changed later.** The stored reasons and distance reflect entry time.
- **ADMIN without a branch.** 403 `NO_BRANCH_ASSIGNED`, and today shows `blockedReason`.
- **Inactive employee or company mid-shift.** 401 from the converter on the next request.
- **No inactive-branch concept.** Not representable yet (no column). Stage 4 decides.
- **Consent missing.** 403 before any processing or write. Today and time still work.
- **GPS denied or unavailable.** The entry is accepted as PENDING_REVIEW `NO_GPS`, with no GPS evidence row.
- **GPS (0,0) "null island"** from buggy devices. The distance is far, so the result is `OUTSIDE_GEOFENCE`. No special case.
- **Comma decimals** (`9,93` from a Spanish-locale client). 400 `INVALID_REQUEST`. The PWA must send dot decimals (Stage 3 note).
- **NaN or Infinity strings.** `Double.valueOf` accepts them, so the explicit `isFinite` check rejects them.
- **GPS spoofing / mock-location apps.** Not detectable server-side. Mitigated by photo evidence, the review queue, and an accuracy heuristic (exactly 0 or perfectly constant accuracy is suspicious; Stage 4 dashboard idea). Noted only.
- **Phone clock wrong but online.** With `deviceSentAt` (the PWA always sends it), a slow phone gives a positive `skew_seconds` and no reason. Only a client without `deviceSentAt` falls back to the old rule, where a phone 5 min slow looks like `SYNCED_LATE`.
- **`deviceSentAt` earlier than `deviceTime`** (client bug). It is ignored, the fallback rule applies, and the entry is never rejected for it.
- **Camera denied or broken.** The photo is required, so the employee cannot clock in (400 `PHOTO_REQUIRED`). Stage 3 must show a clear message ("enable the camera or ask your administrator"). The admin records the time in Stage 4 through an `adjustment`. This is the accepted trade-off of the user's decision.
- **Replay without a photo.** Returns the original entry (it already has its photo). A new `clientUuid` without a photo gives `PHOTO_REQUIRED`. Another employee's uuid gives 409 `CLIENT_UUID_CONFLICT`, never their data.
- **Missing photo and other errors together.** The fixed check order applies (see the error table), so missing photo plus missing consent gives `PHOTO_REQUIRED`.
- **Device time absurd** (year 1970, or 2100+). 400. Within range, but days off: accepted, flagged, stored.
- **Two offline entries synced together.** Near-zero worked time, both flagged.
- **Out-of-order offline flush.** A CLOCK_OUT before its CLOCK_IN gets 409 `NOT_CLOCKED_IN`. The Stage 3 queue must flush FIFO and serially.
- **Token expiry during the offline flush.** Re-login, then replay with the same uuid gives 200 replays.
- **Concurrent same-uuid or double tap.** The employee row lock plus the unique backstops give exactly one row and one file.
- **Same `clientUuid` in two companies.** Allowed (per-tenant key).
- **Replay with a different payload.** The first write wins. The photo is not reprocessed or stored again.
- **Replay after the CLOCK_OUT.** Returns the original CLOCK_IN.
- **Disk full or I/O error during the write.** 500 `INTERNAL_ERROR`, rollback, no row. The client retries with the same uuid.
- **Commit fails after the file write.** `afterCompletion` deletes the file. A crash leaves an orphan (Stage 5 sweeper).
- **Evidence row present, file missing** (manual deletion, retention). The photo endpoint returns 404 and logs WARN.
- **Huge PNG header / decompression bomb.** Rejected from the header before decoding.
- **CMYK or progressive JPEG.** Progressive decodes fine. CMYK is rejected as `INVALID_PHOTO`, which is acceptable because phone cameras and canvas never produce it.
- **PNG with alpha.** Flattened onto white before JPEG encoding (the JDK JPEG writer fails on alpha).
- **EXIF orientation.** Canvas-encoded selfies carry none. A rotated non-PWA upload may be stored sideways after the re-encode; acceptable.
- **The client lies about the content type or filename.** Ignored; only the bytes matter.
- **Oversize upload.** In MockMvc the app check gives 413. In real Tomcat, Spring raises `MaxUploadSizeExceededException`, which gives 413. Beyond `max-swallow-size`, the client may see a connection reset (manual check).
- **Malformed multipart.** `MultipartException` gives 400 `INVALID_REQUEST`, not 500.
- **Too many parts.** `max-part-count: 10`; exceeding it gives `MaxUploadSizeExceededException` (the message contains "count"), so 413. Acceptable.
- **Photo GET with `Accept: image/jpeg` on an error.** The ProblemDetail may not be renderable. The status is still 404/400 (tested).
- **Rate limit with a legitimate offline backlog.** 30 per 10 min covers it. The PWA honors `Retry-After`.
- **ImageIO temp cache.** Disabled.
- **Lock waits.** The login UPDATE on the same employee row may wait briefly behind a POST transaction (tens of ms). The 5 s lock timeout gives 500 on pathological waits, logged.
- **Test isolation.** The cached context shares one DB and one `target/test-uploads`. Tests use random tenants and assert files by their own key, never directory counts.
- **Dev DB already seeded.** The new `DEV_SEED_*` vars update the existing branch; otherwise they would do nothing.
- **Existing rows without `work_date`.** V3 backfills from `server_time` in the company zone before `SET NOT NULL`. If a dev DB somehow has two same-day CLOCK_INs for one employee, the unique index fails. The fix is `scripts/db/reset-dev-schema.sql`, which is dev only.

## Open Questions (as originally asked; superseded by the resolution below)

1. **Overnight shifts and the "work date" rule** (roadmap open question).
   - **Recommendation:** a shift belongs to the company-local date of its CLOCK_IN, and a CLOCK_OUT closes the open CLOCK_IN if it is at most **16 h** old (configurable `app.time-entry.max-shift-duration`). After that, the CLOCK_IN counts as a forgotten clock-out.
   - Alternative: take the work date from the schedule (a CLOCK_IN shortly before an after-midnight start belongs to the next day). That is more precise for night shifts, but harder to explain and to test.
2. **How lateness is counted once the grace period is exceeded.**
   - **Recommendation:** count from the scheduled start. With start 08:00 and grace 5, 08:05 is on time and 08:12 is "late 12 min". This matches the blueprint's "late N min" and common practice in Costa Rica.
   - Alternative: count from the end of the grace period (08:12 is "late 7 min").
3. **Add an optional `deviceSentAt` field, so late sync can be told apart from a wrong phone clock?**
   - **Recommendation: yes.** The PWA sends the phone time when it actually transmits. `deviceSentAt - deviceTime` is the queue delay measured on one clock, so the skew cancels and it gives `SYNCED_LATE` reliably. `serverTime - deviceSentAt` is the real clock skew, giving `DEVICE_CLOCK_AHEAD` or a large-skew reason.
   - Without it, a phone that is just 3 minutes slow flags every entry as late-synced.
   - When it's absent, fall back to the single-timestamp rule. No extra column is needed, because `skew_seconds` is computed from the better source.
4. **Accept a GPS `accuracy` field and store it (`gps_accuracy_meters`)?**
   - **Recommendation: yes.** Browsers report accuracy with every fix. A "35 m away" reading with ±800 m accuracy proves nothing, so accuracy above 100 m adds `LOW_GPS_ACCURACY` (PENDING_REVIEW). The column is already in the V3 draft.
   - If you say no, drop that column, the field and the reason.
5. **Is the selfie required, or optional with review?**
   - **Recommendation: optional, and a missing photo sets `NO_PHOTO`, which makes the entry PENDING_REVIEW** (the same treatment as missing GPS). An employee with a broken or denied camera can still record the legal time, and the admin sees it in the review queue.
   - Alternative: required (400 `PHOTO_REQUIRED`). That is stricter against "buddy punching", but it blocks clock-in when the camera fails.

## Open Questions: RESOLVED (2026-10-22, user)

These answers override any earlier text in this plan that conflicts with them. The decisions taken without asking (consent required, server-side re-encode, stored lateness, authenticated `GET /api/time`, admin with a branch can clock in, three specific sequence codes) all stand.

1. **Work date:** as recommended. The work date is the CLOCK_IN's company-local date. A CLOCK_OUT closes the open CLOCK_IN if it is at most 16 h old (configurable `app.time-entry.max-shift-duration`).
2. **Lateness:** as recommended. It counts from the scheduled start, and grace only decides whether the entry is late. With start 08:00 and grace 5, 08:12 is late 12 min.
3. **`deviceSentAt`:** yes. It is an optional form field.
   - With it: queue delay = `deviceSentAt - deviceTime`, which decides `SYNCED_LATE`, and `skew_seconds = server_time - deviceSentAt`.
   - Without it, or when it is earlier than `deviceTime`: the single-timestamp fallback.
4. **GPS `accuracy`:** yes. It is an optional field, stored in `gps_accuracy_meters`. Above 100 m it adds `LOW_GPS_ACCURACY`, making the entry PENDING_REVIEW.
5. **Selfie: REQUIRED** (the user chose this over the recommendation).
   - A new entry without a photo part gets 400 `PHOTO_REQUIRED`, localized in EN/ES, and nothing is written.
   - The `NO_PHOTO` review reason is removed.
   - **Replay rule:** a POST whose `clientUuid` already exists for the same employee and type returns the original entry (200) even without a photo part, because the original already has its photo. The replay lookup runs before the photo-required check.
   - Missing GPS still gives PENDING_REVIEW `NO_GPS` (unchanged).

## Amendments after architect review (2026-10-22)

These override anything above that conflicts with them, including the RESOLVED section where it touches the same points. Source: the gaps listed by the architect for `docs/diagrams/stage-2-time-entry.md`.

1. **Rate limit before validation.** The per-employee limiter for `POST /api/time-entries` runs as a `HandlerInterceptor`, or a filter placed after authentication, before `@ModelAttribute` binding and Bean Validation. So a malformed request still counts toward the limit and gets 429 first. Add a test: N malformed posts give 429 on attempt N+1.
2. **No `common` ↔ `auth` cycle.**
   - `TooManyAttemptsException` and `InvalidCredentialsException` move to `common`, or become subclasses of `common.ApiException` defined where they are used.
   - `ApiExceptionHandler` handles `ApiException` generically, so `common` imports nothing from `auth`, `timeentry` or `storage`.
   - Add an ArchUnit-free reflection or import test asserting that `common` doesn't depend on feature packages.
3. **Cheap checks before the expensive photo work.** After `PHOTO_REQUIRED` (presence only), run a read-only pre-check of **consent** and **branch**: 403 `CONSENT_REQUIRED` / `NO_BRANCH_ASSIGNED`. Only then decode, validate and re-encode the photo. The checks are repeated inside the transaction under the employee row lock, as planned.
   - Resulting order: token → rate limit → form fields → replay lookup → photo present → consent / branch pre-check → photo validation and re-encode → transaction (lock, consent, branch, sequence) → store → respond.
   - A request with no consent *and* an invalid photo now gets 403 `CONSENT_REQUIRED`.
4. **Replay `hasPhoto`.** The photo-less replay path looks up the original entry's PHOTO evidence via `EvidenceRepository` (tenant-scoped, with a `companyId` parameter). Add that method in Step 37.
5. **No stuck state from the 16 h rule.**
   - A CLOCK_OUT for an open CLOCK_IN older than `max-shift-duration` is **accepted**, saved as PENDING_REVIEW with the new review reason `SHIFT_TOO_LONG`. It isn't rejected.
   - If the open CLOCK_IN belongs to an **earlier** work date and is older than `max-shift-duration`, it counts as a forgotten clock-out: a new CLOCK_IN for today is allowed, and `today` reports the earlier shift as open or forgotten for admin review.
   - Add tests for both. Update `ReviewReason`, the messages if any, and the ER and sequence notes.
6. **Docs.** The coder adds a one-line "Stage 2 changes: see stage-2-time-entry.md" note at the top of `docs/diagrams/stage-1-er.md`, and updates the Stage 2 diagram for amendments 1, 3 and 5.
7. **Deferred to Stage 3:** after an overnight shift ends (for example at 06:00), `today` shows `CLOCK_IN` for the new day. That's correct for the API; the PWA decides how to present it.

## Audit remediation (2026-10-23)

Security audit fixes M-1, L-1, L-2, L-3 and L-5 are applied; see `securityAudit-report/stage-2-time-entry.md` ("Remediation status"). Changes to this plan: input limits are 2560 px per side and 6 MP; 16-bit/interlaced PNG and progressive JPEG are `INVALID_PHOTO`; decoding is capped by a semaphore (503 `SERVER_BUSY`); the rate limiter is a security-chain filter, so 429 comes before 415; multipart is parsed lazily into `<root>/.upload-tmp`; the row-lock wait is bounded by `app.time-entry.lock-timeout` (503 `SERVER_BUSY`). Manual step 12.7 (oversize upload against real Tomcat) now also confirms that lazy parsing still yields 413.
