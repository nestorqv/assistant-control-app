# Stage 4 - Admin panel diagrams

Source of truth: `docs/plans/stage-4-admin-panel.md`, including its last section "Open Questions: RESOLVED (2026-10-28, user)", which overrides earlier text (map link is **Google Maps**, not OpenStreetMap; register is "usted" everywhere; the other defaults are adopted, including the two extra review reasons and the opt-in demo day). The Stage 4 code does not exist yet, so everything marked (new) is drawn from the plan. The base was checked against the live code: `V1`..`V3` migrations, `cr.attendance.*` (config, common, auth, company, employee, timeentry, storage, seed), `SchemaEnglishNamingTest`, and `frontend/src/App.tsx`, `AppHeader.tsx`, `vite.config.ts`. Inconsistencies found are listed at the end under "Notes for the coder".

Previous diagrams: `stage-1-er.md` (full V2 schema) and `stage-2-time-entry.md` (V3 delta, POST sequence, package layers).

Contents:
1. ER diagram after V4
2. Layer diagrams: backend packages, frontend admin chunk versus employee bundle
3. Sequences: (a) dashboard load, (b) review approve / reject / correct, (b2) admin-created entry, (c) evidence view with photo audit and rate limit, (d) Excel export
4. State diagram: time entry status
5. Notes for the coder

---

## 1. ER diagram after V4

Eight tables. The plan has **no separate photo access log table**: a photo view is one `audit_log` row with `action = PHOTO_VIEW`, `target_type = TIME_ENTRY` and `target_id` = the entry id. Conventions are the same as in Stage 1 and 2. A composite FK `(x_id, company_id)` is shown on the `x_id` column with `FK` and a comment. `UK` marks a unique constraint or unique index, and the comment names the columns. `id_company_uk` is a drawing device for `UNIQUE (id, company_id)`, not a real column. Columns tagged `NEW V4` are added by the migration, and `CHANGED V4` marks a changed CHECK. Unchanged columns are kept where they matter for the Stage 4 rules. `evidence` and `work_schedule` are drawn in full-row form only where Stage 4 reads them, with no DDL change.

```mermaid
erDiagram
    COMPANY {
        bigint id PK "identity"
        varchar_20 code UK "NOT NULL, upper-case, unchanged"
        varchar_120 name "NOT NULL, unchanged, shown in the dashboard header"
        varchar_64 time_zone "NOT NULL, unchanged, converts every admin local date and time"
        boolean active "NOT NULL, unchanged"
    }

    BRANCH {
        bigint id PK "identity"
        bigint company_id FK "NOT NULL, tenant column, FK company(id)"
        varchar_120 name "NOT NULL, UK (company_id, name) uq_branch_company_name"
        double_precision latitude "NOT NULL, CHECK -90..90, unchanged"
        double_precision longitude "NOT NULL, CHECK -180..180, unchanged"
        integer radius_meters "NOT NULL, CHECK 10..5000, unchanged"
        boolean active "NEW V4, NOT NULL, default true, retire a branch without deleting history"
        bigint id_company_uk UK "UK (id, company_id), unchanged"
    }

    EMPLOYEE {
        bigint id PK "identity"
        bigint company_id FK "NOT NULL, tenant column, FK company(id)"
        bigint branch_id FK "NULL only for ADMIN, composite FK (branch_id, company_id), unchanged"
        varchar_20 code UK "NOT NULL, UK (company_id, code), immutable in the app"
        varchar_120 name "NOT NULL"
        varchar_80 job_title "NULL"
        varchar_100 pin_hash "NOT NULL, BCrypt, written by create and PIN reset in 4b"
        varchar_10 role "NOT NULL, CHECK EMPLOYEE or ADMIN, unchanged"
        boolean active "NOT NULL"
        timestamptz consent_at "NULL"
        integer failed_attempts "NOT NULL, cleared by unlock"
        timestamptz locked_until "NULL, cleared by unlock"
        integer token_version "NOT NULL, bumped by role change, deactivate, PIN reset"
        bigint id_company_uk UK "UK (id, company_id), target of the composite FKs. Locked FOR UPDATE by the PWA POST and by admin-created entries"
    }

    WORK_SCHEDULE {
        bigint id PK "identity"
        bigint company_id "NOT NULL, tenant column"
        bigint employee_id FK "NOT NULL, composite FK (employee_id, company_id)"
        integer day_of_week "NOT NULL, ISO 1..7, UK (employee_id, day_of_week)"
        time start_time "NOT NULL"
        time end_time "NOT NULL, CHECK end_time <> start_time"
        integer grace_minutes "NOT NULL, CHECK 0..240. No DDL change. 4b replaces the whole week, hard delete allowed (configuration, not evidence)"
    }

    TIME_ENTRY {
        bigint id PK "identity"
        bigint company_id "NOT NULL, tenant column"
        bigint employee_id FK "NOT NULL, composite FK (employee_id, company_id)"
        bigint branch_id FK "NOT NULL, composite FK (branch_id, company_id), branch of the employee at entry time"
        varchar_10 type "NOT NULL, CHECK CLOCK_IN or CLOCK_OUT"
        timestamptz server_time "NOT NULL, IMMUTABLE receipt time. Read by ShiftResolver, replay, photo month and skew"
        timestamptz effective_time "NEW V4, NOT NULL after backfill, equals server_time until an admin corrects it. Read by dashboard, review, export, worked time"
        timestamptz device_time "NULL, review signal only. NULL for source ADMIN"
        double_precision latitude "NULL, CHECK -90..90. NULL for source ADMIN"
        double_precision longitude "NULL, CHECK -180..180. NULL for source ADMIN"
        integer distance_meters "NULL, CHECK >= 0"
        varchar_10 source "NOT NULL, CHANGED V4 CHECK MOBILE or TABLET or QR or ADMIN"
        boolean synced_late "NOT NULL"
        varchar_20 status "NOT NULL, CHECK VALID or PENDING_REVIEW or REJECTED. REJECTED is first used in Stage 4"
        uuid client_uuid UK "NOT NULL, UK (company_id, client_uuid). Admin entries get a server-side random UUID. Never exposed in admin APIs"
        date work_date "NOT NULL, company-local shift date, NEVER changed by a correction"
        integer skew_seconds "NULL"
        integer late_minutes "NULL, CHECK only CLOCK_IN and >= 0. Recomputed from effective_time when a CLOCK_IN is corrected"
        integer gps_accuracy_meters "NULL, CHECK >= 0"
        varchar_200 review_reasons "NULL, CSV of ReviewReason, kept after approval. Empty set is stored as NULL, so admin entries pass the regex CHECK"
        bigint id_company_uk UK "UK (id, company_id). Index uq_time_entry_employee_work_date_type (employee_id, work_date, type) includes REJECTED rows"
    }

    EVIDENCE {
        bigint id PK "identity"
        bigint company_id "NOT NULL, tenant column"
        bigint time_entry_id FK "NOT NULL, composite FK (time_entry_id, company_id), UK (time_entry_id, type)"
        varchar_10 type "NOT NULL, PHOTO or GPS or QR. No DDL change. Admin-created entries have NO evidence rows"
        varchar_500 storage_key "NULL, tenant-prefix CHECK, never returned by any API"
        timestamptz created_at "NOT NULL"
    }

    ADJUSTMENT {
        bigint id PK "identity"
        bigint company_id "NOT NULL, tenant column"
        bigint time_entry_id FK "NOT NULL, composite FK (time_entry_id, company_id), index"
        bigint admin_id FK "NOT NULL, composite FK (admin_id, company_id), ADMIN role checked in the app. admin_id = employee of the entry means self-approved"
        varchar_20 action "NEW V4, NOT NULL, CHECK APPROVE or REJECT or CORRECT_TIME or CREATE_ENTRY"
        text reason "NOT NULL, CHECK btrim not empty, CHANGED V4 also CHECK char_length <= 500. The app requires 5..500"
        text field "NOT NULL, CHANGED V4 CHECK status or effective_time or late_minutes or entry"
        text old_value "NULL, plain text. NULL for CREATE_ENTRY"
        text new_value "NOT NULL, plain text, ISO instant with offset or status name or integer or entry description"
        timestamptz created_at "NOT NULL, one user action writes 1..3 rows with the same reason and created_at. New index (company_id, created_at)"
    }

    AUDIT_LOG {
        bigint id PK "NEW V4 table, identity"
        bigint company_id "NOT NULL, tenant column, no direct FK to company, anchored by the composite FK below"
        bigint actor_id FK "NOT NULL, composite FK (actor_id, company_id) to employee, fk_audit_log_actor_company"
        varchar_40 action "NOT NULL, CHECK regex ^[A-Z_]{3,40}$ so new AuditAction values need no migration"
        varchar_20 target_type "NOT NULL, CHECK TIME_ENTRY or EMPLOYEE or BRANCH or WORK_SCHEDULE or REPORT"
        bigint target_id "NULL, polymorphic by target_type, deliberately NO FK (REPORT has none)"
        varchar_500 details "NULL, ids and field names only. No PIN, no names, no coordinates"
        timestamptz created_at "NOT NULL, default now(). Index (company_id, created_at)"
    }

    COMPANY ||--o{ BRANCH : "has (company_id)"
    COMPANY ||--o{ EMPLOYEE : "employs (company_id)"
    BRANCH |o--o{ EMPLOYEE : "assigned, composite FK"
    EMPLOYEE ||--o{ WORK_SCHEDULE : "has up to 7, composite FK"
    EMPLOYEE ||--o{ TIME_ENTRY : "clocks, composite FK"
    BRANCH ||--o{ TIME_ENTRY : "where clocked, composite FK"
    TIME_ENTRY ||--o{ EVIDENCE : "max one PHOTO and one GPS, composite FK"
    TIME_ENTRY ||--o{ ADJUSTMENT : "adjusted by, composite FK, append-only"
    EMPLOYEE ||--o{ ADJUSTMENT : "admin_id, composite FK"
    EMPLOYEE ||--o{ AUDIT_LOG : "actor_id, composite FK, append-only"
```

### What V4 does, in order

| # | Statement | Why the order matters |
|---|---|---|
| 1 | `time_entry.effective_time` added as NULL, backfilled `= server_time`, then `SET NOT NULL` | Existing rows must be filled before the NOT NULL |
| 2 | `ck_time_entry_source` dropped and recreated with `ADMIN` | Name is the V2 name, so the constraint count does not change |
| 3 | `ix_time_entry_pending_review` dropped and recreated as `(company_id, work_date) WHERE status = 'PENDING_REVIEW'` | Review queue is one company, oldest work date first |
| 4 | `adjustment.action` added NULL, backfilled `CORRECT_TIME`, `SET NOT NULL`, then 3 new CHECKs and `ix_adjustment_company_created` | Backfill before NOT NULL and before the `field` CHECK |
| 5 | `branch.active boolean NOT NULL DEFAULT true` | Existing rows become active |
| 6 | `audit_log` table, `fk_audit_log_actor_company`, 2 CHECKs, `ix_audit_log_company_created` | New tenant table, tenant anchored by the composite FK |
| 7 | Function `forbid_audit_change()` and 4 triggers | Created **last**, after the backfill `UPDATE`s of steps 1 and 4, otherwise the migration would block itself |

### Append-only triggers

| Trigger | Table | Fires | Effect |
|---|---|---|---|
| `tr_adjustment_append_only` | `adjustment` | `BEFORE UPDATE OR DELETE`, per row | `RAISE EXCEPTION 'append-only table: adjustment'` |
| `tr_adjustment_no_truncate` | `adjustment` | `BEFORE TRUNCATE`, per statement | same |
| `tr_audit_log_append_only` | `audit_log` | `BEFORE UPDATE OR DELETE`, per row | same, for `audit_log` |
| `tr_audit_log_no_truncate` | `audit_log` | `BEFORE TRUNCATE`, per statement | same |

All four call the same function `forbid_audit_change()`. This is **defense in depth**: the repositories (`AdjustmentRepository`, `AuditLogRepository`) already expose only `save` and scoped finds. The app role owns the tables, so the trigger protects against application bugs and casual SQL, not against the table owner (Stage 5 can split migration and runtime roles). `ON DELETE RESTRICT` on every FK already blocks deleting an employee or a time entry that has audit rows.

New constraints and indexes (V4):

| Object | On | Rule |
|---|---|---|
| `ck_time_entry_source` (replaced) | `time_entry` | `source IN ('MOBILE','TABLET','QR','ADMIN')` |
| `ck_adjustment_action` | `adjustment` | `action IN ('APPROVE','REJECT','CORRECT_TIME','CREATE_ENTRY')` |
| `ck_adjustment_field` | `adjustment` | `field IN ('status','effective_time','late_minutes','entry')` |
| `ck_adjustment_reason_length` | `adjustment` | `char_length(reason) <= 500` |
| `fk_audit_log_actor_company` | `audit_log` | `(actor_id, company_id) -> employee (id, company_id)`, `ON DELETE RESTRICT` |
| `ck_audit_log_action` | `audit_log` | `action ~ '^[A-Z_]{3,40}$'` |
| `ck_audit_log_target_type` | `audit_log` | `target_type IN ('TIME_ENTRY','EMPLOYEE','BRANCH','WORK_SCHEDULE','REPORT')` |
| `ix_time_entry_pending_review` (recreated) | `time_entry` | partial `(company_id, work_date) WHERE status = 'PENDING_REVIEW'` |
| `ix_adjustment_company_created` | `adjustment` | `(company_id, created_at)` for the export Adjustments sheet |
| `ix_audit_log_company_created` | `audit_log` | `(company_id, created_at)` |

Key rules shown:
- **Two clocks on one row.** `server_time` is the legal receipt fact and never changes. `effective_time` is the time that counts. Admin corrections write only `effective_time` and leave `work_date` alone (the unique index and the pairing depend on it).
- **Which clock each reader uses.**

| Reads `effective_time` | Reads `server_time` |
|---|---|
| dashboard, review queue, evidence detail, Excel export, `TodayService.workedMinutes`, `EntrySummary.effectiveTime`, `LatenessCalculator` on correction | `ShiftResolver` (open shift, 16 h window, `SHIFT_TOO_LONG`), replay and idempotency, photo storage month, `skew_seconds`, the `...ServerTimeAfter...` lookback queries |

- **Admin-created rows** (`source = ADMIN`): `status = VALID`, `server_time = now`, `effective_time` = the converted local time, no `device_time`, no lat/lng, no `distance_meters`, no `evidence` rows, `review_reasons` NULL.
- **Tenant isolation is unchanged.** `audit_log` follows the same pattern as `time_entry`, `evidence` and `adjustment`: it carries `company_id` and anchors the tenant through a composite FK to `employee`. A cross-tenant actor is impossible at the database level.
- **No hard deletes.** `branch.active` and `employee.active` retire rows. Entries reference branches with `RESTRICT`.
- **Constraint and index counts for `SchemaEnglishNamingTest`:** see the notes at the end (the plan's count is off by one).

---

## 2. Layer diagrams

### 2a. Backend packages

Arrows point from the depending package to the package it uses. Solid arrows are compile-time dependencies. Dashed arrows are Spring bean injection by type or qualifier with no import, or a coupling that needs a decision. Nodes marked (new) do not exist yet.

```mermaid
flowchart TD
    subgraph WEB["Web layer, every class has class-level PreAuthorize hasRole ADMIN (new rule)"]
        direction LR
        ADMC["admin (new): AdminDashboardController, ReviewQueueController, AdminTimeEntryController, AdminBranchController. 4b: AdminEmployeeController, schedule endpoints"]
        PHC["timeentry: AdminTimeEntryPhotoController (modified: PreAuthorize, limiter, audit)"]
        REPC["report (new, 4c): ReportController"]
        EXH["common: ApiExceptionHandler (+14 ErrorCode values, +AccessDeniedException handler)"]
    end

    subgraph SVC["Service layer"]
        direction LR
        ADMS["admin (new): AdminDashboardService, ReviewService, AdminBranchService (4b), AdminEmployeeService (4b), AdminScheduleService (4b), AdminProperties"]
        RULES["admin (new, pure): DayStatusResolver, KpiCalculator, WorkedTime, CorrectionRules, DayAssembler"]
        REPS["report (new, 4c): ReportService, ExcelReportWriter, ExcelText"]
        AUD["audit (new): AuditService, AuditLogEntry, AuditLogRepository"]
        TSV["timeentry: TimeEntryWriter, TodayService, PhotoService (modified), ShiftResolver, LatenessCalculator, ReviewPolicy"]
        AUTHP["auth: PinGenerator (new, 4b), PinPolicy"]
        SEED["seed (dev): DevDataSeeder (ensure 1007-1012), DemoDayEntries (new)"]
    end

    subgraph DATA["Persistence: entities and tenant-scoped repositories"]
        direction LR
        TDATA["timeentry: TimeEntry (+effectiveTime, correctEffectiveTime, changeStatus), Adjustment (+action), AdjustmentAction (new), TimeEntrySource (+ADMIN), repositories (+batched, lock, page and count queries)"]
        EDATA["employee: Employee (+mutators), WorkSchedule, repositories (+IdIn, lock on admins, unlock)"]
        CDATA["company: Company, Branch (+active, mutators), repositories"]
    end

    CMN["common: EmployeePrincipal, Role, ApiException, ErrorCode, FixedWindowRateLimiter, AuditAction and AuditTargetType (new)"]
    CFG["config: SecurityConfig (+EnableMethodSecurity), TimeConfig (Clock), AdminRateLimits (new bean config)"]
    DB[("PostgreSQL 16 - Flyway V1..V4, 4 append-only triggers")]
    DISK[("Local disk: UPLOADS_DIR")]
    STG["storage: StorageService"]

    ADMC --> ADMS
    ADMC --> CMN
    PHC --> TSV
    REPC --> REPS
    REPC --> AUD
    REPC --> CMN
    EXH --> CMN

    ADMS --> RULES
    ADMS --> TSV
    ADMS --> TDATA
    ADMS --> EDATA
    ADMS --> CDATA
    ADMS --> AUD
    ADMS --> AUTHP
    ADMS --> CMN
    RULES --> TDATA
    RULES --> EDATA
    REPS --> RULES
    REPS --> ADMS
    REPS --> AUD
    REPS --> CMN
    AUD --> CMN
    AUD --> DB
    TSV -. "PHOTO_VIEW audit: timeentry now depends on audit (decision, see notes)" .-> AUD
    TSV --> STG
    STG --> DISK
    SEED --> TSV
    SEED --> STG

    TDATA --> EDATA
    TDATA --> CDATA
    EDATA --> CDATA
    TDATA --> DB
    EDATA --> DB
    CDATA --> DB

    ADMS -. "injects Clock and limiter beans" .-> CFG
    PHC -. "injects photo limiter bean by qualifier" .-> CFG
    REPC -. "injects export limiter bean by qualifier" .-> CFG
```

Dependency summary (new and changed rows):

| Package | Depends on | Notes |
|---|---|---|
| `admin` (new) | `timeentry`, `employee`, `company`, `audit`, `common`, `auth` (only `PinGenerator`, 4b) | Dashboard, review, branches, employees, schedules. Holds the shared pure rules `DayAssembler`, `DayStatusResolver`, `WorkedTime`, `KpiCalculator`, `CorrectionRules`. **Nothing depends on `admin` except `report`.** |
| `audit` (new) | `common` only | `AuditLogEntry` keeps plain `Long` ids, so it does not import `employee`. `AuditAction` and `AuditTargetType` live in `common` so a caller can name an action without importing `audit` internals. |
| `report` (new, 4c) | `admin`, `audit`, `common` | Reuses `DayAssembler` and `WorkedTime`, so the dashboard and the Excel file agree. Nothing depends on `report`. |
| `timeentry` (modified) | `storage`, `employee`, `company`, `common`, **`audit`** (new edge) | The only new edge into a leaf. The plan's hand-off arrows do not list it, but `PhotoService` must write `PHOTO_VIEW`. It does not create a cycle because `audit -> common` only. |
| `auth` | adds `PinGenerator` (4b) | Used by `admin` and by `seed` later. `auth` never depends on `admin`. |
| `config` | already depends on `auth` and `timeentry` (`TimeEntryRateLimitFilter`) | Adds `@EnableMethodSecurity` and the `AdminRateLimits` limiter beans. |
| `common` | adds `AuditAction`, `AuditTargetType`, 14 `ErrorCode` values, an `AccessDeniedException` handler | `common` still imports no feature package. |

Key rules shown:
- **No cycles.** The graph is a DAG: `report -> admin -> timeentry -> audit -> common`, with `admin -> audit` as well. `timeentry` must **never** import `admin`. That is why `AdminProperties` stays in `admin` and the photo limiter reaches `timeentry` as an injected `FixedWindowRateLimiter` bean (qualifier), not as a type from `admin`.
- **Two authorization layers.** URL rule `/api/admin/** -> hasRole("ADMIN")` stays in `SecurityConfig`. Class-level `@PreAuthorize("hasRole('ADMIN')")` is the second layer on every controller under `/api/admin`, including the two existing ones. A reflection test enforces it.
- **Tenant isolation.** Every repository method takes `companyId` from `EmployeePrincipal`. New repositories (`AuditLogRepository` and the batched queries) are added to `TenantRepositoryConventionTest`. Ids inside request bodies (`employeeId`, `branchId`) are loaded with `findByIdAndCompanyId`, so another tenant's id is 404.
- **Same URL prefix, two controllers.** `/api/admin/time-entries/{id}/photo` stays in `timeentry.AdminTimeEntryPhotoController`, while `GET /{id}`, the actions and `POST` live in `admin.AdminTimeEntryController` under the same base path. Spring allows this.
- **Pure rules are unit-testable.** `DayStatusResolver`, `KpiCalculator`, `WorkedTime`, `CorrectionRules` take values and a `Clock` instant, with no Spring and no repositories.

### 2b. Frontend: admin chunk versus employee bundle

The admin code is a separate Rolldown chunk, `assets/admin-<hash>.js` plus `admin-<hash>.css`, built by `build.rolldownOptions.output.codeSplitting.groups` for `src/admin/**` and `src/i18n/adminResources`. The service worker precache (`globIgnores: ['**/admin-*.js','**/admin-*.css']`) never contains it, so phones do not download the panel and the employee bundle does not grow. Modules that the admin code imports from the main app (`api/client`, `auth/*`, `ModalDialog`, `PinKeypad`, `ConfirmDialog`, `i18n/resources`) stay in the employee bundle and are **shared**, not duplicated.

```mermaid
flowchart TD
    subgraph EMP["Employee bundle (entry chunk and lazy SelfieCapture), precached by the service worker"]
        direction TB
        MAIN["main.tsx, App.tsx"]
        HDR["AppHeader (language switcher and logout)"]
        RS["auth/RequireSession (consent gate)"]
        EH["pages: LoginPage (role landing and safe next), ConsentPage, EmployeeHome"]
        EC["components: EntryTimes and EntryResult (show effectiveTime and adjusted note), ClockButton, PinKeypad, ModalDialog, ConfirmDialog"]
        CL["api/client.ts (+PUT, DELETE, apiBlob), api/types.ts (+effectiveTime, adjusted, TimeEntrySource), errors/errorMessages"]
        SES["auth/session and SessionProvider (token in sessionStorage, identity with role in IndexedDB, markNeedsReauth)"]
        RES["i18n/resources.ts (+14 error keys, usted copy) and useI18n"]
    end

    subgraph ADM["Admin chunk admin-hash.js and admin-hash.css, lazy, NOT precached"]
        direction TB
        AAPP["admin/AdminApp: Routes for /admin/*, ChunkErrorBoundary"]
        GUARD["RequireAdmin (cosmetic only, no identity goes to /login?next, role not ADMIN goes to /)"]
        LAYOUT["AdminLayout, AdminNav (review badge), AdminReauthDialog"]
        PAGES["pages: DashboardPage, ReviewQueuePage, ReportsPage (4c), EmployeesPage and EmployeeDetailPage (4b), BranchesPage (4b)"]
        COMP["components: KpiCards, AttendanceTable, DayStatusChip, EvidenceDialog, ReviewActionDialog, CreateEntryDialog, ReasonField, Pagination, PinRevealDialog (4b), ScheduleEditor (4b)"]
        HOOK["hooks/useAdminQuery and format.ts (company-zone Intl)"]
        AAPI["admin/api: adminApi.ts and adminTypes.ts"]
        AI18N["admin/adminI18n.ts and i18n/adminResources.ts (admin ES and EN strings)"]
        ACSS["admin/admin.css (imported only by AdminApp)"]
    end

    SW["Service worker precache: employee JS and CSS, html, icons. Contains no admin- file"]
    API[("Backend /api/admin/**")]
    OSM["External link only on click: Google Maps search URL, new tab, noopener noreferrer, nothing embedded"]

    MAIN --> HDR
    MAIN --> RS
    MAIN --> EH
    RS --> EC
    EH --> CL
    EC --> RES
    CL --> SES
    MAIN -. "lazy import on route /admin/*, outside RequireSession" .-> AAPP

    AAPP --> GUARD
    AAPP --> LAYOUT
    GUARD --> PAGES
    LAYOUT --> PAGES
    PAGES --> COMP
    PAGES --> HOOK
    COMP --> HOOK
    HOOK --> AAPI
    AAPI --> CL
    COMP --> AI18N
    PAGES --> AI18N
    AAPP --> ACSS
    LAYOUT -. "401 sets needsReauth" .-> SES
    COMP -. "shared UI" .-> EC
    COMP -.-> OSM

    CL --> API
    EMP -. "precached" .-> SW
```

Key rules shown:
- **One direction.** `App` points to `AdminApp`, never the other way. The employee bundle has no static import of anything under `admin/` or of `adminResources`. A bundle test checks the budget (employee JS <= 130 KB gzip, entry <= 110 KB, admin JS <= 80 KB, admin CSS <= 10 KB, precache <= 600 KB and no `admin-` file).
- **Admin route stays outside `RequireSession`.** `RequireAdmin` does its own check, and the consent gate must not apply to an admin. An admin who opens `/` for their own clock still goes through `RequireSession`.
- **The server is the real control.** `RequireAdmin` only avoids rendering a panel that would get 403s. A 401 on any admin call sets `needsReauth`, `AdminReauthDialog` opens over the page, and writes are never auto-replayed.
- **Binary downloads.** The evidence photo and the `.xlsx` go through `apiBlob` with the bearer token, never through a plain `<img src>` or `<a href>` to the API.
- **Chunk failure.** If the lazy import fails (offline, or an old hashed chunk after a deploy), `ChunkErrorBoundary` shows a retry message. The admin panel needs the network anyway.
- **Global header.** `App.tsx` renders `AppHeader` above every route, so `/admin/*` would show two language switchers and two logout buttons. See the notes at the end.

---

## 3. Sequence diagrams

Common to all admin endpoints: the request passes the Stage 1 security chain (JWT decode, per-request database re-check of existence, `active`, `token_version` and **role**), then the URL rule, then the class-level `@PreAuthorize`. A missing, invalid or stale token is 401. A non-admin is 403 with an empty body. `companyId` and the actor come only from `EmployeePrincipal`. These steps are drawn once here as `SEC` and are not repeated in every diagram.

### 3a. Admin dashboard load for a date

`GET /api/admin/dashboard?date=YYYY-MM-DD[&branchId=]`. Read-only. All queries are batched per request (not one query per employee) and carry `companyId`. The date is a company-local `LocalDate`. The admin PC zone is never used.

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin browser (DashboardPage)
    participant SEC as Security chain and PreAuthorize
    participant CT as AdminDashboardController
    participant SV as AdminDashboardService (readOnly transaction)
    participant CR as CompanyRepository
    participant BR as BranchRepository
    participant ER as EmployeeRepository
    participant WS as WorkScheduleRepository
    participant TR as TimeEntryRepository
    participant EV as EvidenceRepository
    participant AR as AdjustmentRepository
    participant DA as DayAssembler and pure rules
    participant DB as PostgreSQL

    Note over A: Triggers: first load, date or branch change, Actualizar button, visibilitychange, every 60 s while visible and the date is today. Photos are never prefetched.
    A->>SEC: GET /api/admin/dashboard?date=2026-10-07&branchId=3  Bearer token
    alt no token, stale employee, or token older than 6 h
        SEC-->>A: 401 (client sets needsReauth, AdminReauthDialog opens, no auto replay)
    else DB role is not ADMIN
        SEC-->>A: 403 (empty body, the UI shows session ended)
    end
    SEC->>CT: dispatch with EmployeePrincipal(employeeId, companyId, role)
    CT->>CT: bind date and branchId
    alt malformed date, or date outside plus or minus 5 years of today
        CT-->>A: 400 INVALID_REQUEST (value not echoed)
    end
    CT->>SV: dashboard(principal, date, branchId)

    SV->>CR: findById(companyId)
    CR->>DB: SELECT company
    SV->>SV: zone = ZoneId.of(company.timeZone), now = Instant.now(clock), today = now in zone
    Note over SV: date defaults to today in the company zone. A future date is allowed and every row becomes NOT_YET_DUE or DAY_OFF.
    opt branchId given
        SV->>BR: findByIdAndCompanyId(branchId, companyId)
        BR->>DB: SELECT branch
        alt branch of another tenant or missing
            SV-->>A: 404 NOT_FOUND
        end
    end

    SV->>TR: findAllByCompanyIdAndWorkDate(companyId, date)
    TR->>DB: SELECT time_entry WHERE company_id and work_date (index ix_time_entry_company_work_date)
    SV->>ER: active employees with a branch, plus employees with an entry that day (findAllByCompanyIdAndIdIn)
    ER->>DB: SELECT employee WHERE company_id and (active and branch_id not null or id IN entry employees)
    SV->>WS: findAllByCompanyIdAndDayOfWeek(companyId, ISO weekday of date)
    WS->>DB: SELECT work_schedule WHERE company_id and day_of_week
    SV->>EV: findAllByCompanyIdAndTimeEntryIdInAndType(PHOTO)
    EV->>DB: SELECT evidence WHERE company_id and time_entry_id IN ids and type PHOTO
    SV->>AR: time entry ids that have at least one adjustment
    AR->>DB: SELECT DISTINCT time_entry_id FROM adjustment WHERE company_id and time_entry_id IN ids
    SV->>BR: branches of the rows (names, filter)
    BR->>DB: SELECT branch WHERE company_id

    SV->>DA: DayAssembler.assemble(employees, schedules, entries, evidence, adjusted, date, now, zone)
    Note over DA: Applies the branch filter by the entry branch when an entry exists, otherwise the employee current branch. Sorted by name, DAY_OFF rows last.
    alt more rows than app.admin.max-dashboard-rows (1000)
        DA-->>A: 400 REPORT_TOO_LARGE (the UI asks for a branch filter)
    end
    loop each row
        DA->>DA: WorkedTime from effective_time, floor of (out minus in) in minutes, null when in progress or REJECTED
        DA->>DA: DayStatusResolver, first match of 11 rules (REJECTED, OUTSIDE_AREA, NEEDS_REVIEW, MISSING_CLOCK_OUT, COMPLETE, LATE, ON_TIME, UNSCHEDULED, NOT_YET_DUE, NOT_CLOCKED, DAY_OFF)
    end
    DA->>DA: KpiCalculator over the filtered rows (present of expected, late, notClocked plus notYetDue, toReview and toReviewByReason)
    DA-->>SV: DashboardResponse
    SV-->>CT: DashboardResponse
    CT-->>A: 200 {date, today, timeZone, companyName, branchId, kpis, rows, generatedAt}
    Note over A: Times arrive as ISO instants and are formatted with Intl in the company zone from the identity. Entrada and Salida show effective_time and an edited marker when adjusted is true.
```

Key rules shown:
- **Every number comes from `effective_time`.** `WorkedTime` and `late_minutes` use the corrected time. `server_time` is returned only in `AdminEntrySummary` for the detail views.
- **The same code feeds the export.** `DayAssembler` and `DayStatusResolver` are shared with `ReportService`, so the dashboard chip and the Excel "Estado del dia" can never disagree.
- **Bounded queries.** Five or six batched queries per request, all with `companyId`. The 1000-row cap turns an oversized company into a 400 instead of a slow page.
- **No coordinates and no `client_uuid`** in rows. `hasGps`, `distanceMeters` and `insideGeofence` are enough for the Evidencia column.
- **Admins without a branch** are not listed (they cannot have entries). An admin with a branch appears like any employee.

### 3b. Review: approve, reject and correct

Three POST endpoints share one flow: `POST /api/admin/time-entries/{id}/approve | reject | correct`. Each writes 1 to 3 `adjustment` rows in one transaction, with the same `reason` and `created_at`. `server_time` and `work_date` are never touched. The transition table is the plan's: approve from `PENDING_REVIEW` or `REJECTED`, reject from `PENDING_REVIEW` or `VALID`, correct from any state (and then `VALID`).

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin browser (ReviewActionDialog)
    participant SEC as Security chain and PreAuthorize
    participant CT as AdminTimeEntryController
    participant SV as ReviewService (transactional)
    participant TR as TimeEntryRepository
    participant CR as CompanyRepository
    participant WS as WorkScheduleRepository
    participant RU as Pure rules (CorrectionRules, LatenessCalculator)
    participant AR as AdjustmentRepository
    participant DB as PostgreSQL

    A->>SEC: POST /api/admin/time-entries/{id}/correct  {reason, expectedStatus, expectedEffectiveTime, localTime}
    Note over A: Writes are never auto-replayed. After a 401 the user re-authenticates and repeats the action.
    SEC->>CT: dispatch (401 or 403 as in the common note)
    CT->>CT: Bean Validation (reason trimmed 5..500 with no control characters, enums, localTime YYYY-MM-DDTHH:mm)
    alt invalid body
        CT-->>A: 400 INVALID_REQUEST (no echo)
    end
    CT->>SV: correct(principal, id, request)
    activate SV
    SV->>DB: select set_config('lock_timeout', '5000ms', true)
    SV->>TR: findWithLockByIdAndCompanyId(id, companyId) PESSIMISTIC_WRITE
    TR->>DB: SELECT time_entry WHERE id and company_id FOR UPDATE
    alt entry missing or of another tenant
        SV-->>A: 404 NOT_FOUND (same body for both)
    else lock wait longer than lock_timeout
        SV-->>A: 503 SERVER_BUSY + Retry-After
    end

    alt expectedStatus differs from entry.status, or (correct) expectedEffectiveTime differs
        SV-->>A: 409 ENTRY_CHANGED (the UI reloads the item, another admin or the employee was first)
    end

    alt action is approve
        alt status is not PENDING_REVIEW and not REJECTED
            SV-->>A: 409 INVALID_STATUS_CHANGE (no-op, nothing written)
        end
        SV->>SV: entry.changeStatus(VALID)
        SV->>AR: save Adjustment(action APPROVE, field status, old PENDING_REVIEW or REJECTED, new VALID)
        Note over SV: review_reasons are kept so the export can show approved but was OUTSIDE_GEOFENCE
    else action is reject
        alt status is not PENDING_REVIEW and not VALID
            SV-->>A: 409 INVALID_STATUS_CHANGE
        end
        SV->>SV: entry.changeStatus(REJECTED)
        SV->>AR: save Adjustment(action REJECT, field status, old status, new REJECTED)
    else action is correct
        SV->>CR: findById(companyId) for time_zone
        CR->>DB: SELECT company
        SV->>SV: newInstant = LocalDateTime.parse(localTime).atZone(ZoneId.of(timeZone)).toInstant()
        SV->>TR: the other entry of the pair (companyId, employeeId, work_date, other type)
        TR->>DB: SELECT time_entry
        SV->>RU: CorrectionRules.validate(type, workDate, newInstant, now, pair effective_time, max-shift-duration 16 h)
        Note over RU: not later than now plus 1 min. CLOCK_IN between work_date 00:00 local minus 12 h and work_date 24:00 local and before the CLOCK_OUT effective_time. CLOCK_OUT strictly after the CLOCK_IN effective_time and at most 16 h after it.
        alt rule violated
            RU-->>A: 400 INVALID_ENTRY_TIME
        end
        alt newInstant equals the current effective_time to the minute
            SV-->>A: 409 INVALID_STATUS_CHANGE (no-op)
        end
        opt entry.type is CLOCK_IN
            SV->>WS: schedule for (employee, ISO weekday of work_date), current schedule
            WS->>DB: SELECT work_schedule
            SV->>RU: LatenessCalculator.lateMinutes(newInstant, workDate, zone, schedule)
            RU-->>SV: new late_minutes (0, n, or null without schedule)
        end
        SV->>SV: entry.correctEffectiveTime(newInstant, newLateMinutes)
        SV->>AR: save Adjustment(CORRECT_TIME, effective_time, old ISO instant, new ISO instant)
        opt CLOCK_IN and late_minutes changed
            SV->>AR: save Adjustment(CORRECT_TIME, late_minutes, old, new)
        end
        opt entry was not VALID
            SV->>SV: entry.changeStatus(VALID) (correcting means the admin reviewed it)
            SV->>AR: save Adjustment(CORRECT_TIME, status, old, VALID)
        end
    end

    SV->>DB: flush UPDATE time_entry and INSERT adjustment rows
    Note over DB: Triggers allow INSERT on adjustment. Any UPDATE or DELETE on adjustment raises append-only table.
    alt flush or commit fails (DataIntegrityViolationException)
        DB-->>SV: error, ROLLBACK (no partial adjustment rows)
        SV-->>A: 500 INTERNAL_ERROR (mapped outside the transaction, see notes)
    else committed
        DB-->>SV: COMMIT, row lock released
        deactivate SV
        SV-->>CT: AdminEntryDetail (with adjustments history, admin name)
        CT-->>A: 200 AdminEntryDetail
        Note over A: The item leaves the queue, the nav badge count is refreshed from GET /api/admin/review-queue/count, and the polite status region announces the result.
    end
```

Key rules shown:
- **Optimistic check under a pessimistic lock.** The row lock serializes two admins. The `expected*` fields tell the second one that the world changed: 409 `ENTRY_CHANGED`, and the UI reloads the item instead of overwriting.
- **A no-op writes nothing** (409 `INVALID_STATUS_CHANGE`): approving a `VALID` entry, or correcting to the same minute.
- **Audit rows are in the same transaction as the change**, so either both exist or neither. The batch shares `reason` and `created_at`.
- **Lateness is recomputed with the current schedule.** Schedules are not versioned. The `late_minutes` change is its own adjustment row so the history explains it.
- **Self-approval is allowed** (Open Question 4) and visible: `admin_id` equals the entry's `employee_id`, flagged "Autoaprobado" in the export.
- **What a correction never does:** change `server_time`, change `work_date`, re-open or close a shift for `ShiftResolver`, or delete anything.

### 3b2. Admin-created entry (missing clock-out, broken camera, lockout)

`POST /api/admin/time-entries` `{employeeId, type, workDate, localTime, reason}`. It takes the **employee row lock**, the same lock as the PWA `POST /api/time-entries`, so the two serialize. The unique index `(employee_id, work_date, type)` is the backstop.

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin browser (CreateEntryDialog)
    participant PW as Employee PWA (concurrent POST)
    participant CT as AdminTimeEntryController
    participant SV as ReviewService (transactional)
    participant ER as EmployeeRepository
    participant BR as BranchRepository
    participant TR as TimeEntryRepository
    participant WS as WorkScheduleRepository
    participant RU as Pure rules
    participant AR as AdjustmentRepository
    participant DB as PostgreSQL

    A->>CT: POST /api/admin/time-entries {employeeId, type CLOCK_OUT, workDate, localTime, reason}
    Note over CT: Security chain and PreAuthorize as in the common note. Bean Validation as in 3b.
    CT->>SV: create(principal, request)
    activate SV
    SV->>DB: select set_config('lock_timeout', '5000ms', true)
    SV->>ER: findWithLockByIdAndCompanyId(employeeId, companyId)
    ER->>DB: SELECT employee FOR UPDATE
    Note over SV,DB: The PWA POST of the same employee waits here, or this request waits for it. One of the two wins.
    PW-->>DB: concurrent POST waits for the employee row lock
    alt employee missing or of another tenant
        SV-->>A: 404 NOT_FOUND
    else lock wait too long
        SV-->>A: 503 SERVER_BUSY
    end
    opt employee has no branch
        SV-->>A: 409 NO_BRANCH_ASSIGNED (an inactive employee is allowed, historical fix)
    end
    SV->>BR: findByIdAndCompanyId(employee.branchId, companyId)
    BR->>DB: SELECT branch
    SV->>TR: findByCompanyIdAndEmployeeIdAndWorkDateAndType for this type and for CLOCK_IN
    TR->>DB: SELECT time_entry WHERE employee and work_date and type
    alt an entry of this type already exists for that work date
        SV-->>A: 409 ENTRY_ALREADY_EXISTS
    else type is CLOCK_OUT and no CLOCK_IN on that work date
        SV-->>A: 409 CLOCK_IN_REQUIRED
    end
    SV->>RU: CorrectionRules.validate(...) and workDate not in the future
    alt time in the future, outside the work day, or not after the CLOCK_IN
        RU-->>A: 400 INVALID_ENTRY_TIME
    end
    opt type is CLOCK_IN
        SV->>WS: schedule for the weekday of work_date
        SV->>RU: LatenessCalculator.lateMinutes(effective, workDate, zone, schedule)
    end
    SV->>TR: save TimeEntry(source ADMIN, status VALID, server_time now, effective_time converted local time, client_uuid random, no device_time, no GPS)
    TR->>DB: INSERT time_entry (no evidence rows)
    SV->>AR: save Adjustment(CREATE_ENTRY, field entry, old NULL, new description with type and ISO instant)
    AR->>DB: INSERT adjustment
    alt unique index violation (a concurrent writer committed first)
        DB-->>SV: uq_time_entry_employee_work_date_type
        SV-->>A: 409 ENTRY_ALREADY_EXISTS (mapped outside the transaction)
    else committed
        DB-->>SV: COMMIT, employee lock released
        deactivate SV
        SV-->>A: 201 AdminEntryDetail
        DB-->>PW: the waiting PWA POST now sees the new entry and gets ALREADY_CLOCKED_IN or NOT_CLOCKED_IN or SHIFT_ALREADY_COMPLETED
    end
```

Key rules shown:
- **Same lock as the PWA.** Both writers lock the `employee` row, so an admin-created entry and a phone clock-in of the same employee never interleave. The loser gets a 409 with a stable code.
- **`server_time = now`, `effective_time` = the typed time.** For a CLOCK_IN created for today, `ShiftResolver` sees an open shift and the employee can clock out from the phone. See the note about past work dates.
- **No evidence rows.** The dashboard shows "Registro manual", and the entry never needs review (`VALID`, no reasons).

### 3c. Evidence view with photo access logging and rate limit

Two requests. The detail is the **only** endpoint that returns exact coordinates. The photo is fetched as a blob only when the dialog opens, and every successful photo view writes one `PHOTO_VIEW` audit row. The limiter is per admin: 300 views per 10 minutes.

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin browser (EvidenceDialog)
    participant SEC as Security chain and PreAuthorize
    participant DC as AdminTimeEntryController
    participant DS as Entry detail service
    participant PC as AdminTimeEntryPhotoController
    participant RL as FixedWindowRateLimiter (photo, per admin)
    participant PS as PhotoService
    participant AU as AuditService
    participant TR as TimeEntryRepository
    participant EV as EvidenceRepository
    participant AR as AdjustmentRepository
    participant ST as StorageService
    participant DB as PostgreSQL

    A->>SEC: GET /api/admin/time-entries/{id}
    SEC->>DC: dispatch (401 or 403 as in the common note)
    DC->>DS: detail(principal, id)
    DS->>TR: findByIdAndCompanyId(id, companyId)
    TR->>DB: SELECT time_entry
    alt missing or other tenant
        DS-->>A: 404 NOT_FOUND
    end
    DS->>AR: findAllByCompanyIdAndTimeEntryIdOrderByCreatedAt
    AR->>DB: SELECT adjustment JOIN employee for admin name
    DS-->>A: 200 AdminEntryDetail (effectiveTime, serverTime, deviceTime, skew, status, reasons, distance, accuracy, location, branch, pair, adjustments). No client_uuid, no storage key.
    Note over A: The dialog shows the facts, the distance versus the radius, the exact coordinates next to the Google Maps link (opened only on click, new tab, noopener noreferrer, nothing embedded), and the adjustment history.

    A->>SEC: GET /api/admin/time-entries/{id}/photo via apiBlob
    SEC->>PC: dispatch (PreAuthorize on the class)
    PC->>RL: tryAcquire(companyId:adminEmployeeId)
    alt more than 300 views in 10 min
        RL-->>A: 429 TOO_MANY_ATTEMPTS + Retry-After (no audit row, no file read)
    end
    PC->>PS: photoFor(companyId, null, id)
    PS->>TR: findByIdAndCompanyId(id, companyId)
    TR->>DB: SELECT time_entry
    PS->>EV: findByCompanyIdAndTimeEntryIdAndType(PHOTO)
    EV->>DB: SELECT evidence
    alt no entry, other tenant, no PHOTO row, key NULL (purged), or source ADMIN
        PS-->>A: 404 NOT_FOUND (identical body, the dialog shows Sin foto)
    end
    PS->>ST: load(companyId, storage_key)
    alt key invalid, wrong tenant prefix, or file missing
        ST-->>PS: empty
        PS-->>A: 404 (WARN log with the entry id only)
    end
    ST-->>PS: Resource
    PC->>AU: record(PHOTO_VIEW, TIME_ENTRY, id, details with ids only)
    AU->>DB: INSERT audit_log (company_id, actor_id, PHOTO_VIEW, TIME_ENTRY, id, created_at)
    Note over AU,DB: Only a successful load is audited. The same transaction cannot be the read-only one in PhotoService, see the notes. The log line is INFO photo_access actor, entry, company, ids only.
    alt audit insert fails
        AU-->>A: 500 INTERNAL_ERROR (fail closed, no photo is served without its audit row)
    end
    PC-->>A: 200 image/jpeg, Cache-Control private no-store, Content-Disposition inline, nosniff
    Note over A: The blob becomes an object URL for the img element. The URL is revoked when the dialog closes or unmounts. CSP img-src self blob is already allowed.
```

Key rules shown:
- **Coordinates only in the detail.** Lists and the dashboard never carry latitude or longitude. The map link opens an external site only when clicked, so the CSP does not change.
- **Same 404 for every miss.** The existence of an entry, an employee or a file is never leaked. A 429 is the only other photo failure.
- **Audit on success only, limiter on every attempt.** Drawing the limiter first (as the Stage 2 `TimeEntryRateLimitFilter` does for POSTs) means a scripted loop of ids is throttled even when it only gets 404s. The plan says "over that gives 429" without fixing the order; see the notes.
- **The employee's own photo route is not audited** (their own data) and not limited by this limiter.
- **No prefetch and no cache.** `Cache-Control: private, no-store`, no runtime caching in the service worker, object URL revoked on close.

### 3d. Excel export (4c)

`GET /api/admin/reports/excel?from&to[&branchId]`. The workbook is built **in memory** with `XSSFWorkbook` (at most 20 000 detail rows), so no temp files holding personal data are written to disk. The rows come from the same `DayAssembler` as the dashboard, read in batches per range, not per day.

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin browser (ReportsPage or dashboard export button)
    participant SEC as Security chain and PreAuthorize
    participant CT as ReportController
    participant RL as FixedWindowRateLimiter (export, per admin)
    participant RS as ReportService (readOnly transaction)
    participant DA as DayAssembler and WorkedTime
    participant DB as PostgreSQL
    participant XW as ExcelReportWriter and ExcelText
    participant AU as AuditService

    A->>SEC: GET /api/admin/reports/excel?from=2026-10-01&to=2026-10-31  via apiBlob
    SEC->>CT: dispatch (401 or 403 as in the common note)
    CT->>RL: tryAcquire(companyId:adminEmployeeId)
    alt more than 10 exports in 10 min
        RL-->>A: 429 TOO_MANY_ATTEMPTS + Retry-After
    end
    CT->>CT: validate from, to, branchId
    alt malformed date, from after to, or more than 62 days inclusive
        CT-->>A: 400 DATE_RANGE_TOO_LARGE or INVALID_REQUEST (no echo)
    end
    CT->>RS: build(principal, from, to, branchId, locale)
    activate RS
    RS->>DB: company (name, time_zone), branch if filtered (tenant scoped, 404 if foreign)
    RS->>DB: employees, schedules for the range, time_entry WHERE work_date BETWEEN from AND to, adjustments by (company_id, created_at) joined to the entry work_date, admin names
    RS->>DA: assemble every employee x work_date with a schedule or an entry, reusing DayStatusResolver and WorkedTime
    Note over DA: Approved minutes count only when both entries are VALID. Pending minutes are days with a PENDING_REVIEW entry. REJECTED days count 0. Hours come from effective_time. Overtime is not classified.
    alt more than 20000 detail rows
        DA-->>A: 400 REPORT_TOO_LARGE
    end
    DA-->>RS: day records, summary per employee, adjustment rows
    RS->>XW: write(records, locale from Accept-Language, default es)
    loop each of the 4 sheets (Resumen, Detalle, Ajustes, Info)
        XW->>XW: ExcelText.sanitize on every free-text cell, strip XML-illegal characters, cut at 32767 characters
        XW->>XW: setCellValue(String) only, quote-prefixed style when the first character is = + - @ tab CR LF or a full-width trigger. Never setCellFormula, never a hyperlink
        XW->>XW: numeric cells for hours (minutes/60 to 2 decimals and minutes/1440 with [h]:mm), no coordinates, no storage keys, no photos
    end
    XW-->>RS: in-memory workbook
    RS-->>CT: workbook
    deactivate RS

    CT->>AU: record(REPORT_EXPORT, REPORT, null, details from, to, branchId)
    AU->>DB: INSERT audit_log (own committed write)
    Note over CT,AU: The audit row is written after the workbook is built and before the first byte is streamed. A failed build leaves no export row, a written row always means a file was produced.
    CT-->>A: 200 .xlsx stream, Content-Type spreadsheetml.sheet, Content-Disposition attachment filename asistencia_COMPANYCODE_from_to.xlsx, Cache-Control no-store
    CT->>XW: close workbook in finally
    Note over A: apiBlob parses the filename, URL.createObjectURL, a hidden anchor with download is clicked and removed, then revokeObjectURL on the next tick.
```

Key rules shown:
- **Bounded on three sides.** Rate limit 10 per 10 min, range at most 62 days, at most 20 000 detail rows. All violations are 4xx before any workbook memory is allocated, except the row cap, which is checked on the assembled data.
- **Payroll-safe numbers.** Approved hours and pending hours are separate columns. The totals use `effective_time` and only fully `VALID` pairs.
- **Injection guard.** Free text cannot become a formula, a DDE call or a link. The Info sheet states the zone and the legend.
- **Audited.** One `REPORT_EXPORT` row per successful export, with ids and dates only.

---

## 4. State diagram: time entry status

Which action moves an entry between statuses. Every transition writes `adjustment` rows and keeps `server_time` and `review_reasons`. A new entry starts in `VALID` or `PENDING_REVIEW` from the PWA (`ReviewPolicy`), or in `VALID` from an admin.

```mermaid
stateDiagram-v2
    [*] --> VALID : PWA POST, no review reasons
    [*] --> PENDING_REVIEW : PWA POST, at least one review reason
    [*] --> VALID : Admin creates the entry (source ADMIN)

    PENDING_REVIEW --> VALID : Approve (adjustment status)
    PENDING_REVIEW --> REJECTED : Reject (adjustment status)
    PENDING_REVIEW --> VALID : Correct time (adjustments effective_time, late_minutes, status)

    VALID --> REJECTED : Reject, spot check (adjustment status)
    VALID --> VALID : Correct time (adjustments effective_time, late_minutes)

    REJECTED --> VALID : Approve, undo (adjustment status)
    REJECTED --> VALID : Correct time (adjustments effective_time, late_minutes, status)

    note right of REJECTED
        The row stays in the unique index (employee, work_date, type).
        The employee cannot clock in again for that day.
    end note
```

`VALID -> VALID` by approve and `REJECTED -> REJECTED` by reject are not transitions: they return 409 `INVALID_STATUS_CHANGE` and write nothing.

### 4b. Dashboard row: open shift vs missing clock-out (code review #1, 2026-10-30)

A CLOCK_IN without a CLOCK_OUT is judged by time since the clock-in, not by the calendar date, so overnight shifts stay "en turno" after midnight. `maxShift` = 16 h (`app.time-entry.max-shift-duration`).

```mermaid
stateDiagram-v2
    [*] --> EN_TURNO : CLOCK_IN, no CLOCK_OUT, now <= in.effectiveTime + maxShift
    EN_TURNO --> COMPLETO : CLOCK_OUT arrives (PWA or admin)
    EN_TURNO --> MISSING_CLOCK_OUT : now > in.effectiveTime + maxShift
    MISSING_CLOCK_OUT --> COMPLETO : Admin "Registrar salida" (offered only here)

    note right of EN_TURNO
        inProgress = true, hours column "en turno",
        no "Registrar salida" action.
        Example: 22:00 on day D, viewed 00:30 on D+1.
    end note
```

Other review #1 rules: admin-created `workDate` and review-queue `from`/`to` must be within today − 5 years … today (400 otherwise, as on the dashboard date); nullable `adjustment.old_value`/`new_value` are stored as SQL `NULL`, never the text `"null"`.

---

## 5. Notes for the coder

Inconsistencies between the plan and the live code, or inside the plan, found while drawing. Ordered by how likely they are to break the build or a test.

1. **`SchemaEnglishNamingTest` counts are wrong in the plan.**
   - The test selects `pg_constraint` rows with `contype in ('u','c','f')` and expects exactly 45. A primary key is `'p'` and is **not** counted. The plan lists `audit_log_pkey` among the constraints to add, but it must not be added to `EXPECTED_CONSTRAINTS`.
   - Recount: V4 replaces `ck_time_entry_source` (net 0) and adds `ck_adjustment_action`, `ck_adjustment_field`, `ck_adjustment_reason_length`, `fk_audit_log_actor_company`, `ck_audit_log_action`, `ck_audit_log_target_type`. That is **6**, so the new total is **51**, not 52.
   - Indexes: the test's list of `ix_%` names goes from 7 to **9** (adds `ix_adjustment_company_created`, `ix_audit_log_company_created`; `ix_time_entry_pending_review` keeps its name). The second index test (line 91) reads the `indexdef` of `ix_time_entry_pending_review`, so its expected text must change to `(company_id, work_date)`.
   - The method name `constraintNamesAreExactlyTheFortyFiveEnglishOnes` and the "seven" index test name must be renamed.

2. **Existing tests insert into `adjustment` without `action`, and one uses a `field` that V4 forbids.** V4 makes `action` `NOT NULL` with no default and checks `field IN ('status','effective_time','late_minutes','entry')`. These inserts will fail after V4:
   - `SchemaMigrationTest` lines 157-158: `field = 'server_time'` (rejected by `ck_adjustment_field`), and the null-field insert at 161-162 no longer proves what it proves today, because it would fail on `action` first.
   - `TenantConstraintsTest` lines 69-70 and 82-83, and `SchemaRangeConstraintsTest` lines 140-141, 143-144 and 158-159, all omit `action`.
   - The plan only tells the coder to grep for `delete`/`truncate` on `adjustment`. It must also update these 6 insert statements (add `action`, use an allowed `field`), and keep each negative test failing for its original reason (name the constraint in the assertion).
   - The `Adjustment` entity comment ("e.g. status or server_time") is stale after V4.

3. **`@PreAuthorize` will produce 500 instead of 403 until the handler is fixed.** `ApiExceptionHandler` has `@ExceptionHandler(Exception.class)` (the last resort that returns 500 `INTERNAL_ERROR`). An `AccessDeniedException` thrown by method security inside a controller is resolved by MVC exception resolvers before it reaches the security filter, so this catch-all will answer 500. The plan notes the risk (step 11). Confirmed in code: add an explicit handler that rethrows or returns the empty 403 (same as the URL layer), and test it with a non-admin token on a method-secured endpoint reached through a path the URL rule does not cover.

4. **Admin-created entries for a past `work_date` look like a fresh open shift to `ShiftResolver`.**
   - The plan sets `server_time = now` for admin entries and says the Stage 2 sequence rules keep using `server_time`. `ShiftResolver.unclosed(...)` and `isFresh(...)` read `server_time`, and the lookback query is `...ServerTimeAfter...`.
   - Case: an employee has no entry on Monday. On Tuesday morning the admin registers only a Monday CLOCK_IN (a `NOT_CLOCKED` row action). That row has `server_time = now`, is unclosed and "fresh", so `openShift` returns it. The employee's Tuesday CLOCK_IN is then refused with `ALREADY_CLOCKED_IN`, and `GET /today` shows `nextAction = CLOCK_OUT` for Monday, until the admin also adds the Monday CLOCK_OUT.
   - Today's work date is already open through the `workDate.equals(localToday)` branch of `isOpen`, so `server_time = now` is only needed to avoid a false `SHIFT_TOO_LONG` on the employee's later CLOCK_OUT (the 16 h freshness check).
   - Needs a decision from the planner: for `source = ADMIN` entries, either ignore them in `openShift` unless `work_date` is the current local day, or restrict admin CLOCK_IN creation to complete pairs. Add a `ShiftResolverTest` case either way.

5. **Lock order and the correct flow.** The PWA POST and admin create lock the **employee** row. Approve, reject and correct lock only the **time_entry** row. A correction of a CLOCK_IN checks "stay before the existing CLOCK_OUT effective_time", but a CLOCK_OUT that the employee posts at the same moment is not covered by that lock. The allowed correction ceiling is `now + 1 min`, so it can land after a CLOCK_OUT whose `effective_time` is `now`. Suggested rule: for a CLOCK_IN or CLOCK_OUT correction take the employee lock first, then the entry lock (always in that order, to avoid deadlock with the PWA, which takes only the employee lock). The plan should state it.

6. **`PhotoService` is `@Transactional(readOnly = true)`, but `PHOTO_VIEW` is an INSERT.** An audit write inside that transaction would fail on PostgreSQL read-only transactions, and writing it inside a read-only Hibernate session is not reliable. Either `AuditService.record` runs `REQUIRES_NEW`, or the controller calls the audit after `photoFor` returns. The plan does not say which. Also decide the order of limiter and lookup (diagram 3c draws the limiter first, so every attempt counts, like the Stage 2 POST limiter).
   - Note on the Stage 2 doc: `stage-2-time-entry.md` amendment 1 says the POST limiter is a `HandlerInterceptor` (`TimeEntryRateLimitInterceptor`). The live code is a servlet **filter**, `TimeEntryRateLimitFilter`, added in `SecurityConfig` after `BearerTokenAuthenticationFilter`. The Stage 2 text is stale. The admin limiters can follow the same filter pattern (path-matched) or be called from the controller.

7. **`timeentry -> audit` is a new edge that the plan's hand-off does not list.** The hand-off says `admin -> timeentry, ..., audit` and `audit -> common`, nothing else. But step 10 has `PhotoService` (package `timeentry`) call `auditService.record`. This is acyclic (diagram 2a) as long as `audit` and `common` never import `timeentry` or `admin`. Keep `AdminProperties`, `AdminRateLimits` types out of any package that `timeentry` imports (the plan does not say where `AdminRateLimits` lives; `config` with qualified `FixedWindowRateLimiter` beans keeps it clean).

8. **The 16 h and 24 h shift limits disagree.** `app.time-entry.max-shift-duration` (16 h) drives `ShiftResolver`, `SHIFT_TOO_LONG` and the `MISSING_CLOCK_OUT` rule 4 of `DayStatus`. `app.admin.max-shift-hours` (24 h) allows a CLOCK_OUT correction up to 24 h after the CLOCK_IN. So an admin can approve a 20 h day that the PWA would have flagged, or correct a 17 h CLOCK_OUT without any review reason. Probably intended (the admin is the authority), but the plan should say so, and `DayStatus` rule 4 should keep using the 16 h value.

9. **Stale OpenStreetMap text after Open Question 6 (Google Maps).** The resolved section says "replace every OpenStreetMap reference". Still unreplaced in the plan body: Decisions > Evidence viewer item 3 (URL, "second link shows the branch's position", "coordinates rounded to 6 decimals"), the CSP section ("external links only to `openstreetmap.org`"), Security item 6, the 4a checklist ("OSM link"), the manual check step 3 ("OSM link"), and the `osmUrl` helper in `admin/format.ts` (rename to a Google Maps helper). The resolved text also adds that the single-entry view shows the **exact coordinates** next to the link: the Decisions text and the Security rule "exact coordinates only in the single-entry detail" are consistent with that, but the edge case "Branch at (0,0)... OSM preview link" in 4b needs the same replacement. A Google Maps URL sends the coordinates to Google on click, which `Referrer-Policy: no-referrer` does not prevent: this is the expected, user-accepted trade-off, but the privacy notice text should mention it.

10. **Global `AppHeader` shows on `/admin/*`.** `App.tsx` renders `<AppHeader />` (language switcher and a logout button) above every route. The plan's admin shell has its own header (company, branch filter, "Mi marca", language, logout), so the admin pages would show two language switchers and two logout buttons. The plan only says to keep `/admin/*` outside `RequireSession`. The coder and designer must decide: hide `AppHeader` when the path starts with `/admin`, or make `AdminLayout` the only header. The current code also mounts `/admin` as an exact route, not `/admin/*`.

11. **Mutation without setters and a transaction split.**
    - `TimeEntry` has no setters and `withEvidence` is creation-only. The plan's `correctEffectiveTime` and `changeStatus` mutators are new, as listed. `effectiveTime` must be set in the **constructor** (= `serverTime`), because `TimeEntryWriter` builds the row with that constructor and `effective_time` is `NOT NULL`. `DevDataSeeder`, and any test that builds `TimeEntry` directly, also go through the constructor.
    - The plan says admin services map `DataIntegrityViolationException` "outside the transaction (the Stage 2 pattern)". Stage 2 does this by keeping `TimeEntryService` non-transactional and `TimeEntryWriter` transactional. A single `@Transactional ReviewService` (as named in hand-off item 3) throws the exception at commit, **after** its own catch block. Use a thin non-transactional facade plus a transactional writer, or catch in the controller layer. Diagrams 3b and 3b2 draw one `ReviewService` for readability.

12. **Smaller points.**
    - `DevSeedContentTest` deletes `work_schedule`, `employee`, `branch` and the `DEMO` company directly. With the opt-in demo day, or any test that leaves `adjustment` or `audit_log` rows for `DEMO`, `ON DELETE RESTRICT` and the append-only triggers will block that cleanup. Keep demo-day entries off in that test, and never let it touch `adjustment` or `audit_log`.
    - `DevDataSeeder` today is create-once (`findByCode(DEMO)` then return when found, log says "7 employees"). The plan turns it into "ensure". The existing log text and `DevSeedContentTest` expectations for 7 employees change to 13 (adds 1007-1012).
    - The plan's `adjustment` section says one user action writes "1-3 rows". Correcting a non-`VALID` CLOCK_IN with changed lateness does write 3 (`effective_time`, `late_minutes`, `status`). OK as drawn in 3b.
    - `TodayService.summary` and the day lookup order by `server_time`. After a correction, `effective_time` order can differ from `server_time` order. Pairing still uses type and `work_date`, so this is safe, but keep it in mind for any new "latest entry" logic.
    - Review reasons from Open Question 8 (`NO_GPS_ACCURACY`, `CLOCK_OUT_AFTER_SCHEDULE`) fit the existing `review_reasons varchar(200)` and regex CHECK. No DDL change, but they add two frontend `reviewReason.*` keys and a contract test entry. The diagram of `ReviewPolicy` in the Stage 2 file is not updated by this document.
    - `AdminEntryDetail.branch` returns the **current** branch values ("radio actual"), while `distance_meters` was computed against the branch at entry time. The UI label already says "current radius". Moving or resizing a branch later can make "420 m from the branch" and "radius 100 m" disagree with the stored `OUTSIDE_GEOFENCE`. This is documented in the plan, listed here so the tester covers it.
