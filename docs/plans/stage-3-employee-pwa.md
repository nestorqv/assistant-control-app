# Stage 3 - Employee PWA

Roadmap: `docs/plans/00-roadmap.md`. API contract: `docs/plans/stage-2-time-entry-api.md`, read with the precedence Amendments > "Open Questions: RESOLVED" > the rest, plus its "Audit remediation (2026-10-23)" section. Diagrams: `docs/diagrams/stage-2-time-entry.md` (button state diagram in section 3). Audits: `securityAudit-report/stage-1-auth.md` (L-6, L-7, I-3, I-4) and `securityAudit-report/stage-2-time-entry.md` (Stage 1 follow-ups table, I-3, I-6). Frontend i18n design: `docs/plans/stage-1b-english-naming-i18n.md` (Step 7 and its resolved Open Questions). Blueprint: `docs/prototype/Prototipo-Control-Asistencia.pdf`, page 1.
Branch: `stage1-2`. Status: IMPLEMENTED 2026-10-27 (coder); open: manual phone test, security audit, architect diagrams, PROGRESS.md. Open Questions RESOLVED 2026-10-26 (see the last section; where earlier text conflicts, the resolution wins).

## Summary

Stage 3 turns the `/` placeholder into the employee PWA that the mockup describes:
- a login screen with an 8-digit PIN keypad
- a Ley 8968 consent screen
- one main screen with three states (CLOCK_IN / CLOCK_OUT / NONE), driven by `GET /api/time-entries/today` and a server clock

Every clock action goes through **one path**, an IndexedDB outbox, whether the phone is online or not:
1. capture a front-camera selfie (getUserMedia, with a file-input fallback)
2. compress it on a canvas to a baseline JPEG (~1024 px, quality 0.7)
3. take the best GPS fix available
4. store `{type, clientUuid, deviceTime, coords, photo bytes}` in the outbox
5. flush the outbox strictly FIFO, one request at a time, with `deviceSentAt` set at send time

The flush runs immediately, on the `online` event, on app open, on `visibilitychange` and on a backoff timer. 200 and 201 delete the item. A 401 pauses the flush, opens a PIN re-auth dialog and resumes with the same `clientUuid`. Business 4xx errors become final failures that the user acknowledges.

State lives in a few small React contexts (session, outbox/sync, server clock). The only new runtime dependency is `idb` (about 1.2 KB). Text goes through the existing typed EN/ES resources, and dates and times use `Intl` in the company time zone.

One small backend change: `POST /api/auth/logout`, which bumps `token_version`. Audit L-6 makes this a Stage 3 requirement. Branch coordinates are **not** exposed (resolved Open Question 2). Before sending, the location chip shows only readiness and accuracy; the distance and geofence result come from the server's POST response.

Security:
- a strict CSP plus Trusted Types for the built app, shared by `vite preview` now and Caddy in Stage 5
- no API or photo caching in the service worker
- `registerType: 'prompt'` updates
- a minimal token footprint: the token lives in `sessionStorage` (resolved Open Question 1)

HTTPS on the LAN uses `@vitejs/plugin-basic-ssl` in a separate `dev:phone` mode, so plain `npm run dev` is unchanged.

## Verified against official sources (2026-10-26, WebFetch / WebSearch)

| Item | Finding | Consequence |
|---|---|---|
| `@vitejs/plugin-basic-ssl` (npm latest) | 2.3.0. Peer `vite ^6 \|\| ^7 \|\| ^8`. Node `^18 \|\| ^20 \|\| >=22`. No runtime dependencies. | Compatible with Vite 8 and Node 24. Dev dependency `^2.3.0`. |
| `idb` (npm latest) | 8.0.4. No dependencies, ESM, ships types. | The chosen IndexedDB wrapper. It gives a typed `DBSchema`, an `upgrade` callback and promise transactions, at about 1.2 KB brotli. |
| `idb-keyval` (npm latest) | 6.3.0. No dependencies. | Rejected. It is a single key-value store with no auto-increment key or indexes. FIFO ordering and a per-employee filter would have to be hand-built on top. |
| `fake-indexeddb` (npm latest + README) | 6.2.6, Node `>=18`, exports `fake-indexeddb/auto`. Since v5 **no `structuredClone` polyfill**, and "jsdom lacks structuredClone". A fresh state comes from `new IDBFactory()`. | Dev dependency `^6.2.6`. Vitest's jsdom environment keeps Node 24's global `structuredClone` (the coder confirms this in Step 3). **Node's `structuredClone` cannot clone jsdom `Blob` objects**, so the outbox stores the photo as an `ArrayBuffer` plus a MIME type, never as a `Blob`. That also avoids old iOS Safari Blob-in-IndexedDB bugs. |
| `vite-plugin-pwa` (npm latest + React docs) | 2.0.0 (installed). Peer `vite ... \|\| ^8`. It depends on `workbox-build`/`workbox-window ^7.4.1`. `useRegisterSW` from `virtual:pwa-register/react` returns `needRefresh`, `offlineReady` (`[value, setter]`) and `updateServiceWorker(reload?)`. The docs say to add `workbox-window` as a dev dependency. Types: `vite-plugin-pwa/react`. Hook options are not reactive. | `registerType: 'prompt'`, `injectRegister: false`, an `UpdatePrompt` component behind a thin adapter so tests can mock it. Add `workbox-window ^7.4.1` (latest 7.4.1). Add the `vite-plugin-pwa/react` reference to `vite-env.d.ts`. |
| `react-router` (npm latest) | 8.5.0, peer `react >=19.2.7`, Node `>=22.22`. | The installed `^8.4.0` is fine. No upgrade needed. |
| Vite CSP guide | `html.cspNonce` adds nonces in dev and build, but needs a per-request value. Don't allow `data:` in `script-src`. `build.assetsInlineLimit: 0` turns off data-URI inlining. | No nonce. The production build has no inline scripts, so `script-src 'self'` works. Set `assetsInlineLimit: 0`, so `img-src` needs no `data:`. Dev gets no CSP (see Decisions). |
| Service workers with a self-signed cert (web search: Dean Hume, MSW docs) | Chrome **refuses to register a service worker** when the certificate is untrusted, even after "Proceed anyway" ("An SSL certificate error occurred when fetching the script"). | `basic-ssl` on the LAN is good for camera, GPS and the in-page offline queue. Install and cold-start-offline tests use **USB port forwarding to `localhost`** (a secure context with no cert). See the manual steps. |
| Skia `SkJpegEncoder::Options` (Chromium canvas encoder) | Fields: quality, downsample, alpha, XMP, HDR, origin. **No progressive option.** | Chrome and Android `canvas.toBlob('image/jpeg')` produce a baseline (SOF0) JPEG. |
| Firefox `nsJPEGEncoder.cpp` | `jpeg_set_defaults` + `jpeg_set_quality`, and never `jpeg_simple_progression`. | Firefox output is baseline too. Safari (CoreGraphics) was not verified, so a runtime SOF-marker check guards every photo (Step 6). |
| oxlint `react/no-danger` | Exists. It flags `dangerouslySetInnerHTML`. | Enable it as `error` (audit L-7 item 2). |
| Web Locks API (MDN) | Secure contexts only, Baseline widely available since 2022, also in workers. | Use `navigator.locks.request('attendance-outbox-flush', ...)` so two tabs never flush at the same time. Fall back to an in-memory flag when the API is missing. |

Not verified online (stable platform behaviour; the coder re-checks on MDN while implementing):
- `getUserMedia` error names (`NotAllowedError`, `NotFoundError`, `NotReadableError`, `OverconstrainedError`)
- `geolocation.watchPosition` options
- `crypto.randomUUID` (secure contexts)
- `navigator.storage.persist/estimate`
- `createImageBitmap` applying EXIF orientation by default
- **`ServiceWorkerContainer.register()` is a Trusted Types `TrustedScriptURL` sink.** It matters for the CSP (Decisions, Security).

## Decisions

### Routing and guards (react-router 8)

| Path | Element | Guard |
|---|---|---|
| `/login` | `LoginPage` | If a valid session exists, redirect to `/`. |
| `/consent` | `ConsentPage` | Needs a session (otherwise `/login`). If consent is already given, redirect to `/`. |
| `/` | `EmployeeHome` (main screen) | `RequireSession`: with no identity, go to `/login`. If `consentPending`, go to `/consent`. If there is an identity but no token (cold open, possibly offline), stay and show the main screen from the snapshot. Clocking works and items are queued; sending waits for the PIN (resolved Open Question 3). |
| `/admin` | `AdminHome` placeholder (unchanged; it now also renders `HealthStatus`) | None until Stage 4. Lazy-loaded with `React.lazy`, so the employee bundle doesn't grow in Stage 4. |
| `*` | `NotFound` | None |

`HealthStatus` moves from `EmployeeHome` to `AdminHome`, because a "Servidor en línea" line on the employee screen is noise. The employee pages own a single `role="status"` announcer (`Announcer`).

### State management: React context and hooks, no library

There are three small providers, each a `useSyncExternalStore` over a plain module store, the same pattern as `i18n/language.ts`:
- `SessionProvider`: identity, token, `needsReauth`
- `OutboxProvider`: pending and failed items, flush status, `online`
- `ServerClockProvider`: offset and approximate flag

`today` is fetched by a `useToday()` hook with `reload()`. That makes four kinds of state with few writers, and nothing is shared across distant trees except the session. Redux, Zustand or TanStack Query would add 3-15 KB for no real gain. Query-style caching of `today` would also be wrong, because the outbox overlays it.

### Token handling (Stage 1 decision 2 revisited; resolved Open Question 1 = B, `sessionStorage`)

**Decided:**

| Data | Where | Lifetime |
|---|---|---|
| The token and `expiresAt` | `sessionStorage` key `attendance.session`, a JSON `{token, expiresAt, employeeId}` | Survives the reloads and tab discards that are common on low-end Android. Gone when the app or tab is closed. |
| Non-secret identity: `{employeeId, name, role, companyName, timeZone, companyCode, employeeCode}` | IndexedDB, store `identity` | Until logout. It is needed to pre-fill the re-auth dialog and to tag outbox items. |
| The PIN | Nowhere | Never stored, never logged. |
| `lastCompanyCode` | `localStorage` | Kept after logout, for convenience. Not personal data. |

Other rules:
- **Token expiry.** The token counts as expired when the server-clock estimate is past `expiresAt - 60 s`. The user is asked for the PIN before a send instead of after a failed POST.
- **401 on any call.** Mark `needsReauth` and keep the identity, the outbox and the screen. `ReauthDialog` shows the company and employee codes read-only plus the PIN keypad. On success the new token replaces the old one and the flush resumes. "Not you? / ¿No es usted?" leads to the logout flow.
- **Logout** (header menu):
  1. If the outbox has pending items for this identity, a confirm dialog says so: "N entries not sent yet; signing out deletes them". The primary action is "Cancel".
  2. Then best effort `POST /api/auth/logout` with a 3 s timeout. A 401 or a network error is ignored.
  3. Then clear `sessionStorage`, the IndexedDB stores `identity`, `outbox` and `snapshot`, and in-memory state. Navigate to `/login`.
  4. Offline logout clears local data only. The token stays valid server-side until it expires (documented).
- **One identity per device.** Signing in as a different employee while another employee's items are pending requires discarding them (confirm). Outbox items are flushed only with a token whose `employee.id` equals the item's `employeeId`.

### Server clock (`time/serverClock.ts`, a pure store with injected `now`/`perf` functions)

- **Sync:**
  - `t0 = perf()`, then `GET /api/time`, then `t1 = perf()`
  - `server = epochMillis + (t1 - t0) / 2`
  - store `{server, mono: t1, wall: wallNow(), rtt}`
  - The first sync takes up to 3 samples and keeps the lowest RTT.
- **Read:** `nowMs() = server + (perf() - mono)`. It never uses the phone clock while the monotonic base is valid.
- **Suspend / clock change detection.** On every read, compare `perf() - mono` with `wallNow() - wall`.
  - If they differ by more than 2 s, the phone slept (on Android `performance.now()` may not advance during suspend) or the user changed the clock.
  - The estimate falls back to `server + (wallNow() - wall)`, flagged `approximate`, and a resync is requested.
- **Resync triggers:** app open, `visibilitychange` to visible, `online`, every 10 min while visible, and after any divergence.
- **After a reload while offline:** use the persisted `{offsetMs = server - wall}` from the `snapshot` store, flagged `approximate`. The UI shows "(aprox.)".
- **Ticking.** The display shows HH:mm. A `setTimeout` is scheduled to the next minute boundary of the estimate, not a 1 s interval, which saves battery. It only runs while the page is visible.
- **`deviceTime` and `deviceSentAt` always come from the phone clock** (`new Date().toISOString()`). The server's skew math needs both from the same clock. **Exception:** if the phone clock's year is outside 2001-2099 (a reset battery), both come from the server-clock estimate, so the entry is not rejected with 400 (Stage 2 range check).

### Main screen states (mockup page 1)

The effective state is a pure function `effectiveState(today, pendingItems, lastCompleted)`:

| Condition | Status line (ES) | Button | Underneath |
|---|---|---|---|
| `blockedReason = CONSENT_REQUIRED` | (route guard sends to `/consent`) | | |
| `blockedReason = NO_BRANCH_ASSIGNED` | "Su usuario no tiene sucursal asignada. Consulte a su administrador." | disabled | a link to `/admin` if `role = ADMIN` |
| `nextAction = CLOCK_IN`, nothing pending | "Aún no has marcado hoy" | **Marcar entrada** | Entrada --:-- / Salida --:-- |
| pending CLOCK_IN (offline) | "Entrada guardada · se enviará al reconectar" | **Marcar salida** (enabled; it queues after the CLOCK_IN) | Entrada {deviceTime} (pending icon) · "Pendiente de envío" |
| `nextAction = CLOCK_OUT` | "En turno desde {clockIn}" plus a live worked duration | **Marcar salida** | Entrada {clockIn} with its server geofence result / Salida --:-- |
| pending CLOCK_OUT | "Salida guardada · se enviará al reconectar" | **Listo por hoy** (disabled) | Salida {deviceTime} (pending) · "Pendiente de envío" |
| `nextAction = NONE` with clockIn and clockOut | "Jornada completa · {9 h 05 min}" | **Listo por hoy** (disabled) | both times, each with its server geofence result |
| `nextAction = CLOCK_IN` after an overnight shift (`lastCompleted` ended < 12 h ago) | "Aún no has marcado hoy" plus a secondary line "Último turno: {date} · {in}–{out} · {duration}" | **Marcar entrada** | |

**Overlays:**
- `missingClockOut` / `forgottenWorkDate`: a notice "No marcaste la salida del {date}. Tu administrador lo revisará."
- `PENDING_REVIEW` entries: a badge "En revisión: {reasons}", with reasons translated from `reviewReason.*`.
- `lateMinutes > 0`: "Llegada tarde: {n} min".
- `SHIFT_TOO_LONG` is translated like the others.
- **`lastCompleted`** is taken from the CLOCK_OUT POST response (plus the known clock-in time) and kept in the `snapshot` store (Stage 2 Amendment 7). It is cleared on logout or when a new CLOCK_IN succeeds.

**Pending items are applied on top of `today`.** A queued CLOCK_IN can never be offered again because the server hasn't received it yet. Pending items whose `clientUuid` the server already knows (from a 200 or 201) have been removed by then. A `today` received while a pending item exists never "rolls back" the button.

**Display details:**
- **Greeting:** "Hola, {firstName}", where the first name is the first whitespace token of `name`.
- **Date:** `Intl.DateTimeFormat(locale, {weekday:'long', day:'numeric', month:'long', timeZone})`, with the first letter capitalized via `toLocaleUpperCase(locale)`. For example "Miércoles, 7 de octubre". The comma comes from Intl, which is accepted.
- **Times:** `hourCycle: 'h23'` in both languages, as in the mockup.
- **Locale:** `es-CR` / `en-US`, from the UI language.
- **Time zone:** `today.timeZone`, falling back to the identity's `timeZone`.

**Pronoun.** The mockup uses "tú" ("Aún no has marcado"), while the backend bundles use "usted". The PWA follows the mockup ("tú"), and the designer keeps that consistent across all new strings. The PWA shows its own translated text per error `code`, not the server's `detail` (see Errors), so the two registers don't mix on screen.

### Clock-in / clock-out flow

1. The user taps the primary button. A `SelfieCapture` modal dialog opens.
2. **At the same time,** `acquireLocation()` starts `watchPosition({enableHighAccuracy: true, maximumAge: 0, timeout: 20000})`. It keeps the best fix (lowest `accuracy`) and stops at accuracy ≤ 30 m, or after 20 s, or on error.
3. **Camera.**
   - `getUserMedia({video: {facingMode: 'user', width: {ideal: 1280}, height: {ideal: 720}}, audio: false})`.
   - The preview is mirrored with CSS only; the captured image is not mirrored.
   - "Tomar foto" draws the current frame. The user can then choose "Usar foto" or "Repetir".
   - Tracks are stopped as soon as a photo is accepted or the dialog closes.
4. **Fallback** to `<input type="file" accept="image/*" capture="user">` when `getUserMedia` is missing or fails with `NotFoundError`, `NotReadableError` or `OverconstrainedError`.
   - On `NotAllowedError` (denied), show instructions to re-enable the camera in the site settings first, with a secondary "Usar la cámara del teléfono" action that opens the file input.
   - If both are impossible, show "No se puede marcar sin selfie. Consulte a su administrador." (the Stage 2 trade-off: the selfie is required).
5. **Compression** (`compressImage`): the long side is at most 1024 px, encoded to JPEG at 0.7.
   - If the result is over 1.5 MB, re-encode at 0.5. If it is still over 2 MB, reject (`photoTooLarge`).
   - Every output must start with `FF D8 FF` and pass `isBaselineJpeg()` (SOF0 or SOF1 before the first SOS, no SOF2/SOF3/SOF9-SOF15). Otherwise the photo is rejected with a clear message (`photoUnsupported`).
   - The source frame or file is released after encoding: object URLs revoked, canvas width/height set to 0, the `ImageBitmap` closed.
6. **Confirm.** If no fix is available yet, show "Obteniendo ubicación…" for up to the remaining 20 s, with "Marcar sin ubicación" after 5 s.
   - Denied or unavailable GPS is allowed, with the denied/unavailable chip warning (the server sets `NO_GPS`).
   - A fix with accuracy > 100 m is allowed, with the low-accuracy chip warning.
   - No distance is shown before sending (see "Location readiness and the geofence result").
7. **Enqueue:**
   - `clientUuid = crypto.randomUUID()`, never `Math.random`. Stage 2 audit I-3. There is a `getRandomValues` v4 fallback for browsers without `randomUUID`.
   - `deviceTime` = phone clock (with the year exception above)
   - `lat`/`lng`/`accuracy`
   - photo `ArrayBuffer` + `'image/jpeg'`
   - `employeeId`, `createdAt`
8. Close the dialog, return focus to the primary button, announce the result, and flush.

The button is disabled while the dialog is open or an item for the same action is being created, so a double tap gives one item. The `clientUuid` is created once per confirmed capture, never per send attempt.

### Location readiness and the geofence result (resolved Open Question 2 = B, no backend change)

The client never knows the branch coordinates and computes **no** distance. The server is the only source of "inside / outside".

**Before sending** (the `LocationChip` in the evidence section, and inside the capture dialog), the chip shows readiness only:

| GPS state | ES | EN |
|---|---|---|
| searching | "Buscando ubicación…" | "Finding location…" |
| fix with accuracy ≤ 100 m | "Ubicación lista · ±{accuracy} m" | "Location ready · ±{accuracy} m" |
| fix with accuracy > 100 m | "Ubicación imprecisa · ±{accuracy} m · la marca quedará en revisión" | "Low location accuracy · ±{accuracy} m · the entry will be reviewed" |
| denied | "Ubicación bloqueada · la marca quedará en revisión" | "Location blocked · the entry will be reviewed" |
| unavailable or timeout | "Ubicación no disponible · la marca quedará en revisión" | "Location unavailable · the entry will be reviewed" |

- `accuracy` is rounded to whole meters.
- The 100 m threshold is the client constant `LOW_ACCURACY_METERS = 100`, mirroring the server default `app.time-entry.max-gps-accuracy-meters`. A comment says the two must stay in sync. The warning is advisory; the server decides `LOW_GPS_ACCURACY`.

**After the server answers**, the `EntryResult` line under each Entrada/Salida time shows the server's verdict:

| Server data | ES | EN |
|---|---|---|
| `insideGeofence = true` | "Dentro del área · a {distanceMeters} m del local" | "Inside the area · {distanceMeters} m from the branch" |
| `insideGeofence = false` | "Fuera del área · {distanceMeters} m · en revisión" | "Outside the area · {distanceMeters} m · under review" |
| `insideGeofence = null` (no GPS) | "Sin ubicación · en revisión" | "No location · under review" |

- If `status = PENDING_REVIEW`, the review badge lists the translated `reviewReasons` (for example "En revisión: Fuera del área, Enviada con retraso").
- **Sources of the result:**
  - the POST response (`TimeEntryResponse`, 201 or 200 replay), shown immediately and announced
  - afterwards `today.clockIn` / `today.clockOut` (`EntrySummary` has the same `distanceMeters`, `insideGeofence`, `status` and `reviewReasons`), so the result survives a reload
- **Queued offline items** show "Pendiente de envío" / "Pending send" in place of the result until their response arrives. The chip's pre-send readiness text is not repeated there.

### Offline outbox (`storage/outbox.ts`, `sync/flush.ts`)

**IndexedDB `attendance`, version 1, via `idb`.**

| Store | Key | Contents |
|---|---|---|
| `outbox` | autoIncrement `seq` (FIFO order) | `{seq, employeeId, type, clientUuid, deviceTime, lat?, lng?, accuracy?, photo: ArrayBuffer \| null, photoType, createdAt, attempts, state: 'pending' \| 'failed', failure?: {status, code?}}`. Index `byEmployee`. |
| `identity` | `'current'` | see Token handling |
| `snapshot` | `'current'` | `{employeeId, today (last good TodayResponse minus nothing sensitive beyond what the screen shows), lastCompleted?, clockOffsetMs, savedAt}` |

**Flush algorithm.** It runs under a Web Lock, otherwise an in-memory single-flight flag.
1. Skip when offline, when there is no valid token, or when `needsReauth` is set.
2. Take the oldest `pending` item of this `employeeId`.
3. Build the `FormData`:
   - `type`, `clientUuid`, `deviceTime`
   - `deviceSentAt = new Date().toISOString()`, set **now**, at send time
   - `lat`/`lng`/`accuracy` via `String(number)`, which always gives dot decimals; never `toLocaleString`
   - `photo` as `new Blob([buffer], {type})` with the filename `selfie.jpg`
   - No manual `Content-Type` header, so the browser sets the boundary.
4. `POST /api/time-entries` with `AbortSignal.timeout(45 s)`.
5. Handle the outcome:

| Outcome | Action |
|---|---|
| 201 or 200 (`replayed`) | Delete the item, including the photo. Keep the `TimeEntryResponse` in memory as `lastResult[type]`, so the `EntryResult` line shows the server's geofence result until the next `today`. Announce "Entrada/Salida registrada a las {serverTime} · {geofence result}" (for example "· Fuera del área · 1112 m · en revisión"). Update `lastCompleted` on a CLOCK_OUT. Continue with the next item. |
| 401 | Stop and set `needsReauth`. The item is kept unchanged. |
| 403 `CONSENT_REQUIRED` | Stop and route to `/consent`. The item is kept and resent after consent. |
| 429, or 503 `SERVER_BUSY` | Stop and retry after `Retry-After` (default 30 s). |
| network error, timeout, other 5xx | Stop. Exponential backoff 5 s → 15 s → 30 s → 60 s → 5 min (cap). `attempts++`. |
| any other 4xx (400 `PHOTO_REQUIRED`/`INVALID_PHOTO`/`INVALID_REQUEST`, 413, 403 `NO_BRANCH_ASSIGNED`, 409 `ALREADY_CLOCKED_IN`/`NOT_CLOCKED_IN`/`SHIFT_ALREADY_COMPLETED`/`CLIENT_UUID_CONFLICT`) | **Final failure:** `state = 'failed'`, the `failure` is recorded, and **the photo is set to `null` at once** (privacy). Stop the flush. A card shows the translated message plus "contact your administrator" and an "Entendido" button. That deletes the item, resumes the flush and reloads `today`. |

**Triggers:**
- after an enqueue
- the `online` event
- app open, after `today`
- `visibilitychange` to visible
- the backoff timer, which runs only while items are pending
- after a successful re-auth or consent

**Background Sync: no** (app-level retry only).
- It is Chromium-only.
- It would put the token inside the service worker.
- A 401 needs UI anyway.

This can be revisited later.

**Limits:**
- At most **8** pending items per device, which is 4 days of offline pairs. A new capture when full shows "Demasiadas marcas sin enviar; conéctese a internet" and is refused.
- Before an enqueue, `navigator.storage.estimate()` must leave at least 5 MB free. `QuotaExceededError` maps to the same translated message, and the photo is not kept.
- After the first enqueue, call `navigator.storage.persist()` once (best effort) to reduce eviction.
- Items are never dropped silently, whatever their age. An item older than 24 h shows "pendiente desde {date}".

**UI:**
- The offline banner "Sin señal, la marca se guarda y se envía al reconectar." shows when `navigator.onLine === false` **or** the last request failed at network level. It is the mockup footer, promoted to a polite live region when the state changes.
- The queued state is shown on the button and the status line (table above), plus a small "{n} pendientes" counter.

**The rate limit** (30 POSTs per 10 min) is respected by the serial flush and the backoff. Network failures never reach the server, so they never count.

### Errors (`errors/errorMessages.ts`)

`api/problem.ts` turns every non-2xx into an `ApiProblem {status, code?, retryAfterSeconds?}`:
- It parses `application/problem+json` only when the content type says JSON.
- Security-layer 401/403 have empty bodies, so `code` stays undefined.

`messageKeyFor(problem | clientError)` returns a `MessageKey`. Client-side kinds: `network`, `timeout`, `sessionExpired` (401), `forbidden` (403 with no code), `unknown`. The mapping covers every backend `ErrorCode`, and a test parses `ErrorCode.java` to catch drift.

The UI shows **its own** translated text per code, not `detail`, for three reasons:
- the text is available offline
- the tone matches the "tú" register
- the code can adapt it, for example 429 on login shows "Demasiados intentos. Intente de nuevo en {minutes} min" from `Retry-After`

Login specifics:
- The backend returns the **same 401 `INVALID_CREDENTIALS`** for a wrong PIN, an unknown code, an inactive account and a **locked account** (no enumeration). The "lockout message" is therefore the `INVALID_CREDENTIALS` text, which already says "if it failed several times, wait 15 minutes or contact your administrator".
- The per-IP 429 is the only distinct login error.
- The UI never says which field was wrong.

### Login and keypad

- **Fields:** company code and employee code are text inputs with `autocapitalize="characters"`, `autocomplete="off"`, `spellcheck="false"`, uppercased on submit, pattern `^[A-Z0-9-]{1,20}$` (V2 CHECKs). The company code is pre-filled from `lastCompanyCode`.
- **PIN keypad (`PinKeypad`):**
  - a 3×4 grid: 1-9, then an empty cell or "Borrar todo", then 0, then backspace
  - each key at least 56×56 px (WCAG 2.5.8 needs 24 px; the project asks for ≥ 44 px)
  - hardware digit keys and Backspace are supported when the keypad has focus
- **PIN dots:** 8 dots grouped 4 + 4, `aria-hidden`. A visually hidden polite text "{n} de 8 dígitos" announces progress. Digits are never announced or shown, and there is no hint about which digit is wrong.
- **Submit:** an explicit "Ingresar" button, enabled at 8 digits. **No auto-submit on the 8th digit:** WCAG 3.2.2 On Input, and fewer accidental submits.
- **On failure:** the PIN is cleared, the fields are kept, focus moves to the error message (`role="alert"`), and the keypad is re-enabled. While a request runs, the keypad and button are disabled and the button shows a spinner (no motion under `prefers-reduced-motion`).
- **On success:** store the session and identity, then go to `/consent` if `consentPending`, otherwise `/`.

### Consent (Ley 8968)

The page is shown when `consentPending` is true, either from the login response or from `today`.

**Sections** (ES/EN texts are in the resources; content per resolved Open Question 4, text version `2026-10-v1`, marked "pending legal review" in the README and revisited in Stage 5):
1. Who is responsible: `{companyName}`.
2. What is collected: a selfie and the GPS location (latitude, longitude, accuracy) on each clock-in and clock-out, plus the phone's time.
3. Why: attendance control and review of irregular entries. No other use.
4. Who sees it: authorized administrators of `{companyName}`.
5. How long it is kept: selfies and exact coordinates for **12 months**, then deleted. Time records are kept as labour law requires. (This also covers coordinates, per Stage 2 audit I-6. The deletion job itself is Stage 5 work.)
6. Your rights: access, rectification, cancellation and opposition (Ley 8968 art. 7). Requests go to your company's administrator.
7. Without consent you cannot clock in or out.

**Actions:**
- **"Acepto"** sends `POST /api/me/consent` (204, idempotent), then reloads `today`, navigates to `/` and resumes the flush.
- **"No acepto"** shows a short explanation and a "Cerrar sesión" action.
- Consent needs a connection. Offline, the screen says so and keeps the button disabled with a reason.

A frontend constant `CONSENT_TEXT_VERSION = '2026-10-v1'` is kept for the Stage 5 consent versioning (Stage 1 audit I-3). The API doesn't take it yet.

### PWA and service worker

- **Registration:** `registerType: 'prompt'` with `injectRegister: false`. The `UpdatePrompt` component uses `useRegisterSW`, through `pwa/registerSW.ts` so tests can mock it.
- **Update UX:** when `needRefresh` is set, a non-modal banner says "Hay una nueva versión · Actualizar". It is never shown while the selfie dialog is open or a flush is running. "Actualizar" calls `updateServiceWorker(true)`. The outbox is in IndexedDB, so a reload loses nothing.
- **Why not `autoUpdate`:** it could reload the page in the middle of a capture.
- **Workbox config:**
  - `globPatterns: ['**/*.{js,css,html,svg,png,ico,webmanifest}']`
  - `navigateFallback: 'index.html'`, keeping the existing `navigateFallbackDenylist: [/^\/api\//]`
  - **no `runtimeCaching`**, so API responses and photos (personal data) are never cached
  - `cleanupOutdatedCaches: true`
  - the default `skipWaiting`/`clientsClaim` (false) with `prompt`
- **Manifest:** unchanged (Spanish; Stage 1b Open Question 1).

### HTTPS in dev

- `npm run dev` stays `http://localhost:5173`. `localhost` is a secure context, so the camera and GPS work on the PC.
- **New `npm run dev:phone`** (`vite --mode phone --host`): with `mode === 'phone'`, `vite.config.ts` adds `basicSsl()`. Vite then serves `https://<LAN-IP>:5173`, and the `/api` proxy still targets `http://localhost:8080`. The backend stays plain HTTP on the PC; the phone only ever talks to Vite.
- **New `npm run preview:phone`** (`vite preview --mode phone --host`): the built app with the production CSP over LAN HTTPS. The service worker will not register there, because the cert is untrusted.
- **Service worker / install / cold-offline tests:** `npm run build && npm run preview`, plus Chrome USB port forwarding of 4173 to the phone's `localhost:4173`.
- `vite.config.ts` becomes `defineConfig(({ mode }) => ...)`. `pwaConfig.test.ts` calls it with `{ mode: 'development', command: 'serve' }`.

### Security headers and CSP

`frontend/securityHeaders.ts` (new, imported by `vite.config.ts`) exports `PROD_CSP`, `SECURITY_HEADERS` and `DEV_HEADERS`.

**`PROD_CSP`:**
```
default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self';
worker-src 'self'; manifest-src 'self'; font-src 'self'; media-src 'self'; object-src 'none';
base-uri 'none'; frame-ancestors 'none'; form-action 'self'; upgrade-insecure-requests;
require-trusted-types-for 'script'; trusted-types default
```

**Why each part:**
- `blob:` in `img-src` is for the selfie preview's object URL.
- The camera preview uses `video.srcObject` (a MediaStream), which no CSP directive governs.
- React `style` props go through the CSSOM, which `style-src` does not block.
- `upgrade-insecure-requests` is harmless on `localhost`.
- **Trusted Types:** `navigator.serviceWorker.register()` is a `TrustedScriptURL` sink, and workbox-window passes a string. `src/security/trustedTypes.ts` is imported **first** in `main.tsx`. It creates a `default` policy whose `createScriptURL` returns the input only when it equals `/sw.js` (same origin) and throws otherwise. `createHTML` and `createScript` always throw. Where `trustedTypes` is missing (Firefox, older Safari), it does nothing.

**`SECURITY_HEADERS`** = `PROD_CSP` plus:
- `Referrer-Policy: no-referrer`
- `Permissions-Policy: camera=(self), geolocation=(self), microphone=(), payment=(), usb=()`
- `X-Content-Type-Options: nosniff`
- `Cross-Origin-Opener-Policy: same-origin`

**Where each set is applied:**

| Server | Headers | Why |
|---|---|---|
| `vite preview` | `SECURITY_HEADERS` (enforced) | the built app, tested before release |
| `vite dev` | `DEV_HEADERS`: everything **except** the CSP | dev only. React Refresh injects an inline preamble script, Vite injects `<style>` tags, and HMR uses `ws:`/`wss:`. |
| Caddy (Stage 5) | the same strings, copied from `securityHeaders.ts`, plus HSTS | |

`build.assetsInlineLimit: 0` keeps `data:` out.

**Lint:**
- `react/no-danger: error`
- `no-eval: error`
- `no-new-func: error`, `no-implied-eval: error`, if oxlint 1.81 has them (the coder checks the rule list and drops any missing one)

**Same origin.** The API and UI stay on the same origin and there is no CORS (audit L-7 item 3). There are no third-party scripts, fonts or CDNs.

### Accessibility (WCAG 2.2 AA)

- **Targets:** the primary button is at least 64 px tall and full width. Keypad keys are at least 56 px. Everything else is at least 44×44 px.
- **Contrast** (computed):
  - `#0F5F56` on `#F2F2F0`: about 6.7:1
  - white on `#0F5F56`: about 7.5:1
  - Both pass AA for normal text. The disabled "Listo por hoy" keeps at least 4.5:1 text contrast and is not shown by color alone (icon plus text).
- **Focus management:**
  - Each route's `<h1>` gets focus on navigation (`tabIndex={-1}`).
  - After a state change (success, queued, failure), focus goes to the status line heading.
  - Dialogs (`SelfieCapture`, `ReauthDialog`, logout confirm) use the native `<dialog>` with `showModal()`: focus trap, Esc to close, and focus returned to the opener.
  - Errors use `role="alert"`.
- **Announcements:** a single polite `role="status"` `Announcer` per employee page. It announces "Entrada registrada a las 07:58", "Salida guardada; se enviará al reconectar", "Sin señal…" (on change only), flush results, and "{n} de 8 dígitos". The `LanguageSwitcher` keeps its own `aria-live` span, not `role="status"`.
- **Clock:** the server clock is not a live region; it updates every minute and would be noisy. It has `aria-label` "Hora del servidor 07:58" on a `<time>` element.
- **Reduced motion:** with `prefers-reduced-motion: reduce`, no spinner rotation and no transitions.
- **Language:** `lang` attributes come from the existing i18n, and every new string exists in both languages.
- **Camera dialog:** the `<video>` has an accessible name ("Vista previa de la cámara"). The capture button is reachable by keyboard. The file fallback is a labelled button that triggers the hidden input.

### Frontend types (hand-written, `src/api/types.ts`)

There is no OpenAPI generator in the backend (springdoc is absent), so the types are hand-written next to these Java records. Jackson's Boot defaults serialize `Instant` as an ISO string, `LocalDate` as `"YYYY-MM-DD"` and `LocalTime` as `"HH:mm:ss"` (parse only `HH:mm`, so `"HH:mm"` also works), and enums as their names.

```ts
type Role = 'EMPLOYEE' | 'ADMIN'
type NextAction = 'CLOCK_IN' | 'CLOCK_OUT' | 'NONE'
type TimeEntryType = 'CLOCK_IN' | 'CLOCK_OUT'
type TimeEntryStatus = 'VALID' | 'PENDING_REVIEW' | 'REJECTED'
type ReviewReason = 'NO_GPS' | 'OUTSIDE_GEOFENCE' | 'LOW_GPS_ACCURACY' | 'SYNCED_LATE' | 'DEVICE_CLOCK_AHEAD' | 'SHIFT_TOO_LONG'
type BlockedReason = 'CONSENT_REQUIRED' | 'NO_BRANCH_ASSIGNED'
// auth/LoginResponse.java
type LoginResponse = { token: string; tokenType: 'Bearer'; expiresAt: string;
  employee: { id: number; name: string; jobTitle: string | null; role: Role; consentPending: boolean };
  company: { name: string; timeZone: string } }
// employee/ProfileResponse.java (not called on app open; kept for completeness)
type ProfileResponse = { id: number; code: string; name: string; jobTitle: string | null; role: Role;
  consentPending: boolean; company: { name: string; timeZone: string } }
// timeentry/TimeResponse.java
type TimeResponse = { serverTime: string; epochMillis: number; timeZone: string; localDateTime: string }
// timeentry/TodayResponse.java
type EntrySummary = { id: number; serverTime: string; status: TimeEntryStatus; reviewReasons: ReviewReason[];
  distanceMeters: number | null; insideGeofence: boolean | null; lateMinutes: number | null; hasPhoto: boolean }
type TodayResponse = { workDate: string; nextAction: NextAction; blockedReason: BlockedReason | null;
  clockIn: EntrySummary | null; clockOut: EntrySummary | null; workedMinutes: number | null;
  inProgress: boolean; missingClockOut: boolean; forgottenWorkDate: string | null;
  schedule: { startTime: string; endTime: string; graceMinutes: number; overnight: boolean } | null;
  branch: { name: string; radiusMeters: number /* no coordinates, by design */ } | null;
  serverTime: string; timeZone: string; consentPending: boolean }
// timeentry/TimeEntryResponse.java
type TimeEntryResponse = { id: number; clientUuid: string; type: TimeEntryType; serverTime: string; workDate: string;
  status: TimeEntryStatus; reviewReasons: ReviewReason[]; syncedLate: boolean; skewSeconds: number | null;
  distanceMeters: number | null; radiusMeters: number | null; insideGeofence: boolean | null;
  lateMinutes: number | null; hasPhoto: boolean; replayed: boolean }
// common/ApiExceptionHandler (RFC 9457)
type ProblemBody = { type?: string; title?: string; status: number; detail?: string; instance?: string; code?: string }
```

- `jobTitle` is nullable in the schema; the coder confirms this against `Employee`.
- `parseInstant(s)` trims fractional seconds to 3 digits before `new Date(s)`. Java may emit microseconds, and older Safari is strict about that.
- **Drift guard:** `src/api/contract.test.ts` reads these Java enums from `../backend/src/main/java/...` with `node:fs` and compares their constants with the TS unions and the translation maps: `ErrorCode.java`, `ReviewReason.java`, `NextAction.java`, `TimeEntryStatus.java`, `Role.java`. The test is skipped if the backend folder is absent.

### Backend change (small)

The only backend change is the logout endpoint. `today` and `TodayResponse` are unchanged, and branch coordinates stay private (resolved Open Question 2).

1. **`POST /api/auth/logout`** (authenticated, 204). It runs `update Employee e set e.tokenVersion = e.tokenVersion + 1 where e.id = :id and e.companyId = :companyId`. **It ends every session of that employee on every device.** This is documented and acceptable: it is the only revocation mechanism (`ver` claim).
   - No `SecurityConfig` change is needed: the path falls under `anyRequest().authenticated()`, and `LoginAwareBearerTokenResolver` only strips tokens on login and health.
   - An expired token gets 401, which the client ignores.

### Bundle and performance budget (low-end Android, slow data)

| Asset | Budget | Note |
|---|---|---|
| All JS, gzip | ≤ 130 KB | React 19 + react-dom is about 60 KB, react-router about 20 KB, app plus `idb` plus workbox-window the rest |
| Entry chunk, gzip | ≤ 110 KB | `AdminHome` and `SelfieCapture` + `compressImage` are lazy chunks (still precached for offline) |
| CSS, gzip | ≤ 10 KB | |
| Precache total, raw | ≤ 600 KB | icons included |

These are enforced by `src/bundleBudget.test.ts`, which reads `dist/` like `pwaConfig.test.ts` and is skipped without a build. **The coder first measures the current build and reports it.** If the untouched baseline already exceeds a number, the coder reports it instead of silently raising it.

Performance notes:
- system fonts only, no images other than the icons
- the clock ticks per minute, not per second
- the camera at 1280×720 ideal, not full sensor
- canvas work at 1024 px
- the camera stopped right after capture
- no polling of `today`: it is refreshed on open, on visibility, after a flush and at the minute tick only when `inProgress`, for the worked time, which is computed locally with no request
- `fetch` timeouts: 15 s for GET, 45 s for POST

## Requirements Checklist

### Planning
- [x] Read CLAUDE.md, roadmap, Stage 1b and Stage 2 plans (with precedence), Stage 2 diagrams, both audits, README, the whole frontend and the relevant backend controllers/DTOs
- [x] Verified basic-ssl, idb, idb-keyval, fake-indexeddb, vite-plugin-pwa, workbox-window, react-router, the Vite CSP guide, SW + self-signed cert behaviour, the Chromium/Firefox JPEG encoders, the oxlint rule and Web Locks
- [x] Open Questions answered by the user (2026-10-26) and applied to this plan
- [x] Designer hand-off delivered before coding
- [ ] Architect: `docs/diagrams/stage-3-employee-pwa.md` (recommended; see the hand-off)

### Backend
- [x] `POST /api/auth/logout` bumps `token_version`, 204; the old token then gets 401; tests
- [x] No change to `today` / `TodayResponse` (resolved OQ 2 = B)

### Screens and flows
- [x] Login: company code + employee code + 8-digit keypad, dots grouped 4+4, explicit submit, 401 and 429 (`Retry-After`) messages, no field hint
- [x] Consent: Ley 8968 content, accept gives `POST /api/me/consent`, decline explains and offers logout, blocks clocking, bilingual
- [x] Main screen: greeting, date in the company zone, server clock, status line, worked duration, entry and exit times, review badges, late minutes, missing clock-out notice, overnight "last shift" line
- [x] Three states plus blocked plus queued states from `effectiveState()`
- [x] Selfie: getUserMedia front camera, file-input fallback, denied or broken camera message, required
- [x] Compression: ≤ 1024 px, JPEG 0.7 (0.5 retry), baseline check, < 2 MB, resources released
- [x] Geolocation: high accuracy, best fix within 20 s, denied/unavailable allowed with a warning, accuracy > 100 m warning
- [x] Location chip before sending: readiness and accuracy only, with warnings for denied, unavailable or low-accuracy GPS. After the response: the server's distance, geofence result and review reasons. Queued items show "Pendiente de envío".
- [x] Offline outbox: IndexedDB via `idb`, FIFO serial flush, `deviceSentAt` at send, 200/201 success, final 4xx failures acknowledged, backoff, `Retry-After`, Web Lock, limits and quota, banner text from the mockup
- [x] 401 mid-shift: item kept, PIN re-auth dialog, resend with the same `clientUuid`
- [x] Logout: server revocation + local wipe; confirm when items are pending; one identity per device
- [x] Token in `sessionStorage`, identity and outbox in IndexedDB (OQ 1 = B); PIN never stored
- [x] Cold open offline with a stored identity allows queued clocking; sending waits for the PIN (OQ 3)
- [x] Consent text version `2026-10-v1` with the 12-month retention and administrator contact; README marks it "pending legal review" (OQ 4)

### Platform
- [x] `dev:phone` / `preview:phone` with basic-ssl over the LAN; `/api` proxy works; plain `dev` unchanged
- [x] PWA: prompt updates, app shell precache, no runtime caching of `/api` or photos
- [x] CSP + Trusted Types + headers in preview; dev headers without the CSP; `securityHeaders.ts` as the single source for Stage 5
- [x] Lint bans (`react/no-danger`, eval family)
- [x] WCAG 2.2 AA: targets, contrast, focus, announcements, reduced motion, dialogs
- [x] i18n: every new string in ES and EN; `Intl` dates and times in the company zone; no-hardcoded-text test extended and green
- [x] Bundle budget test

### Tests and docs
- [x] All tests in Step 11 green; `npm test`, `npm run lint`, `npm run build`, `npm test` again; backend `.\mvnw.cmd clean test` green
- [x] README: "Employee PWA", "Testing on a phone", security headers, logout endpoint
- [ ] Manual phone test (user) completed and reported
- [ ] Security audit (Stage 3 focus list)
- [ ] Main session updates `CHANGELOG.md` and `docs/PROGRESS.md` (not the planner) (CHANGELOG done by the coder 2026-10-27; PROGRESS.md still open)

## Implementation Steps

Paths are relative to `frontend/` unless they start with `backend/` or `docs/`. After each step, `npm test`, `npm run lint` and `npm run build` stay green; backend steps keep `.\mvnw.cmd clean test` green.

### Step 0 - Designer hand-off (before coding)

The `designer` agent produces `docs/design/stage-3-employee-pwa.md`. It may also write presentational components (`components/*`) and `index.css`, under the same i18n and test rules. Contents:
1. **Screen specs** (mobile first, 360×640 baseline, up to 480 px wide centered), each in ES and EN, with exact copy keys:
   - login (empty, typing, submitting, 401, 429, network error)
   - consent (content, accept, decline, offline)
   - main: CLOCK_IN, CLOCK_OUT (live worked time), NONE ("Listo por hoy"), blocked NO_BRANCH_ASSIGNED, queued CLOCK_IN, queued CLOCK_OUT, final failure card, missing clock-out notice, overnight "last shift" line, review badges, late
   - re-auth dialog
   - logout confirm with pending items
   - update banner
2. **Camera flow:** the permission pre-prompt explainer, the live preview (mirrored), capture, review (use / retake), denied (with site-settings instructions per Android Chrome / iOS Safari), no camera, the file-fallback path, and "cannot clock without a selfie".
3. **GPS flow and geofence result** (OQ 2 = B):
   - before sending, the `LocationChip` shows readiness only: searching, "Ubicación lista · ±{n} m", low accuracy, denied, unavailable, plus "Marcar sin ubicación" after 5 s. No distance is shown.
   - after the response, the `EntryResult` line under each time shows inside (with distance), outside (distance plus under review) or no location, plus the review badge
   - a queued item shows "Pendiente de envío"
   - both the chip and the result line are designed in ES and EN
4. **Offline:** the banner, the pending counter, "pending since {date}", the queue-full message, an approximate clock.
5. **Component list** with props and states: `PinKeypad`, `PinDots`, `ClockButton`, `ServerClock`, `StatusLine`, `EvidenceChips` (`SelfieChip`, `LocationChip` for readiness only), `EntryTimes`, `EntryResult` (the server geofence result or "Pendiente de envío"), `ReviewNotice`, `OfflineBanner`, `FailureCard`, `SelfieCapture`, `ReauthDialog`, `ConfirmDialog`, `UpdatePrompt`, `Announcer`, `AppHeader` (+ the logout menu).
6. **Visual tokens:** teal `#0F5F56`, background `#F2F2F0`, text `#1C1F1E`, plus an error color and a warning color with ≥ 4.5:1 contrast. Type scale (clock ~3.5rem), spacing, radius, button heights (primary ≥ 64 px, keypad ≥ 56 px).
7. **A11y notes per screen:** heading order, focus targets after each transition, live-region texts, dialog behaviour, reduced motion.
8. The "tú" register for all new Spanish copy, consistent with the mockup.

### Step 1 - Backend: logout (the only backend change)

1. `backend/src/main/java/cr/attendance/employee/EmployeeRepository.java` (modify): add `@Transactional @Modifying int incrementTokenVersion(@Param("id") Long id, @Param("companyId") Long companyId)`. `TenantRepositoryConventionTest` must stay green.
2. `backend/src/main/java/cr/attendance/auth/AuthController.java` (modify): `@PostMapping("/logout") @ResponseStatus(NO_CONTENT) void logout(@AuthenticationPrincipal EmployeePrincipal p)` calls `incrementTokenVersion(p.employeeId(), p.companyId())` and logs INFO `Logout employee={id} company={id}` (ids only).
3. `backend/src/test/java/cr/attendance/auth/LogoutIntegrationTest.java` (new):
   - logout gives 204, and then `GET /api/me` with the same token gives 401
   - a fresh login works and the new token is accepted
   - no token gives 401
   - a second logout with the old token gives 401
   - another employee's token in the same company is unaffected
   - the same employee code in another company is unaffected
4. `README.md` (modify, Authentication section): `POST /api/auth/logout`, "ends all sessions of that employee".
5. `TodayResponse`, `TodayService` and `TodayStateIntegrationTest` are **not** changed (resolved OQ 2 = B).

### Step 2 - Dependencies and build configuration

6. `package.json` (modify):
   - `dependencies`: `"idb": "^8.0.4"`
   - `devDependencies`: `"@vitejs/plugin-basic-ssl": "^2.3.0"`, `"fake-indexeddb": "^6.2.6"`, `"workbox-window": "^7.4.1"`
   - `scripts`: `"dev:phone": "vite --mode phone --host"`, `"preview:phone": "vite preview --mode phone --host"`
   - Install with `npm install`, which updates `package-lock.json`. Check the versions again on npm at install time.
7. `securityHeaders.ts` (new, project root next to `vite.config.ts`): `PROD_CSP`, `SECURITY_HEADERS`, `DEV_HEADERS`, as in Decisions. Use a plain object and no imports, so Caddy can be written from it.
8. `vite.config.ts` (modify):
   - `defineConfig(({ mode }) => ({...}))`
   - `plugins: [react(), ...(mode === 'phone' ? [basicSsl()] : []), VitePWA({...})]`
   - VitePWA: `registerType: 'prompt'`, `injectRegister: false`, workbox `globPatterns`, `navigateFallback: 'index.html'`, the existing denylist, `cleanupOutdatedCaches: true`, no `runtimeCaching`
   - `server.headers: DEV_HEADERS`, `preview.headers: SECURITY_HEADERS`
   - `build.assetsInlineLimit: 0`
   - keep `proxy` for both server and preview, and the `test` block
9. `tsconfig.node.json` (modify): `"include": ["vite.config.ts", "securityHeaders.ts"]`.
10. `src/vite-env.d.ts` (modify): add `/// <reference types="vite-plugin-pwa/react" />`.
11. `.oxlintrc.json` (modify): `"react/no-danger": "error"`, `"no-eval": "error"`, plus `no-new-func` and `no-implied-eval` if they exist in oxlint 1.81.
12. `src/pwaConfig.test.ts` (modify):
    - call the config function
    - assert `server.proxy`/`preview.proxy` `/api`
    - `preview.headers['Content-Security-Policy'] === PROD_CSP`
    - `server.headers` has no CSP
    - built output: `sw.js` contains no `/api` runtime route (no `registerRoute(` with `/api`), and still has the navigation denylist
    - built `index.html`: no inline `<script>` without `src`, no inline `<style>`, no `style=` attributes, and no `registerSW.js` injection
    - the existing manifest assertions stay unchanged
13. `src/securityHeaders.test.ts` (new):
    - the CSP contains every directive in the Decisions list
    - no `'unsafe-inline'`, `'unsafe-eval'` or `data:`
    - `Permissions-Policy` allows only camera and geolocation for self

### Step 3 - API layer and types

14. `src/api/types.ts` (new): the types in Decisions.
15. `src/api/problem.ts` (new):
    - `class ApiProblem extends Error {status, code?, retryAfterSeconds?}`
    - `async function toProblem(response)`
    - `class NetworkError`, `class TimeoutError` (from `TypeError` / `AbortError` / `TimeoutError` DOMException)
    - `parseRetryAfter` (delta seconds only)
16. `src/api/client.ts` (modify):
    - keep `apiFetch` (Accept-Language) and `ApiError` (`health.ts` still uses them)
    - add `apiRequest<T>(path, {method, body, auth?: boolean, timeoutMs})`, which adds `Authorization: Bearer` from the session store when `auth` is set, applies `AbortSignal.timeout`, maps non-2xx through `toProblem`, notifies the session store on 401, and parses JSON for 200/201, or returns undefined for 204
17. `src/api/auth.ts` (new): `login(companyCode, employeeCode, pin)` and `logout()`.
18. `src/api/me.ts` (new): `giveConsent()`.
19. `src/api/timeEntries.ts` (new): `getTime()`, `getToday()`, `postTimeEntry(item): Promise<{status: 200 | 201; body: TimeEntryResponse}>`, which builds the `FormData` as in Decisions.
20. `src/api/parse.ts` (new): `parseInstant`, `parseLocalTime` (HH:mm).

### Step 4 - Storage and session

21. `src/storage/db.ts` (new): `openDB<AttendanceDB>('attendance', 1, {upgrade})` with the stores `outbox` (autoIncrement `seq`, index `byEmployee`), `identity` and `snapshot`. Export `getDb()` and `resetDbForTests()`. Handle `blocked` / `versionchange` by closing.
22. `src/storage/outbox.ts` (new): `enqueue(item)` (applies the limits and the quota check), `listPending(employeeId)`, `oldestPending(employeeId)`, `remove(seq)`, `markFailed(seq, failure)` (also sets `photo = null`), `incrementAttempts(seq)`, `countPending(employeeId)`, `clearAll()`.
23. `src/storage/snapshot.ts` (new): `saveSnapshot`, `loadSnapshot`, `clearSnapshot`.
24. `src/auth/session.ts` (new): a module store with `getSession()`, `subscribe`, `signIn(loginResponse, codes)`, `markNeedsReauth()`, `reauthenticated(loginResponse)`, `isTokenUsable(nowMs)`, `signOut({discardPending})`. The token goes in `sessionStorage` and the identity in IndexedDB (resolved Open Question 1 = B). Storage calls are wrapped in try/catch, falling back to memory.
25. `src/auth/SessionProvider.tsx`, `src/auth/useSession.ts`, `src/auth/RequireSession.tsx` (new).
26. `src/security/trustedTypes.ts` (new): the `default` policy from Decisions. It exports `scriptUrlAllowed(url)` for tests.

### Step 5 - Time and formatting

27. `src/time/serverClock.ts` (new): the store from Decisions, with injectable `perf`, `wallNow` and `fetchTime`, and `getEstimate(): {ms, approximate}`.
28. `src/time/ServerClockProvider.tsx`, `src/time/useServerClock.ts` (new): resync triggers and the minute-boundary tick.
29. `src/time/format.ts` (new): `formatLongDate(ms, tz, lang)`, `formatTime(ms, tz, lang)`, `formatDuration(minutes, t)` (uses the `duration.hm` / `duration.m` keys), `firstName(name)`, `localeFor(lang)`.

### Step 6 - Capture

30. `src/capture/camera.ts` (new): `openFrontCamera()` returns `{stream, stop}`, and `classifyCameraError(e)` returns `'denied' | 'notFound' | 'busy' | 'unsupported' | 'unknown'`.
31. `src/capture/compressImage.ts` (new): `compressImage(source: ImageBitmap | HTMLVideoElement | Blob, deps = {createCanvas, decode})` returns `{bytes: ArrayBuffer, type: 'image/jpeg', width, height}`. It follows the rules in Decisions, and the dependency injection is there so tests can mock the canvas. A `Blob` source is decoded with `createImageBitmap`, falling back to `HTMLImageElement` + `URL.createObjectURL` (revoked in `finally`).
32. `src/capture/jpegInfo.ts` (new): `isJpeg(bytes)`, `isBaselineJpeg(bytes)` (a marker walk up to SOS, skipping APPn/DQT/DHT segments by length).
33. `src/capture/geolocation.ts` (new): `acquireLocation({target: 30, timeoutMs: 20000})` returns a controller `{best(): Fix | null, done: Promise<Fix | null>, error: 'denied' | 'unavailable' | 'timeout' | null, stop()}`, with `navigator.geolocation` injectable.
34. `src/capture/locationStatus.ts` (new): `locationStatus(fix | null, error)` returns `'searching' | 'ready' | 'lowAccuracy' | 'denied' | 'unavailable'` plus the rounded accuracy, using `LOW_ACCURACY_METERS = 100` (keep in sync with `app.time-entry.max-gps-accuracy-meters`). `geofenceResult(entry: TimeEntryResponse | EntrySummary | null, pending: boolean)` returns `'pending' | 'inside' | 'outside' | 'noLocation'` plus `distanceMeters`. No `geo.ts` and no client-side distance (resolved OQ 2 = B).
35. `src/capture/uuid.ts` (new): `newClientUuid()`, which uses `crypto.randomUUID`, falling back to a `getRandomValues` v4.

### Step 7 - Sync

36. `src/sync/flush.ts` (new): `flushOnce(deps)`, a pure-ish runner with injected `outbox`, `post`, `session`, `now`, `locks` and `announce`. It returns an outcome (`'idle' | 'sent' | 'reauth' | 'consent' | 'retryAfter' | 'backoff' | 'failed'`). It follows the outcome table in Decisions.
37. `src/sync/OutboxProvider.tsx`, `src/sync/useOutbox.ts` (new): the triggers, the backoff timer, `online`/`offline` listeners, the `lastNetworkError` flag, and the exposed `{pending, failed, flushing, online, enqueueCapture, acknowledgeFailure}`.
38. `src/sync/effectiveState.ts` (new): the pure function from Decisions.

### Step 8 - UI

39. **Components** (new unless stated; the designer may author them): `PinKeypad.tsx`, `PinDots.tsx`, `ClockButton.tsx`, `ServerClock.tsx`, `StatusLine.tsx`, `EvidenceChips.tsx` (the `LocationChip` shows readiness only), `EntryTimes.tsx`, `EntryResult.tsx`, `ReviewNotice.tsx`, `OfflineBanner.tsx`, `FailureCard.tsx`, `SelfieCapture.tsx` (lazy), `ReauthDialog.tsx`, `ConfirmDialog.tsx`, `UpdatePrompt.tsx`, `Announcer.tsx`. `AppHeader.tsx` (modify): the switcher plus, when signed in, a "Cerrar sesión" button that opens the logout confirm.
40. **Pages:**
    - `src/pages/LoginPage.tsx` (new)
    - `src/pages/ConsentPage.tsx` (new)
    - `src/pages/EmployeeHome.tsx` (rewrite into the main screen; it uses `useToday`, `useServerClock`, `useOutbox`, `useSession`)
    - `src/pages/AdminHome.tsx` (modify: render `HealthStatus`)
    - `src/pages/useToday.ts` (new)
41. `src/pwa/registerSW.ts` (new): re-exports `useRegisterSW` from `virtual:pwa-register/react`, as the single mock point.
42. `src/App.tsx` (modify): the routes and guards from Decisions; `AdminHome` via `React.lazy` + `Suspense`.
43. `src/main.tsx` (modify):
    - `import './security/trustedTypes'` as the **first** import
    - `initLanguage()`
    - providers: `I18nProvider` > `SessionProvider` > `ServerClockProvider` > `OutboxProvider` > `BrowserRouter`
44. `src/index.css` (modify): the designer's tokens and layouts, `@media (prefers-reduced-motion: reduce)`, `:focus-visible` outlines, and the `.visually-hidden` utility (kept).
45. `index.html` (modify): nothing inline. Optionally add `<meta name="color-scheme" content="light">`. Keep the comment and the Spanish defaults.

### Step 9 - i18n

46. `src/i18n/resources.ts` (modify): add every key in both languages. Groups and the key Spanish texts (the designer finalizes the copy):

| Group | Keys and Spanish text |
|---|---|
| `login.*` | title, companyCode, employeeCode, pin, pinProgress `{n} de 8 dígitos`, submit `Ingresar`, keypad.delete, keypad.clear, submitting |
| `consent.*` | title, intro `{company}`, whatTitle/whatBody, whyTitle/whyBody, whoTitle/whoBody, retentionTitle/retentionBody, rightsTitle/rightsBody, blockedNote, accept `Acepto`, decline `No acepto`, declinedBody, offline |
| `home.*` | greeting `Hola, {name}`, serverClock `Hora del servidor`, approx `(aprox.)`, status.notYet `Aún no has marcado hoy`, status.onShift `En turno desde {time}`, status.dayComplete `Jornada completa · {duration}`, status.queuedIn, status.queuedOut, status.noBranch, lastShift, missingClockOut `{date}`, late `{minutes}`, entry `Entrada`, exit `Salida`, noTime `--:--`, pendingCount `{n}`, pendingSince `{date}` |
| `button.*` | clockIn `Marcar entrada`, clockOut `Marcar salida`, done `Listo por hoy` |
| `evidence.*` | title `Evidencia`, takeSelfie `Tomar selfie`, selfieReady, location.searching `Buscando ubicación…`, location.ready `Ubicación lista · ±{accuracy} m`, location.lowAccuracy `Ubicación imprecisa · ±{accuracy} m · la marca quedará en revisión`, location.denied `Ubicación bloqueada · la marca quedará en revisión`, location.unavailable `Ubicación no disponible · la marca quedará en revisión`, location.skip `Marcar sin ubicación` |
| `result.*` | inside `Dentro del área · a {meters} m del local`, outside `Fuera del área · {meters} m · en revisión`, noLocation `Sin ubicación · en revisión`, pending `Pendiente de envío` (EN: `Inside the area · {meters} m from the branch`, `Outside the area · {meters} m · under review`, `No location · under review`, `Pending send`) |
| `camera.*` | title, preview, capture `Tomar foto`, use `Usar foto`, retake `Repetir`, cancel, denied, deniedHelp, notFound, busy, useNativeCamera `Usar la cámara del teléfono`, cannotClock, processing, photoTooLarge, photoUnsupported |
| `offline.*` | banner `Sin señal, la marca se guarda y se envía al reconectar.`, queueFull, storageFull |
| `announce.*` | clockedIn `{time}`, clockedOut `{time}`, queuedIn, queuedOut, sentWithReview `{reasons}` |
| `reauth.*` | title, body, notYou |
| `logout.*` | button `Cerrar sesión`, confirmTitle, pendingWarning `{n}`, confirm, cancel |
| `update.*` | available, reload, dismiss |
| `failure.*` | title, contactAdmin, acknowledge `Entendido` |
| `reviewReason.*` | 6 reasons |
| `status.*` | VALID, PENDING_REVIEW, REJECTED |
| `error.*` | one key per backend `ErrorCode`, plus `network`, `timeout`, `sessionExpired`, `forbidden`, `unknown`, `tooManyAttemptsMinutes {minutes}` |
| `duration.*` | hm `{h} h {mm} min`, m `{m} min` |
| `common.*` | close, back |

47. `src/i18n/resources.test.ts` (modify): `SAME_IN_BOTH` gains the language-neutral keys (`duration.hm`, `duration.m`, `home.noTime`, and any others the coder finds identical on purpose, each justified in a comment).
48. `src/i18n/noHardcodedText.test.ts` (modify):
    - the glob also covers non-test `../**/*.ts`, excluding `i18n/resources.ts` and `test/**`
    - `SPANISH_WORDS` gains `Marcar|entrada|salida|turno|Jornada|señal|Hola|Acepto|Ingresar|Cerrar|sesión|ubicación|Evidencia`
    - the glob sanity test also expects `pages/LoginPage.tsx` and `components/PinKeypad.tsx`
    - the JSX text regex stays

### Step 10 - Docs

49. `README.md` (modify):
    - **"Employee PWA":** screens, offline behaviour (including clocking on a cold open offline), token storage in `sessionStorage`, logout, and the location display (readiness before sending, server result after)
    - **Consent text:** version `2026-10-v1` (12-month retention for selfies and exact coordinates, time records per labour law, requests to the company administrator), marked **"pending legal review"** and to be revisited in Stage 5
    - **"Testing on a phone":** the manual steps below, condensed
    - **Frontend section:** `dev:phone`, `preview:phone`
    - **"Security headers":** where they are defined, dev vs preview vs Stage 5
50. Architect (recommended, not blocking): `docs/diagrams/stage-3-employee-pwa.md` (see the hand-off).
51. Main session only: `CHANGELOG.md`; `docs/PROGRESS.md` gets these extra Stage 3 steps:
    - "Backend: POST /api/auth/logout"
    - "Security headers / CSP / Trusted Types"
    - "PWA update prompt"
    - "Security audit (Stage 3)"
    - "Manual test on a real phone (camera, GPS, offline, install)"

### Step 11 - Tests (Vitest 5 + Testing Library + user-event + jsdom)

Shared helpers:
- `src/test/renderWithI18n.tsx` (modify): optional `session`, `today` and `outbox` fixtures that wrap the new providers with test stores.
- `src/test/fixtures.ts` (new): `TodayResponse`, `LoginResponse` and `TimeEntryResponse` builders that match the Java JSON exactly.
- `src/test/fakeCanvas.ts` (new)
- `src/test/fakeGeolocation.ts` (new)
- `src/test/fakeMediaDevices.ts` (new)
- `src/test/setup.ts` (modify): `sessionStorage.clear()`, reset the session, clock and outbox stores, and `indexedDB = new IDBFactory()` in the IndexedDB suites, which import `fake-indexeddb/auto`.

Each file and what it covers:
1. `src/api/problem.test.ts`:
   - a JSON problem gives `code`
   - an empty 401/403 has no `code`
   - an HTML 502 is handled
   - `Retry-After` seconds are parsed, and an HTTP-date is ignored
   - `TypeError` gives `NetworkError`, and a timeout gives `TimeoutError`
2. `src/api/timeEntries.test.ts`:
   - the `FormData` part names and values
   - dot decimals even with `lang = 'es'` and a stubbed `Number.prototype.toLocaleString`
   - `deviceSentAt` is set at call time (fake timers) and ≥ `deviceTime`
   - the photo part is a JPEG `Blob` named `selfie.jpg`
   - no manual `Content-Type`
   - the `Authorization` and `Accept-Language` headers
3. `src/api/contract.test.ts`:
   - fixtures parse
   - the enum drift guard against the Java files
   - `parseInstant` with micro- and nanosecond fractions
   - `parseLocalTime` with `"08:00:00"` and `"08:00"`
4. `src/auth/session.test.ts`:
   - the token is in `sessionStorage` and the identity in IndexedDB, never the PIN (scan both stores for the PIN string)
   - `isTokenUsable` uses the server clock and the 60 s margin
   - `signOut` wipes everything
   - storage throwing falls back to memory
5. `src/components/PinKeypad.test.tsx` + `src/pages/LoginPage.test.tsx`:
   - keypad: digits append up to 8, backspace, clear, hardware keys
   - 8 dots in two groups of 4
   - the progress text announced, never the digits
   - submit disabled under 8 digits
   - codes uppercased
   - 401: the `INVALID_CREDENTIALS` text in ES and EN, PIN cleared, focus on the alert
   - 429 with `Retry-After: 300`: "5 min"
   - a network error message
   - success goes to `/consent` when `consentPending`, otherwise `/`
   - every key has an accessible name, and targets have the size classes
6. `src/pages/ConsentPage.test.tsx` and `src/App.routes.test.tsx` (modify):
   - consent gating: a session with `consentPending` landing on `/` is redirected to `/consent`
   - accept calls `POST /api/me/consent` and lands on `/`
   - decline shows the explanation and logout
   - offline disables accept
   - `/admin` still renders the placeholder plus `HealthStatus`
   - not found unchanged
   - the switcher is present on every route
   - **Removed or replaced:** the old "employee home placeholder" assertions and "each page renders the health status region" (now only `/admin`)
7. `src/pages/EmployeeHome.test.tsx`:
   - one test per state from a mocked `today`: CLOCK_IN, CLOCK_OUT (live duration with fake timers), NONE, NO_BRANCH_ASSIGNED (with and without the admin link), `missingClockOut`, review badges (each reason translated), late minutes, the overnight `lastCompleted` line
   - queued CLOCK_IN and queued CLOCK_OUT from the outbox fixture
   - the date for `2026-10-08T05:30:00Z` in `America/Costa_Rica` shows **7 de octubre** (the UTC day is the 8th)
   - an EN rendering
8. `src/sync/effectiveState.test.ts`: every combination in the Decisions table, and "a stale `today` never offers CLOCK_IN while a CLOCK_IN is pending".
9. `src/time/serverClock.test.ts`:
   - offset with RTT/2
   - lowest-RTT sample kept
   - ticks follow `perf`, not `wallNow`, when the phone clock is wrong by ±1 h
   - suspend divergence > 2 s switches to the wall estimate and flags `approximate`
   - the persisted offset after a reload gives `approximate`
   - resync on `visibilitychange`
   - the minute-boundary scheduling (fake timers)
10. `src/time/format.test.ts`: the long date ES/EN with capitalization, `h23` times, durations (`545` gives `9 h 05 min`), `firstName`.
11. `src/capture/compressImage.test.ts` (fake canvas):
    - 1920×1080 becomes 1024×576 and 720×1280 becomes 576×1024
    - small images are not upscaled
    - `toBlob` is called with `'image/jpeg', 0.7`
    - a > 1.5 MB result is retried at 0.5
    - a > 2 MB result after the retry gives `photoTooLarge`
    - output bytes with SOF2 give `photoUnsupported`
    - non-JPEG output (the PNG fallback of `toBlob`) is rejected
    - the object URL is revoked, the bitmap closed and the canvas zeroed (spies)
12. `src/capture/jpegInfo.test.ts`: hand-built byte arrays for SOF0, SOF2, SOF9, APP1 before SOF, a truncated stream and non-JPEG.
13. `src/capture/geolocation.test.ts`:
    - best-fix selection
    - early stop at ≤ 30 m
    - the 20 s timeout returns the best so far
    - `PERMISSION_DENIED` gives `'denied'`
    - `POSITION_UNAVAILABLE`
    - `stop()` clears the watch
14. `src/capture/locationStatus.test.ts` and `src/components/EvidenceChips.test.tsx` + `EntryResult.test.tsx`:
    - **readiness:** searching; accuracy 12.4 gives "Ubicación lista · ±12 m" / "Location ready · ±12 m"; 100 is ready and 101 is low accuracy; denied; unavailable and timeout
    - **the chip never shows a distance** (no "m del local" text before sending)
    - **server result:** a 201 with `insideGeofence: true, distanceMeters: 35` gives "Dentro del área · a 35 m del local"; `false, 1112` with `PENDING_REVIEW [OUTSIDE_GEOFENCE]` gives "Fuera del área · 1112 m · en revisión" plus the badge; `null` gives "Sin ubicación · en revisión"
    - the same result is rendered from `today.clockIn` after a reload
    - a queued item gives "Pendiente de envío" until its response arrives
    - ES and EN
15. `src/storage/outbox.test.ts` (fake-indexeddb):
    - FIFO order by `seq`
    - per-employee filter
    - limit 8 refuses the 9th
    - a quota estimate below 5 MB refuses
    - `markFailed` removes the photo bytes
    - `clearAll`
    - the photo round-trips as an `ArrayBuffer` with identical bytes
16. `src/sync/flush.test.ts` (fake-indexeddb + mocked `post`):
    - strictly serial FIFO (the second POST starts only after the first resolves)
    - 201 and 200 replay delete the item
    - 401 stops and keeps the item, and `needsReauth` is set
    - 403 `CONSENT_REQUIRED` keeps the item and returns `'consent'`
    - 409 `NOT_CLOCKED_IN` gives `failed`, the photo is nulled and the flush stops; acknowledging resumes with the next item
    - 429 and 503 honor `Retry-After`
    - a network error backs off with the sequence 5/15/30/60/300 s
    - `deviceSentAt` differs per attempt, while `clientUuid` and `deviceTime` stay the same
    - items of another `employeeId` are never sent
    - a concurrent `flushOnce` call while one is running does nothing (single flight)
17. `src/sync/reauth.test.tsx` (integration):
    - an item pending, the token expires, flush gives 401, the `ReauthDialog` opens with the codes pre-filled and read-only
    - after the PIN, `login` is called with the stored codes, and the same item is resent with the **same `clientUuid`**
    - 201, the item is gone and success is announced
    - a wrong PIN keeps the item and the dialog
    - "Not you?" opens the logout confirm with the pending warning
18. `src/errors/errorMessages.test.ts`: every backend `ErrorCode` plus the client kinds map to a key whose ES and EN texts are non-empty and different (or listed as same on purpose); unknown codes fall back to `error.unknown`.
19. **Accessibility** (inside the component and page tests, plus `src/pages/EmployeeHome.a11y.test.tsx`):
    - roles and names: the main button by its visible text, the keypad keys, dialogs by their titles, the `<time>` label
    - one `role="status"` per employee page
    - focus moves to the status heading after success
    - dialogs return focus to the opener
    - Esc closes the camera dialog and stops the tracks
    - `role="alert"` on errors
    - the reduced-motion class
20. `src/components/UpdatePrompt.test.tsx` (mocks `pwa/registerSW`): hidden by default; `needRefresh` shows the banner; "Actualizar" calls `updateServiceWorker(true)`; hidden while capture is open.
21. `src/security/trustedTypes.test.ts`: `/sw.js` is allowed; `https://evil/sw.js`, `/other.js` and `javascript:` throw; a no-op without `trustedTypes`.
22. `src/securityHeaders.test.ts`, `src/pwaConfig.test.ts`, `src/bundleBudget.test.ts` (Step 2 and Decisions).
23. `src/i18n/noHardcodedText.test.ts`, `resources.test.ts` (Step 9). The existing `LanguageSwitcher`, `I18nProvider`, `language`, `client` and `HealthStatus` tests stay green; `HealthStatus` now renders under `/admin` in the route tests.
24. Backend: `LogoutIntegrationTest` only (`TodayStateIntegrationTest` is unchanged). `TenantRepositoryConventionTest` and `MessageBundleParityTest` stay unchanged and green.

**Only verifiable on a real phone** (manual steps below):
- real camera capture and the permission prompts
- front-camera orientation and mirroring
- JPEG baseline output on Safari/iOS
- GPS accuracy and timing
- airplane-mode offline and the reconnect flush
- PWA install, update prompt and cold start offline
- the Trusted Types default policy letting the SW register in Chrome
- tab discard and reload keeping the `sessionStorage` session, and closing the app dropping it
- performance on a low-end device

### Step 12 - Verification (coder and tester, sandbox)

52. `cd frontend; npm install; npm test; npm run lint; npm run build; npm test`. The second run covers `pwaConfig`, the bundle budget and the built-HTML checks. Report the bundle sizes.
53. `cd backend; .\mvnw.cmd clean test`: green; report the new total.

### Step 13 - Manual test on a real phone (user; Windows PowerShell)

PINs are typed at prompts only, never pasted into chat.

1. **Optional geofence at your location.** In `backend\.env` set `DEV_SEED_LAT=9.93xx`, `DEV_SEED_LNG=-84.07xx` (dot decimals, from Google Maps: long-press, then copy the coordinates) and `DEV_SEED_RADIUS=100`. They are applied to the existing branch on every dev start.
2. **Backend** (plain HTTP on the PC; the phone reaches it only through the Vite proxy):
   ```powershell
   cd backend
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
   ```
   To test token expiry quickly, add `"-Dspring-boot.run.arguments=--app.jwt.ttl-employee=3m"` (step 9).
3. **Frontend over HTTPS on the LAN** (second window):
   ```powershell
   cd frontend
   npm install
   npm run dev:phone        # prints  Network: https://192.168.x.y:5173/
   ```
   - **Find the PC's LAN IP:** `ipconfig` (the "IPv4 Address" of the Wi-Fi adapter) or `(Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'Wi-Fi').IPAddress`.
   - **Firewall:** the PC and phone must be on the same Wi-Fi. Check that the network profile is Private with `Get-NetConnectionProfile`. Allow "Node.js" on Private networks when Windows asks. If you missed the prompt, run as admin: `New-NetFirewallRule -DisplayName "Vite 5173" -Direction Inbound -Protocol TCP -LocalPort 5173 -Profile Private -Action Allow`.
4. **On the phone (Chrome),** open `https://192.168.x.y:5173`. On "Your connection is not private", choose **Advanced**, then **Proceed to 192.168.x.y (unsafe)**. This is expected for the self-signed dev cert. Camera and GPS work. The service worker does **not**, which is expected (step 10 covers it).
5. **Log in** as `DEMO` / `1001` with the `DEV_SEED_PIN`.
   - The consent screen appears for a fresh employee. If 1001 already consented in Stage 2 testing, it doesn't.
   - Accept, then allow location.
6. **Clock in:** tap "Marcar entrada", allow the camera, take the selfie, choose "Usar foto".
   - Expect "Entrada registrada a las HH:mm".
   - Before you take the photo, the location chip shows "Ubicación lista · ±N m" with no distance.
   - After the response, the line under Entrada shows "Dentro del área · a N m del local" or "Fuera del área · N m · en revisión".
   - The status line reads "En turno desde …".
7. **Offline clock out**, keeping the page open (dev mode has no service worker):
   - Turn on airplane mode and keep Wi-Fi off.
   - Tap "Marcar salida" and take the selfie. Expect the banner "Sin señal, la marca se guarda y se envía al reconectar.", the status "Salida guardada · se enviará al reconectar", the button "Listo por hoy" and "1 pendiente".
   - Turn Wi-Fi back on. Within a few seconds expect "Salida registrada a las HH:mm (en revisión: Enviada con retraso)" if more than 2 minutes passed.
   - Don't reload while offline in dev: the dev server is unreachable.
8. **Verify on the server** (third window):
   ```powershell
   $base = 'http://localhost:8080'
   $pin = Read-Host 'DEV_SEED_PIN' -AsSecureString | ConvertFrom-SecureString -AsPlainText   # PowerShell 7; on 5.1 use Read-Host without -AsSecureString
   $login = Invoke-RestMethod -Method Post "$base/api/auth/login" -ContentType 'application/json' `
            -Body (@{companyCode='DEMO'; employeeCode='1001'; pin=$pin} | ConvertTo-Json)
   $today = Invoke-RestMethod "$base/api/time-entries/today" -Headers @{Authorization="Bearer $($login.token)"}
   $today | ConvertTo-Json -Depth 4           # nextAction NONE, clockIn and clockOut set, reviewReasons on the clock-out
   curl.exe -s -o "$env:TEMP\in.jpg" -w "%{http_code} %{content_type}`n" "$base/api/time-entries/$($today.clockIn.id)/photo" -H "Authorization: Bearer $($login.token)"
   Get-ChildItem backend\uploads -Recurse -File | Sort-Object LastWriteTime | Select-Object -Last 2 FullName, Length
   ```
   - Open `$env:TEMP\in.jpg`: it should be your selfie, not mirrored, about 1024 px.
   - This login resets nothing important. The phone's token stays valid, because only logout bumps `token_version`.
9. **Token expiry mid-shift** (needs the 3-minute TTL from step 2, on a day without a completed pair, or as employee `1002`):
   - Clock in, wait 3+ minutes, go offline, clock out, go back online.
   - Expect the PIN dialog with the codes pre-filled. After entering the PIN the queued clock-out is sent. Check in the server that **exactly one** CLOCK_OUT exists.
10. **Install, service worker and cold start offline** (needs a trusted origin, so use USB):
    1. On the phone, turn on Developer options and USB debugging, then connect the USB cable.
    2. On the PC, run `cd frontend; npm run build; npm run preview` (http://localhost:4173).
    3. In PC Chrome open `chrome://inspect/#devices`. Click **Port forwarding**, add `4173` → `localhost:4173`, and enable it.
    4. On the phone open `http://localhost:4173`. It is a secure context with no cert warning.
    5. Log in. The Chrome menu shows **Install app**; install it.
    6. In the PC's `chrome://inspect`, the phone's page shows the service worker as "activated". Check that the console shows no Trusted Types or CSP violation.
    7. Turn on airplane mode, force-close the app and open it from the home screen. The shell loads, the offline banner shows and the clock shows "(aprox.)". The `sessionStorage` token is gone after the force-close, but clocking still works from the stored identity: the entry shows "Pendiente de envío". Turn airplane mode off with the app open. The PIN dialog appears, and after the PIN the entry is sent and its server result replaces "Pendiente de envío".
    8. **Update prompt:** change any text, `npm run build` again with preview still running, and reopen the app. Expect "Hay una nueva versión · Actualizar".
11. **Logout:** "Cerrar sesión" goes to the login page. Logout ends every session of employee 1001, including the PC token from step 8, which was issued before the logout:
    ```powershell
    try { Invoke-RestMethod "$base/api/me" -Headers @{Authorization="Bearer $($login.token)"} } catch { $_.Exception.Response.StatusCode.value__ }   # 401
    ```
12. **Camera denied:** in Chrome site settings block the camera, then tap "Marcar entrada". Expect the instructions and the "Usar la cámara del teléfono" fallback.
13. **GPS denied:** block location. The clock-in works with the warning, and the server shows `NO_GPS` / PENDING_REVIEW.
14. Optional: iPhone Safari for steps 4-7, to check the baseline-JPEG check and camera behaviour.
15. Report the results to the main session so it can tick PROGRESS.

## Hand-off to the architect (recommended)

`docs/diagrams/stage-3-employee-pwa.md`:
1. **Sequence, clock action:** UI, `SelfieCapture`, `compressImage`, `acquireLocation`, `outbox.enqueue`, `flushOnce`, `POST /api/time-entries` (201/200), `outbox.remove`, `Announcer`, `today` reload.
2. **Sequence, 401 during a flush:** `flushOnce`, then 401, `session.markNeedsReauth`, `ReauthDialog`, `POST /api/auth/login`, `session.reauthenticated`, `flushOnce` with the same `clientUuid`, then 200 or 201.
3. **State diagram, outbox item:** pending → sending → (deleted | pending with backoff | failed → acknowledged → deleted).
4. **Frontend module diagram:**
   - `pages` → `components` → hooks/providers (`auth`, `sync`, `time`) → `api`, `storage`, `capture`
   - `api` → `i18n/language` (Accept-Language) and `auth/session` (token)
   - no cycles; `capture` has no dependency on `api`
5. **Effective button state:** the Stage 2 state diagram plus the queued states and the blocked state.

## Hand-off to the security auditor (after implementation)

Focus on:
- **Token storage (OQ 1 = B, `sessionStorage`):**
  - check that the token never reaches `localStorage`, IndexedDB, logs, URLs or the service worker
  - the cold-open offline queueing without a session (OQ 3): entries are tagged with the stored identity and sent only with that employee's token
  - the PIN is never stored
  - the `sessionStorage` lifetime on Android and iOS
  - the logout endpoint (`token_version` bump; all-device effect)
  - identity data left on the device
- **CSP and Trusted Types:** the preview headers match `securityHeaders.ts`; no `unsafe-*`; the built HTML has no inline script or style; the `default` TT policy allows only `/sw.js`; the lint bans are active; no third-party origins.
- **Service worker cache:**
  - the precache manifest contains only build assets
  - no `runtimeCaching`
  - `/api/**` is never cached or served from the cache (navigation denylist)
  - the photo endpoint is never cached (`no-store` from the backend, and no SW route)
  - the update prompt can't be abused to keep a stale vulnerable shell indefinitely
- **IndexedDB:**
  - the outbox holds photos and coordinates only until a 2xx (then deleted) or a final failure (photo nulled at once)
  - everything is wiped on logout
  - per-employee isolation on shared devices
  - no PIN, no token
  - the quota and limit behaviour
- **Photo blob lifetime:**
  - camera tracks stopped
  - object URLs revoked
  - canvas cleared
  - no photo kept in React state after enqueue
  - no photo in `console` logs
- **Input and output:**
  - `clientUuid` from the CSPRNG
  - dot-decimal numbers
  - `deviceTime` handling of absurd phone clocks
  - error messages never render server `detail` as HTML
  - React escaping only
- **Same origin, no CORS;** `Permissions-Policy` limits the camera and geolocation to self.
- **Backend:** `POST /api/auth/logout` is authenticated, tenant-scoped, and logs no token. Branch coordinates are still never sent to the client (OQ 2 = B), so the Stage 2 audit's note stands. Check that the bundle has no distance or geofence logic that could mislead.
- **Business logic** (Stage 2 L-4, still deferred): the PWA always sends `accuracy` and `deviceSentAt`. Note that a scripted client can still omit them.
- **File-input fallback:** on some browsers `capture="user"` is only a hint, so a gallery photo could be chosen. Assess the photo-reuse risk (only review can catch it).

## Edge Cases to Handle

- **Token expires mid-shift** (8 h token, 9 h shift). The PIN is requested before a send, or at the 401, and the queued item is resent with the same `clientUuid`. The server replays a 200 if the first attempt had landed.
- **POST times out after the server stored the entry.** The retry with the same `clientUuid` gives 200 `replayed`, with no duplicate. (Sending the retry without the photo was considered and rejected: it saves about 150 KB but adds a branch. The server supports it if needed later.)
- **Double tap / two tabs.** The button is disabled during capture, one `clientUuid` is used per capture, and the Web Lock gives single-flight flushing. The server's employee lock and idempotency are the backstop.
- **CLOCK_OUT queued before its CLOCK_IN is sent.** FIFO order guarantees CLOCK_IN first. If the CLOCK_IN fails finally, the CLOCK_OUT is not sent until the failure is acknowledged, and then it will likely get 409 `NOT_CLOCKED_IN`, shown with "contact your administrator".
- **Clocked in on another device meanwhile.** 409 `ALREADY_CLOCKED_IN` is a final failure, acknowledged, and then `today` is reloaded.
- **Consent revoked or missing when the flush runs.** 403 `CONSENT_REQUIRED` keeps the item, routes to consent, and resends after.
- **Overnight shift.** After a 22:00 to 06:00 shift, `today` shows CLOCK_IN for the new day. The "Último turno" line comes from `lastCompleted` (Stage 2 Amendment 7).
- **Forgotten clock-out / `SHIFT_TOO_LONG`.** Shown as a notice and a review badge. The button follows `nextAction`.
- **Local midnight vs UTC.** The date and "today" are always formatted in `today.timeZone`, never the phone zone (a test at 23:30 CR = 05:30 UTC).
- **Phone clock wrong.**
  - The display uses the server estimate.
  - `deviceTime`/`deviceSentAt` use the phone clock on purpose, which the server flags.
  - A year outside 2001-2099 switches both to the server estimate.
  - A phone clock that jumps backwards between capture and send gives `deviceSentAt < deviceTime`, which the server ignores (fallback rule). It is never rejected.
- **Device sleep.** The monotonic clock may pause, so the divergence check switches to the wall estimate and resyncs.
- **Opened offline after a reload.** The persisted offset is used, marked "(aprox.)". Without a snapshot and offline, the button is disabled with "Conéctese para cargar su estado".
- **`navigator.onLine` lies** (captive portal, Wi-Fi without internet). A network-level failure also sets the offline banner, and backoff retries.
- **Camera:**
  - in use by another app gives `NotReadableError`, which leads to the file fallback
  - no front camera: `facingMode: 'user'` is `ideal`, so any camera is used
  - iOS standalone PWA camera quirks are a manual check
  - permission permanently denied gives the instructions plus the file fallback
- **Huge photo from the file fallback** (12 MP). It is decoded through `createImageBitmap`, downscaled to 1024 px, and released. On out-of-memory or a decode failure, the user sees `photoUnsupported`.
- **EXIF orientation from the file fallback.** `createImageBitmap` / `<img>` apply it by default. The canvas output has no EXIF, so it can't be double-rotated server-side.
- **Progressive or non-JPEG canvas output** (an unknown engine, or `toBlob` falling back to PNG). Rejected client-side with a clear message instead of a server 400.
- **No pre-send geofence (OQ 2 = B).**
  - An employee standing outside the radius learns it only after the entry is recorded as PENDING_REVIEW. This is accepted. The chip still warns about denied, unavailable or low-accuracy GPS beforehand.
  - The client never computes a distance, so it can't disagree with the server.
  - An offline item shows "Pendiente de envío" until its response arrives, which could be days later. After the response, the result comes from `TimeEntryResponse`, and after a reload from `today`.
  - A 200 replay carries the original entry's distance and reasons (the first write wins), and that is what is shown.
  - The client's 100 m low-accuracy constant could drift from the server setting. That only affects the advisory warning, never the recorded status.
- **GPS:**
  - a cold fix can be slow, so the best fix is taken in parallel with the selfie
  - `accuracy` > 100 m warns
  - (0,0) "null island" is just far, which the server flags
  - mock-location apps can't be detected (Stage 2 note)
- **Storage evicted or quota full.** The enqueue fails with a clear message, and the user retries online. `persist()` reduces eviction. Items are never lost silently once stored. Eviction itself is not detectable after the fact (documented).
- **Shared device.** Logout bumps `token_version` and wipes data. Another employee can't flush or see the first one's items. The pending-items warning appears on logout or on a user switch.
- **Logout offline.** The local wipe only. The token stays valid until expiry (documented; the token is in `sessionStorage`, so it is gone anyway once the app closes).
- **Logout ends all sessions** of that employee on all devices (the `token_version` design).
- **Admin user in the employee app.** With a branch they can clock. Without one they see NO_BRANCH_ASSIGNED and a link to `/admin`.
- **Language switch mid-flow.** All texts come from keys at render time. Announcements already made stay in the old language (acceptable).
- **Service worker update during a capture or flush.** The banner is suppressed until both are idle. A reload loses nothing (IndexedDB).
- **Rate limit.** A serial flush and the backoff stay under 30 per 10 min. A 429 honors `Retry-After`.
- **503 `SERVER_BUSY`** at shift start (decode semaphore). Retry after 5 s with the same `clientUuid`.
- **Empty 401/403 bodies.** Mapped by status, never by parsing.
- **Microsecond timestamps** from Java. Trimmed before `Date` parsing.
- **jsdom limitations in tests:** no canvas, no `getUserMedia`, no geolocation, no `Blob` cloning into fake-indexeddb. All are injected or faked, and the photo is stored as an `ArrayBuffer`.
- **StrictMode double effects.** The providers' listeners and timers are idempotent. The flush is single-flight.

## Open Questions (as originally asked; superseded by the resolution below)

1. **Where should the PWA keep the token?** This revisits Stage 1 decision 2 in light of audit L-7, the shared devices and the 8 h token against a 9 h shift.
   - A) IndexedDB until expiry (the current Stage 1 decision). The flush resumes automatically after reopening the app, but anyone who opens the phone within 8 h is signed in.
   - **B) `sessionStorage` (recommended).** The token survives reloads and tab discards, which are frequent on low-end Android, and is gone when the app is closed. The non-secret identity and the outbox stay in IndexedDB, so offline capture and the PIN re-auth still work. XSS exposure during a session is the same as A; the CSP and Trusted Types are the real control. The cost: one PIN entry per app open. Because the token is shorter than a shift, that already happens at most clock-outs.
   - C) Memory only. Any reload or discard asks for the PIN again, which is too frequent on low-end phones.
2. **Show "inside the area · 35 m" before the employee clocks?**
   - **A) Recommended:** add `latitude`, `longitude` and `maxGpsAccuracyMeters` to `today.branch` (no new endpoint). The employee can step closer before clocking, which cuts `OUTSIDE_GEOFENCE` reviews and matches the mockup.
     - Privacy: a branch location is a business address, not personal data under Ley 8968. Only that branch's own authenticated employees receive it.
     - Spoofing: knowing the coordinates gives no real advantage, because anyone who works there already knows where the branch is, and the server stays authoritative.
     - The client distance is labelled approximate.
   - B) No backend change: before sending show only "location ready (±12 m)", and show the server's `distanceMeters`/`insideGeofence` after the POST. This is simpler, but the employee learns of an "outside" entry only after it is recorded.
3. **Allow clocking when the app is opened offline without a valid session?** This is the main offline case: no signal at the branch, app closed since the morning.
   - **Recommended: yes.** If a stored identity exists, the main screen works from the last snapshot. The entry is queued with a selfie and GPS and sent after the PIN once online.
     - The risk is someone else using the phone to queue an entry for its owner. Their face is in the selfie, and an entry queued for more than 2 min is flagged `SYNCED_LATE`, which sends it to review.
     - It can't be checked offline: the alternative of storing a PIN verifier would make the PIN brute-forceable offline.
   - Alternative: require an online login on every cold open. Offline clocking would then only work while the app stays open since the last login.
4. **Consent text: photo/GPS retention period and the contact for data rights** (Ley 8968). The project has no retention policy yet: it is planned for Stage 5, and audit I-6 asks to also cover coordinates.
   - **Recommendation:** ship a version `2026-10-v1` text that says:
     - selfies and exact coordinates are kept **12 months** and then deleted
     - time records are kept as labour records as the law requires
     - requests go to your company's administrator
   - Mark the text "pending legal review" in the README and revisit it in Stage 5. If you prefer, give the period and a contact (an email or phone per company, which needs a new `company` field in Stage 4).

## Open Questions: RESOLVED (2026-10-26, user)

These answers override any earlier text in this plan that conflicts with them. The backend logout endpoint (`POST /api/auth/logout`, bumping `token_version`) stays in scope.

1. **Token storage: B, `sessionStorage`** (as recommended).
   - The token and `expiresAt` go in `sessionStorage`. The non-secret identity, the outbox and the snapshot go in IndexedDB.
   - The PIN is never stored.
   - Logout revokes the token on the server and wipes everything locally.
2. **Pre-clock distance: B, no backend change** (the user chose this over the recommendation).
   - `today.branch` stays `{name, radiusMeters}`. No coordinates, no `geo.ts`, no client distance, and no changes to `TodayResponse`, `TodayService` or `TodayStateIntegrationTest`.
   - **Before clocking**, the chip shows readiness and accuracy only: "Ubicación lista · ±12 m" / "Location ready · ±12 m", or a warning when GPS is denied, unavailable or below the accuracy threshold.
   - **After the POST response**, the line shows the server's `distanceMeters`, `insideGeofence` and review reasons, for example "Dentro del área · a 35 m del local" or "Fuera del área · 1112 m · en revisión". After a reload the same result comes from `today`.
   - **Queued offline entries** show "Pendiente de envío" / "Pending send" until their response arrives.
3. **Offline clocking without a valid session: yes** (as recommended).
   - With a stored identity, a cold open (even offline) shows the main screen from the snapshot and queues entries.
   - Sending waits for the PIN once the phone is online.
4. **Consent text** (as recommended): version `2026-10-v1`.
   - Selfies and exact coordinates are kept 12 months and then deleted.
   - Time records are kept as labour law requires.
   - Requests go to the company administrator.
   - The README marks it "pending legal review", and it is revisited in Stage 5.
