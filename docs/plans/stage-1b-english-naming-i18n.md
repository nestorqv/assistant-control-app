# Stage 1b - English naming + bilingual (EN/ES) user text

Roadmap: `docs/plans/00-roadmap.md`. Previous stage: `docs/plans/stage-1-data-model-auth.md`. Primary input: `docs/naming-inventory.md`.
Branch: `stage1-2`. Status: PLANNED 2026-10-22. No code written yet.

## Summary

This stage renames every technical identifier from Spanish to English and makes all human-readable text available in Spanish and English. It does not change behavior. The rename covers Java packages, classes, members and locals; DB tables, columns, constraints, indexes and stored enum values; API URLs, JSON fields, error codes and JWT claims; and config keys. Names follow `docs/naming-inventory.md`, with one override: `marca` becomes `time_entry` / `TimeEntry` / package `timeentry`. The root package becomes `cr.attendance`.

`V2__initial_schema.sql` is edited in place because it was never deployed. The user resets the local dev schema with one psql command, connecting as `asistencia_app`. The DB name `asistencia` and the role `asistencia_app` stay as they are (user decision).

**Backend i18n:**
- Spring Boot's auto-configured `MessageSource` reads `messages.properties` (base, Spanish), `messages_es.properties` and `messages_en.properties`.
- A `LocaleResolver` bean (`AcceptHeaderLocaleResolver`, supported `es` and `en`, default `es`) resolves the locale from `Accept-Language`.
- `ApiExceptionHandler` reads the ProblemDetail `title` and `detail` from the bundle, keyed by a new `ErrorCode` enum. The machine `code` stays English and never changes with the language.

**Frontend i18n:**
- A small custom React context, with no new dependencies, holds the ES and EN resources as typed TS objects.
- A keyboard-accessible language switcher sits in a shared header.
- The choice is persisted in `localStorage` inside try/catch. `<html lang>` is kept in sync.
- One `apiFetch` wrapper sends `Accept-Language` on every API call.

The work runs in green-build steps:
1. Package move
2. Schema, entities and internal names
3. API contract
4. Error codes
5. Backend i18n
6. Spanish-leftover sweep
7. Frontend
8. Docs

Each step updates its tests in the same step.

## Verified against official sources (2026-10-22, WebFetch)

| Item | Finding | Consequence |
|---|---|---|
| `react-i18next` (npm registry, latest) | 17.1.2. Peers: `react >= 16.8.0`, `i18next >= 26.2.0`, `typescript ^5 \|\| ^6 \|\| ^7`. Deps: `@babel/runtime`, `html-parse-stringify`, `use-sync-external-store`. | Compatible with React 19 and TS 6, but it adds 2 runtime packages plus 3 transitive ones. |
| `i18next` (npm registry, latest) | 26.5.5, unpacked size about 543 KB, no engines restriction. | Node 24 is fine. It is heavy for ~15 strings. |
| `AcceptHeaderLocaleResolver` (Framework javadoc + 6.2.x source) | With `defaultLocale` set and **no** `Accept-Language` header, it returns the default. Otherwise it tries an exact match, then a **language-only fallback when the supported locale has no country** (so `en-US` matches a supported `en`). With no match it returns `defaultLocale`. | Configure supported `[es, en]` (language only) and default `es`. This gives missing → es, `fr` → es, `en-US` → en, `es-CR` → es. |
| Boot 3.5 i18n reference | The `MessageSource` auto-configuration applies **only if the base `messages.properties` exists**. `spring.messages.fallback-to-system-locale` is documented, but the docs don't state the default. | Ship a base `messages.properties`. Set `fallback-to-system-locale: false` and `encoding: UTF-8` explicitly so the JVM's OS locale can never decide the language. |
| `MessageSourceProperties` (Boot 3.5.16 API) | Defaults are not documented on the page. | Set every relevant `spring.messages.*` key explicitly (see Step 5). |

**Library decision: a lightweight custom context, not `react-i18next`.**
- Today there are ~15 strings, 2 languages, no plurals and only `{name}` interpolation.
- The PWA targets phones, possibly on weak networks.
- A ~60-line context with `useSyncExternalStore` covers detection, persistence, `<html lang>` and a non-React getter for the API client. It adds zero dependencies.
- Typed resources (`en` must satisfy the key type of `es`) give compile-time key parity, which `react-i18next` only gets with extra typing setup.
- Plurals and dates later use the built-in `Intl.PluralRules` / `Intl.DateTimeFormat`.
- Exit path: the resources are flat `key → string` maps in the i18next JSON shape, so moving to `react-i18next` later only touches the provider and the hook.

## Decisions (final)

1. **All technical names in English**, per `docs/naming-inventory.md`. Inventory "punch" becomes **time entry** everywhere:
   - table `time_entry`, FK column `time_entry_id`
   - classes `TimeEntry`, `TimeEntryRepository`, `TimeEntryType`, `TimeEntryStatus`, `TimeEntrySource`
   - fields `timeEntry` / `timeEntryId`
   - package `cr.attendance.timeentry`. It is all lowercase with no underscore, per the Java package naming convention.
2. **Root package `cr.attendance`.** `AsistenciaApplication` becomes `AttendanceApplication`. In Maven, `groupId` becomes `cr.attendance`, `artifactId`/`name` become `attendance-backend` and `description` becomes `Attendance - backend`. `spring.application.name` becomes `attendance-backend`.
3. **DB name and role unchanged (user decision 2026-10-22).** The local database stays `asistencia` and the role stays `asistencia_app`. `scripts/db/*`, the `DB_URL`/`DB_USER` defaults in `application.yml` and `backend/.env.example` keep those names. They are local environment names, and renaming them would need the postgres superuser password.
4. **Text that stays as data, not translated:**
   - the seed company "Empresa Demo", "Sucursal Centro", person names, and the demo **job titles** (Cajera, Bodega, Ventas, Limpieza, Administración). Job titles are free-text data that a company types in its own language, so they stay Spanish.
   - test fixture **string values** ("Empresa de prueba", "Persona de prueba") may stay. Test **identifiers** become English.
5. **Text that is developer-facing, English only, not translated:** log lines, startup failure messages (`JwtProperties`, `DevDataSeeder` PIN message), internal exception messages (`super("...")`), and the `InvalidBearerTokenException` message. The client never sees the last one, because `HttpStatusEntryPoint` returns an empty 401.
6. **Message keys are tied to error codes:** `error.<CODE>.title` and `error.<CODE>.detail`. `code` values come from a new enum `cr.attendance.common.ErrorCode`.
7. **Error responses carry `Content-Language: es|en`**, matching the language used in `title`/`detail`.
8. **Frontend detection order:**
   1. a valid stored choice
   2. the primary browser language (`navigator.languages[0] ?? navigator.language`) if its primary subtag is `es` or `en`
   3. otherwise `es`

   `<html lang>` is `es` or `en`.
9. **PWA manifest:** single-language, kept Spanish (`lang: 'es-CR'`, unchanged values). The installed-app label is the only manifest text users see. The UI language is a runtime choice that a static manifest can't follow. See Open Question 1.
10. **Stage 1 plan:** add a short "renamed per stage-1b" note at the top. Don't rewrite it. It is a dated design record, its 700 lines would be error-prone to rewrite, and `docs/naming-inventory.md` already holds the exact mapping.
11. **Old tokens:** tokens issued before the rename carry `empresaId`/`rol` and issuer `asistencia-backend`. The decoder rejects them with 401 (missing required claim `companyId` and wrong issuer). This is acceptable: dev only, no stored tokens in the frontend yet. The user logs in again.

## Gaps found in the inventory (add to it in Step 8)

These Spanish or old-name items are not listed in `docs/naming-inventory.md` but must be renamed:

| Item | Current | New |
|---|---|---|
| Config key | `app.jwt.ttl-empleado` / `JwtProperties.ttlEmpleado` | `app.jwt.ttl-employee` / `ttlEmployee` |
| JWT issuer (`app.jwt.issuer`) | `asistencia-backend` | `attendance-backend` |
| Health response `service` | `asistencia-backend` | `attendance-backend` |
| ProblemDetail `instance` | `urn:asistencia:error` | `urn:attendance:error` |
| `application-dev.yml` logger | `logging.level.cr.asistencia` | `logging.level.cr.attendance` |
| `application-test.yml` | `login-rate-limit.max-intentos` | `max-attempts` |
| Static factory | `EmpleadoResumen.de(...)` | `EmployeeSummary.from(...)` |
| JPQL named params | `:maxIntentos`, `:bloqueoHasta`, `:ahora`, `:fecha` | `:maxAttempts`, `:lockUntil`, `:now`, `:consentAt` |
| Repository params | `bloqueoHasta`, `ahora`, `fecha` | `lockUntil`, `now`, `consentAt` |
| `Adjustment.field` example values (comment + test data) | `estado`, `hora_servidor` | `status`, `server_time` |
| Private helpers / constants | `problema`, `detalle`, `codigo()`, `instanciaOpaca`, `INSTANCIA`, `MENSAJE_*`, `requerido`, `esPerfilDePrueba`, `aLong`, `INVALIDO`, `normalizar`, `mismoDigito`, `secuencia`, `fecha`, `num`, `LONGITUD`, `ANIO_MIN/MAX`, `OFICINA_ENTRADA/SALIDA`, `EMPLEADOS`, `EMPRESA_CODIGO`, `MENSAJE_PIN`, locals (`ahora`, `expira`, `emitido`, `bloqueado`, `pinCorrecto`, `encontrado`, `dia`...) | English equivalents: `problem`, `detail`, `codeFor`, `OPAQUE_INSTANCE`, `required`, `isTestProfile`, `toLong`, `INVALID_TOKEN`, `normalize`, `allSameDigit`, `isSequence`, `isValidDate`, `digits`, `LENGTH`, `MIN_YEAR/MAX_YEAR`, `OFFICE_START/END`, `DEMO_EMPLOYEES`, `DEMO_COMPANY_CODE`, `PIN_MESSAGE`, `now`, `expiresAt`, `issued`, `locked`, `pinMatches`, `found`, `day` |
| Log text | "unknown empresa or empleado", "Dev seed created: empresa DEMO, 7 empleados (...)" and `empresa=`/`empleado=` log keys | "unknown company or employee", "Dev seed created: company DEMO, 7 employees (codes 1001-1006, 9001)", `company=`/`employee=` |
| Internal exception messages | "Credenciales inválidas", "Demasiados intentos", "Recurso no encontrado", "Token inválido" | "Invalid credentials", "Too many attempts", "Resource not found", "Invalid token" |
| Spanish comments | throughout | English, same meaning |

**Column name note:** the inventory gives `distancia_m` → `distance_m`, while `radio_metros` → `radius_meters`. The plan follows the inventory unless Open Question 3 says otherwise.

## Requirements Checklist

### Naming (backend)
- [ ] Root package `cr.attendance`; subpackages `employee`, `company`, `timeentry`; `AttendanceApplication`
- [ ] Maven `groupId` `cr.attendance`, `artifactId` `attendance-backend`, `spring.application.name: attendance-backend`
- [ ] Every class, method, field, record component, local, constant and comment in `src/main` and `src/test` is English (inventory sections 3–5 plus the "Gaps" table)
- [ ] `V2__initial_schema.sql` edited in place: 7 tables, all columns, 37 constraints and 6 indexes renamed (mapping table in Step 2), stored enum values renamed
- [ ] Entities `@Table`/`@Column` match. Hibernate `ddl-auto=validate` passes
- [ ] Java enums: `Role {EMPLOYEE, ADMIN}`, `TimeEntryType {CLOCK_IN, CLOCK_OUT}`, `TimeEntrySource {MOBILE, TABLET, QR}`, `TimeEntryStatus {VALID, PENDING_REVIEW, REJECTED}`, `EvidenceType {PHOTO, GPS, QR}`
- [ ] Endpoints `POST /api/me/consent`, `GET /api/admin/employees[/{id}]`
- [ ] JSON fields per inventory section 7 (login request/response, profile, summary, error `code`)
- [ ] Error codes `INVALID_CREDENTIALS`, `TOO_MANY_ATTEMPTS`, `INVALID_REQUEST`, `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_FOUND`, `INTERNAL_ERROR` (plus `INVALID_PIN`, see Open Question 2)
- [ ] JWT claims `companyId`, `role` (plus `ver`, `sub`, `iss`, `exp`, `iat`, `jti`); issuer `attendance-backend`; authority `ROLE_EMPLOYEE`
- [ ] Config keys `max-attempts`, `duration`, `window`, `ttl-employee` (main and test yml)
- [ ] DB name `asistencia`, role `asistencia_app`, `scripts/db/*`, `.env.example` unchanged (decided)
- [ ] `TenantRepositoryConventionTest` expects parameter `companyId`; exception `CompanyRepository.findByCode`
- [ ] Dev seed: company code `DEMO`, codes 1001–1006 and 9001 unchanged; roles `EMPLOYEE`/`ADMIN`; seed data strings unchanged

### i18n (backend)
- [ ] `messages.properties` (Spanish base), `messages_es.properties`, `messages_en.properties`, UTF-8
- [ ] `spring.messages.*` set explicitly (basename, encoding, `fallback-to-system-locale: false`, `use-code-as-default-message: false`)
- [ ] `LocaleResolver` bean named `localeResolver`: `AcceptHeaderLocaleResolver`, supported `es`, `en`, default `es`
- [ ] `ApiExceptionHandler` resolves `title` and `detail` from `MessageSource` with `LocaleContextHolder.getLocale()`. `code` never translated. `Content-Language` header set
- [ ] PinPolicy user message moved to the bundle (`error.INVALID_PIN.detail`)
- [ ] Failure bodies are still identical across all login failure causes **within one language** (no enumeration)
- [ ] Tests: `en`, `es`, missing, unsupported (`fr`), regional (`en-US`, `es-CR`), q-list; same `code` in all; `detail`/`title` differ between es and en
- [ ] Tests: key parity across the 3 properties files; every `ErrorCode` has `title` and `detail` keys; no blank values

### i18n (frontend)
- [ ] Custom i18n context; typed `es` and `en` resources for every current UI string (3 pages, HealthStatus, switcher, document title)
- [ ] Language switcher in a shared header on every route: native buttons, `aria-pressed`, group label, `lang` on each option, polite live announcement after a change, 44 px targets
- [ ] Persistence in `localStorage` with try/catch; invalid stored values ignored
- [ ] Default: browser primary language if `es`/`en`, else `es`
- [ ] `<html lang>` and `document.title` follow the language
- [ ] `Accept-Language` sent on every API call via `apiFetch`
- [ ] HealthStatus errors are translated by kind (no hard-coded Spanish in `api/health.ts`); dates formatted with `es-CR` / `en-US`, zone `America/Costa_Rica`
- [ ] Tests: switcher (click + keyboard), persistence (including storage throwing), `html lang`, header sent, detection, resource key parity, existing tests updated
- [ ] PWA manifest decision applied and `pwaConfig.test.ts` consistent

### Process / docs
- [ ] Each step leaves `.\mvnw.cmd clean test` (backend) and `npm test && npm run lint && npm run build` (frontend) green
- [ ] Files and packages moved with `git mv`
- [ ] Final grep gate finds no Spanish identifiers (Step 6)
- [ ] `docs/diagrams/stage-1-er.md`, `README.md`, `00-roadmap.md` (glossary + notes), Stage 1 plan note, audit-report one-line note, `naming-inventory.md` "Status: applied" + gaps
- [ ] `scripts/db/reset-dev-schema.sql` added and documented
- [ ] Main session updates `CHANGELOG.md` and `docs/PROGRESS.md` (not the planner)
- [ ] User resets the dev DB and runs the end-to-end check

## Implementation Steps

General rules for the coder:
- **Moves:** use `git mv` for every directory and file rename so history follows (`git log --follow`). Prefer IDE "Rename" refactors for symbols. If renaming by script, use **word-boundary** regexes and go **longest name first**. Examples of traps:
  - `EmpleadoJwtAuthenticationConverter` must be renamed before `Empleado`, and `EmpresaInfo` before `Empresa`.
  - `\brol\b` must not touch `control`/`rollback`.
  - `codigo` appears inside `codigoEmpresa`.
  - SQL uses `time_entry`, Java uses `timeEntry`/`TimeEntry`.
- **Builds:** always run `.\mvnw.cmd clean test`, never an incremental build. Stale `target/classes/cr/asistencia/**` and old `V2` copies in `target/` must not survive.
- **Commits:** commit only when the user asks (project rule). If intermediate commits are allowed, commit after Step 1 (pure moves) so Git's rename detection keeps history even for small files whose content changes a lot later.
- **Tests:** test method names containing Spanish words (about 44) are renamed in the step that touches their file. Test-only fixture string values may stay.

### Step 1 - Package move and application identity (no behavior change)

Moves (`git mv`):
- `backend/src/main/java/cr/asistencia` → `backend/src/main/java/cr/attendance`
- `backend/src/test/java/cr/asistencia` → `backend/src/test/java/cr/attendance`
- inside both trees: `empleado/` → `employee/`, `empresa/` → `company/`, `marca/` → `timeentry/`
- `AsistenciaApplication.java` → `AttendanceApplication.java`

Modify:
- every `package` / `import` line (`cr.asistencia.*` → `cr.attendance.*`, with the subpackage names)
- `backend/pom.xml`: `groupId`, `artifactId`, `name`, `description`
- `application.yml`: `spring.application.name: attendance-backend`
- `application-dev.yml`: `logging.level.cr.attendance: DEBUG`

Gate: `.\mvnw.cmd clean test` gives 278 passing.

### Step 2 - Schema, entities, repositories, enums and internal Java names

**`V2__initial_schema.sql` (edit in place).** Tables: `company`, `branch`, `employee`, `work_schedule`, `time_entry`, `evidence`, `adjustment`.

Columns per inventory section 6, with these differences:
- `marca_id` becomes **`time_entry_id`**
- `distancia_m` becomes `distance_m` (or `distance_meters`, per Open Question 3)

Stored enum CHECK values per inventory section 6. The `time_entry.status` default becomes `'VALID'`, and the partial index uses `WHERE status = 'PENDING_REVIEW'`. Rewrite the header comments in English. **`status varchar(12)` is too short for `PENDING_REVIEW` (14 chars), so widen it to `varchar(20)`.** Every enum column length was checked against the new values:

| Column | Old length | Longest new value | New length |
|---|---|---|---|
| `role` | `varchar(10)` | `EMPLOYEE` (8) | 10, OK |
| `type` | `varchar(10)` | `CLOCK_OUT` (9) | 10, OK |
| `source` | `varchar(10)` | `MOBILE` (6) | 10, OK |
| `status` | `varchar(12)` | `PENDING_REVIEW` (14) | **20** |
| `evidence.type` | `varchar(10)` | `PHOTO` (5) | 10, OK |

The `@Column(length = ...)` on `TimeEntry.status` must match (20).

Constraint and index mapping (37 constraints, 6 indexes):

| Old | New |
|---|---|
| `uq_empresa_codigo` / `ck_empresa_codigo` / `ck_empresa_nombre` | `uq_company_code` / `ck_company_code` / `ck_company_name` |
| `fk_sucursal_empresa` / `uq_sucursal_id_empresa` / `uq_sucursal_empresa_nombre` | `fk_branch_company` / `uq_branch_id_company` / `uq_branch_company_name` |
| `ck_sucursal_latitud` / `ck_sucursal_longitud` / `ck_sucursal_radio` | `ck_branch_latitude` / `ck_branch_longitude` / `ck_branch_radius` |
| `fk_empleado_empresa` / `fk_empleado_sucursal_empresa` | `fk_employee_company` / `fk_employee_branch_company` |
| `uq_empleado_empresa_codigo` / `uq_empleado_id_empresa` | `uq_employee_company_code` / `uq_employee_id_company` |
| `ck_empleado_codigo` / `ck_empleado_rol` / `ck_empleado_intentos` / `ck_empleado_sucursal_rol` | `ck_employee_code` / `ck_employee_role` / `ck_employee_failed_attempts` / `ck_employee_branch_role` |
| `ix_empleado_empresa_activo` | `ix_employee_company_active` |
| `fk_horario_empleado_empresa` / `uq_horario_empleado_dia` | `fk_work_schedule_employee_company` / `uq_work_schedule_employee_day` |
| `ck_horario_dia` / `ck_horario_horas` / `ck_horario_tolerancia` | `ck_work_schedule_day` / `ck_work_schedule_times` / `ck_work_schedule_grace` |
| `fk_marca_empleado_empresa` / `fk_marca_sucursal_empresa` | `fk_time_entry_employee_company` / `fk_time_entry_branch_company` |
| `uq_marca_empresa_client_uuid` / `uq_marca_id_empresa` | `uq_time_entry_company_client_uuid` / `uq_time_entry_id_company` |
| `ck_marca_tipo` / `ck_marca_origen` / `ck_marca_estado` | `ck_time_entry_type` / `ck_time_entry_source` / `ck_time_entry_status` |
| `ck_marca_latitud` / `ck_marca_longitud` / `ck_marca_distancia` | `ck_time_entry_latitude` / `ck_time_entry_longitude` / `ck_time_entry_distance` |
| `ix_marca_empresa_hora` / `ix_marca_empleado_hora` / `ix_marca_por_revisar` | `ix_time_entry_company_server_time` / `ix_time_entry_employee_server_time` / `ix_time_entry_pending_review` |
| `fk_evidencia_marca_empresa` / `ck_evidencia_tipo` / `ix_evidencia_marca` | `fk_evidence_time_entry_company` / `ck_evidence_type` / `ix_evidence_time_entry` |
| `fk_ajuste_marca_empresa` / `fk_ajuste_admin_empresa` / `ck_ajuste_motivo` / `ix_ajuste_marca` | `fk_adjustment_time_entry_company` / `fk_adjustment_admin_company` / `ck_adjustment_reason` / `ix_adjustment_time_entry` |

Column names `role`, `type`, `status`, `source`, `code`, `name`, `field`, `active` are non-reserved in PostgreSQL and need no quoting.

**Renames (`git mv` + content), main code:**

| Package | Renames |
|---|---|
| `common` | `Rol`→`Role` (values `EMPLOYEE`, `ADMIN`), `EmpleadoPrincipal`→`EmployeePrincipal(employeeId, companyId, role)`, `RecursoNoEncontradoException`→`ResourceNotFoundException` |
| `company` | `Empresa`→`Company`, `EmpresaRepository`→`CompanyRepository` (`findByCode`), `Sucursal`→`Branch`, `SucursalRepository`→`BranchRepository` |
| `employee` | `Empleado`→`Employee`, `EmpleadoRepository`→`EmployeeRepository`, `Horario`→`WorkSchedule`, `HorarioRepository`→`WorkScheduleRepository`, `EmpleadoConsultaService`→`EmployeeQueryService` (`list`/`get`), `AdminEmpleadoController`→`AdminEmployeeController` (`list`/`get`), `MeController` (`profile`/`giveConsent`) |
| `timeentry` | `Marca`→`TimeEntry`, `MarcaRepository`→`TimeEntryRepository`, `Evidencia`→`Evidence`, `EvidenciaRepository`→`EvidenceRepository`, `Ajuste`→`Adjustment`, `AjusteRepository`→`AdjustmentRepository`, `TipoMarca`→`TimeEntryType`, `OrigenMarca`→`TimeEntrySource`, `EstadoMarca`→`TimeEntryStatus`, `TipoEvidencia`→`EvidenceType` |
| `auth` | `EmpleadoAuthentication`→`EmployeeAuthentication`, `EmpleadoJwtAuthenticationConverter`→`EmployeeJwtAuthenticationConverter`, `CredencialesInvalidasException`→`InvalidCredentialsException`, `DemasiadosIntentosException`→`TooManyAttemptsException` (`getRetryAfter`) |

**Modify:**
- all entity fields, getters, constructors and `@Column` names (inventory section 5, `timeEntryId` for `marcaId`)
- repository methods and parameters:
  - `findByIdAndCompanyId`, `findAllByCompanyIdOrderByName`, `findByCompanyIdAndCode`, `findAllByCompanyIdAndEmployeeId`
  - `recordFailedLogin(id, companyId, maxAttempts, lockUntil, now)`, `recordSuccessfulLogin(id, companyId, now)`, `recordConsent(id, companyId, consentAt)`
  - JPQL rewritten on `Employee e` with the new field names
- every caller: `LoginService`, `TokenService`, `EmployeeJwtAuthenticationConverter`, `MeController`, `EmployeeQueryService`, `AdminEmployeeController`, `DevDataSeeder`, `SecurityConfig` (import only)
- `PinPolicy` methods: `isValid`, `requireValid`, `isRepeatedBlock`, `isDate`, plus the private helper names from the Gaps table
- `LoginRateLimiter`: `tryAcquire`, `retryAfter`, fields `maxAttempts`/`window`/`attempts`
- `SecurityProperties`: `Lockout(maxAttempts, duration)`, `LoginRateLimit(maxAttempts, window)`. The YAML keys change in the same step, otherwise binding gives 0/null:
  - `application.yml`: `lockout.max-attempts`, `lockout.duration`, `login-rate-limit.max-attempts`, `login-rate-limit.window`
  - `application-test.yml`: `login-rate-limit.max-attempts: 100000`
- `JwtConfig.isTestProfile`; `LoginAwareBearerTokenResolver.isPublicEndpoint`

**Not in this step:** DTO record components (JSON), URLs, JWT claim names, error codes. They stay on their old names until Step 3/4, so each step is reviewable on its own. The `Role` enum value does change the JSON `rol` value to `EMPLOYEE` here.

**Tests, same step.** Move with `git mv`:
- `employee/AdminEmpleadoControllerTest` → `AdminEmployeeControllerTest`
- `employee/HorarioTimeZoneIntegrationTest` → `WorkScheduleTimeZoneIntegrationTest`

Update:
- `support/TestData`:
  - `Tenant(companyId, companyCode, branchId)`
  - `Usuario` → `TestUser(id, code, pin)` (avoid clashing with Spring Security `User`)
  - `codigoAleatorio` → `randomCode`, `empresa()` → `company()`, `codigoEmpresa` → `companyCode`, `sucursal` → `branch`, `empleado` → `employee`, `usuario` → `user`, `marca` → `timeEntry`
  - all SQL strings use the new tables, columns and enum values (`'CLOCK_IN'`, `'MOBILE'`, `'EMPLOYEE'`)
- `tenancy/TenantRepositoryConventionTest`:
  - list `BranchRepository, EmployeeRepository, WorkScheduleRepository, TimeEntryRepository, EvidenceRepository, AdjustmentRepository`
  - parameter name **`companyId`**
  - method `everyMethodExceptSaveDeclaresACompanyIdParameter`
  - Javadoc exception `CompanyRepository.findByCode`
- `db/SchemaMigrationTest`:
  - table list `company, branch, employee, work_schedule, time_entry, evidence, adjustment`
  - every SQL string and enum literal updated; invalid values stay invalid (`'FAX'`, `'MAYBE'`)
  - adjustment `field` value `'server_time'`
  - add an assertion that `time_entry.status` accepts `'PENDING_REVIEW'` (guards the varchar widening)
- `db/TenantConstraintsTest`, `db/SchemaRangeConstraintsTest`, `tenancy/TenantIsolationIntegrationTest`, `auth/LoginServiceTest` (mocks of the renamed repo methods), `auth/LockoutIntegrationTest`, `auth/LockoutRaceIntegrationTest`, `auth/LoginClockIntegrationTest`, `employee/WorkScheduleTimeZoneIntegrationTest`, `seed/DevSeed*Test` (enum `EMPLOYEE`), `auth/PinPolicyTest` (method names), `auth/LoginRateLimiterTest`, `auth/TokenServiceTest` (entity type)

Gate: `.\mvnw.cmd clean test` green (the embedded DB is fresh, so the in-place V2 edit applies).

### Step 3 - API contract: URLs, JSON, JWT, config

Modify:
- `auth/LoginRequest(companyCode, employeeCode, pin)` and its `toString` (PIN still masked)
- `auth/LoginResponse(token, tokenType, expiresAt, EmployeeInfo employee, CompanyInfo company)`, with `EmployeeInfo(id, name, jobTitle, role, consentPending)` and `CompanyInfo(name, timeZone)`
- `employee/PerfilResponse.java` → `git mv` → `ProfileResponse(id, code, name, jobTitle, role, consentPending, CompanyInfo company)`
- `employee/EmpleadoResumen.java` → `git mv` → `EmployeeSummary(id, code, name, jobTitle, role, active, branchId)` with `static from(Employee)`
- `AuthController`: uses `request.companyCode()` / `employeeCode()`, `rateLimiter.tryAcquire`, `TooManyAttemptsException(rateLimiter.retryAfter())`
- `TokenService`:
  - `IssuedToken(token, expiresAt)`, `issue(Employee)`
  - claims `companyId`, `role`, `ver`
  - TTL from `ttlAdmin()` / `ttlEmployee()`
- `JwtProperties`: `ttlEmployee`
- `application.yml`: `app.jwt.ttl-employee: 8h`, `app.jwt.issuer: attendance-backend`
- `JwtConfig`: required claims `exp`, `iat`, `sub`, `companyId`, `role`, `ver`
- `EmployeeJwtAuthenticationConverter`: reads `jwt.getClaim("companyId")`. Authority `"ROLE_" + role.name()` now gives `ROLE_EMPLOYEE` / `ROLE_ADMIN`. The role still comes from the DB, not the claim.
- `MeController`: `@PostMapping("/consent")`
- `AdminEmployeeController`: `@RequestMapping("/api/admin/employees")`
- `SecurityConfig`: `/api/admin/**` + `hasRole("ADMIN")` unchanged (`ADMIN` is unchanged). Re-check that no matcher names Spanish paths. `LoginAwareBearerTokenResolver` paths unchanged.
- `health/HealthController`: `service` = `attendance-backend`

Tests, same step:
- `support/JwtTestTokens`:
  - `ISSUER = "attendance-backend"`
  - `valid`, `expired`, `otherIssuer`, `otherKey`, `withoutCompanyId`, `algNone`, `withTamperedPayload`, `sign`
  - claims `companyId`/`role`; the tamper regex targets `"companyId":\d+`
- `support/TestData.login`: posts `companyCode` / `employeeCode`
- `auth/LoginIntegrationTest` (JSON paths `$.expiresAt`, `$.employee.*`, `$.company.timeZone`; claims `companyId`, `role`; `iss` `attendance-backend`)
- `auth/JwtAuthenticationIntegrationTest`, `auth/JwtHardeningIntegrationTest` (issuer literals, admin URL, `/api/me/consent`, `role` claim)
- `auth/TokenServiceTest`, `auth/JwtPropertiesTest`, `auth/JwtConfigTest` (issuer and `ttlEmployee`)
- `employee/MeControllerTest` (`$.consentPending`, `$.company.name`, `/api/me/consent`)
- `employee/AdminEmployeeControllerTest`, `tenancy/TenantIsolationIntegrationTest`, `seed/DevSeedContentTest` (`/api/admin/employees`, `$[*].jobTitle`, `role` `EMPLOYEE`)
- `health/HealthEndpointTest` (`attendance-backend`)
- `config/SecurityAndActuatorTest` (any Spanish URL)

Add `auth/LoginIntegrationTest#oldSpanishFieldNamesAreRejectedWith400`: posting `{"empresaCodigo":...}` gives 400 `INVALID_REQUEST` (code name final after Step 4). It guards against a half-renamed client. Add `JwtHardeningIntegrationTest#tokenWithLegacyEmpresaIdClaimIsRejected`: an old-shape token (`empresaId`, `rol`, old issuer) gives 401.

Gate: green.

### Step 4 - Error codes and English internal messages

Create:
- `backend/src/main/java/cr/attendance/common/ErrorCode.java`: enum `INVALID_CREDENTIALS, TOO_MANY_ATTEMPTS, INVALID_REQUEST, METHOD_NOT_ALLOWED, NOT_ACCEPTABLE, UNSUPPORTED_MEDIA_TYPE, NOT_FOUND, INTERNAL_ERROR` (plus `INVALID_PIN`, see Open Question 2). Method `messageKey(String part)` returns `"error." + name() + "." + part`.

Modify:
- `common/ApiExceptionHandler`:
  - ProblemDetail property **`code`** (was `codigo`) with `ErrorCode.name()`
  - status-to-code switch returns `ErrorCode`
  - `OPAQUE_INSTANCE = urn:attendance:error`
  - handler methods `invalidCredentials`, `tooManyAttempts`, `notFound`
  - Spanish text still inline here; it moves to the bundle in Step 5
- `InvalidCredentialsException`, `TooManyAttemptsException`, `ResourceNotFoundException`, `EmployeeJwtAuthenticationConverter`: internal messages in English (Gaps table)

Tests: `common/ApiExceptionHandlerTest` (`code`, new code values, English identifiers `handle`, `expected`, `SECRET`), plus every `jsonPath("$.codigo")` in the integration tests becomes `$.code` with the new values (`LoginIntegrationTest`, `LoginHardeningIntegrationTest`, `LoginRateLimitIntegrationTest`, `TenantIsolationIntegrationTest`, `AdminEmployeeControllerTest`, `SecurityAndActuatorTest`).

Gate: green.

### Step 5 - Backend i18n (MessageSource + Accept-Language)

Create:
- `backend/src/main/resources/messages.properties`: Spanish base, same keys as `_es`
- `backend/src/main/resources/messages_es.properties`
- `backend/src/main/resources/messages_en.properties`
  - All three are UTF-8 without BOM, written with real accented characters (Boot reads UTF-8 because `encoding` is set below). They have no `{0}` arguments, so apostrophes ("can't") need no `''` escaping. If an argument is ever added, `MessageFormat` escaping applies. Document that in a header comment.
  - Keys: `error.<CODE>.title` and `error.<CODE>.detail` for every `ErrorCode`. Spanish texts are today's texts, unchanged (`detail` of `INVALID_CREDENTIALS`: "Empresa, código o PIN incorrectos. Si falló varias veces, espere 15 minutos o consulte a su administrador.", and so on). English suggestions:

| Code | EN title | EN detail |
|---|---|---|
| INVALID_CREDENTIALS | Invalid credentials | Company, code or PIN is incorrect. If it failed several times, wait 15 minutes or contact your administrator. |
| TOO_MANY_ATTEMPTS | Too many attempts | Too many attempts. Try again in a few minutes. |
| INVALID_REQUEST | Invalid request | The request is not valid. |
| METHOD_NOT_ALLOWED | Method not allowed | Method not allowed. |
| NOT_ACCEPTABLE | Not acceptable | Response format not available. |
| UNSUPPORTED_MEDIA_TYPE | Unsupported media type | Content type not supported. |
| NOT_FOUND | Not found | Resource not found. |
| INTERNAL_ERROR | Internal error | Internal error. |
| INVALID_PIN | Invalid PIN | The PIN must be exactly 8 digits and cannot be a sequence, a repeated pattern or a date. |

  Spanish titles: "Credenciales inválidas", "Demasiados intentos", "Solicitud inválida", "Método no permitido", "Formato no aceptable", "Tipo de contenido no soportado", "No encontrado", "Error interno", "PIN inválido".
- `backend/src/main/java/cr/attendance/config/LocaleConfig.java`: `@Bean LocaleResolver localeResolver()`. The bean **must be named `localeResolver`**, which `DispatcherServlet` and Boot's `@ConditionalOnMissingBean(name = "localeResolver")` look for. It returns an `AcceptHeaderLocaleResolver` with `setSupportedLocales(List.of(Locale.of("es"), Locale.of("en")))` and `setDefaultLocale(Locale.of("es"))`. Expose `SUPPORTED`/`DEFAULT` constants for tests.
- `backend/src/main/java/cr/attendance/auth/InvalidPinException.java` (only if Open Question 2 is answered "now"): extends `IllegalArgumentException`, English internal message "PIN does not meet the policy", never contains the PIN.

Modify:
- `application.yml`:
  ```yaml
  spring:
    messages:
      basename: messages
      encoding: UTF-8
      fallback-to-system-locale: false
      use-code-as-default-message: false
  ```
- `common/ApiExceptionHandler`:
  - constructor-inject `MessageSource`
  - build every body via one `problem(HttpStatusCode, ErrorCode)` that resolves `title` and `detail` with `LocaleContextHolder.getLocale()`. `DispatcherServlet` populates it from the `localeResolver` bean. Don't use `WebRequest.getLocale()`, which is the raw servlet locale and ignores the resolver.
  - return `ResponseEntity` with `Content-Language` = the resolved language tag (`es`/`en`). Keep `Retry-After` on 429 and `Allow`/`Accept` headers from the base class.
  - remove the Spanish constants
- `auth/PinPolicy`:
  - `MESSAGE` becomes `MESSAGE_KEY = "error.INVALID_PIN.detail"`
  - `requireValid` throws `InvalidPinException` (or `IllegalArgumentException` with an English message if Open Question 2 says defer)
  - if Open Question 2 is "now": `ApiExceptionHandler` maps `InvalidPinException` → 400 `INVALID_PIN`
- `seed/DevDataSeeder`: `PIN_MESSAGE` stays an English operator message (not translated)

Tests, create:
- `backend/src/test/java/cr/attendance/support/TestMessages.java`: builds a `ResourceBundleMessageSource` (basename `messages`, UTF-8, `fallbackToSystemLocale=false`) for unit tests. It must match the YAML.
- `backend/src/test/java/cr/attendance/i18n/MessageBundleParityTest.java`:
  - loads the 3 files with `Properties.load(new InputStreamReader(..., UTF_8))`
  - asserts identical key sets across all 3
  - no blank values
  - every `ErrorCode` has `.title` and `.detail` in each file
  - `messages.properties` equals `messages_es.properties` (base is Spanish)
  - for every key, the `en` value differs from the `es` value (catches copy-paste)
  - no file starts with a BOM (`\uFEFF`)
  - Spanish file contains `ó` correctly decoded (encoding guard)
- `backend/src/test/java/cr/attendance/i18n/LocaleResolutionIntegrationTest.java` (`@IntegrationTest`, MockMvc, a wrong-PIN login against a real tenant):
  - parameterized over `Accept-Language`: `en` → EN, `en-US,en;q=0.9` → EN, `es` → ES, `es-CR` → ES, missing → ES, `fr` → ES, `fr, en;q=0.5` → EN, `*` → ES, garbage `@@@` → ES
  - each asserts `$.code == "INVALID_CREDENTIALS"`, the exact `$.detail` and `$.title` from the bundle, and the `Content-Language` header
  - one test asserts the EN and ES bodies have the same `code`/`status`/`instance` but different `detail`/`title`
  - a 404 (`GET /api/admin/employees/{otherTenantId}` as admin) and a 400 (malformed JSON on login) in both languages
- `backend/src/test/java/cr/attendance/config/LocaleConfigTest.java`: unit test of the resolver with `MockHttpServletRequest` for the same header matrix (fast, no context)

Tests, modify:
- `common/ApiExceptionHandlerTest`:
  - construct with `TestMessages.source()`
  - set `LocaleContextHolder` per test and `resetLocaleContext()` in `@AfterEach`
  - parameterize each existing case over `es`/`en`: same `code`, localized `detail`, no input echo in either language
  - add `INVALID_PIN` and 429 `TOO_MANY_ATTEMPTS` (`Retry-After` still present) cases
- `auth/LoginHardeningIntegrationTest`: the "identical body for every failure cause" test runs once with `Accept-Language: en` and once without. Bodies are identical within each language (no enumeration).
- `auth/PinPolicyTest`: assert `InvalidPinException` (or `IllegalArgumentException`) and that the message never contains the PIN; drop `hasMessage(MENSAJE)`
- `seed/DevSeedProfileTest`: `DevDataSeeder.PIN_MESSAGE`

Gate: green.

### Step 6 - Spanish-leftover sweep (backend)

Run a case-insensitive search over `backend/src` (and `backend/pom.xml`, `application*.yml`) for:
`empres|emplead|sucursal|horario|\bmarca|evidenc|ajuste|consentim|codigo|nombre|puesto|\brol\b|activo|intento|bloque|asistencia|reintent|ventana|duracion|perfil|resumen|consulta|exigir|emitir|listar|obtener|ahora|fecha|motivo|valor_|campo|origen|estado|tipo|hora_|zona_|latitud|longitud|radio_|toleranc|creada|dia_|SECRETO|esperado|manejar|firmar`

Allowed hits only:
- `messages.properties` / `messages_es.properties` text
- `DevDataSeeder` seed data strings ("Empresa Demo", "Sucursal Centro", names, job titles) and the tests asserting them
- test fixture string values ("Empresa de prueba", "Persona de prueba", "Olvido marcar")
- the DB name `asistencia` / role `asistencia_app` in `application.yml` defaults

Fix everything else: identifiers, comments, log lines, test method names and Javadoc. Gate: green.

### Step 7 - Frontend i18n and language switcher

The `designer` agent reviews the switcher markup and CSS (small UI). The coder implements.

Create:
- `frontend/src/i18n/resources.ts`:
  - `export const es = { ... } as const`
  - `export type MessageKey = keyof typeof es`
  - `export const en: Record<MessageKey, string> = { ... }` (compile-time parity)
  - `export const resources = { es, en }`
  - keys:
    - `app.title` (Control de Asistencia / Attendance Control)
    - `employeeHome.subtitle`, `employeeHome.toAdmin`
    - `adminHome.title`, `adminHome.subtitle`
    - `common.backHome`
    - `notFound.title`
    - `health.checking`, `health.online` ("Servidor en línea ({status}) - {time}" / "Server online ({status}) - {time}"), `health.unreachable` ("No se pudo contactar al servidor: {reason}"), `health.unexpectedResponse`, `health.httpStatus` ("HTTP {status}")
    - `language.label` (Idioma / Language), `language.changed` (Idioma: Español / Language: English)
    - `language.name.es` = "Español" and `language.name.en` = "English" in **both** files (endonyms)
- `frontend/src/i18n/language.ts`: tiny store with no React.
  - `SUPPORTED = ['es','en'] as const`, `type Language`
  - `STORAGE_KEY = 'attendance.lang'`
  - `detectInitialLanguage()` (stored → `navigator.languages?.[0] ?? navigator.language` primary subtag → `'es'`)
  - `readStored()` / `writeStored()` each wrapped in try/catch; storage that throws or is missing means in-memory only
  - `getLanguage()`, `setLanguage(lang)` (validates, persists, sets `document.documentElement.lang` and `document.title`, notifies), `subscribe(listener)`
  - `initLanguage()` called once in `main.tsx` **before** render, to avoid a lang/title flash
- `frontend/src/i18n/I18nProvider.tsx`:
  - `I18nProvider` reads the store via `useSyncExternalStore`. It takes an optional `initialLanguage` prop for tests and provides `{ lang, setLang, t }`.
  - `t(key, vars?)` replaces `{name}` placeholders and falls back to `es`, then to the key (never throws)
  - `useI18n()` throws a clear error if used outside the provider
  - use React 19 `<I18nContext value={...}>`
- `frontend/src/components/LanguageSwitcher.tsx`:
  - `<div role="group" aria-label={t('language.label')}>` with two native `<button type="button">`
  - each button has `lang="es"`/`lang="en"`, visible endonym text and `aria-pressed={lang === code}`
  - clicking or Enter/Space calls `setLang`. Focus stays on the button.
  - a visually hidden `<span aria-live="polite">` announces `t('language.changed')` **only after a user change**, not on mount. **Do not use `role="status"`**, because existing tests query the single `role="status"` of HealthStatus.
- `frontend/src/components/AppHeader.tsx`: `<header>` with the switcher (top-right). Rendered by `App.tsx` above `<Routes>`, so every route, including NotFound, has it.
- `frontend/src/api/client.ts`: `apiFetch(path, init)` merges `Accept-Language: getLanguage()` into `init.headers` (via `new Headers(init?.headers)`; caller headers win except Accept-Language) and passes `signal` through. Also `class ApiError extends Error { kind: 'http' | 'unexpected-response'; status?: number }`.
- `frontend/src/test/renderWithI18n.tsx`: helper that renders inside `I18nProvider` with `initialLanguage` (default `'es'`) and an optional `MemoryRouter`
- Tests:
  - `frontend/src/i18n/resources.test.ts`: same key sets in `es` and `en`, no empty strings, placeholders (`{x}`) identical per key, `en` value ≠ `es` value except the `language.name.*` endonyms
  - `frontend/src/i18n/language.test.ts`:
    - detection: stored `en` wins; `navigator.languages[0]` `en-GB` → en, `es-CR` → es, `fr-FR` → es, undefined → es; invalid stored value `xx` ignored
    - persistence: `setLanguage` writes the storage key
    - `localStorage.getItem`/`setItem` throwing (`vi.spyOn(Storage.prototype, ...)`) → no crash, language still switches in memory
    - `document.documentElement.lang` and `document.title` updated
  - `frontend/src/components/LanguageSwitcher.test.tsx`:
    - renders a group named "Idioma"; buttons Español and English
    - `aria-pressed` reflects the current language
    - click English → heading switches to "Attendance Control", `html lang` = `en`, storage = `en`, live region announces "Language: English"
    - keyboard: focus the button, press Enter, then Space → toggles. Use `fireEvent.keyDown`/`click` or `@testing-library/user-event` (Open Question 4 if adding the dev dep is unwanted)
    - no announcement on first render
  - `frontend/src/api/client.test.ts`:
    - `fetch` stub receives `Accept-Language: es` by default and `en` after `setLanguage('en')`
    - the existing `signal` is preserved
    - `fetchHealth` uses `apiFetch` (assert header on `/api/health`)

Modify:
- `src/api/health.ts`: use `apiFetch`; throw `ApiError('http', status)` / `ApiError('unexpected-response')` instead of Spanish text
- `src/components/HealthStatus.tsx`:
  - state stores the error kind/status/network message (not a translated string), so it re-translates on a language switch
  - date via `toLocaleString(lang === 'en' ? 'en-US' : 'es-CR', { timeZone: 'America/Costa_Rica' })`
  - keep `role="status" aria-live="polite"`
- `src/pages/EmployeeHome.tsx`, `AdminHome.tsx`, `NotFound.tsx`: `t()` for every string
- `src/App.tsx`: render `<AppHeader />` and `<Routes>`
- `src/main.tsx`: `initLanguage()` before `createRoot`; wrap `<App/>` in `<I18nProvider>`
- `src/index.css`: `.visually-hidden` utility, header layout, switcher button styles (≥44×44 px, visible `:focus-visible` outline, pressed state not conveyed by color alone, e.g. underline or bold plus `aria-pressed`)
- `index.html`: keep `lang="es"` and the Spanish `<title>` as the no-JS default (runtime overrides). Add a comment.
- `src/test/setup.ts`: `afterEach` also runs `localStorage.clear()`, resets `document.documentElement.lang = 'es'` and resets the language store. **jsdom reports `navigator.language = 'en-US'`**, so tests must pass `initialLanguage` or stub navigator. Otherwise the existing Spanish assertions would render English.
- Existing tests:
  - `App.routes.test.tsx`, `App.test.tsx`: render via `renderWithI18n(..., 'es')`. Add one EN route test ("Attendance Control", "Admin panel", "Page not found"). Add an assertion that the switcher exists on `/`, `/admin` and the not-found route. The not-found test currently does `findByRole('link')`, which still works because the switcher has buttons, not links.
  - `HealthStatus.test.tsx`, `HealthStatus.states.test.tsx`: wrap in the provider; fixture `service: 'attendance-backend'`; error texts from the `es` resource; add one EN case (`Server online`, time formatted `en-US`, still `12:00` in UTC-6); assert the fetch call carries `Accept-Language`
  - `pwaConfig.test.ts`: unchanged if Open Question 1 keeps the Spanish manifest
- `vite.config.ts`: unchanged (manifest decision 9), unless Open Question 1 changes it

Gate: `npm test`, `npm run lint`, `npm run build` green.

### Step 8 - Docs and scripts

Create:
- `scripts/db/reset-dev-schema.sql`: drops the app tables for both old and new names plus Flyway history, so Flyway re-creates everything on next start. It is run as `asistencia_app`, which owns them. Content:
  ```sql
  -- Drops the app schema objects so Flyway re-applies V1/V2 on next start. Dev only.
  -- Run as the app role (it owns the tables):
  --   psql -U asistencia_app -h localhost -d asistencia -v ON_ERROR_STOP=1 -f scripts/db/reset-dev-schema.sql
  DROP TABLE IF EXISTS
      adjustment, evidence, time_entry, work_schedule, employee, branch, company,
      ajuste, evidencia, marca, horario, empleado, sucursal, empresa,
      flyway_schema_history
  CASCADE;
  ```

Modify:
- `docs/diagrams/stage-1-er.md`: an **architect** refresh, mechanical:
  - ER entities and columns, the constraint and index list, sequence diagrams (field names, `INVALID_CREDENTIALS`, `issue`, `recordFailedLogin`, claims `companyId`/`role`, issuer `attendance-backend`, `/api/admin/employees`)
  - package diagram `cr.attendance` (`employee`, `company`, `timeentry`, `config/LocaleConfig`, `common/ErrorCode`)
  - add a note "Renamed per stage-1b (2026-10-22)"
- `README.md`:
  - layout `cr.attendance`
  - auth section: `{"companyCode","employeeCode","pin"}`, `/api/me/consent`, `/api/admin/employees[/{id}]`, roles `EMPLOYEE`/`ADMIN`
  - new "Languages" section: UI switcher, `Accept-Language` (es/en, default es), error `code` stable, `title`/`detail` localized
  - "Reset the dev schema" subsection with the psql command
  - intro line "The UI is in Spanish and English"
  - the DB/role names stay
- `docs/plans/00-roadmap.md`:
  - confirmed decisions: package `cr.attendance`, "Docs in English; UI text in Spanish and English (switchable, default Spanish); technical names in English"
  - Stage 1 bullets in English names
  - Stage 2 bullets: provisional `GET /api/time`, `GET /api/time-entries/today`, `POST /api/time-entries`, `VALID`/`PENDING_REVIEW`, `work_schedule` + `grace_minutes`, `clock-in` before `clock-out`; to be finalized in the Stage 2 plan
  - Stage 4: `adjustment`, CRUD branches/employees/work schedules
  - add a "Stage 1b" entry and a **Glossary (ES → EN)** section (inventory section 1, with `marca → time_entry`)
- `docs/plans/stage-1-data-model-auth.md`: add a block under the title:
  > **Renamed per Stage 1b (2026-10-22).** Identifiers in this plan are the original Spanish names. The code, schema, API and JWT now use English names (`marca` → `time_entry`, root package `cr.attendance`, error codes such as `INVALID_CREDENTIALS`, claims `companyId`/`role`). Mapping: `docs/naming-inventory.md`. Plan: `docs/plans/stage-1b-english-naming-i18n.md`.
- `securityAudit-report/stage-1-auth.md`: one line at the top:
  > Note (2026-10-22): identifiers in this report predate the Stage 1b English rename; see `docs/naming-inventory.md` for the mapping. Findings are unchanged.
- `docs/naming-inventory.md`:
  - header `Status: applied (Stage 1b, 2026-10-22)`
  - note the `punch` → `time_entry` override, the DB name/role kept, and the "Gaps" table from this plan
  - section 10 rewritten: user text now lives in `messages_*.properties` and frontend resources, in ES + EN
- Main session, not the coder's choice: `CHANGELOG.md` (Changed: rename; Added: EN/ES i18n, switcher, reset script; Removed: Spanish error codes and claim names) and `docs/PROGRESS.md` checkboxes for Stage 1b.

### Step 9 - Verification

Coder and tester:
1. `cd backend; .\mvnw.cmd clean test`: all green. Expect about 278 plus the new tests.
2. `cd frontend; npm test; npm run lint; npm run build`: green. Then `npm test` again so `pwaConfig.test.ts` also checks the built manifest.
3. Step 6 grep gate clean. A frontend grep of `src/**/*.tsx` for Spanish literals outside `i18n/resources.ts` and tests finds nothing.
4. `git status` shows renames (R) for the moved files.

User (manual, the sandbox can't run Tomcat): see "User manual steps" below.

### Files summary

| Area | Moved/renamed (`git mv`) | Modified in place | Created | Deleted |
|---|---|---|---|---|
| Backend main Java (49 files) | 2 root dirs, 3 subpackages, 28 class files | all 49 | `common/ErrorCode`, `config/LocaleConfig`, `auth/InvalidPinException` (Open Question 2) | none |
| Backend resources | none | `V2__initial_schema.sql`, `application.yml`, `application-dev.yml`, `pom.xml` | `messages.properties`, `messages_es.properties`, `messages_en.properties` | none |
| Backend tests (31 files) | 2 class files (+ dir moves) | all 31 + `application-test.yml` | `support/TestMessages`, `i18n/MessageBundleParityTest`, `i18n/LocaleResolutionIntegrationTest`, `config/LocaleConfigTest` | none |
| Frontend | none | `api/health.ts`, `components/HealthStatus.tsx`, 3 pages, `App.tsx`, `main.tsx`, `index.css`, `index.html` (comment), `test/setup.ts`, 4 existing tests | `i18n/resources.ts`, `i18n/language.ts`, `i18n/I18nProvider.tsx`, `components/LanguageSwitcher.tsx`, `components/AppHeader.tsx`, `api/client.ts`, `test/renderWithI18n.tsx`, 4 new test files | none |
| Scripts/docs | none | `README.md`, `00-roadmap.md`, Stage 1 plan, `stage-1-er.md`, audit report, `naming-inventory.md` (+ CHANGELOG/PROGRESS by the main session) | `scripts/db/reset-dev-schema.sql` | none |

`scripts/db/create-dev-db.*` and `backend/.env.example` are **not** changed (decision 3).

## User manual steps (after the coder and tester finish)

No passwords are typed into chat. psql prompts for the `asistencia_app` password, which is `DB_PASSWORD` in `backend/.env`.

1. Stop the running backend (Ctrl+C in its terminal).
2. Reset the dev schema (PowerShell, from the repo root):
   ```powershell
   & "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U asistencia_app -h localhost -d asistencia -v ON_ERROR_STOP=1 -f scripts\db\reset-dev-schema.sql
   ```
   Optional check: `& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U asistencia_app -h localhost -d asistencia -c "\dt"` should report "Did not find any relations."
   Without this step, startup fails with a Flyway checksum mismatch for V2.
3. `backend/.env` needs **no** change. `DB_*`, `JWT_SECRET` and `DEV_SEED_PIN` keep their names.
4. Start clean with the dev seed:
   ```powershell
   cd backend
   .\mvnw.cmd clean spring-boot:run "-Dspring-boot.run.profiles=dev"
   ```
   Expect the log line "Dev seed created: company DEMO, 7 employees (codes 1001-1006, 9001)".
5. In a second PowerShell window:
   ```powershell
   $pin = Read-Host 'DEV_SEED_PIN'          # PowerShell 7: add -MaskInput
   $base = 'http://localhost:8080'
   $emp = Invoke-RestMethod -Method Post "$base/api/auth/login" -ContentType 'application/json' `
          -Body (@{companyCode='DEMO'; employeeCode='1001'; pin=$pin} | ConvertTo-Json)
   $emp.employee                             # name María Rojas, jobTitle Cajera, role EMPLOYEE, consentPending True
   Invoke-RestMethod "$base/api/me" -Headers @{Authorization="Bearer $($emp.token)"}
   $adm = Invoke-RestMethod -Method Post "$base/api/auth/login" -ContentType 'application/json' `
          -Body (@{companyCode='DEMO'; employeeCode='9001'; pin=$pin} | ConvertTo-Json)
   (Invoke-RestMethod "$base/api/admin/employees" -Headers @{Authorization="Bearer $($adm.token)"}).Count   # 7
   try { Invoke-RestMethod "$base/api/admin/employees" -Headers @{Authorization="Bearer $($emp.token)"} } catch { $_.Exception.Response.StatusCode.value__ }   # 403
   foreach ($lang in 'en','es',$null) {
     $h = @{}; if ($lang) { $h['Accept-Language'] = $lang }
     try { Invoke-RestMethod -Method Post "$base/api/auth/login" -ContentType 'application/json' -Headers $h `
             -Body (@{companyCode='DEMO'; employeeCode='1001'; pin='00000000'} | ConvertTo-Json) }
     catch { "$lang -> $($_.ErrorDetails.Message)" }   # code INVALID_CREDENTIALS every time; detail EN for en, ES otherwise
   }
   ```
   The wrong-PIN loop counts 3 failures toward the 5-failure lock on 1001. A successful login resets the counter, so log in once more as 1001 afterwards. Otherwise wait 15 minutes if it locks.
6. Frontend: `cd frontend; npm run dev` and open `http://localhost:5173`. Check:
   - the switcher shows Español/English
   - switching changes all text, and the page `<html lang>` (DevTools Elements) shows `en`
   - reloading keeps the choice
   - the DevTools Network tab shows `Accept-Language: en` on `/api/health`
7. Tell the main session the results so it can tick the PROGRESS items and, if asked, commit and push to `stage1-2`.

## Edge Cases to Handle

- **Flyway checksum mismatch** on the dev DB after the in-place V2 edit. The startup error is expected until the user runs the reset (step 2). It never affects tests, because each embedded DB is fresh. If zonky reuses a cached template after the change, `.\mvnw.cmd clean test` (and, as a last resort, deleting the zonky temp folder under `%TEMP%`) refreshes it.
- **Stale compiled classes** under `target/classes/cr/asistencia/**` and an old `V2` copy in `target/classes/db/migration`: always run `clean`.
- **`varchar(12)` too short for `PENDING_REVIEW`.** It is widened to 20 and covered by a new SchemaMigrationTest assertion. Other enum columns were checked (table in Step 2).
- **Config-binding silently 0/null** if the YAML keys and record components drift (`max-attempts` vs `maxAttempts`). Existing lockout and rate-limit tests catch it. Keep them running in Step 2.
- **Old tokens** (`empresaId`/`rol`, issuer `asistencia-backend`) get 401 after the restart. Acceptable in dev, and a test covers it (Step 3).
- **Old clients posting `empresaCodigo`** get 400 `INVALID_REQUEST` (blank required fields), not 500. A test covers it.
- **`MessageSource` not auto-configured** if `messages.properties` is missing (Boot condition). The parity test loads all 3 files, and the integration test proves resolution through the real context.
- **The JVM/OS locale leaking in:** `fallback-to-system-locale: false`, explicit default `es` in the resolver, and the test with `fr`.
- **Missing key → `NoSuchMessageException` → 500.** Parity test plus "every `ErrorCode` has keys" test.
- **Encoding:** properties written UTF-8 without BOM, `spring.messages.encoding: UTF-8`. The parity test checks `ó` decoding and for a BOM. Watch IDE defaults that save `.properties` as ISO-8859-1.
- **MessageFormat apostrophes:** messages have no args today. If args are added, `'` must be doubled.
- **`Accept-Language` variants:** q-lists, `*`, regional tags, garbage, very long headers. Tomcat parses leniently, and the resolver falls back to `es`. All are covered in `LocaleConfigTest`.
- **No enumeration across languages:** bodies differ only by the language the caller chose, never by failure cause. This is verified per language.
- **Security-layer 401/403 have empty bodies** (no text to translate). The frontend must map them by status to its own translated text in Stage 3.
- **jsdom reports `en-US`:** without an explicit `initialLanguage`, existing Spanish assertions would fail. Handled in the setup and helper.
- **`localStorage` unavailable** (Safari private mode, disabled storage, quota): try/catch, in-memory fallback, tested with a throwing spy.
- **Invalid stored value** (`"fr"`, `"EN "`, JSON): ignored, detection continues.
- **React StrictMode double effects:** `setLanguage` and the DOM updates are idempotent.
- **Duplicate `role="status"`:** the switcher's live region must not use it (existing tests query a single status).
- **Data stays Spanish in the English UI** ("Cajera", "Empresa Demo"). This is expected. Never display raw enum values (`EMPLOYEE`, `PENDING_REVIEW`) in the UI. Stage 3/4 map them through resource keys such as `role.EMPLOYEE`.
- **Network error text** (`Failed to fetch`) comes from the browser in the browser's language. It is shown as the `{reason}` inside a translated sentence, which is acceptable.
- **Service worker** may serve the old (Spanish-only) bundle once. `registerType: 'autoUpdate'` replaces it on the next load.
- **Windows path or case issues with `git mv`:** no case-only renames are involved (all renames change letters), so a single `git mv` per path is enough.

## Open Questions

1. **PWA manifest language.** A manifest has one `name`/`short_name`/`lang`.
   - Option A (recommended): keep it Spanish (`Control de Asistencia` / `Asistencia`, `es-CR`). It is the primary market, and the label only appears under the installed icon.
   - Option B: a language-neutral brand name (for example a product name) for `name`/`short_name`. That needs a naming decision from you.
2. **`INVALID_PIN` error code now or in Stage 4?** No endpoint sets PINs until Stage 4.
   - Recommendation: **add it now** (`InvalidPinException` → 400 `INVALID_PIN`, localized). The PinPolicy message then lives in the bundles today and is unit-tested in both languages. The cost is one small class and one handler.
   - Alternative: keep only the bundle keys now and add the handler in Stage 4.
3. **Column `distance_m` vs `distance_meters`.** The inventory says `distance_m`, but the sibling column is `radius_meters`.
   - Recommendation: **`distance_meters`** (Java `distanceMeters` either way) for consistency. Renaming is free now and costly after Stage 2.
4. **Add `@testing-library/user-event` (dev dependency) for the keyboard tests?**
   - Recommendation: **yes**. It simulates real Tab/Enter/Space key sequences more faithfully than `fireEvent`. It is dev-only, React-19-compatible and maintained by the Testing Library team. The coder must check the current version via npm before adding it.
   - Alternative: `fireEvent` only, with no new dependency.

---

**Requirements Checklist status:** all items open. The planner's own deliverable (this plan) is complete; implementation has not started.

## Open Questions: RESOLVED (2026-10-22, user)

All four went with the recommendation. Where earlier text in this plan conflicts with these answers, the answers win.

1. **PWA manifest:** stays Spanish: `Control de Asistencia` / `Asistencia`, `lang: es-CR`.
2. **`INVALID_PIN`:** added now. `InvalidPinException` returns 400 `INVALID_PIN`, localized in EN and ES.
3. **Distance column:** `distance_meters` (Java `distanceMeters`).
4. **`@testing-library/user-event`:** yes, as a dev dependency. Check the current version on npm before adding it.

Also decided: the local DB name `asistencia` and the role `asistencia_app` stay unchanged.
