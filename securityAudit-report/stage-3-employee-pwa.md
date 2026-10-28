# Security Audit Report — Control de Asistencia (Stage 3: Employee PWA)

- **Date:** 2026-10-27
- **Auditor:** security-auditor agent (read-only)
- **Branch:** `stage1-2` (Stage 3 is uncommitted working-tree changes on top of `1eb6669`)
- **Scope:**
  - `frontend/**` Stage 3 changes: `securityHeaders.ts`, `vite.config.ts`, `index.html`, `package.json` / `package-lock.json`, `.oxlintrc.json`, and `src/{api,auth,capture,components,errors,pages,pwa,security,storage,sync,time}/**`
  - the built output in `frontend/dist/` (built 2026-10-27 16:02: `index.html`, `sw.js`, `assets/*.js`)
  - backend: `auth/AuthController.java` (new `POST /api/auth/logout`), `employee/EmployeeRepository.java` (`incrementTokenVersion`), `LogoutIntegrationTest.java`
- **Inputs:**
  - `docs/plans/stage-3-employee-pwa.md`, read with "Open Questions: RESOLVED" taking precedence, and its "Hand-off to the security auditor" list
  - `docs/design/stage-3-employee-pwa.md`, `README.md` ("Testing on a phone"), `CLAUDE.md`
  - `securityAudit-report/stage-1-auth.md`, `securityAudit-report/stage-2-time-entry.md`
- **Method:**
  - I read the code and the configuration.
  - I inspected the generated service worker, the precache manifest and the bundles in `dist/` for sinks (`innerHTML`, `eval`, `new Function`, `document.write`), `console.*`, storage APIs and third-party origins.
  - I ran `npm audit`, `npm audit --omit=dev` and `npm ls`. I diffed `package-lock.json` against `HEAD`.
- **Not done, per the constraints:**
  - I did not read `backend/.env`.
  - I started no server and connected to no database.
  - I ran no build or tests, installed nothing and changed no git state.
  - Runtime browser behaviour (Trusted Types in the service worker, `sessionStorage` lifetime in an installed PWA) was **not executed**. Where a finding depends on it, the finding says so.

## Executive summary

Stage 3 is a careful piece of client security work.
- **Token handling.** The token lives only in memory and `sessionStorage`. It is bound to the stored identity, never written to `localStorage`, IndexedDB, URLs, logs or the service worker, and the PIN is never persisted.
- **CSP and Trusted Types.** The CSP is strict: no `unsafe-*`, no `data:`, no third-party origins, and the built HTML has no inline script or style. The Trusted Types `default` policy allows exactly one same-origin script URL (`/sw.js`, no query) and refuses all HTML and script text. `dangerouslySetInnerHTML`, `eval` and `new Function` are banned by lint, and none appear in the bundle.
- **Service worker.** It precaches only build assets. It has no runtime caching and no fetch route for `/api`, and navigations to `/api/` are excluded from the fallback.
- **Re-auth.** The re-auth dialog cannot attach a token for a different employee, and the flush filters items by employee.
- **Logout.** `POST /api/auth/logout` is authenticated, tenant-scoped, logs ids only and is covered by six integration tests.

I found **no Critical or High issues**.

The **Medium** finding concerns shared phones. On logout or an account switch, the local wipe covers IndexedDB and `sessionStorage` but not the **in-memory outbox results**. The next employee who signs in on the same tab (without a reload) sees and hears the previous employee's last clock-in/out: time, distance from the branch, status and review reasons. On the next refresh that data is also written into the new employee's IndexedDB snapshot.

The **Low** findings:
- **Discarding entries.** Anyone holding the phone can delete another employee's unsent entries without that employee's PIN.
- **Wipe and flush.** The local wipe is fire-and-forget, and the flush does not re-check, at send time, that the item belongs to the token's owner.
- **Dev exposure.** The `dev:phone` LAN setup exposes the dev stack and collapses every LAN client to `127.0.0.1` for the login rate limiter.
- **Device retention.** Unsent photos and exact coordinates have no maximum age on the device.

`npm audit` reports 3 advisories (1 high, 1 moderate, 1 low). All three are in dev or build-time packages that were already in the `HEAD` lockfile. The production tree has **0**.

---

## Findings

### [MEDIUM] M-1 The previous employee's server results survive logout and account switch in memory, are shown and announced to the next employee, and get persisted into the next employee's snapshot

- **File:**
  - `frontend/src/sync/OutboxProvider.tsx#L27-L29` (`lastResults`, `lastDelivered` state), `#L51-L55` (set in `onSent`), with no reset anywhere
  - `frontend/src/auth/session.ts#L204-L208` (`signOut` clears storage and session state only)
  - `frontend/src/sync/effectiveState.ts#L66-L71` (falls back to `lastResults` when `today` has no entry)
  - `frontend/src/pages/EmployeeHome.tsx#L90`, `#L140-L154` (renders and announces `lastDelivered` on mount)
  - `frontend/src/pages/useToday.ts#L35`, `#L108-L125`, `#L80-L91` (rebuilds `lastCompleted` from `lastResults` and persists it)
  - `frontend/src/pages/LoginPage.tsx#L130`, `#L252` (an account switch clears only the `outbox` store, not `snapshot`)
- **Category:** Privacy (Ley 8968) / OWASP (A01 Broken Access Control, client-side isolation)
- **Description:** `OutboxProvider` is mounted above `BrowserRouter` (`main.tsx#L22-L26`), so it survives the client-side navigations of logout → `/login` → `/`. It keeps the last `TimeEntryResponse` per type (`lastResults`) and the last delivered entry (`lastDelivered`) in React state, and nothing clears them when `identity` changes or `signOut()` runs. Three effects follow for employee B signing in on the same tab after employee A:
  1. **Rendered.** `effectiveState` uses `sameDay(lastResults.CLOCK_IN/OUT)` whenever B's `today.clockIn/clockOut` is null and the work date matches. The "Entrada / Salida" column on B's screen therefore shows A's server time, "Dentro del área · a 35 m del local" or "Fuera del área · 1112 m · en revisión", A's status and A's review reasons.
  2. **Announced.** `HomeContent` is remounted for B, so `lastAnnounced` restarts at 0 while `outbox.lastDelivered` still holds A's entry. The polite live region immediately reads A's "Entrada registrada a las 07:58 · Fuera del área · …".
  3. **Persisted.** `useToday`'s `handled` ref also restarts at 0. If A's last result was a CLOCK_OUT, B's `lastCompleted` becomes A's shift. The next `reload()` then calls `persist(fresh, lastCompletedRef.current)`, which writes A's shift times into the IndexedDB snapshot keyed with **B's** `employeeId`. It then survives reloads and shows as B's "Último turno" for up to 12 h.

  A related race: a POST that is in flight when the user signs out still completes, and `onSent` stores its result *after* the wipe. Separately, the account-switch path (`LoginPage`) clears the `outbox` store but leaves A's `snapshot` (A's `today`, including distance, status and review reasons) at rest until B's first successful `today` overwrites it. The `employeeId` check in `useToday` keeps that snapshot off B's screen, but it is still the previous person's data.

  The plan's requirement is "Another employee can't flush or see the first one's items" (Edge Cases, *Shared device*). No test covers a logout followed by a sign-in as another employee in the same tab.
- **Evidence:**
  ```tsx
  // OutboxProvider.tsx — never reset on identity change or sign-out
  const [lastResults, setLastResults] = useState<Partial<Record<TimeEntryType, TimeEntryResponse>>>({})
  const [lastDelivered, setLastDelivered] = useState<DeliveredEntry | null>(null)
  ```
  ```ts
  // effectiveState.ts
  clockIn: toView(pendingIn, today?.clockIn ?? null, sameDay(lastResults.CLOCK_IN)),
  ```
- **Exploit scenario:** A shared phone at a branch is used as a kiosk. Ana clocks in at 07:58 from 1112 m away (outside the area, under review), then signs out. Without the app being reloaded, Bruno signs in.
  - Bruno's main screen shows "Entrada 07:58 · Fuera del área · 1112 m · en revisión", and his screen reader announces it.
  - Bruno learns where Ana was and that she is under review.
  - If Ana had clocked out instead, Bruno's snapshot now permanently claims a "last shift" that was Ana's.
- **Recommendation:**
  1. In `OutboxProvider`, reset `items`, `lastResults`, `lastDelivered`, `networkProblem` and `deliveredCount`, and `clearTimeout(timer.current)`, whenever `employeeId` changes. Either use a `useEffect` keyed on `employeeId`, or render the provider with `key={employeeId ?? 'none'}` so React discards the whole state.
  2. In `onSent`, drop the result if `getSession().identity?.employeeId !== item.employeeId` (this closes the in-flight race).
  3. In `useToday`, ignore `lastResults` entries whose `employeeId` differs from the current one. `TimeEntryResponse` has no `employeeId`, so tag the stored result with `item.employeeId` in `onSent`.
  4. On an account switch, call the full local wipe (`clearSnapshot()` as well as `clearAll()`), or reuse `signOut()` before `signIn()`.
  5. Simplest robust option: after `performLogout()`, navigate with `window.location.replace('/login')` instead of `navigate()`, so all in-memory state is discarded. Keep fixes 1-4 anyway, because the account-switch path does not go through logout.
  6. Test: sign in as A, deliver a CLOCK_IN and a CLOCK_OUT, log out, sign in as B in the same render tree. Assert that B's screen and the live region contain none of A's times, distances or reasons, and that B's snapshot has no `lastCompleted`.

### [LOW] L-1 Anyone holding the phone can permanently delete another employee's unsent entries without that employee's PIN

- **File:**
  - `frontend/src/components/AppHeader.tsx#L16` (the sign-out button whenever an identity exists)
  - `frontend/src/components/LogoutConfirm.tsx#L17-L23`
  - `frontend/src/components/ReauthController.tsx#L43`, `frontend/src/components/ReauthDialog.tsx#L76-L78` ("¿No es usted?")
  - `frontend/src/pages/LoginPage.tsx#L123-L131`, `#L248-L253` (an account switch discards the other person's items)
- **Category:** OWASP (A04 Insecure Design / integrity of attendance evidence)
- **Description:** After a cold open, or a 401, the device holds A's identity and outbox but no token (OQ 3 = yes). In that state any person holding the phone can:
  - tap "Cerrar sesión", or "¿No es usted?" in the re-auth dialog, and confirm "signing out deletes N entries", or
  - sign in with their own credentials and confirm the switch dialog.

  Either way A's queued CLOCK_IN/CLOCK_OUT, selfie and GPS are deleted with no PIN of A's, no server trace and nothing that tells A afterwards. The deletion is also unnecessary for isolation: `listItems`/`oldestPending` already filter by `employeeId` (`storage/outbox.ts#L51-L72`), so B's session could never send or display A's items.
- **Evidence:**
  ```tsx
  // LoginPage.tsx — a valid login for B wipes A's queue after one confirm
  void clearAll().then(() => finish(pendingLogin.response, pendingLogin.codes))
  ```
- **Exploit scenario:** Two employees share a phone. Ana clocks out offline at 17:00 ("Salida guardada · se enviará al reconectar") and leaves. A colleague who wants Ana to appear as having skipped her clock-out opens the app, taps "¿No es usted?" and then "Cerrar sesión y borrar". The server never receives the clock-out: Ana gets `missingClockOut` and the admin sees a forgotten clock-out. No log shows that the entry ever existed.
- **Recommendation:**
  1. While items of another employee are pending, do not delete them on an account switch. Keep them tagged with A's `employeeId` (the filter already isolates them) and send them the next time A signs in on this device. Show B a neutral notice, "Hay marcas de otra persona pendientes en este teléfono", with no details.
  2. If deletion is kept, require A's PIN (an online login with A's codes) to discard A's pending items. Logging out without the PIN would then keep the items, subject to the age cap in L-4.
  3. Optionally record a discard on the server the next time anyone with a token is online (count, type and `deviceTime` only), so admins can see that evidence was discarded.

### [LOW] L-2 The local wipe is fire-and-forget, and the flush does not check at send time that the item belongs to the token's owner

- **File:**
  - `frontend/src/auth/session.ts#L204-L208` (`Promise.allSettled`, results ignored)
  - `frontend/src/sync/OutboxProvider.tsx#L39-L40` (with no identity, `runFlush` returns before clearing the timer), `#L63-L66` (the backoff timer calls the captured `runFlush`)
  - `frontend/src/sync/flush.ts#L73-L81` (the item is chosen by the captured `employeeId`)
  - `frontend/src/api/client.ts#L44-L46` (the token is read from the global session at send time)
- **Category:** OWASP (A01 / A04, defence in depth)
- **Description:**
  - **The wipe can fail silently.** `signOut()` clears memory first and then runs `clearIdentity`, `clearOutbox` and `clearSnapshot` under `allSettled`. If IndexedDB fails (blocked by another tab's upgrade, quota, a browser bug), the user is told they are signed out while photos, coordinates and identity stay on disk. If `clearIdentity` failed, the next cold open restores A's main screen.
  - **The flush is not bound to the token's owner.** The item to send is selected with the `employeeId` captured in the `runFlush` closure, but `postTimeEntry` attaches whatever token is current. A backoff timer (up to 300 s) armed during A's session is not cancelled when the identity becomes null, because the new `runFlush` returns at line 40 before `clearTimeout`. When it fires it runs A's closure. Normally A's items are already gone, so nothing happens. If the wipe had failed and B had signed in meanwhile, A's item (A's selfie and GPS) would be POSTed with **B's** token, and the server would record it as B's entry.

  This needs two unlikely conditions together, hence Low. But it is the only path I found by which an entry could be submitted under a different employee.
- **Evidence:**
  ```ts
  await Promise.allSettled([clearIdentity(), clearOutbox(), clearSnapshot()])   // failures ignored
  ```
  ```ts
  if (employeeId === null) return   // the old timer stays armed
  ```
- **Recommendation:**
  1. In `flush.ts`, before `deps.post`, require `item.employeeId === deps.employeeId === getSession().identity?.employeeId`, and that the stored session's `employeeId` matches. Otherwise stop with `'idle'`. Pass a `currentEmployeeId()` dependency so it can be tested.
  2. Clear the backoff timer on every `employeeId` change (see M-1 fix 1).
  3. Make the wipe verifiable. If any clear rejects, fall back to `indexedDB.deleteDatabase('attendance')` after closing the connection. Set a `localStorage` flag `attendance.wipePending` before the wipe and remove it after success. `initSession` repeats the wipe while the flag is present and never restores an identity in that state.
  4. Test: make `clearOutbox` reject, sign in as B, advance the fake timers, and assert that no POST happens with B's token for A's item.

### [LOW] L-3 `dev:phone` / `preview:phone` expose the dev stack on the LAN; the proxy makes every LAN client `127.0.0.1` to the login rate limiter

- **File:**
  - `frontend/package.json#L7`, `#L11` (`--host`)
  - `frontend/vite.config.ts#L8-L10` (`/api` proxy to `http://localhost:8080`), `#L52-L53`
  - `backend/src/main/resources/application.yml#L72` (`server.port: 8080`, no `server.address`, so all interfaces)
  - `README.md#L174-L175` (firewall rule and certificate click-through)
- **Category:** Config
- **Description:**
  - **LAN reach.** `vite --host` binds `0.0.0.0:5173`, and the README's firewall rule allows the whole Private-profile network. Every device on that Wi-Fi gets the dev server: it serves the app source, and its built-in `server.fs` protections apply because the project root is `frontend/` (no root `package.json`, so `backend/.env` is outside the allow list, and `.env*` / `*.pem` are denied by default). It also gets the `/api` proxy to the dev backend with its seed data.
  - **Rate-limiter collapse.** The http-proxy does not add `X-Forwarded-For` (`xfwd` is off), and the login limiter keys on `getRemoteAddr()` (Stage 1 M-2). Every phone and every LAN attacker therefore shares the single `127.0.0.1` bucket of 20 attempts. A LAN peer can lock the developer out of testing, and per-IP accounting is meaningless.
  - **Direct HTTP.** The backend itself listens on all interfaces over plain HTTP, so a peer can bypass the proxy entirely, and a phone pointed at `:8080` by mistake would send the PIN in clear.
  - **Certificate habits.** The self-signed certificate trains testers to click "Proceed" on certificate warnings. On an untrusted Wi-Fi an attacker's own certificate looks identical, which enables a MITM of the dev PIN and token.
  - **No CSP in dev.** The dev server sends no CSP (by design). The LAN-exposed dev surface is therefore the unprotected one.

  Impact is limited to development data, hence Low.
- **Recommendation:**
  1. Bind the backend to loopback in the dev profile: `server.address: 127.0.0.1` in `application-dev.yml`.
  2. Prefer no LAN at all. With USB debugging, `adb reverse tcp:5173 tcp:5173` lets the phone open `http://localhost:5173`, which is a secure context (camera, GPS and the service worker all work) with no certificate and no firewall rule. The README already uses Chrome port forwarding for 4173. Make that the default for 5173 too, and keep `dev:phone` as a fallback.
  3. If LAN mode stays, narrow the firewall rule with `-RemoteAddress <phone-IP>`, document "stop `dev:phone` when done; never on public or guest Wi-Fi", and consider `mkcert` (a locally trusted CA installed on the test phone) instead of an untrusted certificate.
  4. Stage 5: fix Stage 1 M-2 (trusted-proxy `X-Forwarded-For` handling) before Caddy is in front, because the same collapse happens there.

### [LOW] L-4 Unsent selfies and exact coordinates have no maximum age on the device; failed items keep coordinates

- **File:**
  - `frontend/src/storage/outbox.ts#L38-L42` (`navigator.storage.persist()`), `#L83-L89` (`markFailed` nulls the photo but keeps `lat`/`lng`/`accuracy`)
  - `frontend/src/storage/db.ts#L15-L31`
  - plan: "Items are never dropped silently, whatever their age"
- **Category:** Privacy (Ley 8968, data minimisation)
- **Description:** A pending item holds a selfie plus exact latitude, longitude and accuracy in unencrypted IndexedDB, and `persist()` protects it from eviction. If the employee never signs in again (left the company, lost the PIN, lockout), the item stays on the phone indefinitely. That is longer than the 12-month retention the consent text promises for the server copy, and on a shared or personal phone it is readable by anyone with device or profile access (DevTools, a backup, forensics). Final-failure items drop the photo at once but keep the coordinates until someone taps "Entendido".
- **Recommendation:**
  1. Add a maximum age, for example 7 days after `createdAt`, which is well beyond the 4-day offline budget. Past it, show a notice, convert the item to `failed` with photo **and** coordinates nulled, and let the user acknowledge it.
  2. In `markFailed`, also drop `lat`, `lng` and `accuracy`. The failure card shows only type and time.
  3. Mention device-side storage in the consent text (section 5): "until it is sent, at most N days".
  4. Run the age sweep on app open, before `initSession` resolves.

### [INFO] I-1 Trusted Types in the service worker may block `importScripts` (not verified at runtime)

- **File:** `frontend/dist/sw.js` (the generated AMD loader calls `importScripts(n)` with a string to load `workbox-2fbc6a65.js`); `frontend/vite.config.ts#L53` (`preview.headers` applies `SECURITY_HEADERS` to every response, `sw.js` included); `frontend/securityHeaders.ts#L25-L26`
- **Category:** Config
- **Description:** A service worker's CSP comes from its own script response. `sw.js` is served with `require-trusted-types-for 'script'`, and in a worker `importScripts` is a `TrustedScriptURL` sink. The page's default policy (`src/security/trustedTypes.ts`) does not exist inside the worker global. Where the browser enforces Trusted Types in workers, `importScripts('…/workbox-2fbc6a65.js')` would throw and the worker would fail to install, so there would be no offline shell. This is not a vulnerability. The risk is that it gets "fixed" by loosening the CSP. README step 6 already asks for a console check for TT violations.
- **Recommendation:** Set `workbox: { inlineWorkboxRuntime: true }`, so `sw.js` has no `importScripts`, and keep the strict header on `sw.js`. Confirm in the manual step 6 that `navigator.serviceWorker.controller` is set after install with no console violations. Do not remove `require-trusted-types-for` to make it work.

### [INFO] I-2 The CSP and headers are enforced only by `vite preview` today; production depends on Stage 5 copying them

- **File:** `frontend/securityHeaders.ts`; `frontend/vite.config.ts#L52-L53`
- **Category:** Config
- **Description:** The single-source design is good. However:
  - nothing is enforced in `dev` / `dev:phone` (accepted)
  - production has no headers until Caddy exists
  - HSTS is deferred
  - `Cross-Origin-Resource-Policy` is not set
  - `no-implied-eval` is not in `.oxlintrc.json`. The CSP already blocks string `setTimeout`, so this is cosmetic.
- **Recommendation:**
  1. In Stage 5, add a test or CI check that the Caddyfile's header block equals `SECURITY_HEADERS`, add HSTS, and consider `Cross-Origin-Resource-Policy: same-origin`.
  2. Make the CSP and Trusted Types a go-live blocker in the Stage 5 plan (Stage 1 L-7).

### [INFO] I-3 Freshly issued tokens are discarded without revocation (re-auth mismatch, cancelled account switch)

- **File:** `frontend/src/components/ReauthController.tsx#L30-L31`; `frontend/src/pages/LoginPage.tsx#L247`
- **Category:** OWASP (A07)
- **Description:** When `reauthenticated()` refuses a login for another employee, or the user cancels the switch dialog, the `LoginResponse` token (valid for 8 h) is dropped from memory but not revoked. It never reaches storage, so practical exposure is limited to the page's memory until garbage collection.
- **Recommendation:** Before discarding it, send a best-effort `POST /api/auth/logout` with that explicit token. That needs an `apiRequest` option to pass a token instead of `getToken()`.

### [INFO] I-4 Entries queued by someone else as the device owner are sent automatically after the owner's PIN; the file fallback allows gallery photos

- **File:** `frontend/src/auth/RequireSession.tsx#L9-L15` (OQ 3); `frontend/src/components/ReauthDialog.tsx`; `frontend/src/components/SelfieCapture.tsx#L245-L251` (`capture="user"` is only a hint)
- **Category:** OWASP (A04, accepted risk)
- **Description:**
  - **Queued as the owner.** With OQ 3 accepted, a person holding A's phone (no token) can queue entries as A. They are sent FIFO, ahead of A's own, the moment A enters the PIN. The re-auth dialog does not show what will be sent. `SYNCED_LATE` flags only entries sent more than 2 minutes after capture, and the photo is only checked if someone reviews it.
  - **Gallery photos.** On some browsers the file-input fallback opens the gallery, so a stored photo of the employee can be reused.
- **Recommendation:**
  1. In the re-auth dialog, list the pending items (type and `deviceTime`) with a "this is not mine: discard" action. Once A has authenticated, this is a legitimate deletion, unlike L-1.
  2. Stage 4: consider sending a `photoSource=camera|file` field and adding a review reason for `file`.

### [INFO] I-5 `sessionStorage` lifetime and XSS exposure

- **File:** `frontend/src/auth/session.ts#L44-L65`
- **Category:** Config
- **Description:** I verified that the token is written only to `sessionStorage` and module memory, and that `initSession` drops a token whose `employeeId` does not match the stored identity. `sessionStorage` in an installed PWA lives as long as the app's task does: on Android until the task is swiped away, which can be days. On iOS a standalone app may be restored from memory. Any same-origin script can read it. The CSP and Trusted Types are the control, and they are absent where the browser lacks Trusted Types and in dev.
- **Recommendation:** Accept and document this (the user's decision). In the manual phone test, record how long the token survives "close app" on Android and iOS. Optionally add an idle timeout, for example 30 minutes without interaction, that calls `markNeedsReauth()`.

### [INFO] I-6 Dependencies: `npm audit` has 3 dev-only advisories, none introduced by Stage 3; `workbox-window` ships but is a devDependency

- **File:** `frontend/package.json`, `frontend/package-lock.json`
- **Category:** Dependencies
- **Description:**
  - **`npm audit`:** 3 vulnerabilities (1 high, 1 moderate, 1 low). **`npm audit --omit=dev`: 0.**

    | Package | Version | Pulled in by | Advisories | Runtime reach |
    |---|---|---|---|---|
    | `undici` (high) | 8.11.2 | `jsdom` 30.1.2 (tests) | GHSA-4qv8-2p9j-2685, GHSA-f72v-6w45-q472, GHSA-xjj6-f33q-929c, GHSA-4w7w-5xrc-9h26, GHSA-2m5v-pch6-74hx, GHSA-qrxm-fqhp-jwvm, GHSA-9fm3-97px-rjf6, GHSA-55wc-3pg8-jfw6 | none: test environment only |
    | `fast-uri` (moderate) | 3.1.8 | `workbox-build` → `ajv` (build) | GHSA-w74x-v6f9-j3xg, GHSA-xhq7-xmqj-769h, GHSA-wjr9-j795-jxcr, GHSA-333x-w4r6-6crp | none: validates the build's own config |
    | `serialize-javascript` (low) | 7.1.2 | `workbox-build` → `@rollup/plugin-terser` (build) | GHSA-m5mm-p8h7-m29v | none: serializes the project's own config |

    All three versions are already in the `HEAD` lockfile, so Stage 3 did not introduce them.
  - **New Stage 3 packages:** `idb` 8.0.4 (runtime, no dependencies), `@vitejs/plugin-basic-ssl` 2.3.0, `fake-indexeddb` 6.2.6, and `idb` 7.1.1 nested under workbox. All resolve from `registry.npmjs.org` with `sha512` integrity, and none has an install script. The only install script in the tree is the optional `fsevents`, which is macOS-only. `.npmrc` contains only `engine-strict=true`.
  - **Audit gap.** `workbox-window` 7.4.1 is declared as a **devDependency** but is bundled into the shipped app (`dist/assets/workbox-window.prod.es5-*.js`), and so is the `virtual:pwa-register` code from `vite-plugin-pwa`. A production-only audit (`--omit=dev`) therefore skips code that runs in users' browsers.
- **Recommendation:**
  1. Run `npm audit fix` (it changes the lockfile, so the user decides). All three have fixes available.
  2. Move `workbox-window` to `dependencies` so production audits cover it.
  3. Stage 5: add `osv-scanner` on `package-lock.json` (as for Maven, Stage 2 I-7).

---

## Checked and found OK

**Token and PIN**
- **No leakage paths.** The token is written only by `writeStoredSession` (`sessionStorage` key `attendance.session`) and held in module memory. A grep of `src/` and of the built bundles shows no `localStorage`, IndexedDB, URL or `console` use of the token. `localStorage` holds only the language, `lastCompanyCode` and the camera explainer flag.
- **Header only.** `Authorization: Bearer` is set in one place (`api/client.ts#L44-L46`) and only for `auth: true` requests.
- **Service worker.** The service worker has no fetch handler for API requests, so the token never passes through it. Background Sync is not used.
- **PIN.** The PIN lives only in component state. It is cleared on failure and on a switch, and the component unmounts on success. The keypad accepts only `[0-9]`, up to 8 digits. The PIN is never logged or stored, and the identity type has no PIN field.
- **Errors.** Error text comes from `messageKeyFor(code)`. The server's `detail` is never read (`toProblem` extracts `code` only) and never rendered. All interpolation (`i18n` `format`) produces strings rendered as React text, so React escaping applies.

**Logout**
- **Server.** `POST /api/auth/logout` falls under `anyRequest().authenticated()`. Spring's own `/logout` filter is disabled, so it cannot shadow the controller. It runs `update … where e.id = :id and e.companyId = :companyId`, with both values taken from the principal, and logs ids only. Every request re-checks `token_version` (`EmployeeJwtAuthenticationConverter#L44`). `LogoutIntegrationTest` covers 204 with the old token rejected afterwards, re-login, no token, a second logout, another employee and another tenant. The token is a bearer header, so there is no CSRF exposure.
- **Client.** The client logout is best effort with a 3 s timeout, followed by clearing `sessionStorage`, `identity`, `outbox` and `snapshot` (but see M-1 for memory and L-2 for failure handling). With no token (needsReauth), no server call is made, so a person without the token cannot end the owner's sessions on other devices.

**Re-auth and per-employee isolation**
- **Re-auth.** `ReauthController` logs in with the stored codes only. `reauthenticated()` refuses, and stores nothing, when `login.employee.id` differs from the identity.
- **Sign-in and init.** `signIn` writes the token and identity together. `initSession` drops a token tagged with another `employeeId`.
- **Isolation.** The flush and the UI read items through the `byEmployee` index. `flush.test.ts` and `outbox.test.ts` cover "another employee is never sent / returned". Cross-tab consistency holds because each tab's token and identity live together in that tab's memory.

**Service worker and caching**
- **Precache.** The precache manifest in `dist/sw.js` lists only icons, `manifest.webmanifest`, `index.html` and hashed JS/CSS.
- **No runtime caching.** There is no `runtimeCaching` and no `registerRoute` other than the `NavigationRoute` with `denylist: [/^\/api\//]`. API and photo requests are never intercepted. The PWA never fetches photos.
- **Updates.** `registerType: 'prompt'`, `skipWaiting` only on the user's "Actualizar", and `cleanupOutdatedCaches`. A waiting worker still activates when all clients close, so a user who dismisses the prompt cannot keep a stale shell forever.

**CSP and Trusted Types**
- **The CSP itself.** No `unsafe-inline`, `unsafe-eval` or `data:`. `object-src 'none'`, `base-uri 'none'`, `frame-ancestors 'none'`, `connect-src 'self'`. There are no third-party origins in the bundle (only the XML namespace URIs and React and router doc links).
- **Built HTML.** `dist/index.html` has one external module script and one stylesheet, with no inline script or style. `assetsInlineLimit: 0` is set.
- **The policy.** `scriptUrlAllowed` requires the same origin, the path `/sw.js`, no query and no hash. `createHTML` and `createScript` throw. The policy is installed by the first import in `main.tsx`. `trusted-types default` without `'allow-duplicates'` stops injected code from creating a second permissive policy.
- **Sinks.** There is no `dangerouslySetInnerHTML`, `eval`, `new Function`, `document.write` or inline handler string in `src/`. The bundle contains only React DOM's internal `innerHTML` paths, which go through the throwing `createHTML`. `react/no-danger`, `no-eval` and `no-new-func` are set to `error`.
- **Permissions-Policy.** `camera=(self), geolocation=(self)`, with microphone, payment and USB denied. `Referrer-Policy: no-referrer`, `nosniff` and COOP `same-origin` are set.

**Capture and photo lifetime**
- **Camera.** Tracks are stopped on every exit (cancel, unmount, accept, retake, file path). See `SelfieCapture.tsx#L64-L67`, `#L129-L134`, `#L159-L167`, `#L207-L212`.
- **Object URLs.** The review object URL is revoked on retake, accept, cancel and unmount. `decodeBlob` revokes its own URL (`compressImage.ts#L59`).
- **Canvas.** The canvas is zeroed and the `ImageBitmap` closed in `finally` (`compressImage.ts#L119-L123`).
- **Hand-off and failures.** The photo is passed on as an `ArrayBuffer` and the dialog unmounts. No photo or coordinates are logged. A final failure nulls the photo.
- **Format check.** The output is checked for a JPEG signature and baseline encoding before it is enqueued.

**Input and offline replay trust**
- **`clientUuid`.** It comes from `crypto.randomUUID()`, with a `getRandomValues` v4 fallback and never `Math.random`, and is created once per capture (closes Stage 2 I-3).
- **Form values.** Coordinates use `String(number)` (dot decimals). `deviceSentAt` is set at send time. An implausible phone clock year switches both times to the server estimate.
- **Server trust.** No backend `timeentry/**` file changed in Stage 3. The Stage 2 verification still holds: `server_time` is authoritative, and `deviceTime`/`deviceSentAt` only add review reasons (`SYNCED_LATE`, `DEVICE_CLOCK_AHEAD`). Branch coordinates are still not sent, and the bundle has no distance or geofence code.
- **Login fields.** Codes are trimmed, uppercased and checked against `^[A-Z0-9-]{1,20}$` client-side. The server validates again. Errors never say which field was wrong.

**Secrets and repository**
- **No secrets.** There are no secrets in the changed or untracked files. The only `password` strings are README placeholders.
- **Ignored files.** `backend/.env` is not tracked, and `git log --all` shows no committed `.env`. `.gitignore` covers `.env*`, `dist/`, `dev-dist/` and `node_modules/`, which includes the basic-ssl certificate cache under `node_modules/.vite/basic-ssl`.

---

## Earlier-stage follow-ups touched by this stage

| Item | Status after Stage 3 |
|---|---|
| Stage 1 L-6 No logout on shared devices | **Closed.** Server revocation plus local wipe. Residual issues: M-1, L-1, L-2. |
| Stage 1 L-7 Token in IndexedDB readable by XSS | **Closed for the build served by `vite preview`.** The token is in `sessionStorage`, with a strict CSP, Trusted Types and lint bans. Production enforcement is still a Stage 5 item (I-2). |
| Stage 1 I-3 Consent versioning | **Partly.** `CONSENT_TEXT_VERSION = '2026-10-v1'` exists client-side only, and the API does not record it yet (Stage 5). |
| Stage 1 I-4 Security headers / CSP / HSTS | **Partly.** The headers are defined and enforced in preview. HSTS and production are Stage 5. |
| Stage 1 M-2 Per-IP limiter keyed on `getRemoteAddr()` | **Unchanged.** Now visible in dev through the Vite proxy (L-3). |
| Stage 2 I-3 `clientUuid` from a CSPRNG | **Closed.** |
| Stage 2 I-6 Coordinates retention | **Server side unchanged.** The consent text promises 12 months. The device side is in L-4. |
| Stage 2 L-4 Inflated hours as `VALID` | **Unchanged (Stage 4).** The PWA always sends `accuracy` and `deviceSentAt`, but a scripted client can still omit them. |

---

## Summary by severity

| Severity | Count | Findings |
|----------|-------|----------|
| Critical | 0 | none |
| High | 0 | none |
| Medium | 1 | M-1 Previous employee's results stay in memory after logout or switch: shown, announced and persisted into the next employee's snapshot |
| Low | 4 | L-1 Unsent entries of another employee can be deleted without their PIN; L-2 Fire-and-forget wipe, and the flush is not bound to the token owner at send time; L-3 `dev:phone` LAN exposure and limiter collapse to `127.0.0.1`; L-4 No maximum age for unsent selfies and coordinates on the device |
| Info | 6 | I-1 Trusted Types may block `importScripts` in `sw.js` (unverified); I-2 Headers enforced only by preview until Stage 5; I-3 Discarded tokens not revoked; I-4 Entries queued by another person sent after the owner's PIN, gallery photos; I-5 `sessionStorage` lifetime in installed PWAs; I-6 `npm audit`: 3 dev-only advisories, `workbox-window` ships as a devDependency |

## Recommended remediation

**Before release:**
1. **M-1.** Reset `OutboxProvider` state (and its timer) on every `employeeId` change, or key the provider by `employeeId`. Tag and filter `lastResults` by employee. Clear the snapshot on an account switch. Add the logout-then-other-login test.
2. **L-2.** Add the send-time ownership check in `flush.ts`, clear the backoff timer on identity change, and make the wipe verifiable (fall back to `deleteDatabase`, plus a `wipePending` flag).
3. **L-1.** Stop deleting another employee's items on a switch or logout without their PIN, and keep them isolated by `employeeId` instead (combine with the L-4 age cap).
4. **I-1.** Set `inlineWorkboxRuntime: true` and confirm in manual step 6 that the worker installs under the strict CSP.

**Before the next phone test session:**
5. **L-3.** Set `server.address: 127.0.0.1` in `application-dev.yml`, and prefer `adb reverse` / Chrome port forwarding to `localhost:5173` over `--host` (or restrict the firewall rule to the phone's IP).

**Soon / Stage 5:**
6. **L-4.** Add a device-side maximum age (for example 7 days), null the coordinates on `markFailed`, and mention device storage in the consent text.
7. **I-6.** Run `npm audit fix`, move `workbox-window` to `dependencies`, and add `osv-scanner`.
8. **I-2.** Check that the Caddy headers equal `SECURITY_HEADERS`, and add HSTS. Fix Stage 1 M-2 before Caddy goes in front.
9. **I-3, I-4.** Revoke discarded tokens, and show pending items in the re-auth dialog.

---

## Remediation status (2026-10-28)

| Finding | Status | Where |
|---|---|---|
| M-1 Previous employee's results survive logout and account switch | **Fixed** | `sync/OutboxProvider.tsx` resets `items`, `lastResults`, `lastDelivered`, the network flag and `flushing` as soon as the signed-in `employeeId` changes (state adjusted during render) and clears the backoff timer in an effect keyed on `employeeId`. `refresh` and `onSent` compare against `getSession().identity`, so an answer or a POST that completes after a sign-out is dropped. `pages/useToday.ts` ignores a `today` answer for a different employee and `persist` refuses to write a snapshot unless the signed-in employee is the one it was built for. `auth/session.ts` `signIn` clears a snapshot that belongs to another employee (account switch without logout). The announcer is per page and is disposed with it. Tests: `sync/sessionIsolation.test.tsx` (logout then sign-in as B, direct switch A to B, in-flight POST, snapshot and `lastCompleted`), `auth/session.test.ts` (account switch). I did not use `window.location.replace` after logout: the in-memory reset makes it unnecessary. |
| L-1 Unsent entries of another employee could be deleted without their PIN | **Fixed** | `signOut()` no longer clears the outbox, and the account-switch path in `pages/LoginPage.tsx` no longer calls `clearAll()`. Items stay tagged with their `employeeId` (the existing filters isolate them) and are sent when that employee signs in again, or expire after 7 days (L-4). `components/LogoutConfirm.tsx` and the switch dialog are non-destructive and say so in EN and ES (`logout.pendingKept.*`, `login.switchBody`, `login.switchConfirm`); the "delete" labels were removed. Tests: `components/LogoutConfirm.test.tsx`, `pages/LoginPage.test.tsx`, `sync/reauth.test.tsx`, `sync/sessionIsolation.test.tsx` (items kept, then sent when A signs in again). Not done: the optional server-side record of discarded evidence, since nothing is discarded by a person any more. |
| L-2 Fire-and-forget wipe; flush not bound to the token owner | **Fixed** | `auth/session.ts`: each wipe step (identity, snapshot) is retried 3 times and failures are logged with the step name and error class only. `signOut()` returns whether it completed. While incomplete, `localStorage` `attendance.wipePending` is set; `initSession` repeats the wipe first and restores no identity until it succeeds; a new `signIn` clears the flag. I did not add the `deleteDatabase` fallback. `sync/flush.ts`: new `currentIdentity()` dependency; before every POST the item's `employeeId` (and `companyCode`, now stored on new items) must equal the identity of the current token, otherwise nothing is sent. `OutboxProvider` clears the previous session's backoff timer on identity change. Tests: `auth/session.test.ts` (retry, log content, flag, restart), `sync/flush.test.ts` (mismatch, no identity, other company, sign-out mid-run), `sync/sessionIsolation.test.tsx` (A's backoff timer never posts with B's token). |
| L-3 `dev:phone` LAN exposure | **Documented** | `README.md`, "Testing on a phone": security note that `dev:phone` exposes the dev stack and proxy to the LAN, to be used only on a trusted private Wi-Fi and stopped after testing, and that `adb reverse tcp:5173 tcp:5173` / Chrome port forwarding to `localhost` is the safer option. Per the user's decision, the rate limiter is unchanged (Stage 1 M-2 stays open for Stage 5), and `server.address: 127.0.0.1` for the dev profile and the firewall `-RemoteAddress` narrowing were not applied. |
| L-4 No maximum age for unsent selfies and coordinates | **Fixed** (consent text: pending) | `storage/outbox.ts`: `OUTBOX_MAX_AGE_MS` (7 days) and `expireOld()`, which turns older pending items into `failed` with code `EXPIRED`, photo and coordinates dropped. It runs in `initSession` before the session is ready and at the start of every flush. `markFailed` now also drops `lat`, `lng` and `accuracy`. The failure card shows the new `failure.expired` message (EN/ES). Tests: `storage/outbox.test.ts`, `sync/flush.test.ts`, `auth/session.test.ts`. Still to do: mention the device-side retention ("at most 7 days") in consent text section 5, which needs the legal review of the text. |
| I-1 Trusted Types may block `importScripts` in `sw.js` | **Fixed** (browser check pending) | `vite.config.ts`: `workbox.inlineWorkboxRuntime: true`. The built `dist/sw.js` has no `importScripts`; the CSP and `require-trusted-types-for` are unchanged. Tests: `pwaConfig.test.ts` (option, and the built worker). The runtime check in manual step 6 on a phone is still needed. |
| I-2 Headers enforced only by preview | **Open (Stage 5)** | Unchanged. |
| I-3 Discarded tokens not revoked | **Open** | Unchanged. |
| I-4 Entries queued by someone else as the device owner | **Open (accepted risk)** | Unchanged. With L-1 fixed, such entries now also stay on the phone until the owner signs in or they expire after 7 days. |
| I-5 `sessionStorage` lifetime | **Accepted** | Unchanged. |
| I-6 `workbox-window` is a devDependency | **Fixed** (`npm audit fix` not applied) | `package.json` and `package-lock.json` (`npm install --package-lock-only`): `workbox-window` is now a dependency. Test: `pwaConfig.test.ts`. The three dev-only advisories (`undici`, `fast-uri`, `serialize-javascript`) remain, as `npm audit fix` changes the lockfile and is the user's decision. |

Verification: `npm run lint` clean, `npm run build` passes, `npx vitest run` 595 of 595 passing (566 before, including the bundle budget test). No backend code was touched in this round.
