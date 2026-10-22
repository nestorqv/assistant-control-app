# Spanish naming inventory (backend, Stage 1)

**Status: applied (Stage 1b, 2026-10-22).** Plan: `docs/plans/stage-1b-english-naming-i18n.md`. Deviations from the proposals below:

- `marca` became `time_entry` / `TimeEntry` / package `timeentry` (not `punch`). So `marcaId` is `timeEntryId`, `TipoMarca` is `TimeEntryType`, `OrigenMarca` is `TimeEntrySource` and `EstadoMarca` is `TimeEntryStatus`.
- `distancia_m` became `distance_meters` (column) / `distanceMeters` (field), matching `radius_meters`. `marca.estado` is now `varchar(20)` because `PENDING_REVIEW` has 14 characters.
- The database `asistencia`, the role `asistencia_app`, `scripts/db/*` and `backend/.env.example` kept their names.
- Items not in this inventory that were also renamed: `app.jwt.ttl-empleado` -> `ttl-employee`; JWT issuer and health `service` `asistencia-backend` -> `attendance-backend`; ProblemDetail `instance` `urn:asistencia:error` -> `urn:attendance:error`; logger `cr.asistencia` -> `cr.attendance`; `EmpleadoResumen.de` -> `EmployeeSummary.from`; JPQL parameters (`maxAttempts`, `lockUntil`, `now`, `consentAt`); private helpers and constants (`problem`, `codeFor`, `OPAQUE_INSTANCE`, `isTestProfile`, `toLong`, `normalize`, `allSameDigit`, `isSequence`, `isValidDate`, `DEMO_EMPLOYEES`, `DEMO_COMPANY_CODE`, `PIN_MESSAGE`, ...); `Adjustment.field` examples (`status`, `server_time`); log texts; internal exception messages.
- New: `InvalidPinException` (400 `INVALID_PIN`), `ErrorCode`, `LocaleConfig`.

Inventory of every Spanish identifier in code, database, API and config as of commit `8a06dda` (branch `stage1-2`), with a proposed English name. Use it to decide what to rename before Stage 2 adds more code on top.

## Recommendation

Common industry practice, and the recommendation here:

| Layer | Language | Why |
|---|---|---|
| Code identifiers: packages, classes, methods, fields | **English** | Standard for tooling, libraries, hiring and AI assistants |
| Database schema: tables, columns, constraints | **English** | Same as code; avoids a translation layer in ORM mappings |
| API contract: URLs, JSON fields, enum values, error codes, JWT claims | **English** | Public contract; stable machine codes |
| Text shown to people: UI labels, error `detail` messages, seed names | **Spanish** | End users are in Costa Rica. Keep these, ideally in message files for future i18n |

**Timing:** renaming is cheapest **now**. Only Stage 1 exists, there is no production data, and the dev DB can be reset. Every later stage multiplies the cost.

**Migration approach:** the V2 migration has only been applied to your dev database and the embedded test DBs, never to production. The simplest path is to edit `V2__initial_schema.sql` in place and reset the dev DB, instead of adding a V3 full of `RENAME` statements.

---

## 1. Glossary (domain terms)

| Spanish | Proposed English | Notes |
|---|---|---|
| asistencia | attendance | App name, Java root package, DB name |
| empresa | company | Tenant |
| sucursal | branch | |
| empleado | employee | |
| horario | work_schedule / `WorkSchedule` | `schedule` alone is ambiguous with Spring `@Scheduled` |
| marca | punch / `Punch` | A clock-in/out event. Alternatives: `time_entry`, `clock_event` |
| evidencia | evidence | |
| ajuste | adjustment | |
| consentimiento | consent | |
| puesto | job_title | |
| rol | role | |
| código | code | |

## 2. Packages and application class

| Current | Proposed |
|---|---|
| `cr.asistencia` (root) | `cr.attendance` *(optional; see note)* |
| `AsistenciaApplication` | `AttendanceApplication` |
| `cr.asistencia.empleado` | `...employee` |
| `cr.asistencia.empresa` | `...company` |
| `cr.asistencia.marca` | `...punch` |
| `auth`, `common`, `config`, `health`, `seed` | Already English |

*Note:* renaming the root package touches every file, but the IDE or a script does it mechanically. The Maven `groupId`/`artifactId` (`cr.asistencia` / `asistencia-backend`), the DB name `asistencia` and the DB role `asistencia_app` are infrastructure names. They can stay as a product name, or be renamed too; the DB rename needs a manual step on your PC.

## 3. Java classes (`backend/src/main/java/cr/asistencia/`)

| File | Proposed class |
|---|---|
| `auth/CredencialesInvalidasException.java` | `InvalidCredentialsException` |
| `auth/DemasiadosIntentosException.java` | `TooManyAttemptsException` |
| `auth/EmpleadoAuthentication.java` | `EmployeeAuthentication` |
| `auth/EmpleadoJwtAuthenticationConverter.java` | `EmployeeJwtAuthenticationConverter` |
| `auth/TokenService.java`: inner record `EmitidoToken` | `IssuedToken` |
| `auth/LoginResponse.java`: inner records `EmpleadoInfo`, `EmpresaInfo` | `EmployeeInfo`, `CompanyInfo` |
| `common/EmpleadoPrincipal.java` | `EmployeePrincipal` |
| `common/RecursoNoEncontradoException.java` | `ResourceNotFoundException` |
| `common/Rol.java` | `Role` |
| `empleado/AdminEmpleadoController.java` | `AdminEmployeeController` |
| `empleado/Empleado.java` | `Employee` |
| `empleado/EmpleadoConsultaService.java` | `EmployeeQueryService` |
| `empleado/EmpleadoRepository.java` | `EmployeeRepository` |
| `empleado/EmpleadoResumen.java` | `EmployeeSummary` |
| `empleado/Horario.java` | `WorkSchedule` |
| `empleado/HorarioRepository.java` | `WorkScheduleRepository` |
| `empleado/PerfilResponse.java` (+ inner `EmpresaInfo`) | `ProfileResponse` (+ `CompanyInfo`) |
| `empresa/Empresa.java` | `Company` |
| `empresa/EmpresaRepository.java` | `CompanyRepository` |
| `empresa/Sucursal.java` | `Branch` |
| `empresa/SucursalRepository.java` | `BranchRepository` |
| `marca/Marca.java` | `Punch` |
| `marca/MarcaRepository.java` | `PunchRepository` |
| `marca/Evidencia.java` | `Evidence` |
| `marca/EvidenciaRepository.java` | `EvidenceRepository` |
| `marca/Ajuste.java` | `Adjustment` |
| `marca/AjusteRepository.java` | `AdjustmentRepository` |
| `marca/EstadoMarca.java` | `PunchStatus` |
| `marca/OrigenMarca.java` | `PunchSource` |
| `marca/TipoMarca.java` | `PunchType` |
| `marca/TipoEvidencia.java` | `EvidenceType` |
| `seed/DevDataSeeder.java`: inner record `Demo` | Already English |

Already English: `AuthController`, `JwtConfig`, `JwtProperties`, `LoginAwareBearerTokenResolver`, `LoginRateLimiter`, `LoginRequest`, `LoginService`, `PinPolicy`, `SecurityProperties`, `TokenService`, `ApiExceptionHandler`, `SecurityConfig`, `TimeConfig`, `MeController`, `HealthController`, `HealthResponse`, `SeedProperties`.

## 4. Methods

| Current | Where | Proposed |
|---|---|---|
| `registrarFallo` | `EmpleadoRepository` | `recordFailedLogin` |
| `registrarExito` | `EmpleadoRepository` | `recordSuccessfulLogin` |
| `registrarConsentimiento` | `EmpleadoRepository` | `recordConsent` |
| `findAllByEmpresaIdOrderByNombre` | `EmpleadoRepository` | `findAllByCompanyIdOrderByName` |
| `findByEmpresaIdAndCodigo` | `EmpleadoRepository` | `findByCompanyIdAndCode` |
| `findByIdAndEmpresaId` | several repositories | `findByIdAndCompanyId` |
| `findAllByEmpresaIdAndEmpleadoId` | `HorarioRepository` | `findAllByCompanyIdAndEmployeeId` |
| `findByCodigo` | `EmpresaRepository` | `findByCode` |
| `consentir` | `MeController` | `giveConsent` |
| `perfil` | `MeController` | `profile` |
| `listar` / `obtener` | `AdminEmpleadoController`, `EmpleadoConsultaService` | `list` / `get` |
| `emitir` | `TokenService` | `issue` |
| `exigir` | `PinPolicy` | `requireValid` |
| `esValido` | `PinPolicy` | `isValid` |
| `esFecha` | `PinPolicy` | `isDate` |
| `bloqueRepetido` | `PinPolicy` | `isRepeatedBlock` |
| `esPerfilDePrueba` | `JwtConfig` / `JwtProperties` | `isTestProfile` |
| `esPublico` | `LoginAwareBearerTokenResolver` | `isPublicEndpoint` |
| `intentar` | `LoginRateLimiter` | `tryAcquire` |
| `credencialesInvalidas`, `demasiadosIntentos` | `ApiExceptionHandler` | `invalidCredentials`, `tooManyAttempts` |
| Getters such as `getBloqueadoHasta`, `getIntentosFallidos`, `getConsentimientoFecha`, `getEmpresaId`, `getEstado`, `getPuesto`, `getReintentarEn` | entities and exceptions | Follow the field renames in section 5 |

## 5. Fields and record components (Java)

| Current | Proposed | Current | Proposed |
|---|---|---|---|
| `empresaId` | `companyId` | `empleadoId` | `employeeId` |
| `sucursalId` | `branchId` | `marcaId` | `punchId` |
| `codigo` | `code` | `nombre` | `name` |
| `puesto` | `jobTitle` | `rol` | `role` |
| `activo` | `active` | `zonaHoraria` | `timeZone` |
| `latitud` / `longitud` | `latitude` / `longitude` | `radioMetros` | `radiusMeters` |
| `consentimientoFecha` | `consentAt` | `consentimientoPendiente` | `consentPending` |
| `intentosFallidos` | `failedAttempts` | `bloqueadoHasta` | `lockedUntil` |
| `diaSemana` | `dayOfWeek` | `horaEntrada` / `horaSalida` | `startTime` / `endTime` |
| `toleranciaMin` | `graceMinutes` | `tipo` | `type` |
| `horaServidor` | `serverTime` | `horaDispositivo` | `deviceTime` |
| `distanciaM` | `distanceMeters` | `origen` | `source` |
| `sincronizadaTarde` | `syncedLate` | `estado` | `status` |
| `urlArchivo` | `storageKey` | `creada` | `createdAt` |
| `motivo` | `reason` | `campo` | `field` |
| `valorAnterior` / `valorNuevo` | `oldValue` / `newValue` | `fecha` | `createdAt` |
| `expiraEn` | `expiresAt` | `reintentarEn` | `retryAfter` |
| `maxIntentos` | `maxAttempts` | `duracion` | `duration` |
| `ventana` | `window` | `entrada` / `salida` (seed `Demo`) | `start` / `end` |

## 6. Database schema (`V2__initial_schema.sql`)

### Tables
| Current | Proposed |
|---|---|
| `empresa` | `company` |
| `sucursal` | `branch` |
| `empleado` | `employee` |
| `horario` | `work_schedule` |
| `marca` | `punch` |
| `evidencia` | `evidence` |
| `ajuste` | `adjustment` |

### Columns
| Table | Current → Proposed |
|---|---|
| all tenant tables | `empresa_id` → `company_id` |
| `empresa` | `codigo` → `code`, `nombre` → `name`, `zona_horaria` → `time_zone`, `activo` → `active` |
| `sucursal` | `nombre` → `name`, `latitud` → `latitude`, `longitud` → `longitude`, `radio_metros` → `radius_meters` |
| `empleado` | `sucursal_id` → `branch_id`, `codigo` → `code`, `nombre` → `name`, `puesto` → `job_title`, `rol` → `role`, `activo` → `active`, `consentimiento_fecha` → `consent_at`, `intentos_fallidos` → `failed_attempts`, `bloqueado_hasta` → `locked_until` *(`pin_hash` and `token_version` are already English)* |
| `horario` | `empleado_id` → `employee_id`, `dia_semana` → `day_of_week`, `hora_entrada` → `start_time`, `hora_salida` → `end_time`, `tolerancia_min` → `grace_minutes` |
| `marca` | `empleado_id` → `employee_id`, `sucursal_id` → `branch_id`, `tipo` → `type`, `hora_servidor` → `server_time`, `hora_dispositivo` → `device_time`, `latitud` → `latitude`, `longitud` → `longitude`, `distancia_m` → `distance_m`, `origen` → `source`, `sincronizada_tarde` → `synced_late`, `estado` → `status` *(`client_uuid` is already English)* |
| `evidencia` | `marca_id` → `punch_id`, `tipo` → `type`, `url_archivo` → `storage_key`, `creada` → `created_at` |
| `ajuste` | `marca_id` → `punch_id`, `motivo` → `reason`, `campo` → `field`, `valor_anterior` → `old_value`, `valor_nuevo` → `new_value`, `fecha` → `created_at` *(`admin_id` is already English)* |

### Constraints and indexes
All constraint and index names embed table and column names, for example `uq_empresa_codigo`, `fk_empleado_sucursal_empresa`, `ck_horario_tolerancia` and `ix_marca_por_revisar`. They are renamed by the same glossary, for example `uq_company_code`, `fk_employee_branch_company` and `ix_punch_pending_review`. There are 43 in total: 37 constraints and 6 indexes.

### Stored enum values (CHECK constraints and Java enums)
| Column | Current | Proposed |
|---|---|---|
| `empleado.rol` | `EMPLEADO`, `ADMIN` | `EMPLOYEE`, `ADMIN` |
| `marca.tipo` | `ENTRADA`, `SALIDA` | `CLOCK_IN`, `CLOCK_OUT` |
| `marca.origen` | `MOVIL`, `TABLET`, `QR` | `MOBILE`, `TABLET`, `QR` |
| `marca.estado` | `VALIDA`, `POR_REVISAR`, `RECHAZADA` | `VALID`, `PENDING_REVIEW`, `REJECTED` |
| `evidencia.tipo` | `FOTO`, `GPS`, `QR` | `PHOTO`, `GPS`, `QR` |

## 7. API contract

### Endpoints
| Current | Proposed |
|---|---|
| `POST /api/auth/login` | Already English |
| `GET /api/me` | Already English |
| `POST /api/me/consentimiento` | `POST /api/me/consent` |
| `GET /api/admin/empleados` | `GET /api/admin/employees` |
| `GET /api/admin/empleados/{id}` | `GET /api/admin/employees/{id}` |

### JSON fields
| Payload | Current → Proposed |
|---|---|
| Login request | `empresaCodigo` → `companyCode`, `empleadoCodigo` → `employeeCode`, `pin` (unchanged) |
| Login response | `expiraEn` → `expiresAt`, `empleado` → `employee`, `empresa` → `company`. `token` and `tokenType` are unchanged |
| Employee / profile / summary objects | `codigo` → `code`, `nombre` → `name`, `puesto` → `jobTitle`, `rol` → `role`, `consentimientoPendiente` → `consentPending`, `activo` → `active`, `sucursalId` → `branchId`, `zonaHoraria` → `timeZone` |
| Error body (ProblemDetail) | extra property `codigo` → `code` |

### Error codes (`codigo` values)
| Current | Proposed |
|---|---|
| `CREDENCIALES_INVALIDAS` | `INVALID_CREDENTIALS` |
| `DEMASIADOS_INTENTOS` | `TOO_MANY_ATTEMPTS` |
| `SOLICITUD_INVALIDA` | `INVALID_REQUEST` |
| `METODO_NO_PERMITIDO` | `METHOD_NOT_ALLOWED` |
| `FORMATO_NO_ACEPTABLE` | `NOT_ACCEPTABLE` |
| `TIPO_CONTENIDO_NO_SOPORTADO` | `UNSUPPORTED_MEDIA_TYPE` |
| `NO_ENCONTRADO` | `NOT_FOUND` |
| `ERROR_INTERNO` | `INTERNAL_ERROR` |

### JWT claims
| Current | Proposed |
|---|---|
| `empresaId` | `companyId` |
| `rol` | `role` |
| `ver`, `sub`, `iss`, `exp`, `iat`, `jti` | Already standard |

The `rol` claim is also mapped to the Spring authority `ROLE_EMPLEADO`, which becomes `ROLE_EMPLOYEE`.

## 8. Configuration keys (`application.yml`)

| Current | Proposed |
|---|---|
| `app.security.lockout.max-intentos` | `app.security.lockout.max-attempts` |
| `app.security.lockout.duracion` | `app.security.lockout.duration` |
| `app.security.login-rate-limit.max-intentos` | `app.security.login-rate-limit.max-attempts` |
| `app.security.login-rate-limit.ventana` | `app.security.login-rate-limit.window` |

Env vars (`JWT_SECRET`, `DEV_SEED_PIN`, `DB_*`) are already English. Dev seed company code `DEMO` is language-neutral.

## 9. Tests (`backend/src/test/java/cr/asistencia/`)

- Test **file** names are already English. The package paths follow section 2 (`empleado/` → `employee/`), and `HorarioTimeZoneIntegrationTest` becomes `WorkScheduleTimeZoneIntegrationTest`.
- About **44 test method names** include Spanish domain words (for example `...empresa...`, `...empleado...`, `...bloqueado...`). They are renamed with the glossary.
- `support/TestData` builder methods and every assertion on JSON fields, error codes and enum values follow sections 5–7.
- The total is about 31 test files, all of which reference renamed types.

## 10. User-facing text (Spanish and English)

Done in Stage 1b: this text is no longer Spanish-only.

| Text | Where it lives now |
|---|---|
| Error `title` / `detail` (login 401, PIN policy, 400, 404, 405, 406, 415, 429, 500) | `backend/src/main/resources/messages.properties` (Spanish base), `messages_es.properties`, `messages_en.properties`, keyed `error.<CODE>.title` / `error.<CODE>.detail`; language chosen from `Accept-Language`, default Spanish |
| UI labels ("Control de Asistencia", "Panel del administrador", language switcher, health messages) | `frontend/src/i18n/resources.ts` (`es` and `en`) |
| Seed data: "Empresa Demo", "Sucursal Centro", "Administración", person names, job titles (Cajera, Bodega...) | `DevDataSeeder`. Data, not translated |
| PWA manifest name | Stays Spanish (`Control de Asistencia`, `es-CR`) |
| Developer-facing log lines, startup messages, internal exception messages | English only, not translated |

## 11. Docs that would need updating after a rename

`docs/plans/00-roadmap.md`, `docs/plans/stage-1-data-model-auth.md`, `docs/diagrams/stage-1-er.md`, `README.md`, `CHANGELOG.md` (one new entry; history is not rewritten), `securityAudit-report/stage-1-auth.md` (left as a historical record) and `docs/PROGRESS.md` step labels.

## 12. Effort estimate

- **Scope:** about 50 main and test Java files, 1 migration, 2 YAML files and the docs. Behavior doesn't change, so the 278 existing tests act as the safety net.
- **Your dev DB:** reset it (drop and recreate the `public` schema, or re-run the DB script) because V2 changes in place. The dev seed then re-creates the demo data on the next start with `-Dspring-boot.run.profiles=dev`.
- **Frontend:** no impact yet. Stage 0 only calls `/api/health`, which is unchanged.
