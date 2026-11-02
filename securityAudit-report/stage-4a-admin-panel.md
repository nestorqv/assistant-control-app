# Security Audit Report — Control de Asistencia (Stage 4a: Admin dashboard, evidence viewer, review queue)

- **Date:** 2026-11-02
- **Auditor:** security-auditor agent (read-only)
- **Branch:** `stage1-2` (Stage 4a is uncommitted working-tree changes on top of `0b014ae`)
- **Scope:** the 168 files in the 4a change list. Main areas:
  - backend `admin/**`, `audit/**`, `config/{SecurityConfig,AdminRateLimits}.java`, `timeentry/{AdminTimeEntryPhotoController,TimeEntry,Adjustment,AdjustmentRepository,TimeEntryRepository,TimeEntryWriter,ShiftResolver,TodayService}.java`, `employee/{AdminEmployeeController,EmployeeRepository}.java`, `seed/{DevDataSeeder,DemoDayEntries,SeedProperties}.java`, `common/ApiExceptionHandler.java`, `db/migration/V4__admin_panel.sql`, `application*.yml`, `backend/.env.example`
  - frontend `src/admin/**`, `src/auth/RequireAdmin.tsx`, `src/api/client.ts` (`apiBlob`), `src/pages/LoginPage.tsx` (`safeAdminNext`), `src/App.tsx`, `src/components/Reauth*.tsx`, `vite.config.ts`, `securityHeaders.ts`
  - the built output in `frontend/dist/` (built 2026-10-30 13:20; may be slightly older than the working tree)
- **Inputs:** `docs/plans/stage-4-admin-panel.md` (with "Open Questions: RESOLVED" and "Amendments" taking precedence), `docs/diagrams/stage-4-admin-panel.md`, the Stage 1-3 reports in `securityAudit-report/`.
- **Method:** code and configuration reading; `git diff HEAD` on the modified files; grep of `src/` and the built admin chunk for DOM sinks and third-party origins; inspection of `dist/sw.js` (precache list and routes); `npm audit`, `npm audit --omit=dev`, `npm ls`.
- **Not done, per the constraints:** `backend/.env` was not read (only `git check-ignore` confirmed it is ignored). No server was started, no database was contacted, no build or test was run, nothing was installed and no git state was changed.

## Executive summary

Stage 4a is solidly built from a security point of view. I found **no Critical, High or Medium issues**.

Verified controls:

- **Authorization, two layers.** `/api/admin/**` requires `ROLE_ADMIN` at the URL layer (`SecurityConfig.java#L51`), and all six admin controllers carry a class-level `@PreAuthorize("hasRole('ADMIN')")` with `@EnableMethodSecurity` (`SecurityConfig.java#L28`). A method-security denial is mapped to the same empty 403 instead of a 500 (`ApiExceptionHandler` new `AccessDeniedException` handler). The role is re-read from the database on every request (Stage 1 converter), so a demotion takes effect immediately.
- **Tenant scoping / IDOR.** Every repository call in the admin read and write paths carries `principal.companyId()` from the token: `findByIdAndCompanyId`, `findWithLockByIdAndCompanyId`, `findEmployeeIdByIdAndCompanyId`, the two JPQL review-page queries, `countActiveByBranch`, `findAllByCompanyIdAndIdIn`, evidence and adjustment lookups. A foreign `id`, `employeeId` or `branchId` gives 404 or an empty list, never data. `AdminTenantIsolationIntegrationTest` covers every entry route, create, the queue filter and the dashboard branch filter. The new `audit_log` table has a composite tenant FK `(actor_id, company_id) → employee`.
- **Input validation.** Request bodies are explicit records (no entity binding). Reasons are trimmed, 5-500 chars, `\p{Cc}` rejected, plus a DB `char_length(reason) <= 500` check. Local times must match `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$` and are parsed in the company zone (parse error → 400). `CorrectionRules` bounds the typed time (no future beyond 1 min, clock-in window, clock-out after the clock-in and within the 16 h max shift). `workDate` and the queue `from`/`to` are bounded to today-5y..today; the dashboard to ±5y. Page size is capped at 100. `status=VALID` is refused on the queue.
- **Concurrency / TOCTOU.** Every write locks the employee row first (the same lock as the PWA POST), then the entry, with a bounded `lock_timeout` (503 on timeout). Optimistic guards `expectedStatus` and `expectedEffectiveTime` turn a stale admin view into 409 `ENTRY_CHANGED`. Duplicate creates are caught both by a check under the lock and by the unique constraint (mapped to 409). Adjustment rows are written in the same transaction as the change. `ReviewConcurrencyIntegrationTest` exists.
- **Photo access.** The per-admin limiter (300 / 10 min, key `companyId:employeeId`) is checked **before** the lookup, so id-scanning is throttled even on 404s. A `PHOTO_VIEW` audit row is written in its own `REQUIRES_NEW` transaction before the photo is returned; if the insert fails the photo is not served. The response is `Cache-Control: no-store, private`, `Content-Type: image/jpeg`, inline with a fixed filename. This closes Stage 2 L-6.
- **Append-only audit.** `AdjustmentRepository` and `AuditLogRepository` extend `Repository` with only `save` and scoped finds. V4 adds `BEFORE UPDATE OR DELETE` and `BEFORE TRUNCATE` triggers on both tables, created after the backfill. All FKs to `time_entry` / `employee` are `ON DELETE RESTRICT`. `server_time` is never written by the admin paths; only `effective_time`, `late_minutes` and `status` change. `audit_log.details` holds ids only.
- **Frontend.** No `dangerouslySetInnerHTML`, `innerHTML`, `eval`, `new Function` or `window.open` in `src/` or in the built admin chunk. All server text (names, reasons, history values) is rendered as React text; `translateAdmin` does plain string substitution. The selfie is fetched with the bearer token via `apiBlob`, shown through a `blob:` object URL (allowed by `img-src 'self' blob:`), never prefetched or auto-retried, and revoked on change/unmount. The strict CSP with Trusted Types is unchanged (no new origins).
- **External map links.** `googleMapsUrl` builds the URL from `number.toFixed(6)` only (no string injection), rendered as `<a target="_blank" rel="noopener noreferrer">`, opened only on click; `Referrer-Policy: no-referrer` is set. Nothing is embedded and no key is used, per user decision Q6. The privacy hint is shown next to the links.
- **Service worker.** `globIgnores` excludes `admin-*.js/css`; the built `dist/sw.js` precache list contains no admin asset and no runtime route except the navigation fallback with `denylist: [/^\/api\//]`. Admin data is held only in React state (no `localStorage`, `sessionStorage` or IndexedDB use under `src/admin`).
- **Open redirect.** `safeAdminNext` (`LoginPage.tsx#L26-29`) accepts only `/admin` followed by `/`, `?`, `#` or end, rejects `//`, backslashes and control characters, and falls back to `/admin`.
- **Secrets.** `backend/.env` is ignored by `.gitignore:2`. The tracked `.env.example` has placeholders only (`DB_PASSWORD=change-me`, empty `DEV_SEED_*`); `application*.yml` read secrets from env vars with empty defaults. The README names variables only.
- **Dev demo seed.** `DevDataSeeder` and `DemoDayEntries` are both `@Profile("dev")`. The seeder refuses to run if `prod` is also active or `dev` is not explicitly active, and validates the PIN and location. Demo day is opt-in (`DEV_SEED_DEMO_DAY`, default `false`), idempotent per company-local day, uses generated placeholder images (not real selfies) and logs counts only.

There are five **Low** findings: Unicode format characters allowed in reasons, entry details (with exact coordinates) not throttled or audited, append-only enforcement owned by the runtime DB role, no rate limit on admin write actions, and dev-only dependency advisories.

---

## Findings

### [LOW] L-1 Review reasons accept invisible Unicode format and bidirectional control characters

File: `backend/src/main/java/cr/attendance/admin/ReviewRequests.java#L17` (used at L28, L37, L52)
Category: OWASP (A03 Injection / output integrity)

Description: The reason filter only rejects the `Cc` category (C0/C1 controls). Characters in the `Cf` category pass: bidi overrides and isolates (U+202A-U+202E, U+2066-U+2069), zero-width characters (U+200B-U+200D, U+2060) and U+FEFF. The reason is the legal justification stored in the append-only `adjustment` table, shown in the evidence history (`EvidenceDialog.tsx#L82`) and, from 4c, in the Excel Adjustments sheet. A right-to-left override can make the displayed text read differently from the stored bytes (for example, reversing a number or a name), and zero-width characters make two reasons look identical while they differ. It is not script injection (React escapes it), but it weakens the audit trail's evidential value. Only an authenticated admin can write a reason, which keeps this Low.

Evidence:
```java
static final String NO_CONTROL_CHARACTERS = "^[^\\p{Cc}]*$";
```

Recommendation:
1. Reject `\p{Cf}` as well (or at least the bidi range `[‪-‮⁦-⁩]` and zero-width `[​-‍⁠﻿]`): `^[^\p{Cc}\p{Cf}]*$`.
2. Optionally NFC-normalize the reason before validation.
3. Mirror the rule in the client's `normalizeReason` so the user sees an inline error, and add a test with U+202E.

### [LOW] L-2 The entry detail with exact GPS coordinates is neither throttled nor audited, unlike the photo

File: `backend/src/main/java/cr/attendance/admin/AdminTimeEntryController.java#L33-36`, `backend/src/main/java/cr/attendance/admin/EntryDetailService.java#L99-100`
Category: Privacy (Ley 8968 accountability) / OWASP A01

Description: `GET /api/admin/time-entries/{id}` returns the exact clock-in coordinates (6 decimals), GPS accuracy and distance. It is correctly admin-only and tenant-scoped. However, unlike the photo route, it has no per-admin limit and writes no audit row. A stolen admin token (6 h TTL) or a curious admin can iterate ids and extract every employee's precise location history with no trace in `audit_log`. That is the same exposure Stage 2 L-6 described for photos, which 4a fixed only for photos. Location is personal data on a par with the selfie.

Evidence:
```java
@GetMapping("/{id}")
public AdminEntryDetail detail(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id) {
    return detailService.detail(principal, id);
}
```
No limiter is injected, and there is no `auditService.record(...)` on this path.

Recommendation:
1. Count detail views against a per-admin limiter (a new `detailRateLimiter`, or reuse the photo limiter's budget). Check it before the lookup, as the photo route does.
2. Write a `LOCATION_VIEW` (or `ENTRY_VIEW`) audit row when the response contains a `location`, using ids only in `details`.
3. At minimum, add an `INFO` access log line with actor, entry and company ids, like `photo_access`.

### [LOW] L-3 Append-only enforcement on `adjustment` / `audit_log` is owned by the same DB role the application runs as

File: `backend/src/main/resources/db/migration/V4__admin_panel.sql#L49-61`, `backend/src/main/resources/application.yml#L8-10, L19-20`
Category: Config

Description: Flyway and the runtime share `DB_USER` (no `spring.flyway.user`), so the application role owns the audit tables and the trigger function. A table owner can `ALTER TABLE ... DISABLE TRIGGER`, `DROP TRIGGER`, or `DROP FUNCTION forbid_audit_change() CASCADE`, then rewrite history. The triggers therefore protect against application bugs and casual SQL, not against an attacker who gains SQL execution as the app role (SQL injection, a leaked `DB_PASSWORD`). This is documented as accepted defense in depth in `docs/diagrams/stage-4-admin-panel.md#L158`. I list it so it is tracked for Stage 5.

Evidence:
```yaml
username: ${DB_USER:asistencia_app}
...
flyway:
  enabled: true
```

Recommendation (Stage 5):
1. Create a migration/owner role for Flyway (`spring.flyway.user` / `password`) and a separate runtime role without ownership.
2. Grant the runtime role `SELECT, INSERT` only on `adjustment` and `audit_log` (`REVOKE UPDATE, DELETE, TRUNCATE`), plus usage on their identity sequences.
3. Optionally ship audit rows to an external append-only sink (log shipper) so that even a DB owner compromise leaves a copy.

### [LOW] L-4 Admin write actions (approve, reject, correct, create) have no rate limit

File: `backend/src/main/java/cr/attendance/admin/AdminTimeEntryController.java#L38-61`, `backend/src/main/java/cr/attendance/config/AdminRateLimits.java#L16-29`
Category: OWASP A04 (Insecure design) / A07

Description: Photo views, exports and account operations get per-admin limiters. The payroll-relevant writes do not: `TimeEntryRateLimitFilter` matches only `POST /api/time-entries`, and `AdminRateLimits` defines no limiter for the review actions. With a stolen admin token, a script can approve every pending entry, or create and correct entries, at full speed until the token expires. Every action leaves an `adjustment` row with the admin id, so it is detectable after the fact, but nothing slows or flags it. Each request is valid and authorized, which keeps this Low.

Evidence: The review POST handlers call `reviewService.*` directly. No `FixedWindowRateLimiter` is injected into `AdminTimeEntryController` or `ReviewService`.

Recommendation:
1. Add a `reviewRateLimiter` bean (for example 120 actions / 10 min per `companyId:employeeId`, configurable under `app.admin.rate-limit.review`). Check it in the four POST handlers and return 429 with `Retry-After`, as the photo route does.
2. In Stage 5, alert on unusual adjustment volume per admin.

### [LOW] L-5 Dev/build-only dependency advisories (production tree clean)

File: `frontend/package-lock.json` (unchanged in 4a; last changed in `0b014ae`)
Category: Dependencies

Description: `npm audit` reports 13 advisories (1 high, 9 moderate, 3 low). `npm audit --omit=dev` reports **0**. The notable ones:
- `undici@8.11.2` (high: several DoS, an SRI bypass, cross-origin redirect cache issues), pulled in by `jsdom@30.1.2`. Test environment only.
- `serialize-javascript@7.1.2` (XSS via `</script` in serialized functions) and `ejs@3.1.10`, both via `vite-plugin-pwa@2.0.0 → workbox-build@7.4.1`. Build time only, on trusted input (the project's own config).
- `deepmerge` / `@rollup/plugin-node-resolve` (prototype poisoning, low), also via `workbox-build`.

Nothing ships to the browser or the server, so the runtime risk is low. A compromised build step could still affect the generated `sw.js`.

Evidence:
```
jsdom@30.1.2 -> undici@8.11.2
vite-plugin-pwa@2.0.0 -> workbox-build@7.4.1 -> @rollup/plugin-terser@1.0.0 -> serialize-javascript@7.1.2
vite-plugin-pwa@2.0.0 -> workbox-build@7.4.1 -> @trickfilm400/rollup-plugin-off-main-thread@3.0.0-pre1 -> ejs@3.1.10
npm audit --omit=dev: found 0 vulnerabilities
```

Recommendation:
1. Run the non-breaking `npm audit fix` (it updates `undici` and `serialize-javascript` inside their ranges), then re-run the test suite.
2. Track `workbox-build` / `vite-plugin-pwa` releases for the `ejs` and `deepmerge` chains. The suggested "fix" (`vite-plugin-pwa@1.1.0`) is a downgrade and should not be applied.

---

## Reviewed and accepted (not findings)

- **Self-approval / self-correction** by an admin is allowed per user decision Q4. It is visible (`ownEntry`, `selfApproved` in the history, "Autoaprobado" in the 4c export).
- **Creating an entry for an inactive employee** is allowed by plan line 287 (historical fixes).
- **Correcting a `PENDING_REVIEW` / `REJECTED` entry also sets it `VALID`** (`ReviewWriter.java#L146-151`). This is by design. The client must send the matching `expectedStatus`, so the admin's intent is explicit, and a separate `status` adjustment row is written.
- **In-memory, per-instance rate limiters** (Caffeine, max 10,000 keys) were already noted in Stage 2. They are adequate for the single-instance deployment.
- **All demo employees, including admin `9001`, share `DEV_SEED_PIN`.** This is dev only, already reported in Stage 1, and the README states it.
- **`DemoDayEntries` writes placeholder files before the seeder transaction commits.** A rolled-back seed could leave orphan placeholder JPEGs. These are dev only and contain no personal data.
- **`ReviewService.create` logs the full `DataIntegrityViolationException`** for unexpected constraints. PostgreSQL detail strings contain ids and dates only, which is acceptable.
- **Admin chunk** contains `https://www.google.com` (the map link) plus React Router doc/localhost strings only. There are no other origins.

## Recommended follow-ups (priority order)

1. L-2: throttle and audit the entry detail (location) view.
2. L-4: per-admin limit on review writes.
3. L-1: reject `\p{Cf}` in reasons.
4. L-5: `npm audit fix` (non-breaking).
5. L-3: split DB roles in Stage 5.

## Summary

| Severity | Count | Findings |
|----------|-------|----------|
| CRITICAL | 0 | — |
| HIGH | 0 | — |
| MEDIUM | 0 | — |
| LOW | 5 | L-1 Unicode format/bidi characters in reasons; L-2 entry detail with exact coordinates not throttled or audited; L-3 append-only triggers owned by the runtime DB role; L-4 no rate limit on admin write actions; L-5 dev/build-only npm advisories (production 0) |

VERDICT: APPROVED
