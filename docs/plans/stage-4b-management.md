# Stage 4b - Management: branches, employees, schedules (+ 4a audit L-1, L-2, L-4)

Umbrella plan: `docs/plans/stage-4-admin-panel.md`. Its "Open Questions: RESOLVED", "Amendments" and "From the Stage 4a security audit ... do in 4b" take precedence over its body, and **this file takes precedence over the umbrella for 4b** where they differ (every deviation is listed in "Deviations from the umbrella plan").
Inputs: `docs/diagrams/stage-4-admin-panel.md` (notes 5.x), `docs/design/stage-4-admin-panel.md` (3.8-3.12, 4, 5.3, 8), `securityAudit-report/stage-4a-admin-panel.md` (L-1, L-2, L-4).

Branch: `stage1-2`. Status: PLANNED 2026-11-02; **REVISED 2026-11-03** with the user's answers (Q1-Q14), the six plan-critic findings and the new PII guardrail (CLAUDE.md Project rules). See "Open Questions: RESOLVED". No code written.

## Summary

4b adds the management half of the admin panel on top of the committed 4a code (`33f4458`, `20fcb94`). Backend: three write services in the existing `cr.attendance.admin` package, each split into a non-transactional facade (maps constraint violations after commit, the 4a `ReviewService` pattern) and a transactional writer that takes row locks in one fixed order, compares an `expectedVersion` (new `version` columns on `employee` and `branch`, V5) or the expected week (schedules), applies intention-revealing entity mutators, bumps `token_version` on privilege changes, and writes `audit_log` rows **in the same transaction** (new `AuditService` method with `MANDATORY` propagation; the existing `REQUIRES_NEW` one would self-block on the row locks). PINs come from a new `auth/PinGenerator` (SecureRandom + `PinPolicy`), are hashed before any lock is taken, returned once with `Cache-Control: no-store`, and never logged or audited. `AdminEmployeeController` moves from `employee` to `admin` (same class name and paths) because it now needs `auth`, `audit` and `company`, and `employee -> auth` would be a cycle. The three 4a audit items are done here: a shared `common/TextRules` (NFC + strip, rejects `\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}`) for reasons and every new free-text field (L-1); a per-admin limiter plus a `LOCATION_VIEW` audit row on `GET /api/admin/time-entries/{id}` (L-2); and a servlet filter in the security chain that limits every `POST/PUT/PATCH/DELETE /api/admin/**` per admin and answers 429 `RATE_LIMITED` with `Retry-After` before any controller runs (L-4). Frontend: Employees, Employee detail (profile, access actions, weekly schedule) and Branches pages inside the existing lazy `admin` chunk, exactly per design 3.8-3.12, with the same client-side text rule, version-conflict handling and a dirty-schedule guard that works with the declarative `BrowserRouter` (no `useBlocker`). No new runtime dependency, no CSP change, employee bundle and precache unchanged.

Revision 2026-11-03 adds: two write budgets (general 120 / review 300 per 10 min); an optional PIN-reset reason (preset code + short note, stored in new `audit_log.reason_code` / `reason_note` columns, shown on the employee detail page); a rule that an admin may reset another **admin's** PIN only if that admin is inactive or locked (`ADMIN_PIN_RESET_NOT_ALLOWED`); a lock protocol that takes the actor row (`FOR KEY SHARE`) together with the target row (`FOR UPDATE`) in id order before any branch lock, in every management writer and in the 4a `ReviewWriter`; `logServerErrorDetail=false` on the JDBC driver so SQL errors never put codes, names or hashes into logs; a pause of shell polling and of the re-auth dialog during a self PIN reveal; and the **PII guardrail** (D14): a PIN is only ever returned in the one-time reveal response, and names, employee codes (which may hold a cedula), cedula / phone / social-security numbers never appear in errors, warnings, logs, audit rows, URLs or the PIN-reset note. This also fixes 4 existing Stage 1 log lines that print the employee code.

## Context: what exists after 4a (verified in code, 2026-11-02)

| Area | State |
|---|---|
| Schema | V1-V4. `branch.active` exists (V4). `audit_log` exists with `ck_audit_log_action CHECK (action ~ '^[A-Z_]{3,40}$')` and `ck_audit_log_target_type IN ('TIME_ENTRY','EMPLOYEE','BRANCH','WORK_SCHEDULE','REPORT')`, append-only triggers. No `version` column anywhere. `uq_branch_company_name`, `uq_employee_company_code`, `ck_employee_code ^[A-Z0-9-]{1,20}$`, `ck_employee_branch_role`, `ck_branch_latitude/longitude/radius`, `uq_work_schedule_employee_day`, `ck_work_schedule_times (end <> start)`, `ck_work_schedule_grace 0..240`. No blank-name check on `branch`/`employee` (only `company`). `SchemaEnglishNamingTest` expects **51** u/c/f constraints and 9 `ix_` indexes. |
| Entities | `Employee`, `Branch`, `WorkSchedule`: no setters, no mutators. Lock fields of `Employee` are changed only by atomic JPQL in `EmployeeRepository` (`recordFailedLogin`, `recordSuccessfulLogin`, `recordConsent`, `incrementTokenVersion`). |
| Repositories | `EmployeeRepository.findWithLockByIdAndCompanyId` (PESSIMISTIC_WRITE = `FOR UPDATE` on PostgreSQL), `countActiveByBranch`, `findAllByCompanyIdAndIdIn`. `BranchRepository` has `save`, `findByIdAndCompanyId`, `findAllByCompanyIdOrderByName`, dev-only `updateLocation`. `WorkScheduleRepository` has `save` + finds, **no delete**. All in `TenantRepositoryConventionTest`. |
| Admin API | `GET /api/admin/branches` (`BranchView` incl. lat/lng, `active`, `activeEmployees`), `GET /api/admin/employees[/{id}]` (`EmployeeSummary`, in package `employee`). `ErrorCode` already has all 14 Stage 4 codes and their 28 keys per bundle. `AuditAction` has the 4b actions but not `LOCATION_VIEW`. |
| Limiters | `AdminRateLimits` beans `photoRateLimiter` (used), `exportRateLimiter`, `accountRateLimiter` (**defined, not wired**). `AdminProperties.RateLimits(photo, export, account)`. `TooManyAttemptsException` -> 429 `TOO_MANY_ATTEMPTS` + `Retry-After`. `TimeEntryRateLimitFilter` is the in-chain filter pattern (after `BearerTokenAuthenticationFilter`, resolves the locale itself). |
| L-1 | `ReviewRequests.NO_CONTROL_CHARACTERS = "^[^\\p{Cc}]*$"`; frontend `normalizeReason` collapses whitespace only. |
| L-2 | `AdminTimeEntryController.detail` has no limiter and no audit. |
| L-4 | No limiter on any admin write. |
| Audit | `AuditService.record` is `REQUIRES_NEW` (correct for the read-only photo path). |
| Seed tests | `DevSeedContentTest` / `DevSeedEnabledTest` pin `app.seed.pin` and `app.seed.demo-day` but **not** `branch-latitude/longitude/radius-meters`, yet `DevSeedContentTest` asserts 9.9325 / -84.0796 / 100. `application.yml` imports `backend/.env`, so a developer's `DEV_SEED_LAT` would break it. `DevSeedLocationTest` pins all. |
| Frontend | `main.tsx` uses declarative `<BrowserRouter>`; `AdminApp` routes: index (dashboard), `review`. `AdminLayout` nav: Dashboard, Review. `api/client.ts` already supports `PUT`/`DELETE`. `describeError` formats `Retry-After` only for `error.TOO_MANY_ATTEMPTS`. Design 5.3 already defines the 4b key tables (`admin.employees.*`, `admin.employee.*`, `admin.pin.*`, `admin.schedule.*`, `admin.branches.*`); none are in `adminResources.ts` yet. Budgets: admin JS 80 KB gzip, admin CSS 10 KB gzip. |

## Verified against official sources (2026-11-02)

| Item | Finding | Consequence |
|---|---|---|
| Spring Data JPA reference, "Derived delete queries" | A derived `deleteBy...` **loads the entities and deletes them one by one** (entity deletes); a `@Modifying @Query("delete ...")` is one bulk statement. `@Modifying(clearAutomatically = true)` drops non-flushed changes. | The umbrella's `deleteAllByCompanyIdAndEmployeeId` must be a **JPQL bulk** `@Modifying @Query`, not a derived delete (see next row). |
| Hibernate flush order (Hibernate forum / Vlad Mihalcea "flush operation order") | Fixed ActionQueue order: inserts, updates, collection ops, **entity deletes last**. Delete-then-insert of the same unique key in one flush fails on the unique constraint. | Replacing Monday 08:00 with Monday 09:00 would violate `uq_work_schedule_employee_day` with entity deletes. The writer updates rows in place for days kept, inserts new days, and removes dropped days with a bulk delete. A regression test covers it. |
| PostgreSQL row locks (Hibernate HHH-13135 discussion, Vlad Mihalcea "FOR UPDATE vs FOR NO KEY UPDATE") | Hibernate's PESSIMISTIC_WRITE emits `FOR UPDATE` on PostgreSQL; `FOR UPDATE` blocks other transactions' FK inserts referencing the row (FK checks take `FOR KEY SHARE`); `FOR NO KEY UPDATE` does not. | (a) Audit rows in a writer must be in the **same** transaction (a `REQUIRES_NEW` insert whose `actor_id` FK points to a row the outer transaction locked would wait on its own caller). (b) The company-wide "active admins" lock uses a native `FOR NO KEY UPDATE` so it does not stall concurrent 4a review actions / photo audits (their `admin_id` / `actor_id` FKs). |
| React Router `useBlocker` docs | Available in Framework and Data mode only, **not in Declarative mode** (`BrowserRouter`). | Design 3.9.3's "router blocker" is not available. 4b uses `beforeunload` + an in-panel navigation guard (Decisions, Frontend). |
| Java `Pattern` / JS `RegExp` with `u` flag | Both support Unicode general categories `\p{Cc}`, `\p{Cf}`, `\p{Cs}`, `\p{Zl}`, `\p{Zp}`; a lone surrogate is matched by `\p{Cs}`. `java.text.Normalizer.Form.NFC` / `String.prototype.normalize('NFC')`. | Same rule on both sides. |

| PgJDBC connection properties (jdbc.postgresql.org/documentation/use, 2026-11-03) | `logServerErrorDetail` (default `true`): "Whether to include server error details in exceptions and log messages (for example inlined query parameters). Setting to `false` will only include minimal, not sensitive messages." | Set to `false` through `spring.datasource.hikari.data-source-properties` (applies even when `DB_URL` is overridden). The primary message still names the constraint (`... violates unique constraint "uq_employee_company_code"`); only the `Detail: Key (company_id, code)=(1, 2001)` part goes away, so constraint-name mapping keeps working (D15). |
| PostgreSQL row-lock conflict table (explicit locking docs) | `FOR KEY SHARE` conflicts only with `FOR UPDATE`; `FOR NO KEY UPDATE` conflicts with `FOR SHARE`, `FOR NO KEY UPDATE`, `FOR UPDATE`. FK checks on insert take `FOR KEY SHARE` on the referenced row. Deadlocks are detected (SQLSTATE `40P01`) and one transaction is aborted. | Actor rows are locked `FOR KEY SHARE` (compatible with the admin-set `FOR NO KEY UPDATE` and with other FK checks); a residual `40P01` is translated by Spring to `CannotAcquireLockException` (a `PessimisticLockingFailureException`), which the existing handler maps to 503 `SERVER_BUSY` (D2). |

Not re-verified online (stable, coder re-checks while implementing): `org.hibernate.annotations.OptimisticLock(excluded = true)` in Hibernate 6.6 (Boot 3.5.16); `@Modifying(flushAutomatically = true)`; Jackson's default coercion of the JSON string `"NaN"` to `Double.NaN` (hence the explicit finite check); Spring Boot binding an empty property value to `null` for `Double`/`Integer` (used to pin seed properties).

## Requirements Checklist

### Planning
- [x] Read the umbrella plan (incl. RESOLVED, Amendments, 4a audit items), the Stage 4 diagrams and design, the 4a audit report, and the live 4a code (admin, audit, config, employee, company, seed, tests, frontend admin)
- [x] Verified Spring Data derived deletes, Hibernate flush order, PostgreSQL lock modes vs FK checks, React Router `useBlocker` modes
- [x] Open Questions answered by the user and applied (2026-11-03, see RESOLVED)
- [x] Plan-critic findings 1-6 folded in (2026-11-03)
- [x] PII guardrail folded in (2026-11-03)
- [ ] Architect delta (Step 0)

### PII guardrail (D14, user requirement 2026-11-03)
- [ ] PIN only in the one-time reveal response (`201 create`, `200 pin-reset`, `no-store`) to the requesting admin; nowhere else (responses, ProblemDetails, warnings, logs, audit, test output)
- [ ] Names, employee codes, cedula / phone / social-security numbers: never in error/warning text, logs, `audit_log.details` / `reason_note`, URLs or query strings
- [ ] PIN-reset note refuses digit runs (4+ digits, separators allowed) and shows UI guidance
- [ ] Fix the 4 Stage 1 `LoginService` log lines that print `employee.getCode()` (log the id)
- [ ] `logServerErrorDetail=false`; `SqlExceptionHelper` logging off; facades log constraint name + class only
- [ ] Employee search text stays in component state (never in the URL)
- [ ] 4b adds no cedula / phone / social-security field (checked; none exist in the schema); any future one follows D14

### Security items carried from the 4a audit
- [ ] **L-1** `common/TextRules`: NFC normalize, strip, reject `\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}`; applied to the 4a reasons (`ReviewRequests`) and to branch name, employee name, job title; same localized 400 `INVALID_REQUEST` (no echo); client mirror with an inline error; tests with U+202E, U+200B, U+FEFF, U+2028, a lone surrogate, and accented Spanish text that passes
- [ ] **L-2** `GET /api/admin/time-entries/{id}`: per-admin `detailRateLimiter` checked before the lookup; `LOCATION_VIEW` audit row (ids only) when the response carries a location; INFO `location_access` log line with ids only; 429 `RATE_LIMITED` + `Retry-After`
- [ ] **L-4** per-admin write limiters on every `POST/PUT/PATCH/DELETE /api/admin/**`: review budget (300 / 10 min) for the 4a review actions and admin-created entries, general budget (120 / 10 min) for everything else; in the security chain before the dispatcher, 429 `RATE_LIMITED` + `Retry-After`, nothing written when limited, per admin, employees still get 403
- [ ] New `ErrorCode.RATE_LIMITED`, `RECORD_CHANGED`, `ADMIN_PIN_RESET_NOT_ALLOWED` with EN/ES title/detail in the 3 bundles and in `resources.ts`; `ERROR_CODE_KEYS` + contract test
- [ ] Photo, detail and account limiters answer `RATE_LIMITED` (Q1); login and PWA clock-in keep `TOO_MANY_ATTEMPTS`

### Data model
- [ ] V5: `employee.version`, `branch.version` (`integer NOT NULL DEFAULT 0`), `ck_employee_name`, `ck_branch_name` (blank rejected), `audit_log.reason_code` + `audit_log.reason_note` with `ck_audit_log_reason_code` and `ck_audit_log_reason_note`; V1-V4 untouched; `ddl-auto=validate` passes
- [ ] Entities: `@Version`; `@OptimisticLock(excluded = true)` on credential, lock and consent fields; mutators on `Employee`, `Branch`, `WorkSchedule`
- [ ] `AuditAction.LOCATION_VIEW` (no migration: the DB checks only the format)

### Branches
- [ ] List (existing, + `version`), create, edit (name, lat/lng, radius), activate/deactivate with `expectedVersion`
- [ ] `BRANCH_NAME_TAKEN` (precheck case-insensitive + DB backstop), `BRANCH_IN_USE`, finite/in-range coordinates, radius 10-5000
- [ ] Deactivate vs concurrent assignment cannot leave an active employee on an inactive branch (lock protocol)
- [ ] UI: list without coordinates, form with paste helper and Google Maps check link, row actions with confirm and `aria-disabled` guard

### Employees
- [ ] Extended list/detail view: branch name, consent given, locked + locked until, has schedule, version (never `pinHash`, `failedAttempts`, `tokenVersion`)
- [ ] Create with generated PIN shown once (`no-store`), code uppercased + immutable, role, branch rules
- [ ] Edit name, job title, role, branch with `expectedVersion`; role change bumps `token_version`
- [ ] Activate/deactivate with `expectedVersion`; deactivate bumps `token_version`; activate refused on an inactive branch
- [ ] PIN reset (new PIN once, `token_version` bump, lock cleared, optional reason code + note stored on the `PIN_RESET` audit row), unlock
- [ ] Another active, unlocked ADMIN's PIN cannot be reset (409 `ADMIN_PIN_RESET_NOT_ALLOWED`); self reset allowed
- [ ] Last PIN reset (when, by whom, reason) shown on the employee detail page
- [ ] Guards: `SELF_CHANGE_NOT_ALLOWED`, `LAST_ADMIN` under a company-wide admin lock, actor re-check under lock in **every** management writer, `BRANCH_REQUIRED`, `BRANCH_INACTIVE`, `EMPLOYEE_CODE_TAKEN`
- [ ] Lock protocol: admin set -> actor (`FOR KEY SHARE`) + target (`FOR UPDATE`) in id order -> branch; also in `ReviewWriter`; residual deadlock gives 503, never 500
- [ ] Clock-in treats an inactive branch like no branch (`NO_BRANCH_ASSIGNED`, Q6)
- [ ] Account limiter (create + PIN reset) wired
- [ ] UI per design 3.8-3.10, 3.12 (PIN reveal, confirms, self/last-admin notes) plus the PIN-reset reason fields and the "last PIN reset" line
- [ ] Self PIN reset: shell polling paused and the re-auth dialog suppressed until the PIN dialog closes

### Schedules
- [ ] `GET/PUT /api/admin/employees/{id}/schedule`, replace the whole week with an `expected` week check, in-place update (no unique violation), overnight allowed, `start = end` rejected, grace 0-240
- [ ] Stored `late_minutes` never change
- [ ] UI: week grid with `Intl` weekday names, overnight hint, copy from another employee, dirty guard, error summary

### Audit
- [ ] One `audit_log` row per effective management change, in the same transaction, ids and field names only; no row for no-ops or failures; never a PIN, name, code or coordinate (the PIN-reset `reason_note` is free text but refuses digit runs and carries UI guidance against PII)

### Cross-cutting
- [ ] `@PreAuthorize("hasRole('ADMIN')")` on every new/moved controller; `AdminMethodSecurityTest` route list extended (incl. PUT)
- [ ] Tenant scoping: every new repository method takes `companyId`; ids in bodies (`branchId`) loaded scoped (other tenant = 404)
- [ ] EN/ES user text, "usted", English technical names; i18n parity, no-hardcoded-text and "tú" tests green
- [ ] Admin chunk within budget (or the documented fallback split); employee JS/CSS and precache unchanged; no admin file in the precache
- [ ] Strict CSP / Trusted Types unchanged; no `style=`, no `dangerouslySetInnerHTML`, no new origins
- [ ] Dev seed tests pin **all** `app.seed.*` properties
- [ ] `.\mvnw.cmd clean test`, `npm test`, `npm run lint`, `npm run build`, `npm test` green; report totals and sizes
- [ ] README "Admin panel": management section
- [ ] Hand-offs: architect delta (V5 incl. `audit_log` reason columns, filter, lock order); designer delta (PIN-reset reason fields, last-reset line, admin-PIN-reset note, new nav icons); focused security audit after 4b, before the commit (Q13); main session updates `CHANGELOG.md` / `docs/PROGRESS.md`

## Decisions

### D1. Package layout and dependencies
- New/changed backend classes live in `cr.attendance.admin`: `AdminBranchService` (facade) + `AdminBranchWriter`; `AdminEmployeeService` (facade) + `AdminEmployeeWriter`; `AdminScheduleWriter` (no facade needed: no unique conflict is expected under the employee lock; a violation stays a 500 and is logged); `AdminEmployeeView`; request records in `ManagementRequests`; `PinResetReason` (enum `FORGOT_PIN`, `ACCOUNT_LOCKED`, `POSSIBLE_MISUSE`, `OTHER`); `AdminLocks` (lock-timeout helper **and** the shared actor/target lock step of D2, used by every management writer and by `ReviewWriter`).
- `employee.AdminEmployeeController` **moves** to `admin.AdminEmployeeController`, same class name (the reflection test lists it by simple name) and same paths. `employee.EmployeeQueryService` and `employee.EmployeeSummary` are deleted (no other users). `employee/AdminEmployeeControllerTest` moves to `admin/` and is adapted.
- `admin -> auth` (only `PinGenerator`, `PinPolicy`, `PasswordEncoder` bean). `auth` never imports `admin`. `employee` gains no new dependency.
- `common`: `TextRules`, `RateLimitedException`, 2 new `ErrorCode`s, `AuditAction.LOCATION_VIEW`. `common` still imports no feature package (`CommonPackageDependencyTest`).
- `config`: `AdminWriteRateLimitFilter` (new, not a bean, registered in `SecurityConfig` like `TimeEntryRateLimitFilter`), new limiter beans in `AdminRateLimits`.

### D2. Concurrency: versions, locks, lock order
- **Optimistic check under a pessimistic lock** (the 4a pattern with a version instead of field values):
  - `employee.version` and `branch.version` (V5), mapped with JPA `@Version Integer version`. Responses expose `version`; every edit / activate / deactivate request carries `expectedVersion`. The writer locks the row, compares, and gives **409 `RECORD_CHANGED`** on mismatch. Hibernate increments `version` on flush of a dirty entity, so no manual bump.
  - `Employee` fields `pinHash`, `failedAttempts`, `lockedUntil`, `tokenVersion`, `consentAt` get `@OptimisticLock(excluded = true)`: PIN reset, unlock, logins, consent and logout never make another admin's open profile form stale. `version` therefore means "profile version" (code, name, job title, role, branch, active). The existing atomic JPQL updates do not touch `version` anyway.
  - **Schedules** have no version column: `PUT` carries `expected` (the week the client loaded). The writer locks the employee row and compares the current rows (as a normalized set of `{dayOfWeek, startTime, endTime, graceMinutes}`) to `expected`; mismatch gives 409 `RECORD_CHANGED`.
  - PIN reset and unlock take no `expectedVersion` (absolute, idempotent-in-effect actions behind a confirm).
- **All writes inside a writer transaction go through entity mutators on rows loaded by a locking query.** The first load of a row in a writer transaction must be the locking query (a row already in the persistence context is not refreshed by a later lock query). Because the row is held `FOR UPDATE` from load to commit, Hibernate's full-column UPDATE can never overwrite a concurrent atomic update (`recordFailedLogin` waits for the lock). `token_version` is bumped with a new entity mutator `Employee.endSessions()` in the same UPDATE, not with the bulk `incrementTokenVersion` (a later entity flush with full columns would overwrite a bulk bump). `incrementTokenVersion` stays for logout.
- **Lock timeout:** every writer transaction starts with `select set_config('lock_timeout', <app.time-entry.lock-timeout>, true)` (extracted from `ReviewWriter` into `AdminLocks.apply(entityManager)`); a timeout gives 503 `SERVER_BUSY` through the existing handler.
- **Lock order (always this order; never the reverse). Revised 2026-11-03 (critic finding 2):** the original order ignored the `FOR KEY SHARE` lock that every `audit_log` insert takes on the **actor's** employee row (`fk_audit_log_actor_company`) and every `adjustment` insert takes on the admin row. That lock conflicts with a `FOR UPDATE` on the same row, which gave real cycles: branch deactivate (branch `FOR UPDATE`, then audit FK on actor A) vs another admin assigning A to that branch (A `FOR UPDATE`, then branch `FOR SHARE`); an admin editing a branch while their own PWA clock-in holds their row (`FOR UPDATE`, then `time_entry` FK on the branch); two admins resetting / unlocking / editing schedules of each other (each holds the other's row `FOR UPDATE` and needs `KEY SHARE` on its own). The last one already exists in 4a (`ReviewWriter.java:213` locks the target employee, `:231` inserts the adjustment whose `admin_id` FK points at the actor). New order:
  1. Company admin set (only employee PUT / activate / deactivate): native `select id from employee where company_id = :companyId and role = 'ADMIN' and active = true order by id for no key update`. Ordered by id so two such transactions never interleave. `FOR NO KEY UPDATE` does not conflict with the `FOR KEY SHARE` of step 2 or of FK checks.
  2. **Actor and target employee rows together, in ascending id order** (`AdminLocks.lockActorAndTarget(companyId, actorId, targetId)`): the actor row `FOR KEY SHARE` (native `select role, active from employee where id = :actorId and company_id = :companyId for key share`, a projection, so the actor entity is not put into the persistence context) and the target row `FOR UPDATE` (`findWithLockByIdAndCompanyId`, same lock as the PWA POST). When actor = target, only the `FOR UPDATE`. Writers without a target employee (branch create / edit / activate / deactivate) take only the actor `FOR KEY SHARE`. Because the actor row is locked before any other row, the later audit / adjustment FK check on it never waits.
  3. Branch: `FOR SHARE` (`@Lock(PESSIMISTIC_READ) findWithShareLockByIdAndCompanyId`) when an employee is created, assigned to a branch, or activated; `FOR UPDATE` (`findWithLockByIdAndCompanyId`) on branch edit / activate / deactivate.
  - Then the time entry (`ReviewWriter` only).
  - `ReviewWriter` (4a) adopts step 2 (actor + target in id order, then the time entry); behaviour otherwise unchanged.
  - No transaction takes an employee row after a branch lock. The PWA POST holds only its own employee row, then the FK `KEY SHARE` on its branch; a branch writer whose actor is that same employee waits at step 2 (before taking the branch), so the self clock-in vs branch edit cycle is gone.
  - **Residual deadlocks** (paths not covered above, e.g. a future writer that breaks the order, or operator SQL) end in PostgreSQL `40P01`; Spring translates it to `CannotAcquireLockException` (a `PessimisticLockingFailureException`), and the existing `ApiExceptionHandler.lockTimeout` answers 503 `SERVER_BUSY` + `Retry-After`. A test asserts 503, never 500 (Tests, `ManagementLockOrderIntegrationTest`).
  - **Deactivate vs assign race** (READ COMMITTED): if the deactivation holds the branch first, the assignment's `FOR SHARE` waits, then re-reads the committed inactive row and gives `BRANCH_INACTIVE`; if the assignment holds `FOR SHARE` first, the deactivation waits and its `count` (a new statement) sees the committed assignment and gives `BRANCH_IN_USE`. Invariant kept: **no active employee is ever assigned to an inactive branch.**
  - Known, accepted side effect: a branch edit/deactivate (`FOR UPDATE`) briefly delays clock-ins at that branch (the `time_entry` FK check); these are rare, millisecond transactions.

### D3. Guards (employee writes), evaluated in this order after the locks
1. Target not in the company: 404 (`ResourceNotFoundException`).
2. `expectedVersion` differs: 409 `RECORD_CHANGED`.
3. Validation that needs the DB: branch exists in the company (else 404), branch active when it changes or when activating (`BRANCH_INACTIVE`), role `EMPLOYEE` requires a branch (`BRANCH_REQUIRED`).
4. Self: actor = target and (new role is not ADMIN, or deactivate): 409 `SELF_CHANGE_NOT_ALLOWED`.
5. Last admin: if the change removes the target from the locked active-admin set and the set would become empty: 409 `LAST_ADMIN`. (In normal use this is unreachable because the actor is an active admin; it is the answer in the "two admins demote each other" race, where the second transaction sees a set that no longer contains its actor.)
6. Actor re-check: the actor must be in the locked active-admin set; otherwise an empty 403 (`AccessDeniedException`, already mapped). Covers in-flight requests of an admin who was demoted or deactivated a moment earlier.
7. No-op (nothing changes): 200 with the current view, no audit row, no version bump, no `token_version` bump.

**Actor re-check in every writer (critic finding 3).** Not only employee PUT / activate / deactivate: every management writer (employee create, PIN reset, unlock, schedule replace, branch create / edit / activate / deactivate) and `ReviewWriter` read the actor row at D2 step 2 and require `active = true` and `role = 'ADMIN'`; otherwise an empty 403 and nothing is written. This is the first check after the locks (before 404 of the target), so a demoted admin learns nothing about other rows. Precision: for the three privilege writes the admin-set lock (`FOR NO KEY UPDATE`) makes the check exact; elsewhere the `FOR KEY SHARE` read sees the latest committed row, so a demotion committing in the same millisecond behaves like one committed just after the write (accepted: the demotion bumps `token_version`, so the next request is 401). **Hashing window:** create and PIN reset hash the new PIN (~250 ms) in the facade before the transaction; the actor is re-checked after hashing, inside the transaction, so a demotion during hashing still gives 403 and the hash is discarded.

**PIN reset of another ADMIN (Q14).** In the PIN-reset writer, after the locks and the actor check: if target != actor, target `role = ADMIN`, target `active = true` and target **not locked** (`locked_until` null or <= now): 409 `ADMIN_PIN_RESET_NOT_ALLOWED`, nothing written, no audit row. Allowed: self reset; an inactive admin (prepares a re-activation); a locked admin (the lockout case of Stage 1 M-3). Reason: a single compromised admin session must not be able to take over another working admin account silently. Known residual path: an admin can first demote another admin (audited `EMPLOYEE_ROLE_CHANGE`, the other admin's session ends at once and they notice) and then reset the PIN; this is visible and accepted. Unlock of another admin stays allowed (it only clears a lock).
- Rules on the other paths: activate an already active / deactivate an already inactive employee or branch with the matching version is a no-op 200 (the UI never offers it). Changing the branch of an employee whose **current** branch is inactive is allowed only to an active branch; editing other fields while keeping an inactive current branch is allowed (inactive employees may still point at a retired branch).

### D4. What changes `token_version`
| Change | Bump | Why |
|---|---|---|
| Role change (either direction) | yes | privilege change takes effect now (the per-request check already reads the role; the bump also forces a fresh token with the right TTL) |
| Deactivate | yes | already rejected by the `active` check; the bump makes sure that **re-activating** does not revive old tokens still inside their TTL |
| Activate | no | sessions were ended at deactivation |
| PIN reset | yes | all sessions end (umbrella) |
| Unlock, name, job title, branch, schedule | no | not a credential or privilege change |

### D5. PIN generation and handling
- `auth/PinGenerator` (Spring `@Component`): `nextPin()` draws 8 digits from an injected `RandomGenerator` (production: `new SecureRandom()`, non-blocking default), leading zeros allowed, loops until `PinPolicy.isValid(pin)`; overload `nextPin(String employeeCode)` also rejects a PIN equal to the code. Tests inject a seeded `Random`.
- The writer receives the **already hashed** PIN: the facade generates and BCrypt-hashes it (strength 12, about 250 ms) **before** opening the transaction, so no lock is held during hashing.
- Responses carrying a PIN (`201 create`, `200 pin-reset`) set `Cache-Control: no-store` (and `Pragma: no-cache` is not needed). The PIN is never logged (no request/response body logging exists; the writer logs ids only), never in `audit_log.details`, never in a ProblemDetail, never stored in plain text.
- PIN reset may target oneself (warning in the UI; the response still contains the PIN; the caller's token is already invalid after commit, so the client clears its session **locally** without calling the logout API, which would 401). PIN reset of an inactive employee is allowed (prepares a re-activation). PIN reset of another active, unlocked ADMIN is refused (D3, Q14).
- **The reveal response is the only place a PIN ever leaves the server** (D14). The PIN is not part of `AdminEmployeeView`, of any ProblemDetail (a failed create / reset returns no PIN, and the generated PIN is discarded), of `audit_log` (`details`, `reason_code`, `reason_note`) or of any log line. The facade keeps the PIN in a local variable only; the response record overrides `toString()` to mask it (`pin=********`) so an accidental `log.debug("{}", response)` cannot print it.
- **Self reset in the client (critic finding 4):** after commit the caller's token is invalid, so the 60 s shell poll (`AdminLayout.tsx:52-63`) or any other request would 401, `api/client.ts:61` would call `markNeedsReauth()`, and `AdminLayout.tsx:68` would open the `ReauthController` dialog over `PinRevealDialog`. Rule: when the target is the current user, the page calls `shell.suspendSession(true)` **before** sending the reset; while suspended the shell stops polling (no `refreshPending`, the visibility listener ignored) and does not open the re-auth dialog or the banner even if `session.needsReauth` becomes true. On close of the PIN dialog the session is cleared locally and the app goes to `/login`; on a failed reset `suspendSession(false)` restores normal behaviour.

### D6. Text rule (L-1) and field normalization
- `common/TextRules`:
  - `SAFE_TEXT = "^[^\\p{Cc}\\p{Cf}\\p{Cs}\\p{Zl}\\p{Zp}]*$"` for `@Pattern`.
  - `clean(String)`: `null` stays `null`; otherwise `Normalizer.normalize(s, NFC).strip()`. Called in the compact constructors of every request record **before** Bean Validation runs (the 4a `ReviewRequests.trim` pattern), so lengths are measured after normalization.
  - `cleanOptional(String)`: like `clean`, and an empty result becomes `null` (job title).
- NFC runs **before** validation and storage (Q7).
- Applied to: `ReviewRequests` reason (Action, Correct, Create: replaces `NO_CONTROL_CHARACTERS`), employee `name` (1-120), `jobTitle` (0-80), branch `name` (1-120), PIN-reset `note` (0-200, `cleanOptional`, plus the digit-run rule of D13).
- Employee `code`: `strip().toUpperCase(Locale.ROOT)` then `^[A-Z0-9-]{1,20}$` (same normalization as `LoginService`).
- Any violation is the existing no-echo 400 `INVALID_REQUEST`. The client blocks it first with an inline message (`admin.text.invisibleChars`).
- Accepted consequence: emoji sequences joined with ZWJ (U+200D, `Cf`) are refused in names and reasons; single emoji (`So`) pass.

### D7. Rate limits (L-2, L-4, account)
| Limiter (per `companyId:employeeId`) | Scope | Default | Where it is checked | Code |
|---|---|---|---|---|
| `reviewWriteRateLimiter` (new, Q2) | review actions and admin-created entries: `POST /api/admin/time-entries` and `POST /api/admin/time-entries/{id}/approve`, `/reject`, `/correct` | **300 / 10 min** | `config/AdminWriteRateLimitFilter` (same place as below) | 429 `RATE_LIMITED` |
| `adminWriteRateLimiter` (new) | every other `POST`, `PUT`, `PATCH`, `DELETE` under `/api/admin/` by an authenticated ADMIN (all 4b management writes, 4c later) | **120 / 10 min** | `config/AdminWriteRateLimitFilter`, after `BearerTokenAuthenticationFilter`, before authorization and the dispatcher (so before binding, validation and any write) | 429 `RATE_LIMITED` |
| `detailRateLimiter` (new, L-2) | `GET /api/admin/time-entries/{id}` | 300 / 10 min | controller, before the lookup (photo pattern) | 429 `RATE_LIMITED` |
| `accountRateLimiter` (exists, now wired) | `POST /employees` and `POST /employees/{id}/pin-reset` | 30 / 10 min (Q3) | controller, before PIN generation | 429 `RATE_LIMITED` |
| `photoRateLimiter` (exists) | photo | 300 / 10 min | unchanged | switches to `RATE_LIMITED` (Q1) |
- **Budget choice (Q2):** the filter picks exactly **one** write budget per request by path: the review paths above (matched exactly; `/api/admin/time-entries/{id}` must be a numeric id segment) use the review budget, everything else the general one. A review action never consumes the general budget, so clearing a backlog of 300 approvals one by one (umbrella: approve "one by one") does not block branch or employee management, and vice versa. Both are separate `FixedWindowRateLimiter` instances keyed `companyId:employeeId`.
- Every attempt counts, successful or not (the established `FixedWindowRateLimiter` semantics; Q2). A create or PIN reset counts against both the general write and the account limiter.
- The write filter applies only when the authenticated principal's role is ADMIN; an EMPLOYEE token on an admin write is not counted and still gets the empty 403 from the URL rule.
- `Retry-After` = the window in seconds (existing `retryAfter()`), localized ProblemDetail resolved in the filter exactly like `TimeEntryRateLimitFilter`.
- `RateLimitedException(Duration)` (new, `common`) + handler -> 429 `RATE_LIMITED` + `Retry-After`. `TOO_MANY_ATTEMPTS` stays for login and the PWA POST.
- Config (all configurable): `app.admin.rate-limit.write: {max-attempts: 120, window: 10m}`, `review-write: {max-attempts: 300, window: 10m}`, `detail: {max-attempts: 300, window: 10m}`. `application-test.yml` raises `write`, `review-write` and `account` to 100000 (limiter tests use their own context with small values), like the existing time-entry limiter.
- A rejected write logs INFO `admin_write_rate_limited budget={general|review} actor={} company={}` (ids only). Alerting is Stage 5.

### D8. Location view audit (L-2)
- `AdminTimeEntryController.detail`: limiter -> `detailService.detail(...)` (read-only transaction) -> if `response.location() != null`: `auditService.record(companyId, actor, LOCATION_VIEW, TIME_ENTRY, id, "entry=" + id)` (the existing `REQUIRES_NEW` method, after the read, as for photos) and INFO `location_access actor={} entry={} company={}`. If the audit insert fails, the detail is not served (fail closed, like photos).
- Only this GET is audited. The detail returned by the 4a write endpoints (approve/reject/correct/create) is not: those already write `adjustment` rows naming the admin and the entry, and they are now write-limited.
- Entries without coordinates (`source = ADMIN`, photo-only) give no audit row.

### D9. Audit rows for management actions
Written with a new `AuditService.recordInTransaction(...)` (`Propagation.MANDATORY`), inside the writer transaction, after the change; a rolled-back change leaves no audit row. `details` holds ids, enum values and field names only.

| Action | `action` | `target_type` / `target_id` | `details` example |
|---|---|---|---|
| Create employee | `EMPLOYEE_CREATE` | `EMPLOYEE` / new id | `role=EMPLOYEE,branch=12` |
| Edit name / job title / branch | `EMPLOYEE_UPDATE` | `EMPLOYEE` / id | `fields=name,branchId,branch=3->5` |
| Role change | `EMPLOYEE_ROLE_CHANGE` | `EMPLOYEE` / id | `role=EMPLOYEE->ADMIN` |
| Deactivate / activate | `EMPLOYEE_DEACTIVATE` / `EMPLOYEE_ACTIVATE` | `EMPLOYEE` / id | null |
| PIN reset | `PIN_RESET` | `EMPLOYEE` / id | null (never the PIN); `reason_code` / `reason_note` per D13 |
| Unlock (something was cleared) | `EMPLOYEE_UNLOCK` | `EMPLOYEE` / id | null |
| Schedule replaced (something changed) | `SCHEDULE_UPDATE` | `EMPLOYEE` / employee id | `days=1,2,3,4,5` (the resulting set) |
| Create branch | `BRANCH_CREATE` | `BRANCH` / new id | null |
| Edit branch | `BRANCH_UPDATE` | `BRANCH` / id | `fields=name,latitude,longitude,radiusMeters` (names only, never values) |
| Deactivate / activate branch | `BRANCH_DEACTIVATE` / `BRANCH_ACTIVATE` | `BRANCH` / id | null |
| Entry detail with location (L-2) | `LOCATION_VIEW` | `TIME_ENTRY` / id | `entry=ID` |

- One PUT that changes the role and the name writes 2 rows (`EMPLOYEE_ROLE_CHANGE`, `EMPLOYEE_UPDATE`).
- Not audited: GET lists/details of employees, branches and schedules (no personal data beyond names and the admin already sees them on the dashboard), no-ops, failed attempts (the limiters and logs cover abuse).
- The schedule uses `target_type EMPLOYEE` (its rows' ids change; "everything done to employee X" is one query). `WORK_SCHEDULE` stays allowed by the CHECK but unused.

### D10. Schedule replace without a unique violation
- Request: `{ expected: Day[], days: Day[] }`, each `Day = {dayOfWeek 1-7, startTime "HH:mm", endTime "HH:mm", graceMinutes 0-240}`; both arrays 0-7 items with unique `dayOfWeek`; `startTime != endTime` (end < start = overnight).
- Writer: lock employee -> compare `expected` -> for each requested day: existing row -> `WorkSchedule.change(start, end, grace)`; missing -> insert; days present now but not requested -> `@Modifying @Query("delete from WorkSchedule w where w.companyId = :companyId and w.employeeId = :employeeId and w.dayOfWeek in :days")` (bulk, `flushAutomatically = true`). No delete-then-insert of the same day, so the Hibernate flush order cannot break `uq_work_schedule_employee_day`.
- Stored `late_minutes` never change; the dashboard's today rows (`NOT_YET_DUE`, `NOT_CLOCKED`) use the new schedule immediately; a later CLOCK_IN correction recomputes lateness with the current schedule (umbrella, unchanged).
- Allowed for inactive employees and for admins without a branch (harmless; they cannot clock in until assigned).

### D11. Lists
- `GET /api/admin/employees` and `/branches` stay unpaged with **no hard cap** in 4b (deviation 3). Target companies have well under 1000 people; pagination is a "Later" item.
- `hasSchedule` from one batched query `select distinct w.employeeId from WorkSchedule w where w.companyId = :companyId`; `branchName` from the branch list; `locked = lockedUntil != null && lockedUntil > now` (injected `Clock`), `lockedUntil` returned only when locked; `consentGiven = consentAt != null`.

### D12. Frontend navigation guard (no `useBlocker`)
- `AdminShell` gains `setUnsavedChanges(key, boolean)` / `confirmLeave(): Promise<boolean>`. While the schedule editor is dirty: a `beforeunload` listener is active, and the admin nav links, the "‹ Empleados" back link and the header links call `confirmLeave()` (the design's `ConfirmDialog` "¿Salir sin guardar?") before navigating. The browser Back button and typed URLs are not intercepted (documented; migrating to a data router is out of scope). Confirmed by the user (Q10).
- `AdminShell` also gains `suspendSession(boolean)` for the self PIN reset (D5).

### D13. PIN-reset reason (Q4, user decision 2026-11-03)
- **Optional**, never required. The reset dialog offers a preset (radio group, "Sin motivo" selected by default) and an optional note:
  | Code | ES | EN |
  |---|---|---|
  | `FORGOT_PIN` | Olvidó el PIN | Forgot PIN |
  | `ACCOUNT_LOCKED` | Cuenta bloqueada | Account locked |
  | `POSSIBLE_MISUSE` | Posible uso indebido | Possible misuse |
  | `OTHER` | Otro | Other |
- **Storage: a code column plus a note column** on `audit_log` (V5), not one free-text `reason`:
  - `reason_code varchar(30) NULL` with `ck_audit_log_reason_code CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z_]{3,30}$')` (format check like `ck_audit_log_action`; the Java enum `PinResetReason` is the allow-list, so a new code needs no migration).
  - `reason_note varchar(200) NULL` with `ck_audit_log_reason_note CHECK (reason_note IS NULL OR (reason_code IS NOT NULL AND btrim(reason_note) <> ''))`.
  - Why two columns: the code is language-neutral (shown in ES or EN by the reader, countable for "how many misuse resets"), the note is the rare explanation; one free-text column could not be localized or counted and would invite PII.
  - Generic names (`reason_code`, `reason_note`) so later actions (deactivate, role change) can reuse them without another migration; in 4b only `PIN_RESET` writes them.
- **Request:** `POST /pin-reset` body `{reasonCode?: PinResetReason, note?: string}`; empty body / `{}` still accepted (no reason). A `note` without a `reasonCode` gives 400 (`INVALID_REQUEST`; the UI enables the note only after a preset is chosen). Unknown `reasonCode` gives 400.
- **Note rules:** `TextRules.cleanOptional` (NFC + strip, empty -> null), `SAFE_TEXT` (L-1), 1-200 after cleaning, and the **PII digit rule** of D14: refuse any run of 4 or more digits, counting digits separated only by spaces, dots, dashes or slashes (`\p{Nd}(?:[\s./-]*\p{Nd}){3,}`). This keeps a PIN (8 digits, shown as 4+4), a cedula (9 digits, often written `1-2345-6789`), a phone (8 digits) and a social-security number out of the note. Cost, accepted: a note cannot contain a year or a ticket number; the UI hint says so. Violations give the no-echo 400 `INVALID_REQUEST`; the client blocks them first with inline messages.
- The PIN is never put into the reason (the server generates the PIN after validating the request and never concatenates it anywhere; a test asserts the stored note equals the submitted cleaned note and never contains the returned PIN).
- **Where it is shown:** there is no audit viewer in 4b. It is retrievable and shown in one place: `GET /api/admin/employees/{id}` returns `lastPinReset: {at, byName, reasonCode, note} | null` (latest `PIN_RESET` row for the target, by `created_at desc, id desc`, from `AuditLogRepository.findFirstByCompanyIdAndActionAndTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc`); the Access panel shows "Último restablecimiento de PIN: {date} por {name} · {reason}" with the note below. Only in the detail (not in the list). Admin-only by the URL rule (D14 visibility). A full audit history screen and an audit export are **not planned yet** (the umbrella has no audit viewer; 4c's export is the attendance report): the main session adds "Audit history screen (lists all `audit_log` rows incl. PIN-reset reasons)" as a new backlog step in `docs/PROGRESS.md` after Stage 4.

### D14. PII guardrail (user requirement 2026-11-03, also in CLAUDE.md)
**PII here** = PIN; national id (cedula); phone; social-security number; full name; and the **employee code** (companies may use the cedula as the code).
- **Inventory (checked 2026-11-03):** the schema has no cedula, phone or social-security column (grep of `backend/src/main` for phone / cedula / national id / ssn: only comments about "the phone" device). 4b adds **none**. Any such field added later follows this rule (admin + owner visibility, never in messages, logs, audit, URLs). The PII 4b handles: name, employee code, PIN.
- **PIN:** returned only in the one-time reveal response (`201 create`, `200 pin-reset`) to the admin who requested it, with `Cache-Control: no-store`; never cached (no service-worker route for `/api/`, unchanged), never logged, never in an error, warning, audit row or test output (tests assert on PIN validity and login, and never print the value: no `assertEquals(expected, pin)` message strings with the PIN, no `System.out`). This is the single allowed path and it is tested (Tests).
- **Visibility of names and codes:** only the owner (own data: `/api/me`, the PWA greeting) or an ADMIN of the same company (`/api/admin/**`, URL rule + `@PreAuthorize`, tenant-scoped). An EMPLOYEE token gets an empty 403 on every `/api/admin/**` read; no employee endpoint returns another employee's name or code (tested).
- **Never in error or warning messages:** every 4b ProblemDetail is a fixed localized text with no arguments (`EMPLOYEE_CODE_TAKEN` says "Ese código ya está en uso", never the code; 400s are the no-echo handler); the client shows the field error under the field without repeating the value. UI warnings and confirm texts may name the employee **only inside the admin panel** (already the case in 4a: `admin.evidence.title`, `admin.create.doneIn`), never in a `role="alert"` error that comes from the server, never in `document.title`.
- **Never in logs:** ids only. `LoginService` (Stage 1) currently logs `employee.getCode()` and `company.getCode()` on 4 warning lines (`LoginService.java:69-70, 74-75, 82-83, 90-91`); 4b changes them to `employee={id}` and `company={id}` (the company code is not PII but is changed for consistency; the IP stays). SQL error details are suppressed (D15). The catch-all `ApiExceptionHandler.unexpected` keeps logging the exception, which is safe once D15 is in place; the coder checks no 4b exception message is built from a name or code.
- **Never in audit rows:** `details` holds ids, enum values and field names; `reason_note` refuses digit runs and the UI says "No escriba datos personales (cédula, teléfono, PIN)". A name typed in a note cannot be detected reliably; the UI guidance and admin-only visibility are the mitigation (accepted).
- **Never in URLs or query strings:** pages and API calls carry ids only (4a already does: `?employee=<id>`, `?branch=<id>`). The new employee search box keeps its text in component state, **not** in `useSearchParams`; codes and names never appear in a path.
- **Other existing places checked:** 4a/Stage 2-3 server messages have no `{0}` arguments (messages bundles); `TimeEntryWriter`, `ReviewWriter`, `AuthController`, photo logs use ids only; frontend `console.warn` calls log error names only; `DevDataSeeder.java:126` logs the fixed demo codes `1001-1012, 9001` in the dev profile only (fixed fake data, exempt; documented).

### D15. SQL error details never reach logs (critic finding 1)
- Problem: PgJDBC's `logServerErrorDetail` defaults to `true`, so a unique violation carries `Detail: Key (company_id, code)=(1, 2001) already exists` (a cedula-like code), a check violation can carry the failing row (incl. `name` and `pin_hash` = `$2a$...`), and Hibernate's `SqlExceptionHelper` logs it at ERROR before the facade maps it; the facades' `log.error(..., e)` (`ReviewService.java:50`, `TimeEntryService.java:79`) print it again.
- Fix: `spring.datasource.hikari.data-source-properties.logServerErrorDetail: false` in `application.yml` (applies whatever `DB_URL` is; also add `?logServerErrorDetail=false` to the default URL for tools that bypass Hikari). `logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper: OFF` (the facades and the handler log what is needed). The facades log `constraint=<name> class=<exception class>` instead of the exception's message.
- Constraint mapping keeps working: the primary message still names the constraint; the facades prefer `PSQLException.getServerErrorMessage().getConstraint()` when the cause is a `PSQLException` and fall back to `contains(name)` on the message.

### D16. Inactive branch at clock-in (Q6)
- `TimeEntryWriter` and `TodayService` treat an employee whose branch has `active = false` exactly like an employee with no branch: 403 `NO_BRANCH_ASSIGNED`, `blockedReason NO_BRANCH_ASSIGNED`. Defense in depth for operator SQL; the lock protocol already keeps the invariant.

## Deviations from the umbrella plan (this plan wins for 4b)
1. **`RECORD_CHANGED`** (new) and **`expectedVersion`** on edit/activate/deactivate bodies; activate/deactivate now have a JSON body. The umbrella had no concurrency control for management.
2. **Schedule PUT body** is `{expected, days}`, not a bare array; `GET` still returns the bare sorted array.
3. **No hard cap** on the employee/branch lists (umbrella: "cap 1000 / 200" with no behaviour defined).
4. **PIN-reset response** is `{employee, pin}` (umbrella: `{pin}`), so the page refreshes without a second GET.
5. **`AdminEmployeeController` moves** to `cr.attendance.admin` (umbrella left it to the coder).
6. **429 code `RATE_LIMITED`** for the per-admin limiters (umbrella: `TOO_MANY_ATTEMPTS`), per the adopted L-4 amendment.
7. **Schedule audit target** is `EMPLOYEE` (umbrella's enum lists `WORK_SCHEDULE`).
8. **Derived delete** in the umbrella step 25 replaced by an in-place update + bulk delete (D10).
9. **`token_version` bump via the `Employee.endSessions()` mutator** (umbrella step 25 / line 875: "via `incrementTokenVersion`"): the row is locked and later flushed with all columns, which would overwrite a bulk bump (D2).
10. **Native `FOR NO KEY UPDATE` admin-set query** instead of `@Lock findAllByCompanyIdAndRoleAndActiveTrue` (umbrella line 879): `@Lock(PESSIMISTIC_WRITE)` emits `FOR UPDATE`, which blocks the FK `KEY SHARE` of concurrent 4a review actions and photo audits of every admin.
11. **Entity mutators** `resetPin` / `unlock` instead of atomic `@Modifying` `updatePinHash` / `unlock` queries (umbrella line 879 left the choice): the row is already locked `FOR UPDATE`, and one style per field avoids a bulk update being overwritten by the entity flush.
12. **`AdminScheduleWriter`** instead of `AdminScheduleService` (umbrella line 878): it is the transactional writer of the facade/writer pattern; no facade is needed (D1).
13. **Two write budgets** (general 120, review 300 per 10 min) instead of one write limit (Q2).
14. **`audit_log.reason_code` / `reason_note`** (V5) and an optional PIN-reset reason (Q4); the umbrella had no reason field for management actions.
15. **`ADMIN_PIN_RESET_NOT_ALLOWED`** (new): another active, unlocked admin's PIN cannot be reset (Q14).
16. **Actor + target locked together before any branch lock**, also in the 4a `ReviewWriter` (critic finding 2).
17. **`lastPinReset`** in the employee detail response (Q4 visibility).

## Data model changes

### `V5__management_versions.sql` (new; V1-V4 untouched)

```sql
-- Stage 4b: optimistic concurrency for admin edits. The writer locks the row and compares the version the
-- admin loaded; JPA @Version increments it. Credential, lock and consent changes do not touch it.
ALTER TABLE employee ADD COLUMN version integer NOT NULL DEFAULT 0;
ALTER TABLE branch   ADD COLUMN version integer NOT NULL DEFAULT 0;

-- Names are trimmed by the app; the database refuses blank ones too (company already has this check).
ALTER TABLE employee ADD CONSTRAINT ck_employee_name CHECK (btrim(name) <> '');
ALTER TABLE branch   ADD CONSTRAINT ck_branch_name   CHECK (btrim(name) <> '');

-- Optional reason of an admin action (4b: PIN reset only). Code is language-neutral; the note is short
-- free text that the app keeps free of PINs and other digit runs. Never a PIN, a name or a code.
ALTER TABLE audit_log ADD COLUMN reason_code varchar(30)  NULL;
ALTER TABLE audit_log ADD COLUMN reason_note varchar(200) NULL;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_reason_code
    CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z_]{3,30}$');
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_reason_note
    CHECK (reason_note IS NULL OR (reason_code IS NOT NULL AND btrim(reason_note) <> ''));
```

Notes:
- The `audit_log` ALTERs run in the same migration; the append-only triggers fire on UPDATE/DELETE/TRUNCATE only, so `ADD COLUMN` (nullable, no default, no row rewrite) and `ADD CONSTRAINT` (validation scans, does not update) are not blocked. Existing rows get NULL in both columns.
- `SchemaEnglishNamingTest`: constraints 51 -> **55** (`ck_employee_name`, `ck_branch_name`, `ck_audit_log_reason_code`, `ck_audit_log_reason_note`; rename the test method); indexes unchanged (9). `SchemaMigrationTest`: versions include `"5"`.
- Existing rows: the seed and test data have non-blank names; the coder greps `TestData` / test SQL for blank names before running.
- `AuditAction.LOCATION_VIEW` needs no DDL (`ck_audit_log_action` is a format regex; `TIME_ENTRY` is an allowed target type).
- The ER delta (four columns, four checks) goes to the architect (hand-off).
- `AuditLogEntry` gains `reasonCode` / `reasonNote` (nullable, lengths 30 / 200, constructor overload). `AuditService.recordInTransaction(companyId, actorId, action, targetType, targetId, details, reasonCode, reasonNote)`; the photo/location `record` passes nulls. `AuditLogRepository` gains the `findFirst...` query of D13 (has `companyId`).

### Entities and repositories
- `Employee`: `@Version Integer version`; `@OptimisticLock(excluded = true)` on `pinHash`, `failedAttempts`, `lockedUntil`, `tokenVersion`, `consentAt`; mutators `updateProfile(name, jobTitle)`, `assignBranch(Long)`, `changeRole(Role)`, `activate()`, `deactivate()`, `endSessions()` (token_version + 1), `resetPin(String hash)` (hash, failed_attempts 0, locked_until null, `endSessions()`), `unlock()` (returns whether anything changed). Update the class comment on lock fields ("atomic queries, or a mutator while the row is locked").
- `Branch`: `@Version Integer version`; `update(name, lat, lng, radius)` (returns changed field names), `activate()`, `deactivate()`.
- `WorkSchedule`: `change(start, end, grace)`.
- `EmployeeRepository` (all with `companyId`): native `lockActiveAdminIds(companyId)` (D2 step 1, returns `List<Long>`); native `lockActorForKeyShare(id, companyId)` (D2 step 2, returns a `{role, active}` projection); `existsByCompanyIdAndCode(companyId, code)`; `countByCompanyIdAndBranchIdAndActiveTrue(companyId, branchId)`.
- `BranchRepository`: `@Lock(PESSIMISTIC_WRITE) findWithLockByIdAndCompanyId`, `@Lock(PESSIMISTIC_READ) findWithShareLockByIdAndCompanyId`, `existsByCompanyIdAndNameIgnoreCase(companyId, name)`, `existsByCompanyIdAndNameIgnoreCaseAndIdNot(companyId, name, id)`.
- `WorkScheduleRepository`: `findEmployeeIdsWithSchedule(companyId)` (`@Query select distinct`), bulk `deleteByCompanyIdAndEmployeeIdAndDayOfWeekIn(...)` as `@Modifying(flushAutomatically = true) @Query`.
- `AuditService.recordInTransaction(...)` (`MANDATORY`); the `REQUIRES_NEW` `record` stays for photo/location views.
- `TenantRepositoryConventionTest` needs no list change (no new repository; check that `AuditLogRepository` is already listed, add it if not) but must stay green: every new method's parameter is literally named `companyId` (`@Param("companyId")` on the native and JPQL queries).

## API

All under `/api/admin/**`: URL rule + class-level `@PreAuthorize("hasRole('ADMIN')")`; `companyId` and the actor only from `EmployeePrincipal`; another tenant's id (path or body) gives 404; explicit request records, unknown properties ignored without effect (`companyId`, `pinHash`, `tokenVersion`, `active`, `version`, `code` on update, `failedAttempts`). Every 400 uses the existing no-echo handler. All writes are counted by the write limiter.

### Branches (`admin.AdminBranchController`, extended)
| Method + path | Body | Response | Errors |
|---|---|---|---|
| `GET /api/admin/branches` (exists) | - | `BranchView[]` + `version` | - |
| `POST /api/admin/branches` | `{name, latitude, longitude, radiusMeters}` | 201 `BranchView` | 400, 409 `BRANCH_NAME_TAKEN` |
| `PUT /api/admin/branches/{id}` | `{name, latitude, longitude, radiusMeters, expectedVersion}` | 200 `BranchView` | 400, 404, 409 `RECORD_CHANGED` / `BRANCH_NAME_TAKEN` |
| `POST /api/admin/branches/{id}/deactivate` | `{expectedVersion}` | 200 `BranchView` | 404, 409 `RECORD_CHANGED` / `BRANCH_IN_USE` |
| `POST /api/admin/branches/{id}/activate` | `{expectedVersion}` | 200 `BranchView` | 404, 409 `RECORD_CHANGED` |

Validation: name `TextRules`, 1-120; `latitude`/`longitude` `@NotNull`, `Double.isFinite` (an `@AssertTrue` method on the record, since `"NaN"` strings coerce), -90..90 / -180..180; `radiusMeters` `@NotNull` 10-5000. Name uniqueness: case-insensitive precheck in the writer (`BRANCH_NAME_TAKEN`), `uq_branch_company_name` (case-sensitive) as the DB backstop mapped by the facade. Editing location or radius affects future entries only.

### Employees (`admin.AdminEmployeeController`, moved and extended)
| Method + path | Body | Response | Errors |
|---|---|---|---|
| `GET /api/admin/employees` | - | `AdminEmployeeView[]` (sorted by name) | - |
| `GET /api/admin/employees/{id}` | - | `AdminEmployeeDetail` (= view + `lastPinReset`) | 404 |
| `POST /api/admin/employees` | `{code, name, jobTitle?, role, branchId?}` | 201 `{employee: AdminEmployeeView, pin}`, `no-store` | 400, 404 (branch), 409 `EMPLOYEE_CODE_TAKEN` / `BRANCH_INACTIVE`, 400 `BRANCH_REQUIRED`, 429 |
| `PUT /api/admin/employees/{id}` | `{name, jobTitle?, role, branchId?, expectedVersion}` | 200 `AdminEmployeeView` | 400, 404, 409 `RECORD_CHANGED` / `BRANCH_INACTIVE` / `SELF_CHANGE_NOT_ALLOWED` / `LAST_ADMIN`, 400 `BRANCH_REQUIRED`, 403 |
| `POST /api/admin/employees/{id}/deactivate` | `{expectedVersion}` | 200 `AdminEmployeeView` | 404, 409 `RECORD_CHANGED` / `SELF_CHANGE_NOT_ALLOWED` / `LAST_ADMIN`, 403 |
| `POST /api/admin/employees/{id}/activate` | `{expectedVersion}` | 200 `AdminEmployeeView` | 404, 409 `RECORD_CHANGED` / `BRANCH_INACTIVE` |
| `POST /api/admin/employees/{id}/pin-reset` | optional `{reasonCode?, note?}` (none / `{}` = no reason) | 200 `{employee: AdminEmployeeDetail, pin}`, `no-store` | 400 (note rules, note without code, unknown code), 403 (actor), 404, 409 `ADMIN_PIN_RESET_NOT_ALLOWED`, 429 |
| `POST /api/admin/employees/{id}/unlock` | none | 200 `AdminEmployeeView` | 404 |
| `GET /api/admin/employees/{id}/schedule` | - | `Day[]` sorted by `dayOfWeek` | 404 |
| `PUT /api/admin/employees/{id}/schedule` | `{expected: Day[], days: Day[]}` | 200 `Day[]` | 400, 404, 409 `RECORD_CHANGED` |

```ts
type AdminEmployeeView = { id: number; code: string; name: string; jobTitle: string | null; role: Role;
  active: boolean; branchId: number | null; branchName: string | null; branchActive: boolean | null;
  consentGiven: boolean; locked: boolean; lockedUntil: string | null; hasSchedule: boolean; version: number }
type BranchView = { id: number; name: string; latitude: number; longitude: number; radiusMeters: number;
  active: boolean; activeEmployees: number; version: number }
type ScheduleDay = { dayOfWeek: 1 | 2 | 3 | 4 | 5 | 6 | 7; startTime: string; endTime: string; graceMinutes: number }
type CreatedEmployee = { employee: AdminEmployeeView; pin: string }
type PinResetReason = 'FORGOT_PIN' | 'ACCOUNT_LOCKED' | 'POSSIBLE_MISUSE' | 'OTHER'
type LastPinReset = { at: string; byName: string; reasonCode: PinResetReason | null; note: string | null }
type AdminEmployeeDetail = AdminEmployeeView & { lastPinReset: LastPinReset | null }
type PinResetResult = { employee: AdminEmployeeDetail; pin: string }
```
- `byName` is the actor's current name (admin-only response; D14 visibility). `lastPinReset` never contains the PIN; its row has `details = null`.
- The PIN-carrying records (`CreatedEmployee`, `PinResetResult`) mask the PIN in `toString()`.
- `branchActive` lets the form show a retired current branch as a disabled option.
- Code: uppercased, regex, immutable (absent from the PUT record). `role` required. `branchId` required when `role = EMPLOYEE` (400 `BRANCH_REQUIRED`; `ck_employee_branch_role` is the backstop).
- No error body ever contains the submitted or stored code, name, note or PIN (D14); all codes below are fixed texts without arguments.
- Facade integrity mapping (by constraint name: `PSQLException.getServerErrorMessage().getConstraint()`, falling back to the most specific cause's message, like `ReviewService`; D15). The facade logs only `constraint=` and the exception class: `uq_employee_company_code` -> `EMPLOYEE_CODE_TAKEN`; `uq_branch_company_name` -> `BRANCH_NAME_TAKEN`; `ck_employee_branch_role` -> `BRANCH_REQUIRED`; `fk_employee_branch_company` -> 404; `ck_employee_name` / `ck_branch_name` / `ck_audit_log_reason_code` / `ck_audit_log_reason_note` -> `INVALID_REQUEST`; anything else is logged (constraint name + class only) and rethrown (500).

### 4a endpoints touched
| Endpoint | Change |
|---|---|
| `GET /api/admin/time-entries/{id}` | `detailRateLimiter` + `LOCATION_VIEW` audit + log line (D8) |
| `POST /api/admin/time-entries[/{id}/approve|reject|correct]` | **review** write budget (filter, 300 / 10 min); reason rule `TextRules` (L-1); `ReviewWriter` lock order + actor re-check (D2, D3) |
| `GET /api/admin/time-entries/{id}/photo` | 429 code `RATE_LIMITED` (Q1) |
| `POST /api/auth/login` | unchanged API; the 4 warning log lines log ids instead of codes (D14) |
| PWA clock-in (`TimeEntryWriter`) and today state (`TodayService`) endpoints (paths unchanged) | inactive branch = `NO_BRANCH_ASSIGNED` (D16) |

### New error codes (no arguments, no apostrophes; 6 keys x 3 bundles; frontend `error.*` + `ERROR_CODE_KEYS`)
| Code | Status | EN title / detail | ES title / detail |
|---|---|---|---|
| `RATE_LIMITED` | 429 | Too many actions / Too many actions in a short time. Wait a few minutes and try again. | Demasiadas acciones / Demasiadas acciones en poco tiempo. Espere unos minutos e intente de nuevo. |
| `RECORD_CHANGED` | 409 | Record changed / Someone else changed this record. Reload and try again. | Registro modificado / Otra persona modificó este registro. Recargue e intente de nuevo. |
| `ADMIN_PIN_RESET_NOT_ALLOWED` | 409 | PIN reset not allowed / You cannot reset the PIN of another active administrator. Ask them to change it, or reset it only if their account is locked or deactivated. | Restablecimiento no permitido / No puede restablecer el PIN de otro administrador activo. Pídale que lo cambie, o restablézcalo solo si su cuenta está bloqueada o desactivada. |

The texts name no person and no code (D14).

## Frontend

### Files (`frontend/src/`)
- `admin/AdminApp.tsx`: routes `employees`, `employees/:id`, `branches`. `AdminLayout.tsx`: nav items Empleados / Sucursales; "Reportes" still not rendered until 4c (design conflict 7).
- `admin/AdminIcon.tsx`: **add** two new paths `users` and `building` to `ADMIN_PATHS` (they do not exist today; `AdminIcon.tsx:4-51` has clock, clock-alert, eye, minus-circle, help-circle, calendar-off, pencil, external-link, chevron-left/right, clipboard-check, layout-dashboard). Same 24x24 stroke style, admin-only (the employee `Icon` is not touched).
- `admin/shell.ts`: `setUnsavedChanges`, `confirmLeave` (D12), `suspendSession` (D5). `AdminLayout.tsx` honours `suspendSession`: while true, `refreshPending` and the visibility/interval poll do nothing, and the `ReauthController` dialog and the re-auth banner are not rendered even if `session.needsReauth` is true.
- `admin/api/adminTypes.ts`, `admin/api/adminApi.ts`: the types and calls above (`PUT` already supported by `api/client.ts`). `fetchEmployees` returns `AdminEmployeeView[]` (superset of the 4a `EmployeeOption`, so the review-queue filter keeps working).
- `admin/text.ts` (new): `UNSAFE_TEXT = /[\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}]/u`, `cleanText(s) = s.normalize('NFC').trim()`, `hasUnsafeText(s)`. `ReasonField.normalizeReason` keeps collapsing whitespace, then `isReasonValid` also requires `!hasUnsafeText`; the field shows `admin.text.invisibleChars` inline.
- `errors/errorMessages.ts`: `RATE_LIMITED`, `RECORD_CHANGED`, `ADMIN_PIN_RESET_NOT_ALLOWED` keys; the `Retry-After` minutes/seconds formatting applies to **any 429** with `retryAfterSeconds` (today only `TOO_MANY_ATTEMPTS`). `admin/components/EvidencePhoto.tsx`: treat `RATE_LIMITED` like `TOO_MANY_ATTEMPTS`.
- Pages and components (design 3.8-3.12 and component inventory #29-35): `pages/EmployeesPage.tsx` (+ `EmployeeTable`, `EmployeeFilters`), `components/EmployeeFormDialog.tsx`, `pages/EmployeeDetailPage.tsx` (+ `AccessPanel`), `components/ScheduleEditor.tsx`, `components/PinRevealDialog.tsx`, `pages/BranchesPage.tsx`, `components/BranchFormDialog.tsx`, `components/CoordinatesPasteField.tsx`. Reuse `ModalDialog`, `ConfirmDialog`, `ErrorBox`, `PageHeading`, `format.ts` (Google Maps URL helper from 4a, `toFixed(6)`). There is **no `StatusMessage` component** (`PageParts.tsx` exports only `PageHeading`, `QueryError`, `Pagination`); polite announcements use the existing 4a pattern: one `<span className="visually-hidden" aria-live="polite">` per page/dialog (as in `ReasonField.tsx:100`, `ReviewActionDialog.tsx:225`) and `<p role="status" className="adm-hint">` for visible states (as in `DashboardPage.tsx:223`). No new status component.
- New `components/PinResetDialog.tsx` (the confirm step of the reset, replacing a plain `ConfirmDialog` for this action): the confirm text, a radio group "Motivo (opcional)" with "Sin motivo" + the 4 presets of D13, and a "Nota (opcional)" textarea enabled only when a preset is chosen, `maxLength` 200, live counter, hint `admin.pin.noteHint`, inline errors for invisible characters and digit runs; the own-account warning stays. On confirm it calls `resetPin(id, {reasonCode, note})` and hands the result to `PinRevealDialog`.
- `AccessPanel` shows `lastPinReset` as one line under "Restablecer PIN" (`admin.pin.lastReset`), plus the note in a second muted line; nothing when null.
- For another active, unlocked ADMIN, "Restablecer PIN" is `aria-disabled` with a visible note `admin.pin.adminNotAllowed`; the 409 `ADMIN_PIN_RESET_NOT_ALLOWED` is still handled (race).
- `admin/text.ts`: also `DIGIT_RUN = /\p{Nd}(?:[\s./-]*\p{Nd}){3,}/u` and `hasDigitRun(s)` (D13), mirrored by the same test list as the server.
- `i18n/adminResources.ts`: the 4b key tables of design 5.3, plus `admin.text.invisibleChars` ("El texto contiene caracteres invisibles no permitidos. Escríbalo de nuevo sin copiar y pegar." / "The text contains invisible characters that are not allowed. Type it again instead of pasting it."), `admin.recordChanged.reload` ("Recargar" / "Reload"), `admin.employee.branchInactiveOption` ("{name} (inactiva)" / "{name} (inactive)"), and for D13/Q14:
  | Key | ES ("usted") | EN |
  |---|---|---|
  | `admin.pin.reasonLegend` | Motivo (opcional) | Reason (optional) |
  | `admin.pin.reason.NONE` | Sin motivo | No reason |
  | `admin.pin.reason.FORGOT_PIN` | Olvidó el PIN | Forgot PIN |
  | `admin.pin.reason.ACCOUNT_LOCKED` | Cuenta bloqueada | Account locked |
  | `admin.pin.reason.POSSIBLE_MISUSE` | Posible uso indebido | Possible misuse |
  | `admin.pin.reason.OTHER` | Otro | Other |
  | `admin.pin.noteLabel` | Nota (opcional) | Note (optional) |
  | `admin.pin.noteHint` | No escriba datos personales (cédula, teléfono, PIN) ni números de 4 o más cifras. | Do not enter personal data (ID number, phone, PIN) or numbers of 4 or more digits. |
  | `admin.pin.noteDigits` | La nota no puede contener números de 4 o más cifras. | The note cannot contain numbers of 4 or more digits. |
  | `admin.pin.noteTooLong` | La nota admite como máximo 200 caracteres. | The note can have at most 200 characters. |
  | `admin.pin.lastReset` | Último restablecimiento de PIN: {date} por {name} · {reason} | Last PIN reset: {date} by {name} · {reason} |
  | `admin.pin.adminNotAllowed` | No puede restablecer el PIN de otro administrador activo, salvo que su cuenta esté bloqueada o desactivada. | You cannot reset the PIN of another active administrator unless their account is locked or deactivated. |
  `i18n/resources.ts`: `error.RATE_LIMITED`, `error.RECORD_CHANGED`, `error.ADMIN_PIN_RESET_NOT_ALLOWED` (title + detail each).

### Behaviour rules not spelled out in the design
- **Version conflicts:** forms keep the `version` (or loaded week) of their last GET/response. On 409 `RECORD_CHANGED`: error box with the message and a "Recargar" button that refetches and discards the local edits (the text says so); never auto-retries.
- **Every successful write** replaces the local view with the response (so the next write has the new `version`).
- **Employee form branch select:** active branches only; if the employee's current branch is inactive, it is shown as a disabled, selected option "{name} (inactiva)" so the value is visible; saving other fields keeps it.
- **Self / last-admin notes** per design 3.9.2; "self" = `employee.code === identity.employeeCode`; "last admin" from the list (exactly one active ADMIN and it is this one).
- **PIN reveal:** the PIN lives only in the parent's state for the dialog and is set to `''` before unmount; no `console`, storage, URL, `document.title`, query cache or error object holds it (the reset/create result is not put into any shared cache; only `employee` is merged into the page state). Self reset: `suspendSession(true)` before the request (D5); on close, clear the session locally (no logout API call, the token is already invalid) and go to `/login`.
- **PII in the UI (D14):** server errors are shown as their fixed localized text, never with the submitted value appended; the employee search box text is component state only (never `useSearchParams`); no name or code in `document.title` or URLs.
- **Uncertain create** (network error after submit): design's `admin.employees.createUncertain` message; no automatic retry (not idempotent).
- **429** on any write: the error box shows "try again in N min" from `Retry-After`; the submit button stays enabled (the user may wait and retry).
- **Coordinates:** paste regex per design 3.11; also client-side `Number.isFinite` and ranges; the "Revisar ubicación en Google Maps" link is `aria-disabled` while the pair is invalid.
- **Schedule:** default when a day is checked 08:00 / 17:00 / 5; "copy from" loads `GET .../schedule` of the chosen employee and fills the grid without saving; the PUT sends `expected` = the last loaded week.

### Bundle and CSP
- All 4b code stays in the `admin` chunk (path `src/admin/**`). The coder measures admin JS/CSS gzip before and after. If admin JS exceeds 80 KB gzip, fallback: lazy-load the three management pages as a second group named `admin-manage` (it still matches `globIgnores: ['**/admin-*.js','**/admin-*.css']`), and extend `bundleBudget.test.ts` to sum `admin-*` files; do not raise the budget without asking.
- No `style=` attributes, no `dangerouslySetInnerHTML`, no new origins; the only external link is the existing Google Maps pattern.

## Security
1. **AuthZ:** URL rule + `@PreAuthorize` on the moved/extended controllers; the reflection test keeps listing `AdminEmployeeController` and `AdminBranchController`; `adminRoutes()` gains every new route (needs a `PUT` builder).
2. **Tenant isolation:** scoped lookups only; `branchId` in bodies loaded with `companyId`; composite FKs as backstop; another tenant's id gives 404 and no audit row.
3. **Mass assignment:** explicit records; tests send `companyId`, `pinHash`, `tokenVersion`, `active`, `version`, `code` (on PUT), `failedAttempts` and assert no effect.
4. **Privilege changes are immediate:** `token_version` bumps per D4; the per-request DB check already reads role and `active`.
5. **Admin-set integrity:** company-wide `FOR NO KEY UPDATE` lock, self guard, last-admin guard, actor re-check in every writer (D2, D3).
6. **PIN lifecycle:** SecureRandom + policy + not equal to the code; hashed before locks; returned once in the reveal response only (`no-store`); masked in `toString()`; never logged, audited or kept in client state after the dialog (D5, D14).
7. **Admin account takeover:** another active, unlocked admin's PIN cannot be reset (Q14, `ADMIN_PIN_RESET_NOT_ALLOWED`); the demote-then-reset path is audited and visible.
8. **Audit:** same-transaction rows for every effective change; ids only; optional PIN-reset reason code + note (digit runs refused); append-only table (4a trigger).
9. **L-1, L-2, L-4** per D6, D8, D7 (two write budgets).
10. **Inactive branch invariant** (D2) plus the defensive clock-in check (D16).
11. **No echo:** 400/409 bodies never contain the submitted name, code, note, PIN or coordinates.
12. **Logs:** ids only (`employee_created id=… actor=… company=…`, never name, code, PIN, note, coordinates). `LoginService` fixed to ids (D14). SQL server error detail off and `SqlExceptionHelper` silenced (D15).
13. **PII guardrail (D14):** PIN only in the reveal response; names, codes, cedula / phone / SSN only visible to the owner or a same-company ADMIN; never in messages, logs, audit, URLs or the note.
14. **Deadlocks:** lock protocol (D2); residual `40P01` gives 503 `SERVER_BUSY`, never a 500 with SQL text.

## Implementation Steps

Paths relative to `backend/` or `frontend/`. After each numbered step the backend suite (`.\mvnw.cmd clean test`) and the frontend checks (`npm test; npm run lint; npm run build; npm test`) stay green.

### Step 0 - Hand-offs
1. **Architect (short delta)** in `docs/diagrams/stage-4-admin-panel.md`: V5 columns/checks in the ER section (employee/branch `version`, name checks, `audit_log.reason_code` / `reason_note` + checks); `admin -> auth` edge, `AdminEmployeeController` moved, `config.AdminWriteRateLimitFilter` (two budgets) in the chain; one sequence "deactivate branch vs assign employee" with the **revised** lock order of D2 (admin set -> actor `KEY SHARE` + target `FOR UPDATE` by id -> branch), incl. the audit FK `KEY SHARE`; one sequence "two admins demote each other" (LAST_ADMIN); one sequence "two admins reset each other's PIN" (ordered actor/target locks, `ADMIN_PIN_RESET_NOT_ALLOWED`).
2. **Designer (short delta, revised 2026-11-03):** design 3.8-3.12 stays; the designer specifies (a) the PIN-reset confirm with the optional reason radio group + note (D13, Frontend), (b) the "last PIN reset" line in the Access panel, (c) the disabled "Restablecer PIN" note for another active admin (Q14), (d) the two new admin icons `users` and `building`. The other small additions (invisible-character message, `RECORD_CHANGED` reload, inactive branch option, nav guard, 429 on writes) are specified above.

### Backend
2a. **D15 first:** `application.yml` (`spring.datasource.hikari.data-source-properties.logServerErrorDetail: false`, `?logServerErrorDetail=false` on the default `DB_URL`, `logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper: OFF`); `admin/ReviewService.java:50` and `timeentry/TimeEntryService.java:79` log `constraint=` + class instead of the exception message; prefer `getServerErrorMessage().getConstraint()` for mapping.
2b. **D14 fixes to existing code:** `auth/LoginService.java:69-91` log `employee={id}` / `company={id}` instead of codes; any test asserting those log lines adapted.
3. `db/migration/V5__management_versions.sql` (new, incl. the `audit_log` reason columns). `db/SchemaEnglishNamingTest` (55, rename method), `db/SchemaMigrationTest` (version 5). `audit/AuditLogEntry.java`, `audit/AuditService.java`, `audit/AuditLogRepository.java` (reason fields, `findFirst...`).
4. Entities: `employee/Employee.java`, `company/Branch.java`, `employee/WorkSchedule.java` (D2, data model section).
5. `common/TextRules.java` (new; incl. `DIGIT_RUN`); `common/RateLimitedException.java` (new); `common/ErrorCode.java` (+`RATE_LIMITED`, `RECORD_CHANGED`, `ADMIN_PIN_RESET_NOT_ALLOWED`); `common/ApiExceptionHandler.java` (handler for `RateLimitedException`); `common/AuditAction.java` (+`LOCATION_VIEW`); `messages.properties`, `messages_es.properties`, `messages_en.properties` (+6 keys each).
6. **L-1:** `admin/ReviewRequests.java` uses `TextRules.clean` + `SAFE_TEXT` for the three reasons.
7. **Limiters:** `admin/AdminProperties.java` (`RateLimits` + `write`, `reviewWrite`, `detail`); `config/AdminRateLimits.java` (+`adminWriteRateLimiter`, `reviewWriteRateLimiter`, `detailRateLimiter`); `application.yml` (`write`, `review-write`, `detail`); `src/test/resources/application-test.yml` (raise `write`, `review-write`, `account`).
8. **L-4:** `config/AdminWriteRateLimitFilter.java` (new; matches `/api/admin/` prefix and methods POST/PUT/PATCH/DELETE; picks the review or the general budget by path (D7); ADMIN principals only; localized via `HandlerExceptionResolver` + `LocaleResolver`); `config/SecurityConfig.java` registers it after `BearerTokenAuthenticationFilter` (next to the time-entry filter).
9. **L-2:** `admin/AdminTimeEntryController.java` (limiter, `LOCATION_VIEW` audit, log line). `timeentry/AdminTimeEntryPhotoController.java`: `RateLimitedException` (Q1).
10. `audit/AuditService.java`: `recordInTransaction` (`MANDATORY`, with reason code/note).
11. `admin/AdminLocks.java` (new: lock timeout helper + `lockActorAndTarget` / `lockActor`, D2 step 2, returning the target and throwing `AccessDeniedException` when the actor is not an active ADMIN); `admin/ReviewWriter.java` uses both (actor + target in id order before the time entry; actor re-check).
12. Repositories per the data model section (`EmployeeRepository`, `BranchRepository`, `WorkScheduleRepository`).
13. `auth/PinGenerator.java` (new) + a `RandomGenerator` bean (`SecureRandom`) in `auth/JwtConfig` or a small `auth/PinConfig`.
14. Branches: `admin/ManagementRequests.java` (branch records), `admin/AdminBranchWriter.java`, `admin/AdminBranchService.java`, `admin/AdminBranchController.java` (POST/PUT/activate/deactivate; `BranchView` + `version`).
15. Employees: `admin/AdminEmployeeView.java` (+ `AdminEmployeeDetail` with `lastPinReset`), `admin/PinResetReason.java`, records in `ManagementRequests` (incl. `PinReset {reasonCode, note}` with `TextRules` + `DIGIT_RUN` + "note needs a code"; PIN result records mask `toString()`), `admin/AdminEmployeeWriter.java` (D2 lock step, actor re-check, Q14 admin guard, reason on the `PIN_RESET` row), `admin/AdminEmployeeService.java` (PIN generation + hashing outside the transaction, integrity mapping, read-after-commit), `admin/AdminEmployeeController.java` (moved; account limiter; `no-store`). Delete `employee/AdminEmployeeController.java`, `employee/EmployeeQueryService.java`, `employee/EmployeeSummary.java`.
16. Schedules: `admin/AdminScheduleWriter.java` (D10) + endpoints in `AdminEmployeeController`.
17. D16 (Q6 yes): `timeentry/TimeEntryWriter.java` and `timeentry/TodayService.java` treat an inactive branch like no branch (`NO_BRANCH_ASSIGNED`).
18. **Seed tests:** `seed/DevSeedContentTest.java`, `seed/DevSeedEnabledTest.java`, `seed/DevSeedDemoDayTest.java` pin `app.seed.branch-latitude=`, `app.seed.branch-longitude=`, `app.seed.branch-radius-meters=` (empty = default location) in addition to `pin` and `demo-day`; every new seed-touching test pins all five `app.seed.*` properties. No seed production code changes in 4b.

### Frontend
19. `api` / errors: `errors/errorMessages.ts` (+3 codes, any-429 formatting), `i18n/resources.ts` (+3 `error.*`), `api/contract.test.ts` stays green against `ErrorCode.java`; `admin/components/EvidencePhoto.tsx` (`RATE_LIMITED`).
20. `admin/text.ts` (new) + `admin/components/ReasonField.tsx` (L-1 mirror).
21. `admin/api/adminTypes.ts`, `admin/api/adminApi.ts`; `test/adminFixtures.ts`, `test/adminBackend.ts` (fixtures for the new endpoints).
22. `admin/shell.ts`, `admin/AdminLayout.tsx` (nav + guard + `suspendSession`), `admin/AdminIcon.tsx` (new `users`, `building` paths), `admin/AdminApp.tsx` (routes).
23. Branches page + form + paste field.
24. Employees page + form dialog + PIN reveal (search text in state, not in the URL).
25. Employee detail page + access panel (last PIN reset line, admin-target note) + `PinResetDialog` (reason + note) + schedule editor.
26. `i18n/adminResources.ts` (design 5.3 4b tables + the keys listed in Frontend, incl. the `admin.pin.*` reason keys); `admin-content.css` / `admin-dialogs.css` (new `adm-` classes only; no edits to employee CSS).
27. Measure bundle; apply the D-bundle fallback only if over budget.

### Docs
28. `README.md` "Admin panel": managing branches, employees (PIN shown once, reset with optional reason, unlock, the admin-PIN-reset rule), schedules; the 429 limits (general 120, review 300, account 30, detail 300 per 10 min); the PII rule (D14) in one paragraph; the dev note that `DEV_SEED_LAT/LNG/RADIUS` re-apply to "Sucursal Centro" on every dev start (overwriting an edit made in the panel) and that renaming "Sucursal Centro" stops the seed from adding missing demo employees.

### Verification
29. `cd backend; .\mvnw.cmd clean test` (754 passing, 2 skipped before 4b): report totals.
30. `cd frontend; npm test; npm run lint; npm run build; npm test` (757 passing, 2 skipped before 4b): report totals, employee and admin JS/CSS gzip, precache size, and that `dist/sw.js` has no `admin-` URL.

### Manual check (user, after 4b)
1. Dev start: "Migrating schema … to version 5".
2. As `9001`: create branch "Heredia" by pasting "10.0024, -84.1165"; create employee `2001` assigned to Heredia; the PIN dialog shows 4+4 digits; close it; log in as 2001 in a private window with that PIN.
3. Deactivate Heredia: refused ("Tiene 1 empleados activos…"); move 2001 to Sucursal Centro; deactivate Heredia: OK.
4. Reset 2001's PIN with reason "Olvidó el PIN" and note "Solicitado en persona": the private window gets the re-auth dialog; the old PIN fails; the new one works; the detail page shows "Último restablecimiento de PIN … · Olvidó el PIN" and the note. Typing "1234 5678" in the note is refused inline.
4a. With a second admin account active and unlocked, "Restablecer PIN" on it is disabled with the note; reset your own PIN: the PIN dialog stays open past one minute without a re-auth dialog; on close you are at the login.
5. Lock 1001 with 5 wrong PINs; the list shows "Bloqueado hasta HH:mm"; unlock; 1001 logs in.
6. Give 1006 a Tuesday 22:00-06:00 schedule ("Turno nocturno" hint); save; reload: kept.
7. Open the same employee in two tabs, save a name change in one, then save in the other: "Otra persona modificó este registro" + Recargar.
8. Try to deactivate yourself and to remove your own admin role: the controls explain why; via the API, 409.
9. In psql: `select action, target_type, target_id, details, reason_code, reason_note from audit_log order by id desc limit 20;` shows the actions with ids only (no PIN, no names, no codes, no coordinates); the `PIN_RESET` row has `FORGOT_PIN` and the note; opening an evidence dialog adds `LOCATION_VIEW`.
9a. The backend console during steps 2-5 and a wrong-PIN login shows no PIN, no `$2a$`, no employee code and no name (login warnings show `employee=<id>`).
10. EN switch: all new texts in English.

## Tests

### Backend
- **`db/SchemaV5Test`** (new): `version` NOT NULL default 0 on both tables; `ck_employee_name` / `ck_branch_name` reject `'   '`; existing rows have version 0; `audit_log.reason_code` rejects `'forgot'` (format) and accepts `FORGOT_PIN`; `reason_note` without a code and a blank note are rejected; existing `audit_log` rows have both NULL; the append-only trigger still refuses an UPDATE of `reason_note`.
- **`config/SqlErrorDetailLoggingIntegrationTest`** (new, critic finding 1, `OutputCaptureExtension`): two concurrent creates of employee code `"7777-ABC"` (one 409) and a forced check violation through native SQL in the same context; the captured output contains neither `7777-ABC`, the employee name, nor `$2a$`; the 409 body is `EMPLOYEE_CODE_TAKEN` without the code; the facade log line has `constraint=uq_employee_company_code`. Also asserts the driver property is applied (`DataSource` -> `PGConnection` property `logServerErrorDetail=false`).
- **`admin/ManagementLockOrderIntegrationTest`** (new, critic finding 2; latch threads, 20 rounds each, `lock-timeout` small): (a) branch deactivate by admin A vs admin B assigning **A** to that branch; (b) admin A edits branch X while A's own PWA clock-in at X runs; (c) admins A and B reset / unlock / replace the schedule of each other simultaneously; (d) 4a: A approves an entry of B while B approves an entry of A. Every response is 200 / 409 / 503 `SERVER_BUSY`, **never 500**; captured log has no `deadlock detected` in a 500 path. One extra case forces a real `40P01` (two native transactions locking two rows in opposite order through the writers' helper) and asserts the HTTP answer is 503 `SERVER_BUSY` with `Retry-After`.
- **`admin/ActorRecheckIntegrationTest`** (new, critic finding 3; calls each writer directly with a principal whose row was demoted / deactivated by SQL first): employee create, PIN reset, unlock, schedule replace, branch create / edit / activate / deactivate and a 4a approve all give `AccessDeniedException` (403) and write nothing (no row, no audit row); a PIN reset whose actor is demoted **during** hashing (hook in a test `PasswordEncoder` that demotes then hashes) gives 403 and no PIN in the response.
- **`common/TextRulesTest`** (new, unit): rejects U+202E, U+2066, U+200B, U+200D, U+2060, U+FEFF, U+2028, U+2029, U+0000, tab, LF, a lone `\uD800`; accepts "José Ñandú Pérez", "Ana María O Neil", "Café 24/7", a single emoji; NFC: `"José"` becomes `"José"` (length 4); `cleanOptional("  ")` is null.
- **`admin/ReviewRequestsTextIntegrationTest`** (new) or extend `ReviewActionsIntegrationTest`: approve/reject/correct/create with a reason containing U+202E or U+200B give 400 `INVALID_REQUEST`, the body does not echo the reason, and no `adjustment` row is written; an accented reason passes.
- **`admin/AdminLocationViewIntegrationTest`** (new, own context `detail.max-attempts=3`): one `LOCATION_VIEW` row per successful detail GET of a MOBILE entry with GPS; none for a `source ADMIN` entry; other tenant's id gives 404 and no row; misses count; the 4th attempt gives 429 `RATE_LIMITED` + `Retry-After`; a second admin is unaffected; captured log has `location_access` with ids and no coordinates; approving an entry (write endpoint) writes no `LOCATION_VIEW`.
- **`config/AdminWriteRateLimitIntegrationTest`** (new, own context `write.max-attempts=3`, `review-write.max-attempts=4`): three 4b writes succeed, the 4th (a branch create) gives 429 `RATE_LIMITED` with `Retry-After` and **no** new branch / audit row; with the general budget exhausted, 4 review actions (approve, reject, correct, admin create) still succeed and the 5th gives 429 with no `adjustment` row; with the review budget exhausted a branch create in a fresh window succeeds (budgets independent); `GET /api/admin/time-entries/{id}` is not counted by either; a 400-invalid write also counts; GETs are not limited; a second admin is unaffected; an EMPLOYEE token on `POST /api/admin/branches` still gives an empty 403 (and does not consume an admin budget); `Accept-Language: en` and `es` localize the 429 body.
- **`admin/AdminBranchManagementIntegrationTest`** (new; the existing `AdminBranchIntegrationTest` list test stays):
  - create 201 + `BRANCH_CREATE` row; update with the right `expectedVersion` gives version + 1 and `BRANCH_UPDATE details=fields=…` with no coordinate values in `details`
  - validation: `"NaN"`, 91, -181, `"9,93"` string, radius 9 / 5001 / missing, blank name, name with U+202E, 121 chars give 400
  - duplicate name, also differing only in case, gives 409 `BRANCH_NAME_TAKEN`; renaming to its own name is a no-op
  - stale `expectedVersion` gives 409 `RECORD_CHANGED` and writes nothing
  - deactivate with an active employee gives 409 `BRANCH_IN_USE`; after moving the employee it succeeds; with only inactive employees it succeeds; activate again succeeds
  - assigning an inactive branch on create, on PUT and on activate gives 409 `BRANCH_INACTIVE`
  - moved location: the next clock-in's distance uses the new position; the stored distance of an old entry is unchanged
  - other tenant's branch id gives 404 on every route; EMPLOYEE token 403
  - extra JSON (`companyId`, `active`, `version`) has no effect
  - two concurrent creates with the same name: exactly one 201, one 409, and exactly one `BRANCH_CREATE` audit row (audit atomicity)
- **`admin/BranchDeactivationRaceIntegrationTest`** (new): threads with a latch run "deactivate branch B" and "assign employee E to B" 20 times; invariant after each round: no active employee on an inactive branch; every response is 200 or 409 (`BRANCH_IN_USE` / `BRANCH_INACTIVE`), never 500.
- **`admin/AdminEmployeeManagementIntegrationTest`** (new; umbrella list plus):
  - create gives 201 with an 8-digit PIN that passes `PinPolicy`, `Cache-Control: no-store`; logging in with it gives 200; the PIN appears nowhere in captured logs, in `audit_log` (`details`, `reason_code`, `reason_note`) or in any later GET (list, detail, schedule)
  - code `" ab-12 "` is stored `AB-12`; a duplicate (also lowercase) gives 409 `EMPLOYEE_CODE_TAKEN` whose body contains neither `AB-12` nor `ab-12` nor the name; the code of a deactivated employee is still taken (Q9)
  - EMPLOYEE without a branch gives 400 `BRANCH_REQUIRED`; ADMIN without a branch is 201; another tenant's branch gives 404
  - PUT: the code field is ignored; name/jobTitle cleaned (NFC, trimmed; blank job title becomes null); U+200B in a name gives 400
  - stale `expectedVersion` gives 409 `RECORD_CHANGED`; PIN reset and unlock do **not** change `version` (another admin's form stays valid)
  - role change makes the old token 401 and bumps `token_version` once; a name-only change does not bump it
  - self demote and self deactivate give 409 `SELF_CHANGE_NOT_ALLOWED`; self name change and self branch change succeed
  - deactivate makes the token 401 and login fail; activate restores login but an **old token from before the deactivation stays 401**
  - activate on an inactive branch gives 409 `BRANCH_INACTIVE`; editing the name of an inactive employee whose branch is inactive succeeds
  - PIN reset: old PIN fails, new works, old token 401, `failed_attempts` 0 and `locked_until` null; response `{employee, pin}` + `no-store`; allowed on oneself (the caller's token becomes 401)
  - PIN-reset reason (Q4): no body / `{}` -> row with both reason columns NULL; `{reasonCode: FORGOT_PIN}` -> code stored, note NULL; `{reasonCode: OTHER, note: "  Solicitado por la gerencia  "}` -> note stored trimmed + NFC; `note` with U+202E or U+200B (`\p{Cf}`) -> 400 `INVALID_REQUEST`, no echo, no reset (old PIN still works); note `"PIN 1234 5678"`, `"cédula 1-2345-6789"`, `"tel 8888-8888"`, `"1234"` -> 400; note `"123"` and accented text pass; note without code -> 400; unknown code -> 400; 201 chars -> 400; the stored note never contains the returned PIN (assert `!note.contains(pin)`) and `details` is NULL; `GET /employees/{id}` returns `lastPinReset {at, byName, reasonCode, note}` of the latest reset; the list does not include it
  - admin target (Q14): A resets active, unlocked admin B -> 409 `ADMIN_PIN_RESET_NOT_ALLOWED` (localized EN/ES, "usted"), B's PIN and lock unchanged, no audit row; after B is locked by 5 wrong PINs -> 200; after B is deactivated -> 200; A resets A -> 200; A resets EMPLOYEE -> 200
  - PII (D14): every 4xx body of create / edit / reset (validation, `EMPLOYEE_CODE_TAKEN`, `BRANCH_REQUIRED`, `RECORD_CHANGED`, `ADMIN_PIN_RESET_NOT_ALLOWED`) contains no submitted code, name, note or PIN; captured logs for create, edit, PIN reset and a failed login contain no PIN, no `$2a$`, no employee code, no name (the login lines now show `employee=<id>`); the PIN appears **only** in the 201/200 reveal body; an EMPLOYEE token gets an empty 403 on `GET /api/admin/employees` and `/{id}` (cannot read another employee's name), and the employee's own endpoints return only their own data
  - unlock after 5 failures, then login 200; unlocking an unlocked account is a no-op with no audit row
  - list: `locked`/`lockedUntil` (expired lock shows unlocked), `consentGiven`, `hasSchedule`, `branchName`, `branchActive`, `version`; never `pinHash`, `failedAttempts`, `tokenVersion`
  - audit rows per D9 (2 rows for role + name in one PUT; none for a no-op)
  - account limiter (own context, `account.max-attempts=2`): create + create + pin-reset gives 429 `RATE_LIMITED` before any PIN is generated (no new employee row)
- **`admin/AdminEmployeeWriterGuardsTest`** (new, `@IntegrationTest`, calls `AdminEmployeeWriter` directly with principals so the auth layer is bypassed): admin B (already demoted by A) demoting A gives 409 `LAST_ADMIN`; a deactivated actor editing a name gives `AccessDeniedException` (403); concurrent mutual demotion with real HTTP (latch) leaves at least one active admin and never two 200s.
- **`admin/AdminScheduleIntegrationTest`** (new): replace the week; change Monday 08:00 to 09:00 (regression for the flush-order unique violation); remove a day; add Saturday; overnight 22:00-06:00 accepted; day 0/8, duplicate day, start = end, grace -1 / 241, `"8:00"`, `"24:00"`, 8 items give 400; stale `expected` gives 409 `RECORD_CHANGED`; identical week is a no-op with no audit row; stored `late_minutes` unchanged; other tenant 404; `SCHEDULE_UPDATE` row with `target_type EMPLOYEE`.
- **`common/TextRulesTest`** also covers `DIGIT_RUN`: refuses `1234`, `12 34`, `1-2345-6789`, `8888.8888`, `١٢٣٤` (Arabic-Indic digits); accepts `123`, `12 a 34`, plain text.
- **`auth/PinGeneratorTest`** (new): 10,000 PINs from a seeded `Random` all valid, 8 digits, at least one with a leading zero; a generator forced to emit an invalid PIN first loops; the code-equal PIN is skipped.
- **Modified:** `config/AdminMethodSecurityTest` (new routes incl. PUT; `AdminEmployeeController` now in `admin`); `employee/AdminEmployeeControllerTest` moved to `admin/`; `admin/AdminLimitsIntegrationTest` (photo 429 code is now `RATE_LIMITED`, Q1); `db/SchemaEnglishNamingTest`, `db/SchemaMigrationTest`; the three seed tests (step 18); `TenantRepositoryConventionTest`, `MessageBundleParityTest`, `CommonPackageDependencyTest` stay green unchanged.
- **Modified (revision):** `auth` login tests that assert the warning log lines (if any) expect `employee=<id>`; `admin/ReviewActionsIntegrationTest` stays green with the new lock order.
- **No test prints a PIN** (D14): assertions on PINs use `assertThat(pin).matches("\\d{8}")` / login checks, without custom messages containing the value; `PinGeneratorTest` asserts counts, not printed samples.
- **D16 (Q6):** `TimeEntryValidationIntegrationTest` / `TodayStateIntegrationTest`: an employee whose branch was made inactive by SQL gets 403 `NO_BRANCH_ASSIGNED` and `blockedReason NO_BRANCH_ASSIGNED`.

### Frontend (Vitest + Testing Library)
- `admin/text.test.ts`: the same character list as `TextRulesTest`; NFC; the same `DIGIT_RUN` list.
- `admin/AdminLayout.test.tsx` (extend, critic finding 4): with `suspendSession(true)`, advancing fake timers past 60 s makes no `fetchReviewCount` call; a 401 from any request (`markNeedsReauth`) does **not** render the `ReauthController` dialog or the banner while suspended; after `suspendSession(false)` a 401 opens it again.
- `admin/components/PinResetDialog.test.tsx` (new): "Sin motivo" preselected; note disabled until a preset is chosen; payload `{}` with no reason, `{reasonCode}` with a preset, `{reasonCode, note}` with a note (NFC + trimmed); U+200B in the note shows `admin.text.invisibleChars` and blocks confirm; `"1234 5678"` shows `admin.pin.noteDigits` and blocks confirm; 201 chars blocked; the hint `admin.pin.noteHint` is linked by `aria-describedby`.
- `admin/components/ReasonField.test.tsx` (extend): a reason with U+202E shows `admin.text.invisibleChars` and the action stays disabled.
- `errors/errorMessages.test.ts` (extend): `RATE_LIMITED` with `Retry-After: 600` gives "en 10 min"; `RECORD_CHANGED` and `ADMIN_PIN_RESET_NOT_ALLOWED` texts ES/EN ("usted").
- `admin/pages/EmployeesPage.test.tsx`: list with chips (locked "Bloqueado hasta 10:30" in the company zone, consent, schedule); accent-insensitive search (typing a name or code does **not** change `location.search`); status and branch filters, `?branch=<id>`; nav shows the new `users` / `building` icons; create dialog: code uppercased, validation messages, `EMPLOYEE_CODE_TAKEN` under the field, invisible characters blocked; 201 opens `PinRevealDialog` with 4+4 groups; after closing, the PIN text is absent from the DOM, `sessionStorage`, `localStorage` and IndexedDB; uncertain create message; 429 message.
- `admin/pages/EmployeeDetailPage.test.tsx`: profile save sends `expectedVersion` and uses the returned version for the next save; `RECORD_CHANGED` box + Recargar refetches; role change confirm; own account: deactivate and role radio `aria-disabled` with the visible note; last admin note; `LAST_ADMIN` box; PIN reset confirm (+ own-account warning; on close the session is cleared without calling `/api/auth/logout` and the app goes to `/login`); **self reset: a 401 from the shell poll while `PinRevealDialog` is open does not open the re-auth dialog and the PIN stays visible until closed**; "last PIN reset" line with date, name, localized reason and note; another active unlocked ADMIN: "Restablecer PIN" `aria-disabled` with `admin.pin.adminNotAllowed`, and a 409 `ADMIN_PIN_RESET_NOT_ALLOWED` shows the localized error; PII: error boxes never contain the submitted code/name, the PIN is in the DOM only inside `PinRevealDialog`, `document.title` and `location` contain no name, code or PIN; unlock moves focus to "Restablecer PIN"; deactivate confirm and status text; inactive current branch shown as a disabled selected option.
- `admin/components/ScheduleEditor.test.tsx`: `Intl` weekday names ES/EN starting Monday; unchecked rows disabled; overnight hint; start = end, grace 241 and empty time errors in the summary with links; copy-from fills without saving; PUT payload `{expected, days}`; dirty state: nav link click opens "¿Salir sin guardar?", "Seguir editando" stays, "Salir" navigates; `beforeunload` listener only while dirty.
- `admin/pages/BranchesPage.test.tsx`: no coordinates in the list; paste "9.9325, -84.0796" fills both and announces; "9,9325" shows the hint and fills nothing; radius bounds; Google Maps link `aria-disabled` while invalid and `href` with 6 decimals + `rel="noopener noreferrer"`; deactivate `aria-disabled` with the "{n} empleados activos" note; `BRANCH_IN_USE` / `BRANCH_NAME_TAKEN` messages; `RECORD_CHANGED`.
- `admin/admin.test.tsx` / routes: `/admin/employees`, `/admin/employees/:id`, `/admin/branches` render inside the shell; nav shows the two items; "Reportes" absent.
- `i18n/resources.test.ts`, `noHardcodedText.test.ts`, the "tú" detector: green with the new keys and files.
- `bundleBudget.test.ts`, `pwaConfig.test.ts`: unchanged budgets, no admin file in the precache.
- A11y inside the page tests: one focused `h1` per page, one polite status region, dialog focus return, `role="alert"` errors with `aria-describedby`, labelled inputs (incl. the hidden per-day schedule labels).

## Edge Cases to Handle

| Case | Handling |
|---|---|
| Deactivate or demote yourself | 409 `SELF_CHANGE_NOT_ALLOWED`; UI explains with a permanent note |
| Deactivate / demote the last active admin | 409 `LAST_ADMIN` (reachable only in a race; UI note from the list) |
| Two admins demote each other at the same time | company admin-set lock serializes; the second gets `LAST_ADMIN`, 401 or 403; at least one admin remains |
| An admin is demoted while a request of theirs is in flight | actor re-check under the lock gives 403; their next request 401 (token bump) |
| Deactivate a branch with active employees | 409 `BRANCH_IN_USE` with the count; inactive employees do not block |
| Deactivate a branch while an employee is being assigned to it | lock protocol (D2): one of `BRANCH_IN_USE` / `BRANCH_INACTIVE`; invariant kept |
| Activate an employee whose branch is inactive | 409 `BRANCH_INACTIVE`; move them first |
| Edit an inactive employee whose branch is inactive | allowed while the branch is unchanged; the form shows "(inactiva)" |
| Duplicate employee code, any case, or the code of a deactivated employee | 409 `EMPLOYEE_CODE_TAKEN` (codes are never reused; Q9) |
| Duplicate branch name differing only in case | 409 `BRANCH_NAME_TAKEN` (app precheck; the DB constraint is case-sensitive, a race could leave "Central"/"central", harmless) |
| Concurrent create of the same code / branch name | DB unique constraint, mapped by the facade; one 201, one 409; one audit row |
| Two admins edit the same employee/branch/schedule | `expectedVersion` / `expected` week: 409 `RECORD_CHANGED` + reload |
| PIN reset or unlock while another admin edits the profile | no conflict (`@OptimisticLock(excluded)`) |
| Generated PIN fails the policy or equals the code | generator loops |
| PIN reset on oneself | allowed; PIN shown; local sign-out on close; logout API not called |
| PIN reset on a locked or inactive employee | clears the lock; inactive stays inactive |
| PIN reset of another active, unlocked admin | 409 `ADMIN_PIN_RESET_NOT_ALLOWED`; button `aria-disabled` with a note (Q14) |
| PIN reset of another admin who is locked or deactivated | allowed (lockout recovery / re-activation) |
| Demote another admin, then reset their PIN | allowed; two audit rows; the other admin's session ends at the demotion, so it is visible (accepted residual) |
| Two admins reset each other's PIN at the same time | ordered actor/target locks: one 200, the other waits; if both are active and unlocked, both 409; never 500 |
| PIN-reset note with a PIN, cedula or phone ("1234 5678", "1-2345-6789") | 400, inline "no números de 4 o más cifras"; UI hint against personal data |
| PIN-reset note that names a person | cannot be detected; UI guidance; note visible to same-company admins only (accepted) |
| PIN-reset note without a preset | UI keeps the note disabled; API 400 |
| Self PIN reset while the 60 s poll fires | polling paused and re-auth dialog suppressed until the PIN dialog closes (D5) |
| Duplicate-code error | fixed text, never echoes the code (code may be a cedula; D14) |
| SQL constraint violation with `Detail: Key (...)=(...)` | driver detail off, `SqlExceptionHelper` silent, facade logs the constraint name only (D15) |
| Admin demoted while their branch / schedule / PIN-reset request is in flight | actor re-check in every writer: 403, nothing written (D3) |
| Admin edits a branch while their own PWA clock-in at that branch is running | actor locked first; the edit waits for the clock-in, no deadlock (D2) |
| Residual deadlock (`40P01`) | 503 `SERVER_BUSY` + `Retry-After`, never 500 |
| Large review backlog (300 approvals) | review budget 300 / 10 min, separate from the 120 general budget (Q2) |
| Branch set inactive by operator SQL with active employees still on it | clock-in gives `NO_BRANCH_ASSIGNED` (D16) |
| Employee search for a name or code | filtering in memory; the text is never put in the URL (D14) |
| Network drop after "Crear empleado" | "could not confirm" message; admin checks the list and resets the PIN; no auto retry |
| Role EMPLOYEE -> ADMIN for someone clocked in | entries unaffected; their PWA token 401, they log in again (ADMIN TTL 6 h) |
| Role ADMIN -> EMPLOYEE without a branch | 400 `BRANCH_REQUIRED` (pick a branch in the same PUT) |
| Deactivate an employee with an open shift | allowed; the row becomes `MISSING_CLOCK_OUT` after 16 h; the admin can create the clock-out (inactive allowed in 4a) |
| Move an employee to another branch during an open shift | allowed; their clock-out is checked against the new branch geofence and may go to review (documented) |
| Edit a branch's location during open shifts | future entries only; stored distances and reasons stay |
| Schedule change today after the clock-in | stored `late_minutes` stays; today's not-yet-clocked rows use the new schedule |
| Change Monday's times (same day kept) | in-place update, no unique violation (D10, regression test) |
| Schedule for an inactive employee or an admin without a branch | allowed |
| Name with bidi override, zero-width, BOM, line separator, lone surrogate | 400 server-side; inline error client-side |
| Decomposed accents ("Jose" + U+0301) | NFC; stored as "José"; search and uniqueness behave |
| Coordinates `"NaN"`, out of range, comma decimal, (0,0) | 400 / client hint; (0,0) allowed but the Maps check link shows it |
| Very many writes (stolen token script) | 120 / 10 min general write limit, 300 / 10 min review limit, 30 / 10 min account limit, 300 / 10 min detail limit, 429 `RATE_LIMITED` + Retry-After; ids logged |
| Onboarding more than 30 employees at once | account limiter: 429 with "try again in N min" (Q3: kept at 30) |
| Leaving the page with unsaved schedule edits | `beforeunload` + in-panel link guard; browser Back not intercepted (D12) |
| Token expires mid-form (6 h) | 4a re-auth dialog keeps the form; the user re-submits; versions still valid unless someone else changed the record |
| Dev seed with `DEV_SEED_LAT` set after editing "Sucursal Centro" in the panel | the seed re-applies the env location on each dev start (documented in README) |
| Dev seed tests on a machine with a `backend/.env` | all `app.seed.*` pinned in the tests |

## Hand-off to the security auditor (after 4b, before the commit; confirmed Q13)
Focus: PII guardrail D14 (PIN only in the reveal response; no name / code / PIN in errors, logs, audit, URLs; the note digit rule); D15 driver setting; the Q14 admin-PIN-reset rule; the revised lock protocol and actor re-check in every writer; L-1/L-2/L-4 closure (two write budgets); mass assignment on the new records; tenant isolation of `branchId` in bodies; admin-set lock, self/last-admin guards and actor re-check; `token_version` bump matrix (D4), including "re-activation does not revive old tokens"; PIN entropy, policy, `no-store`, no logging/audit, client wipe; audit atomicity and content (ids only); the write filter's position (before binding) and its ADMIN-only scope; the inactive-branch invariant.

## Open Questions: RESOLVED (2026-11-03, user)

| # | Answer | Applied in |
|---|---|---|
| 1 | **Yes.** `RATE_LIMITED` for all per-admin limiters (general write, review write, detail, account, photo; export in 4c). `TOO_MANY_ATTEMPTS` stays for login and employee clock-in. | D7, API, Tests |
| 2 | **Yes, adjusted.** General admin write limit 120 / 10 min per admin; review actions (approve / reject / correct) and admin-created entries get their own budget of **300 / 10 min** per admin, so a large backlog can be cleared one by one. Both configurable. Every attempt counts. | D7, Deviation 13, Steps 7-8, Tests |
| 3 | **Yes.** Create employee + PIN reset stay at 30 / 10 min. | D7 |
| 4 | **User decision: reason optional, but tracked.** Optional preset (`FORGOT_PIN`, `ACCOUNT_LOCKED`, `POSSIBLE_MISUSE`, `OTHER`) + optional note (0-200, L-1 text rule, digit runs refused). Stored as `audit_log.reason_code` + `audit_log.reason_note` (V5), chosen over one free-text column because the code is language-neutral and countable and the note stays short. Never the PIN or hash. Shown in the employee detail ("last PIN reset"); full audit history screen is a new backlog item. | D13, Data model, API, Frontend, Tests |
| 5 | **Yes.** `version` columns + `expectedVersion`; schedule expected-week check; PIN reset / unlock do not bump `version`. | D2 |
| 6 | **Yes.** Inactive branch = `NO_BRANCH_ASSIGNED` at clock-in. | D16, Step 17 |
| 7 | **Yes.** NFC before validation. | D6 |
| 8 | **Yes.** Case-insensitive app check; DB constraint kept as is. | API (branches) |
| 9 | **Yes.** Codes of deactivated employees are never reusable. | Edge Cases, Tests |
| 10 | **Yes.** `beforeunload` + panel-link guard; Back button not guarded. | D12 |
| 11 | **Yes.** Blank-name checks in V5. | Data model |
| 12 | **Yes.** No hard cap on employee / branch lists in 4b. | D11 |
| 13 | **Yes.** Focused security audit after 4b, before the commit. | Hand-off |
| 14 | (from the critic) An admin may reset another **ADMIN**'s PIN only if that admin is deactivated or locked; otherwise 409 `ADMIN_PIN_RESET_NOT_ALLOWED` (EN/ES, "usted"). Self reset allowed. | D3, D5, API, Frontend, Tests |
| PII | **New user requirement (2026-11-03):** "Never show a PIN in any response, security or warning message, it is PII. Also any Id number, phone, social security or full name are PII. These information can only be seeing for the owner or for an admin account." Applied as D14: PIN only in the one-time reveal response; names, codes (may be a cedula), cedula / phone / SSN visible only to the owner or a same-company ADMIN and never in errors, warnings, logs, audit rows, URLs or the note (note refuses 4+ digit runs + UI guidance). 4b adds no cedula / phone / SSN field. Existing fixes: `LoginService` log lines, SQL error detail (D15). | D14, D15, Security, API, Frontend, Tests, Edge Cases |

Plan-critic findings (all adopted):
| # | Finding | Applied in |
|---|---|---|
| 1 | [MEDIUM] SQL error details leak code / name / `pin_hash` into logs | D15, Step 2a, `SqlErrorDetailLoggingIntegrationTest` |
| 2 | [MEDIUM] Lock order ignored FK `KEY SHARE` on the actor row | D2 (revised order, also `ReviewWriter`), Step 11, `ManagementLockOrderIntegrationTest` (503, never 500) |
| 3 | [LOW] Actor re-check only on three paths | D3 (every writer; hashing window), `ActorRecheckIntegrationTest` |
| 4 | [LOW] Self PIN reset: poll 401 opens re-auth over the PIN dialog | D5, Frontend (`suspendSession`), `AdminLayout.test.tsx`, detail page test |
| 5 | [LOW] `users` / `building` icons and `StatusMessage` do not exist | Frontend (icons marked new; existing polite-status pattern named) |
| 6 | [LOW] Deviations incomplete vs umbrella step 25 | Deviations 9-12 |

No new open question.

## Open Questions (original list, 2026-11-02; kept as history, answered above)

Answer "as recommended" to accept all defaults.

1. **One 429 code for all per-admin limiters?**
   - **Recommended: yes.** `RATE_LIMITED` for the write, detail, account and photo limiters (and export in 4c); `TOO_MANY_ATTEMPTS` stays for login and the PWA clock-in. The photo change is one line; the frontend treats both codes alike.
   - Alternative: `RATE_LIMITED` only for the new write limiter; detail, account and photo keep `TOO_MANY_ATTEMPTS`.
2. **Write limit value and scope (L-4).**
   - **Recommended: 120 per 10 min per admin**, every `POST/PUT/PATCH/DELETE` under `/api/admin/`, every attempt counts (also 4xx), checked in a filter before the controller.
   - Alternative: count only successful writes (needs a post-response hook; a script of invalid requests would not be slowed).
3. **Account limiter (create employee + PIN reset).**
   - **Recommended: keep 30 per 10 min** (umbrella). Onboarding 60 people takes about 20 minutes with clear "try again in N min" messages.
   - Alternative: 60 per 10 min, or exclude create from it (still under the write limit).
4. **PIN-reset reason.** The adopted L-1 amendment mentions a "PIN-reset reason", but no 4b action has a reason field and `audit_log.details` holds ids only.
   - **Recommended: no reason field in 4b.** The audit row already records who reset whose PIN and when; the confirm dialog is the safeguard.
   - Alternative: a required reason (5-500, `TextRules`) for PIN reset, deactivate and role change, stored in a new `audit_log.reason varchar(500)` column (V5) and shown nowhere until an audit viewer exists.
5. **Concurrency model.**
   - **Recommended:** `version` columns on `employee` and `branch` (V5) with `expectedVersion` on edits and status changes; schedules compare the expected week; PIN reset and unlock do not change the version.
   - Alternative: last write wins (no 409), as most small admin tools do.
6. **Defensive inactive-branch check at clock-in.**
   - **Recommended: yes.** `TimeEntryWriter` / `TodayService` treat an inactive branch like no branch (`NO_BRANCH_ASSIGNED`). The app already guarantees no active employee sits on an inactive branch; this guards against operator SQL. Small change to Stage 2/3 code plus 2 tests.
   - Alternative: rely on the invariant only.
7. **NFC normalization of names and reasons.**
   - **Recommended: yes**, before validation and storage (accents typed or pasted in different forms become identical).
   - Alternative: store exactly what was typed (only the character rule applies).
8. **Branch name uniqueness.**
   - **Recommended:** case-insensitive app check ("Central" vs "central" is refused), the V2 case-sensitive DB constraint stays as backstop (no index change).
   - Alternative: add a unique index on `(company_id, lower(name))` in V5 (fully race-proof; one more index in the naming test).
9. **Codes of deactivated employees.**
   - **Recommended: never reusable** (the code is the login and appears in exports and history).
   - Alternative: allow reuse after deactivation (needs a partial unique index and breaks the code-to-person link in old exports).
10. **Unsaved-schedule guard without a data router.**
    - **Recommended:** `beforeunload` + a guard on the admin panel's own links; the browser Back button is not intercepted.
    - Alternative: migrate `main.tsx` to `createBrowserRouter` to use `useBlocker` (touches every route and many tests; better done in its own change).
11. **Blank-name CHECK constraints in V5 (`ck_employee_name`, `ck_branch_name`).**
    - **Recommended: yes** (defense in depth, like `ck_company_name`).
    - Alternative: app validation only (V5 then only adds the two version columns).
12. **Employee and branch lists without a hard cap.**
    - **Recommended: yes for 4b**; pagination later if a customer approaches 1000 employees.
    - Alternative: return 400 `REPORT_TOO_LARGE` above 1000 employees / 200 branches (the panel's lists would stop working for such a company).
13. **Security audit after 4b.**
    - **Recommended: yes**, a focused audit (it touches auth, user data and PIN handling), before the 4b commit.
    - Alternative: one audit after 4c, as the umbrella originally allowed.
