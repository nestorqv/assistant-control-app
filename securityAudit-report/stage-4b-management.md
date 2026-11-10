# Security Audit Report — Control de Asistencia (Stage 4b: management of branches, employees and schedules)

- **Date:** 2026-11-06
- **Auditor:** security-auditor agent (read-only)
- **Branch:** `stage1-2`. Stage 4b is uncommitted working-tree changes on top of `20fcb94`.
- **Scope:** the 130 files in the 4b change list. `.claude/`, `CLAUDE.md`, `CHANGELOG.md` and `docs/PROGRESS.md` were left out, as instructed. Main areas:
  - backend `admin/**` (new controllers, facades, writers, `AdminLocks`, request and view records), `auth/{PinGenerator,PinConfig,LoginService}.java`, `common/{TextRules,Constraints,RateLimitedException,ApiExceptionHandler,ErrorCode,AuditAction}.java`, `config/{AdminWriteRateLimitFilter,AdminRateLimits,SecurityConfig}.java`, `employee/{Employee,EmployeeRepository,WorkSchedule,WorkScheduleRepository}.java`, `company/{Branch,BranchRepository}.java`, `audit/**`, `timeentry/{TimeEntryWriter,TodayService,TimeEntryService,AdminTimeEntryPhotoController}.java`, `V5__management_versions.sql`, `application.yml`, `application-test.yml`, the message bundles, `pom.xml`
  - frontend `src/admin/**` (pages, dialogs, `text.ts`, `adminApi.ts`, `AdminLayout.tsx`, `shell.ts`), `src/errors/errorMessages.ts`, `src/i18n/*`
  - the 4b tests, read only to check what they cover and what they print
- **Inputs:** `docs/plans/stage-4b-management.md` (D1-D16, deviations), `docs/diagrams/stage-4b-management.md`, `docs/design/stage-4b-management.md`, `securityAudit-report/stage-4a-admin-panel.md` (L-1, L-2 and L-4 were scheduled for 4b).
- **Method:**
  - Read the code and configuration, and ran `git diff HEAD` on the modified files.
  - Grepped `src/` for DOM sinks, storage, clipboard, `useSearchParams`, `document.title`, log statements, secrets and `System.out`.
  - Extracted the string constants of Spring Security 6.5.11's `StrictHttpFirewall` and Spring Web 6.2.19's `DefaultPathContainer` from the local Maven cache, to check how request paths are matched.
  - Ran `npm audit` and `npm audit --omit=dev` (read-only).
- **Not done, per the constraints:** I did not read `backend/.env`. I only confirmed it is ignored (`.gitignore:2`). I did not start a server, run a build or a test, install anything, or change git state.

## Executive summary

Stage 4b is carefully built. The most sensitive parts are correct: PIN handling, authorization, tenant isolation, the lock protocol and PII hygiene. I found **one Medium issue**: the new per-admin write limiter (the fix for 4a L-4) can be skipped by percent-encoding one letter of the URL path. I also found two new Low issues.

Verified controls:

- **Authorization, two layers.** Both new or moved controllers (`AdminEmployeeController`, `AdminBranchController`) and `AdminTimeEntryController` carry a class-level `@PreAuthorize("hasRole('ADMIN')")`. The URL rule `/api/admin/** -> hasRole("ADMIN")` is unchanged (`SecurityConfig.java#L53`). `AdminMethodSecurityTest` lists every new route, PUT included (`AdminMethodSecurityTest.java#L136-146`).
- **Actor re-check under lock in every writer.** `AdminLocks.lockActorAndTarget` (`AdminLocks.java#L84-114`) reads the actor `FOR KEY SHARE` (or `FOR UPDATE` when actor = target). It requires `active && role == ADMIN` and throws an empty 403 **before** the target 404, so a demoted admin learns nothing about other rows. This runs in employee create / update / activate / deactivate / PIN reset / unlock, schedule replace, all four branch writes and the 4a `ReviewWriter` (also for a missing entry: `ReviewWriter.lockEntry` calls `lockActor` before the 404). The three privilege writes also check membership in the locked admin set (`AdminEmployeeWriter.java#L254-259`). PIN hashing happens before the transaction, and the actor is re-checked after it.
- **Privilege changes.** A role change and a deactivation bump `token_version` through the entity mutator `endSessions()` in the same UPDATE. A PIN reset clears the lock and ends sessions (`Employee.java#L181-191`). The guards work: `SELF_CHANGE_NOT_ALLOWED` (self demote / deactivate), `LAST_ADMIN` under the `FOR NO KEY UPDATE` admin-set lock, `BRANCH_REQUIRED`, `BRANCH_INACTIVE`, no-op without audit or version bump.
- **`ADMIN_PIN_RESET_NOT_ALLOWED`.** `AdminEmployeeWriter.java#L209-212` refuses a reset of another active, unlocked ADMIN after the locks and the actor check. Nothing is written. It is bypassable in one way, see L-1.
- **PIN generation and one-time reveal.** `PinConfig` provides `new SecureRandom()` as the `RandomGenerator`. `PinGenerator` draws 8 digits with leading zeros, redraws until `PinPolicy.isValid` passes, and redraws a PIN equal to the code. The facade BCrypt-hashes it (strength 12) before any lock. The writer only ever sees the hash. The PIN leaves the server only in `CreatedEmployee` (201) and `PinResetResult` (200), both sent with `CacheControl.noStore()`. Both records override `toString()` to mask the PIN. The PIN is in no view, ProblemDetail, audit column or log line. A failed request discards the generated PIN.
- **Tenant isolation / IDOR.** Every new repository method takes `companyId`: `findWithLockByIdAndCompanyId`, `findWithShareLockByIdAndCompanyId`, `lockActiveAdminIds`, `lockActorForKeyShare`, `existsByCompanyIdAndCode`, `existsByCompanyIdAndNameIgnoreCase[AndIdNot]`, `countByCompanyIdAndBranchIdAndActiveTrue`, `findEmployeeIdsWithSchedule`, the bulk schedule delete, and the `lastPinReset` audit lookup. A `branchId` in a body is loaded scoped, so a foreign one gives 404. The schedule GET and the employee detail are scoped. The `companyId` always comes from the principal.
- **Mass assignment.** Every body is an explicit record (`ManagementRequests`). None has `companyId`, `active`, `pinHash`, `tokenVersion`, `version` or (on update) `code`, and unknown properties are ignored. The employee code is stripped, upper-cased and matched against `^[A-Z0-9-]{1,20}$`.
- **Input validation and text rules (4a L-1).** `TextRules.SAFE_TEXT` rejects `\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}`. `clean` applies NFC and strip in each compact constructor, before Bean Validation. The rule covers the three 4a review reasons, branch name, employee name, job title and the PIN-reset note. Coordinates are checked for finiteness and range (a JSON `"NaN"` is refused). Radius must be 10-5000, grace 0-240, times `HH:mm`, with at most 7 days, each day once, and `start != end`. The note's digit-run rule `\p{Nd}(?:[\s./-]*\p{Nd}){3,}` uses `UNICODE_CHARACTER_CLASS`, so `\s` covers NBSP, U+2007, U+202F and U+3000. The client mirror (`frontend/src/admin/text.ts`) uses the same categories and JavaScript's Unicode `\s`. A note needs a reason code. Violations give the no-echo 400.
- **Optimistic versions and locks.** `@Version` is set on `employee` and `branch`, and credential, lock and consent columns are `@OptimisticLock(excluded = true)`. The version is compared under the row lock, giving 409 `RECORD_CHANGED`. The schedule compares the expected week under the employee lock. The lock order (admin set, then actor + target in id order, then branch) is used by every writer, and lock timeouts are applied. A deadlock (`40P01`) or a lock timeout becomes a `PessimisticLockingFailureException`, which returns 503 `SERVER_BUSY` and logs only the class name.
- **Errors and logging (PII guardrail).** All new messages are fixed texts without arguments (`messages*.properties#L69-74`). All new log lines use ids only. The 4 Stage 1 `LoginService` lines now log ids instead of codes. `logServerErrorDetail=false` is set through Hikari `data-source-properties` and in the default URL, and `SqlExceptionHelper` logging is `OFF`. The facades log `constraint=<name> class=<simple name>` only, using `PSQLException.getServerErrorMessage().getConstraint()` first.
- **Audit.** `recordInTransaction` (MANDATORY) writes the row in the writer transaction, so it commits or rolls back with the change. `details` holds ids, enum values and field names only (`role=A->B`, `fields=name,branchId,branch=3->5`, `days=1,2,3`, branch field names without values). `PIN_RESET` has `details = null` and an optional `reason_code` / `reason_note`. V5 adds `ck_audit_log_reason_code` (format) and `ck_audit_log_reason_note` (a note needs a code, no blank note). V1-V4 are untouched.
- **LOCATION_VIEW (4a L-2).** `AdminTimeEntryController.detail` checks `detailRateLimiter` (300 / 10 min per admin) **before** the lookup. When the response carries a location, it writes a `LOCATION_VIEW` row (`REQUIRES_NEW`, `entry=<id>`) before returning, so if the insert fails the detail is not served. It also logs `location_access` with ids only.
- **Rate limits.** General write 120 / 10 min and review write 300 / 10 min are checked in `AdminWriteRateLimitFilter`, in the chain after bearer authentication and before authorization and binding. Account 30 / 10 min is checked in the controller before PIN generation. Photo and detail are both 300 / 10 min. All answer 429 `RATE_LIMITED` with `Retry-After`. Only ADMIN principals are counted, so an employee token still gets the plain 403. See M-1 for the bypass.
- **Inactive branch at clock-in.** `TimeEntryWriter` and `TodayService` treat an inactive branch like no branch (`NO_BRANCH_ASSIGNED`).
- **Frontend.**
  - No `dangerouslySetInnerHTML`, `innerHTML`, `eval`, `new Function`, `window.open`, `style=`, `localStorage`, `sessionStorage` or IndexedDB in `src/admin`.
  - The PIN lives only in React state. It is cleared when the dialog closes, never put in the title, the URL, a live region or storage, and there is no copy button (`PinRevealDialog.tsx#L11-23`). The only clipboard use is a paste *read* for coordinates.
  - The employee search text is component state with `autoComplete="off"`. The URL holds `?branch=<id>` only. `document.title` is a fixed text.
  - A self PIN reset suspends polling and the re-auth dialog before sending (`PinResetDialog.tsx#L78-82`), then signs out locally.
  - The Google Maps check link is built from numbers, uses `rel="noopener noreferrer"` and opens on click only.
  - `securityHeaders.ts`, `vite.config.ts`, `index.html`, `package.json` and `package-lock.json` are unchanged, so the strict CSP and Trusted Types are intact and there are no new origins.
- **Secrets.** `backend/.env` is ignored. There are no hard-coded credentials, keys or connection strings in the changed files. `.env.example` is unchanged.
- **Dependency change.** `org.postgresql:postgresql` moved from `runtime` to the default `compile` scope, because `Constraints` uses `PSQLException`. The version is managed by Spring Boot 3.5.16; the local cache has 42.7.11. That version is past the fixes for CVE-2024-1597 (fixed in 42.7.2) and CVE-2025-49146 (fixed in 42.7.7). The new scope has no security impact.

## 4a findings scheduled for 4b

| 4a finding | Status | Evidence |
|---|---|---|
| L-1 Unicode format / bidi characters in reasons | **Resolved** | `ReviewRequests.java#L19-54` uses `TextRules.SAFE_TEXT` + `clean`. The same rule covers every new 4b free-text field and is mirrored in the client. |
| L-2 Entry detail with exact coordinates neither throttled nor audited | **Resolved** | `AdminTimeEntryController.java#L50-63`: limiter before the lookup, `LOCATION_VIEW` row, `location_access` log line (ids only). |
| L-4 No rate limit on admin writes | **Partially resolved** | The limits exist and work for normally written URLs, but a percent-encoded path skips them (M-1). |

---

## Findings

### [MEDIUM] M-1 The admin write limiter (and the PWA clock-in limiter) can be bypassed with a percent-encoded path

File: `backend/src/main/java/cr/attendance/config/AdminWriteRateLimitFilter.java#L56-58`, `#L87-92` (same pattern in `backend/src/main/java/cr/attendance/timeentry/TimeEntryRateLimitFilter.java#L43`)
Category: OWASP (A04 Insecure design / A07, security control bypass)

Description: The filter decides whether to count a request by matching the **raw, undecoded** `HttpServletRequest.getRequestURI()` against `/api/admin/` (and the review regex). Spring MVC and Spring Security match the **decoded** path. In Spring Web 6.2.19, `DefaultPathContainer` URI-decodes each segment into `valueToMatch`. Spring Security's `StrictHttpFirewall` (6.5.11) rejects only the encodings `%00 %0A %0D %25 %2E %2F %3B %5C`, so an encoded letter such as `%61` (`a`) is allowed through. A request to, for example, `POST /api/%61dmin/employees/{id}/deactivate` therefore:
1. fails `path(request).startsWith("/api/admin/")`, so `shouldNotFilter` returns true and **nothing is counted**;
2. is still routed to `AdminEmployeeController.deactivate`, and is still authorized: the URL rule matches the decoded path, and `@PreAuthorize` would deny a non-admin even if it did not.

So authorization holds, but the 120 / 10 min general budget and the 300 / 10 min review budget, the main deliverable of 4a L-4, do not hold against anyone who encodes a single character. With a stolen admin token, a script can again deactivate every employee, rewrite every schedule, or approve or correct every entry at full speed. Each action is still audited. The account limiter (create / PIN reset) and the photo / detail limiters are checked in the controllers, so they are **not** affected. The same root cause affects the Stage 2 `TimeEntryRateLimitFilter` (`PATH.equals(request.getRequestURI())`): an employee token can skip the 30 / 10 min clock-in limiter, which bounds photo uploads and decodes, with `POST /api/time-%65ntries`. `AdminWriteRateLimitIntegrationTest` uses only plain paths, so the gap is not covered.

Evidence:
```java
protected boolean shouldNotFilter(HttpServletRequest request) {
    return !WRITE_METHODS.contains(request.getMethod()) || !path(request).startsWith(ADMIN_PREFIX);
}
...
private static String path(HttpServletRequest request) {
    String uri = request.getRequestURI();   // raw, not decoded
```
`StrictHttpFirewall` blocked encodings (from the class constants): `%00 %0A %0D %25 %2E %2F %3B %5C`.

Recommendation:
1. Decide on the **decoded, normalized** path that MVC uses, not on `getRequestURI()`. For example, use `ServletRequestPathUtils`/`UrlPathHelper.defaultInstance.getPathWithinApplication(request)` (decodes), or `request.getServletPath()` + `getPathInfo()`. Better still, use Spring Security matchers (`PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/admin/**")` and the review routes), so the filter and the authorization rule cannot disagree.
2. Apply the same change to `TimeEntryRateLimitFilter` (and, for consistency, `LoginAwareBearerTokenResolver`, which also compares the raw URI).
3. Optionally fail closed: count every write by an authenticated ADMIN whose decoded path is not a known review route against the general budget.
4. Optionally tighten the firewall: a custom `HttpFirewall` that rejects percent-encoded unreserved characters (`%41-%5A`, `%61-%7A`, `%30-%39`, `%2D`, `%5F`, `%7E`). No client of this API needs them.
5. Add regression tests with `post(URI.create("/api/%61dmin/branches"))` and `post(URI.create("/api/time-%65ntries"))`, and assert that they are counted (429 after the budget).

### [LOW] L-1 `ADMIN_PIN_RESET_NOT_ALLOWED` can be bypassed by deliberately locking the target admin through the public login

File: `backend/src/main/java/cr/attendance/admin/AdminEmployeeWriter.java#L209-212`, `backend/src/main/java/cr/attendance/auth/LoginService.java#L78-84`, `backend/src/main/resources/messages*.properties#L74`
Category: OWASP (A01 Broken access control / A04 Insecure design)

Description: The Q14 rule protects an active admin from having their PIN reset by another (possibly compromised) admin session, **unless** the target is locked. Anyone who knows the company code and the target's employee code can lock an account: 5 wrong PINs at the unauthenticated `POST /api/auth/login`, each calling `recordFailedLogin`. An attacker holding admin A's token sees B's code in the employee list. The attacker can then:
1. lock B with 5 wrong PINs;
2. call `POST /api/admin/employees/{B}/pin-reset`, which is now allowed because B is locked;
3. receive B's new PIN and log in as B.

The ProblemDetail text even suggests this route ("reset it only if their account is locked or deactivated"). The plan accepts a similar residual path (demote B first), which leaves an `EMPLOYEE_ROLE_CHANGE` row and visibly changes B's role. This path changes nothing visible about B except that B is logged out. It is audited (`PIN_RESET` with A as actor, plus `wrong PIN` / `locked account` log lines with ids), and it needs an already-compromised admin session, which keeps it Low.

Evidence:
```java
if (!actorId.equals(id) && target.getRole() == Role.ADMIN && target.isActive()
        && !target.isLockedAt(Instant.now(clock))) {
    throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ADMIN_PIN_RESET_NOT_ALLOWED);
}
```

Recommendation (pick one, or document as accepted alongside the demote path):
1. For another admin, allow the reset only when the target is **inactive** (the lockout recovery for an admin becomes "unlock", which does not change the PIN, or "deactivate, reset, re-activate", which leaves the more visible rows).
2. Or keep the locked case but also require that the lock is not recent (for example, it has existed for longer than the lockout duration), or a second admin's confirmation.
3. Make the ProblemDetail text neutral ("You cannot reset the PIN of another active administrator") so it does not describe the bypass.
4. Stage 5: alert on `PIN_RESET` of an ADMIN target, and on a lockout followed by a reset of the same account within minutes.

### [LOW] L-2 Test assertions would print the generated PIN if they fail

File: `backend/src/test/java/cr/attendance/admin/AdminEmployeeManagementIntegrationTest.java#L98, #L102, #L108, #L285, #L317` (and similar `matches("\\d{8}")` / `doesNotContain(pin)` calls in `PinGeneratorTest`)
Category: Privacy (PII guardrail: "a PIN is never shown in ... test output")

Description: The tests correctly avoid `System.out`, `andDo(print())` and message strings that contain the PIN, and they check the logs for it (`output.getAll().contains(pin)` with `.as(...)` and `isFalse()`, the right pattern). However, several AssertJ calls pass the PIN as the *actual* or *expected* value: `assertThat(pin).matches(...)`, `.doesNotContain(pin)`. When such an assertion fails, AssertJ puts both values in the failure message, so the generated PIN lands in the Surefire report and the CI log. These are throwaway PINs from test databases, so the risk is very small. It is still a literal breach of the guardrail on failure.

Evidence:
```java
assertThat(pin).matches("\\d{8}");
assertThat(body.get("employee").toString()).doesNotContain(pin).doesNotContain("pinHash");
... .isEqualTo("Solicitado por la gerencia").doesNotContain(noted.get("pin").asText());
```

Recommendation:
1. Use boolean forms with a fixed description, as line 113 already does: `assertThat(pin.matches("\\d{8}")).as("PIN format").isTrue()` and `assertThat(json.contains(pin)).as("response carries the PIN").isFalse()`.
2. Optionally add a small helper (`assertNoPin(String haystack, String pin)`) so future tests follow the pattern.

---

## Still open from earlier stages (not new in 4b)

- **4a L-3 (Low):** Flyway and the runtime share one DB role that owns the append-only triggers on `adjustment` / `audit_log`. This is planned for Stage 5. 4b adds two columns to `audit_log` and does not change the risk.
- **4a L-5 (Low):** dev / build-only npm advisories. `package-lock.json` is unchanged in 4b. `npm audit` today: 9 (1 high, 5 moderate, 3 low). `npm audit --omit=dev`: **0**.

## Reviewed and accepted (not findings)

- **Digit-run heuristic in the PIN-reset note.** Only spaces (Unicode included), `.`, `-` and `/` count as separators. A PIN written with commas, underscores, en-dashes or non-breaking hyphens (e.g. four pairs) still passes. The rule's purpose is to stop accidental entry of the usual formats, which it does: `4+4`, a cédula with dashes, a phone with a space. The note is admin-only, and the UI warns against personal data. Accepted per D13 / D14.
- **A name typed into the PIN-reset note** cannot be detected. Accepted per D14 (UI guidance, admin-only visibility).
- **Admin-to-admin powers by design.** Any admin can create admins, promote employees, reset an employee's PIN and then promote that account, edit branch coordinates and radius, and unlock other admins. Every one of these writes an audit row with the actor id.
- **Demote-then-reset path** for another admin's PIN is accepted in the plan (D3) and leaves an `EMPLOYEE_ROLE_CHANGE` row.
- **The PIN-reset facade pre-reads the target code** without a lock (`AdminEmployeeService.java#L84-86`), only to avoid a PIN equal to the code. Responses do not differ (the writer decides 403 / 404), and both paths hash.
- **The detail returned by approve / reject / correct / create** includes the location without a `LOCATION_VIEW` row. Those actions write `adjustment` rows naming the admin and are write-limited (subject to M-1). Accepted per D8.
- **`logServerErrorDetail`.** A `DB_URL` that explicitly sets `logServerErrorDetail=true` would override the Hikari property (PgJDBC URL parameters take precedence). The default URL and `.env.example` do not set it. Keep it out of deployment URLs.
- **The catch-all `ApiExceptionHandler.unexpected`** still logs the full exception. With `logServerErrorDetail=false`, a re-thrown integrity violation carries the constraint name and the SQL with `?` placeholders only (no row values).
- **Unpaged employee / branch lists** (deviation 3) for an admin of the same company only. Acceptable at the target size.
- **The in-memory per-instance limiters** (Caffeine) are unchanged since Stage 2. They are fine for a single instance.
- **The h1 of the employee detail page shows the name** inside the admin area only. It is not in `document.title`, a URL or a server error.

## Recommended follow-ups (priority order)

1. M-1: match the decoded path in `AdminWriteRateLimitFilter` and `TimeEntryRateLimitFilter`, and add encoded-path regression tests. **Needed before the 4b commit.**
2. L-1: restrict another admin's PIN reset to inactive targets (or add a second condition), and neutralize the error text.
3. L-2: switch the PIN assertions to boolean forms with fixed descriptions.
4. Carried: 4a L-5 `npm audit fix` (non-breaking); 4a L-3 DB role split in Stage 5.

## Summary

| Severity | Count | Findings |
|----------|-------|----------|
| CRITICAL | 0 | none |
| HIGH | 0 | none |
| MEDIUM | 1 | M-1 Admin write limiter (and PWA clock-in limiter) bypassed with a percent-encoded path |
| LOW | 2 (+2 carried) | L-1 `ADMIN_PIN_RESET_NOT_ALLOWED` bypassed by first locking the target admin via login; L-2 failing test assertions would print generated PINs. Carried, unchanged: 4a L-3 DB role owns the audit triggers; 4a L-5 dev-only npm advisories (production 0) |

4a findings: L-1 resolved, L-2 resolved, L-4 partially resolved (see M-1).

Initial audit verdict (superseded by the re-audit below): REJECTED

---

## Re-audit (2026-11-06)

- **Cycle:** 2 of 2.
- **Scope:** the fixes for M-1, L-1 (rewording only, per the user's decision) and L-2, plus a check of the fixes for new issues. The files were the same uncommitted working tree on `stage1-2`, using the change list provided (136 files). Re-read:
  - `config/AdminWriteRateLimitFilter.java`, `timeentry/TimeEntryRateLimitFilter.java`, `config/SecurityConfig.java`, `auth/LoginAwareBearerTokenResolver.java`
  - `application.yml`, the three `messages*.properties`, `frontend/src/i18n/{resources,adminResources}.ts`, `frontend/src/admin/components/AccessPanel.tsx`
  - `AdminWriteRateLimitIntegrationTest`, `TimeEntryRateLimitIntegrationTest`, `AdminEmployeeManagementIntegrationTest`, `PinGeneratorTest`
  - every `@RequestMapping` / write mapping in `backend/src/main/java`
- **Method:**
  - Disassembled (`javap`, read-only) the classes that decide how a path is decoded and matched in the cached jars: `UrlPathHelper` and `DefaultPathContainer` (spring-web 6.2.19), `StrictHttpFirewall` (spring-security-web 6.5.11), and `Encoding.shouldForce` (spring-boot 3.5.16).
  - Grepped the tests for PIN-valued assertions.
- **Not done:** I did not read `backend/.env`, run a build or a test, or start a server.

### Status of the previous findings

| Finding | Status | Evidence |
|---|---|---|
| M-1 Rate-limit filters matched the raw URI | **Resolved** | Both filters now match `UrlPathHelper.defaultInstance.getPathWithinApplication(request)`: `AdminWriteRateLimitFilter.java#L58`, `#L67`, `#L88-91`, and `TimeEntryRateLimitFilter.java#L43-45`. Regression tests: `AdminWriteRateLimitIntegrationTest.java#L194-221` covers `/api/%61dmin/branches` (the first 3 get 400, which proves they reach the controller, then 429; the plain path shares the budget) and `.../{id}/%61pprove` (review budget, general budget untouched). `TimeEntryRateLimitIntegrationTest.java#L89-104` covers `/api/time-%65ntries`. |
| L-1 Other-admin PIN-reset rule bypass via lockout | **Accepted residual (user decision 2026-11-06, option A); rewording verified** | All six texts are neutral, and none mentions locked or deactivated accounts: `messages.properties#L74`, `messages_es.properties#L74`, `messages_en.properties#L74`, `frontend/src/i18n/resources.ts#L269-270` (ES), `#L538-539` (EN), and the UI note `admin.pin.adminNotAllowed` (`adminResources.ts#L543-545`, shown in `AccessPanel.tsx#L98`). The rule in `AdminEmployeeWriter.java#L209-212` is unchanged, as decided. Recommendation 4 of L-1 (alert on a lockout followed by a reset of an ADMIN) stays a Stage 5 suggestion. |
| L-2 Test assertions would print the PIN | **Resolved** | `AdminEmployeeManagementIntegrationTest.java#L98, #L102, #L110, #L113, #L115, #L300, #L348` now use `assertThat(<boolean>).as("<fixed text>").isTrue()/isFalse()`. The other PIN uses are `loginStatus(...)` / `isValid(pin)`, so the asserted value is a status code or a boolean, never the PIN. `PinGeneratorTest.java#L48-49, #L70-71` compares against a scripted fixture through `pin.equals(...)` with a fixed description. No `andDo(print())`. The frontend `LoginPage.test.tsx#L93` already compares `sent.pin === PIN` as a boolean. |
| 4a L-3 DB role owns the trigger tables | **Deferred to Stage 5 (user)** | Unchanged. |
| 4a L-5 Dev-only npm advisories | **Deferred to Stage 5 (user)** | `package-lock.json` is unchanged; production advisories are still 0. |

### Path-variant analysis of the M-1 fix

What the filters now compare (verified in the bytecode):
- `getPathWithinApplication` = `getRequestUri` (`removeSemicolonContent`, then `decodeRequestString`, then `getSanitizedPath`, which collapses `//`), minus the context path. The context path is matched case-insensitively.
- The dispatcher and the Spring Security URL rule use `PathPattern` on `DefaultPathContainer`, which decodes each segment with a fixed `StandardCharsets.UTF_8`.

| Variant | Firewall (StrictHttpFirewall defaults, no custom firewall in `SecurityConfig`) | Filter sees | Dispatcher | Result |
|---|---|---|---|---|
| Encoded unreserved character (`%61`, `%65`, mixed case hex) | allowed | decoded, e.g. `/api/admin/...` | routed | **Counted.** The bypass is closed, and tests cover it. |
| Encoded `/` `.` `%` `\` (`%2F %2E %25 %5C`), NUL / CR / LF | rejected (400) before the chain | — | — | No bypass |
| Double slash, literal or encoded (`//`, `%2F%2F`) | rejected (`//`, `%2f%2f`, `%2F%2F` in the blocklist) | — | — | No bypass |
| Matrix params / semicolon (`;`, `%3B`, `;jsessionid=`) | rejected (`;`, `%3b`, `%3B` in the blocklist) | (would be stripped anyway) | — | No bypass |
| `./`, `../` segments | rejected (`isNormalized`) | — | — | No bypass |
| Trailing slash `/api/time-entries/` | allowed | `/api/time-entries/` (not counted) | **not routed**: Spring 6 has no trailing-slash match, and there is no `configurePathMatch` / `WebMvcConfigurer` in the project | 404, no bypass |
| Trailing slash on an admin route | allowed | under `/api/admin/`, so counted in the general budget | not routed | 404, counted (harmless, fails closed) |
| Case (`/api/Admin/...`, `/API/time-entries`) | allowed | not counted | **not routed**: `PathPattern` is case-sensitive, and so is the `/api/admin/**` rule | 404, no bypass |
| Context path / servlet path | — | `server.servlet.context-path` and `spring.mvc.servlet.path` are not set, so the context path is empty and the DispatcherServlet is on `/` | same | Consistent. If a context path is added later, `getPathWithinApplication` strips it, as `RequestPath` does. |
| Request charset (`Content-Type: ...; charset=UTF-16` + `%61`) | allowed | `UrlPathHelper` decodes with `request.getCharacterEncoding()` | UTF-8 always | **Not exploitable today.** Boot's `Encoding.shouldForce(REQUEST)` defaults to `true`, and `server.servlet.encoding.*` is not overridden in the project. So the auto-configured `CharacterEncodingFilter` (highest precedence, ahead of the security chain) resets every request to UTF-8, and both sides decode the same way. See the note below. |
| Malformed or overlong UTF-8 (`%C1%A1`) | allowed | U+FFFD | U+FFFD | Neither matches, 404 |
| Lower-case or unknown method (`post`) | rejected (method allow-list) | — | — | No bypass |
| Review id variants (`+5`, `%2B5`, `005`, non-ASCII digits) | allowed | `REVIEW_PATH` uses ASCII `\d`, so `+5` and Unicode digits miss it, but the path is still under `/api/admin/` | `Long` binding accepts `+5` and Unicode digits | Counted in the **general** budget instead of the review one. This is a budget swap, not a bypass: an admin already has both budgets, so the total is not exceeded. It fails closed. |
| Error dispatch to `/error` | — | `OncePerRequestFilter` skips error dispatches and marks the request as already filtered | — | No double count, no skip |

Coverage: every write mapping in `backend/src/main/java` is under `/api/admin/...` (`AdminBranchController`, `AdminEmployeeController`, `AdminTimeEntryController`), or is `/api/time-entries`, `/api/me/consent` or `/api/auth/{login,logout}`. The last three were never covered by these filters, which is unchanged and by design. So no admin write reaches a controller without passing the admin filter.

False positives: none. Read methods (`GET`, `HEAD`, `OPTIONS`) are still excluded, and an employee token is still never counted (`AdminWriteRateLimitFilter.java#L65-66`). The only new effect is that encoded variants of real routes now count, which is the intent.

### New issues introduced by the fixes

None at CRITICAL, HIGH, MEDIUM or LOW. Two informational hardening notes, neither counted as a finding:

- **Info N-1: the filters rely on Boot's forced UTF-8 request encoding.** `UrlPathHelper` decodes with the request charset, while the dispatcher always uses UTF-8. They agree only because `server.servlet.encoding.force-request` defaults to `true`. If someone later sets `server.servlet.encoding.force-request=false`, `force=false` or `enabled=false`, a request with `Content-Type: application/json; charset=UTF-16` (or an EBCDIC charset) plus `/api/%61dmin/...` would again skip the counting. Optional hardening, any one of:
  1. Match on `ServletRequestPathUtils.parseAndCache(request).pathWithinApplication().value()` (the same UTF-8 `RequestPath` the dispatcher uses), or on `PathPatternRequestMatcher` instances.
  2. Call `new UrlPathHelper()` with `setDefaultEncoding("UTF-8")` and override `determineEncoding` to always return UTF-8.
  3. Add a test that sends `charset=UTF-16` on an encoded admin path.
- **Info N-2: `LoginAwareBearerTokenResolver` still compares the raw URI** (`LoginAwareBearerTokenResolver.java#L17`). This only fails closed: an encoded login or health path such as `/api/auth/%6Cogin` keeps the bearer token, so a stale token can produce a 401 on that unusual URL. The exact raw match cannot make a protected path public. No security impact. Align it with the filters if convenient.

### Re-audit summary

| Severity | Count | Findings |
|----------|-------|----------|
| CRITICAL | 0 | none |
| HIGH | 0 | none |
| MEDIUM | 0 | M-1 resolved |
| LOW | 0 open (+1 accepted, +2 deferred) | L-1 accepted by the user (option A), rewording verified in all 3 bundles and both frontend resource files; L-2 resolved. Deferred to Stage 5 by the user: 4a L-3 DB role owns the audit triggers; 4a L-5 dev-only npm advisories (production 0) |
| Info | 2 | N-1 the filters depend on Boot's default forced UTF-8 request encoding; N-2 the bearer-token resolver still compares the raw URI (fails closed only) |

4a L-4 (no rate limit on admin writes): now **resolved**.

VERDICT: APPROVED
