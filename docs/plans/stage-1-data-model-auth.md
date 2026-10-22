# Stage 1 - Data model + authentication

Roadmap: `docs/plans/00-roadmap.md`. Previous stage: `docs/plans/stage-0-foundation.md`. Blueprint: `docs/prototype/Prototipo-Control-Asistencia.pdf`.
Status: PLANNED 2026-10-21. Open questions RESOLVED 2026-10-21 (see the end). No code written yet.

## Summary

Flyway migration `V2__initial_schema.sql` creates the seven blueprint tables with Spanish names. It also adds the agreed columns (`empresa.codigo`, `empresa.zona_horaria`, `empleado.codigo`, `empleado.consentimiento_fecha`, `marca.client_uuid`) and the auth state on `empleado` (`intentos_fallidos`, `bloqueado_hasta`, `token_version`). IDs are `bigint` identity and every instant is `timestamptz`. Enums are `varchar` with `CHECK` constraints. Every tenant-owned table carries `empresa_id`, and **composite foreign keys** `(x_id, empresa_id)` make cross-tenant rows impossible at the database level. JPA entities are flat (FK ids as `Long`, no associations) and are checked by `ddl-auto=validate`. Tenant-owned repositories extend the bare Spring Data `Repository<T, Long>`, so they expose only methods that take `empresaId` and the unscoped `findById` / `findAll` are not reachable. The `empresaId` always comes from the authenticated principal, never from the request. Authentication uses the **Spring Security OAuth2 Resource Server** (Nimbus) with **HS256**. `POST /api/auth/login` accepts `{empresaCodigo, empleadoCodigo, pin}`, checks the BCrypt-hashed 8-digit PIN, and issues a JWT with `sub`=empleadoId, `empresaId`, `rol` and `ver`. The secret comes from `JWT_SECRET`, base64, at least 32 bytes after decoding, and startup fails fast if it is missing or too short. On each request, a JWT converter re-checks that the employee exists in that empresa, is `activo` and has a matching `token_version`. Brute force is limited three ways. Five consecutive failures lock the employee for 15 minutes (auto-unlock). A per-IP limiter (Caffeine) caps login attempts. Every failure returns one generic 401 and always runs one BCrypt comparison, so unknown, inactive, locked and wrong-PIN cases look and take the same. A `dev`-profile seeder creates the blueprint demo company. It reads the shared seed PIN from `DEV_SEED_PIN`, never logs it, and never runs under `test` or `prod`. Tests run on zonky embedded PostgreSQL through MockMvc. Running the full app is a manual step in the user's terminal.

## Verified against official docs (2026-10-21, WebFetch)

| Item | Finding | Consequence for the plan |
|------|---------|--------------------------|
| Boot 3.5 dependency versions | Spring Security **6.5.11**, Hibernate **6.6.53.Final**, Flyway **11.7.2**, Caffeine **3.2.4** (all Boot-managed). Bucket4j is **not** managed. | No versions in the POM for new deps. Use Caffeine for the IP limiter instead of Bucket4j. |
| `NimbusJwtEncoder` (Security 6.5.7 javadoc) | Only constructor `NimbusJwtEncoder(JWKSource<SecurityContext>)`; **no `withSecretKey`**. `withSecretKey(SecretKey)` builder appears only **since 7.0**. | Build the encoder as `new NimbusJwtEncoder(new ImmutableSecret<>(secretKey))`. This compiles on 6.5 **and** 7.x (Boot 4 safe). |
| `NimbusJwtDecoder.SecretKeyJwtDecoderBuilder` (6.5.7) | `macAlgorithm(MacAlgorithm)`, `validateType(boolean)` (since 6.5, enabled by default), `jwtProcessorCustomizer`, `build()`. | `NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()`, then `setJwtValidator(...)`. |
| Resource server JWT reference (7.1.1) | `JwtTimestampValidator` default clock skew 60 s. Validators combine via `DelegatingOAuth2TokenValidator`, `JwtIssuerValidator`, `JwtClaimValidator`. Authorities via `JwtAuthenticationConverter`. | Use a custom `Converter<Jwt, AbstractAuthenticationToken>` (DB re-check). Skew 30 s. Issuer validated. |
| Password storage reference (7.1.1) | `BCryptPasswordEncoder` default strength 10. The docs advise tuning so that verification takes about 1 s. Delegating format `{id}hash`. | Strength configurable, **12** by default (about 250 ms), **4** in tests. Plain `BCryptPasswordEncoder`; see Edge Cases for why not Delegating. |
| Boot 4.0 migration guide | `spring-boot-starter-oauth2-resource-server` -> `spring-boot-starter-security-oauth2-resource-server` (old name deprecated, still works). `spring-boot-starter-web` -> `-webmvc`. Jackson 3 is the default. Flyway needs `spring-boot-starter-flyway`. Modules move to `org.springframework.boot.<module>` packages. | Keep main code free of direct Jackson 2 API usage (`ajuste.valor_anterior` is `text`, not a Jackson-mapped JSON type). Record the starter renames for the 4.x migration. |
| jjwt (GitHub README) | Latest 0.13.0, three artifacts (`jjwt-api`, `-impl`, `-jackson`). | Not chosen (see Open Question 1). |
| OWASP Authentication Cheat Sheet | Use the same message **and status code** for unknown user, wrong password, locked and disabled accounts. Avoid quick-exit timing leaks. Count lockouts per account, not per IP. Watch for lockout DoS. Log failures and lockouts. | Single generic 401 for every failure. Dummy BCrypt for unknown accounts. Per-account lockout plus a per-IP limiter as a separate layer. Failures are logged without the PIN. |
| Hibernate 6.6 `@Filter` / `@TenantId` | Could not verify the find-by-id and native-query behavior from the guide (the page fetch was truncated). | Not relied on. Explicit `empresaId` parameters plus DB composite FKs are used instead (works the same on Hibernate 6.6 and 7). |

Forward-compatibility rules for the Boot 4 / Security 7 migration:
- Lambda DSL only.
- `requestMatchers(String...)` only. No `AntPathRequestMatcher`, `MvcRequestMatcher` or `.and()`.
- `@EnableMethodSecurity` if method security is ever needed (not needed in this stage).
- No `NimbusJwtEncoder.withSecretKey`.
- No Jackson 2 classes in main code.

## Design decisions (final, per the resolved Open Questions)

### Schema (`V2__initial_schema.sql`)

Shared conventions:
- `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`
- `timestamptz` for all instants; `time` for local schedule times
- `varchar` + `CHECK` for enums
- `ON DELETE RESTRICT` on every FK. Attendance data is legal evidence: never hard-delete it; deactivate with `activo = false`.
- Codes are stored upper-case and checked by regex.

**empresa**
- `id`
- `codigo varchar(20) NOT NULL UNIQUE CHECK (codigo ~ '^[A-Z0-9-]{3,20}$')`
- `nombre varchar(120) NOT NULL CHECK (btrim(nombre) <> '')`
- `zona_horaria varchar(64) NOT NULL DEFAULT 'America/Costa_Rica'` (validated as a `ZoneId` in the app)

**sucursal**
- `id`
- `empresa_id bigint NOT NULL REFERENCES empresa`
- `nombre varchar(120) NOT NULL`
- `latitud double precision NOT NULL CHECK (latitud BETWEEN -90 AND 90)`
- `longitud double precision NOT NULL CHECK (longitud BETWEEN -180 AND 180)`
- `radio_metros integer NOT NULL DEFAULT 100 CHECK (radio_metros BETWEEN 10 AND 5000)`
- `UNIQUE (id, empresa_id)` (target of composite FKs)
- `UNIQUE (empresa_id, nombre)`

**empleado**
- `id`
- `empresa_id bigint NOT NULL REFERENCES empresa`
- `sucursal_id bigint NULL`, with `FOREIGN KEY (sucursal_id, empresa_id) REFERENCES sucursal (id, empresa_id)`
- `codigo varchar(20) NOT NULL CHECK (codigo ~ '^[A-Z0-9-]{1,20}$')`, `UNIQUE (empresa_id, codigo)`
- `nombre varchar(120) NOT NULL`
- `puesto varchar(80)` (nullable)
- `pin_hash varchar(100) NOT NULL`
- `rol varchar(10) NOT NULL CHECK (rol IN ('EMPLEADO','ADMIN'))`
- `activo boolean NOT NULL DEFAULT true`
- `consentimiento_fecha timestamptz NULL`
- `intentos_fallidos integer NOT NULL DEFAULT 0 CHECK (>= 0)`
- `bloqueado_hasta timestamptz NULL`
- `token_version integer NOT NULL DEFAULT 0`
- `CHECK (rol = 'ADMIN' OR sucursal_id IS NOT NULL)`
- `UNIQUE (id, empresa_id)`
- Index `(empresa_id, activo)`

**horario**
- `id`
- `empresa_id bigint NOT NULL`
- `empleado_id bigint NOT NULL`, with `FK (empleado_id, empresa_id) -> empleado (id, empresa_id)`
- `dia_semana integer NOT NULL CHECK (BETWEEN 1 AND 7)` (ISO: 1 = lunes)
- `hora_entrada time NOT NULL`, `hora_salida time NOT NULL`, `CHECK (hora_salida <> hora_entrada)` (overnight semantics are decided in Stage 2)
- `tolerancia_min integer NOT NULL DEFAULT 0 CHECK (BETWEEN 0 AND 240)`
- `UNIQUE (empleado_id, dia_semana)` (one shift per day; multiple shifts are "Later")

**marca**
- `id`
- `empresa_id bigint NOT NULL`
- `empleado_id bigint NOT NULL`, with `FK (empleado_id, empresa_id)`
- `sucursal_id bigint NOT NULL`, with `FK (sucursal_id, empresa_id)`
- `tipo varchar(10) NOT NULL CHECK (IN ('ENTRADA','SALIDA'))`
- `hora_servidor timestamptz NOT NULL DEFAULT now()`
- `hora_dispositivo timestamptz NULL`
- `latitud double precision NULL`, `longitud double precision NULL` (same range checks as sucursal)
- `distancia_m integer NULL CHECK (>= 0)`
- `origen varchar(10) NOT NULL CHECK (IN ('MOVIL','TABLET','QR'))`
- `sincronizada_tarde boolean NOT NULL DEFAULT false`
- `estado varchar(12) NOT NULL DEFAULT 'VALIDA' CHECK (IN ('VALIDA','POR_REVISAR','RECHAZADA'))`
- `client_uuid uuid NOT NULL UNIQUE`
- `UNIQUE (id, empresa_id)`
- Indexes:
  - `(empresa_id, hora_servidor)`
  - `(empleado_id, hora_servidor)`
  - partial `(empresa_id) WHERE estado = 'POR_REVISAR'`

**evidencia**
- `id`
- `empresa_id bigint NOT NULL`
- `marca_id bigint NOT NULL`, with `FK (marca_id, empresa_id) -> marca (id, empresa_id)`
- `tipo varchar(10) NOT NULL CHECK (IN ('FOTO','GPS','QR'))`
- `url_archivo varchar(500) NULL`. This is a **storage key**, not a public URL; nullable because GPS evidence has no file.
- `creada timestamptz NOT NULL DEFAULT now()`
- Index `(marca_id)`

**ajuste**
- `id`
- `empresa_id bigint NOT NULL`
- `marca_id bigint NOT NULL`, with `FK (marca_id, empresa_id)`
- `admin_id bigint NOT NULL`, with `FK (admin_id, empresa_id) -> empleado (id, empresa_id)`. The ADMIN role is checked in the app in Stage 4.
- `motivo text NOT NULL CHECK (btrim(motivo) <> '')`
- `valor_anterior text NOT NULL` (JSON snapshot as text; avoids a Jackson-bound Hibernate JSON type before Boot 4)
- `fecha timestamptz NOT NULL DEFAULT now()`
- Index `(marca_id)`

Additional notes:
- Integer columns use `integer` (not `smallint`) so Hibernate `validate` matches Java `Integer` without `Short`.
- The migration header comment states: tenant isolation is enforced by composite FKs; there are no hard deletes; codes are upper-case.

### Tenant isolation (three layers)
1. **Database:** composite FKs. A child row cannot point to a parent from another empresa.
2. **Repositories:** tenant-owned repositories (Sucursal, Empleado, Horario, Marca, Evidencia, Ajuste) extend `org.springframework.data.repository.Repository<T, Long>`. They declare only `save(...)` and methods with an `EmpresaId` parameter, for example `findByIdAndEmpresaId` and `findAllByEmpresaIdOrderByNombre`.
   - The **only** documented unscoped lookups are `EmpresaRepository.findByCodigo` and `EmpleadoRepository.findByEmpresaIdAndCodigo`, used by login before any token exists.
   - A reflection test enforces this convention.
3. **Web:** controllers get `EmpleadoPrincipal` (`empleadoId`, `empresaId`, `rol`) via `@AuthenticationPrincipal` and pass `principal.empresaId()` to services.
   - DTOs never contain `empresaId`.
   - Another tenant's id returns **404**, not 403, so existence is not leaked.

### Authentication flow
- **Login rate limiter:**
  - Runs before any DB or BCrypt work: per `request.getRemoteAddr()`, Caffeine cache `maximumSize 10_000`, `expireAfterWrite` = window. If exceeded, return **429** with `Retry-After` and the generic message "Demasiados intentos. Intente de nuevo en unos minutos."
  - Every attempt counts, successful or not.
  - Defaults: 20 per 5 min, generous because staff share the office NAT IP.
- **Lookup:** normalize the codes (trim, `toUpperCase(Locale.ROOT)`), then `empresa` by codigo, then `empleado` by (empresa_id, codigo).
- **Uniform failure path.** For unknown empresa, unknown empleado, inactive, locked (`bloqueado_hasta > now`) or wrong PIN:
  - Run exactly **one** `passwordEncoder.matches` (against a dummy hash computed at startup with the same strength when there is no real hash).
  - Return **401** ProblemDetail `codigo: CREDENCIALES_INVALIDAS`, message "Empresa, código o PIN incorrectos. Si falló varias veces, espere 15 minutos o consulte a su administrador."
- **Failure accounting** (only for an existing, active, not-locked employee with a wrong PIN): one atomic `@Modifying` update in its own transaction, so it commits even though the request returns 401:
  - `intentos_fallidos = intentos_fallidos + 1`
  - When the count reaches 5: `bloqueado_hasta = now + 15 min` and `intentos_fallidos = 0`.
  - Log at WARN with the empresa code, employee code and IP. **Never** log the PIN or the token.
- **Success:**
  - Reset `intentos_fallidos = 0, bloqueado_hasta = NULL`.
  - Issue the JWT.
  - Response `200`: `{ token, tokenType: "Bearer", expiraEn, empleado: { id, nombre, puesto, rol, consentimientoPendiente }, empresa: { nombre, zonaHoraria } }`.
- **JWT:**
  - HS256, `iss=asistencia-backend`, `sub=<empleadoId>`, `empresaId`, `rol`, `ver=<token_version>`, `iat`, `exp`, `jti`.
  - TTL: EMPLEADO 8 h, ADMIN 6 h (configurable). No refresh tokens.
  - Issued with the injected `Clock`.
- **Per request:** `BearerTokenAuthenticationFilter` -> `NimbusJwtDecoder` (signature, `exp`/`nbf` with 30 s skew, issuer, required claims) -> `EmpleadoJwtAuthenticationConverter`.
  - The converter loads the employee by (sub, empresaId).
  - It throws `InvalidBearerTokenException` (-> 401) if the employee is missing, inactive, or `ver != token_version`.
  - Authorities come from the **DB** role (`ROLE_EMPLEADO` / `ROLE_ADMIN`), so a demoted admin loses access immediately. The `rol` claim is informational for the frontend.
- **Stale tokens on the login request:** a wrapping `BearerTokenResolver` returns `null` for `POST /api/auth/login`, so a stale token sent to login does not cause a 401.
- **Entry point / denied handler:** `HttpStatusEntryPoint(401)` and a plain 403. This keeps the Stage 0 test "no `WWW-Authenticate`, no cookies" valid.

### PIN policy and brute-force budget (8 digits)
`PinPolicy` accepts exactly `^\d{8}$` (a string; leading zeros allowed) and rejects these trivial patterns:
- **All the same digit:** `00000000` ... `99999999`.
- **Straight sequences, wrapping mod 10:** each digit is +1 from the previous one (`01234567`, `12345678`, `23456789`, `34567890`, `45678901`, ...) or -1 (`98765432`, `87654321`, `76543210`, `65432109`, ...).
- **Repeated blocks:** 2-digit blocks such as `12121212` (ABABABAB) and 4-digit blocks such as `12341234` or `19901990` (ABCDABCD).
- **Calendar dates.** 8 digits invite birthdays, so valid dates with years 1900-2099 are rejected in these formats:
  - `DDMMYYYY` (e.g. `15081990`)
  - `MMDDYYYY`
  - `YYYYMMDD` (e.g. `19900815`)

  Use `java.time` strict parsing (`ResolverStyle.STRICT`, so `31022000` is not a date and stays allowed).

All the rules together remove well under 1% of the 10^8 space (about 220k dates plus a few hundred patterns).

The rejection message is in Spanish and never echoes the PIN: "El PIN debe tener exactamente 8 dígitos y no puede ser una secuencia, un patrón repetido ni una fecha."

Login does **not** validate the PIN format with a 400. A PIN of the wrong length or with non-digits goes through the normal path: generic 401, counted as a failure. The only 400 is for blank values or more than 12 characters.

Brute-force arithmetic, rechecked for 10^8:
- **Online, one account.**
  - The lockout allows 5 guesses per 15 min, which is 480 guesses per day.
  - The expected search is 5×10^7 guesses, which takes **about 104,000 days (about 285 years)**.
  - With 6 digits it would have been about 1,040 days (about 2.9 years).
- **Online, if users chose dates.**
  - Plausible dates are about 110k combinations (3 formats × about 36.5k days per century). At 480 per day that is roughly 230 days for the full set.
  - That is why dates are rejected.
  - Even better, Stage 4 should let the system *generate* random PINs for new employees.
- **Online, spraying one PIN across many accounts or companies.**
  - The per-IP limit (20 per 5 min, 5,760 per day) caps a single source.
  - Uniform random PINs make spraying pointless (each guess has a 10^-8 chance).
  - The residual risk is botnets (many IPs), which is out of scope until Stage 5 (Caddy rate limiting, monitoring of failed logins).
- **Offline, after a DB or hash leak.**
  - BCrypt cost 12 is about 250 ms per guess per CPU core, so the full space is about 2.5×10^7 core-seconds, about 290 core-days for one hash.
  - A single high-end GPU is much faster. A rough order of magnitude, not benchmarked: from about a day to a few days per hash.
  - **8 digits do not make a leaked hash safe**; they only make online guessing impractical.
  - Incident response for a hash leak: force-reset all PINs and bump `token_version` (Stage 4 admin tooling; note it in the Stage 5 runbook).

### Endpoints in this stage
| Method + path | Access | Purpose |
|---|---|---|
| `POST /api/auth/login` | public | Login (above) |
| `GET /api/me` | authenticated | Profile for "Hola, María" + `consentimientoPendiente` |
| `POST /api/me/consentimiento` | authenticated | Sets `consentimiento_fecha = now` if null (idempotent, 204). Ley 8968 (OQ 9) |
| `GET /api/admin/empleados` | ADMIN | Read-only list of the token's empresa (needed to test roles + tenant isolation; full CRUD in Stage 4) (OQ 9) |
| `GET /api/admin/empleados/{id}` | ADMIN | 404 if not in the token's empresa |

Errors use Spring `ProblemDetail` (RFC 9457) with an extra `codigo` property. Validation 400s never echo the submitted input.

### Frontend token handling (decision for Stage 3, recorded now)
Decided (OQ 2): send the token in the `Authorization: Bearer` header and keep it in IndexedDB, in the same store as the offline queue.
- **Stateless:** the API stays stateless and CSRF stays disabled, consistent with Stage 0.
- **Offline flush:** queued marks are flushed from the page with whatever token is current. If it has expired, the app asks for the PIN (it is online at that moment) and retries.
- **Origins:** it keeps working if API and UI are later on different origins.

Risk: XSS could read the token. Mitigations:
- No third-party scripts.
- React escaping.
- A strict CSP in Stage 5.
- The short TTL plus the `token_version` kill-switch.

The httpOnly cookie alternative was rejected: it needs CSRF tokens or SameSite reliance, plus a custom `BearerTokenResolver`.

## Requirements Checklist

### Schema
- [ ] Flyway `V2__initial_schema.sql` creates `empresa`, `sucursal`, `empleado`, `horario`, `marca`, `evidencia`, `ajuste` exactly as in Design decisions
- [ ] Agreed additions present: `empresa.codigo` (unique), `empresa.zona_horaria` (default `America/Costa_Rica`), `empleado.codigo` (unique per empresa), `empleado.consentimiento_fecha`, `marca.client_uuid` (unique)
- [ ] Auth state on `empleado`: `intentos_fallidos`, `bloqueado_hasta`, `token_version`
- [ ] CHECK constraints for every enum and range; `timestamptz` for instants; bigint identity ids
- [ ] `empresa_id` on every tenant-owned table + composite FKs; `ON DELETE RESTRICT`
- [ ] Indexes listed above
- [ ] `ddl-auto=validate` passes against the entities (context starts)
- [ ] V1 untouched; `flyway_schema_history` shows V1 and V2

### Domain / persistence
- [ ] Flat JPA entities for all 7 tables; enums `@Enumerated(STRING)`
- [ ] Tenant-owned repositories extend `Repository<T, Long>` with `empresaId`-scoped methods only
- [ ] Documented allow-list of the two unscoped login lookups

### Auth
- [ ] `POST /api/auth/login` with `{empresaCodigo, empleadoCodigo, pin}`; Bean Validation (`@NotBlank`, `@Size(max=20)` codes, `@Size(max=12)` pin)
- [ ] PIN policy: exactly 8 digits. Reject all-same-digit, ascending/descending sequences (wrapping), repeated 2-digit and 4-digit blocks, and calendar dates (DDMMYYYY / MMDDYYYY / YYYYMMDD, 1900-2099). `PinPolicy` is used by the seeder now and by admin CRUD in Stage 4.
- [ ] BCrypt, strength from config (12 default, 4 in tests)
- [ ] Same 401 body and status, and exactly one BCrypt call, for unknown empresa, unknown empleado, inactive, locked and wrong PIN
- [ ] Lockout: 5 consecutive failures -> 15 min lock, auto-unlock, counter reset on success
- [ ] Per-IP login limiter -> 429 + `Retry-After`
- [ ] JWT HS256 via the resource server; `JWT_SECRET` base64, at least 32 decoded bytes, fail fast at startup with a clear message
- [ ] JWT claims `sub`, `empresaId`, `rol`, `ver`, `iss`, `iat`, `exp`, `jti`; TTL 8 h EMPLEADO / 6 h ADMIN; no refresh tokens
- [ ] Per-request DB re-check (exists in empresa, activo, `token_version`)
- [ ] Roles: `/api/admin/**` requires `ROLE_ADMIN`; other `/api/**` authenticated; 401 vs 403 correct
- [ ] Stale token on login ignored
- [ ] No PIN, hash or token in logs or responses (other than the issued token)
- [ ] `GET /api/me`, `POST /api/me/consentimiento`, `GET /api/admin/empleados[/{id}]`

### Config / seed
- [ ] `backend/.env.example` gains `JWT_SECRET=` and `DEV_SEED_PIN=` placeholders (empty) with generation instructions
- [ ] Dev seed only under profile `dev`, and never if `prod` is also active; idempotent; PIN from `DEV_SEED_PIN`, validated by `PinPolicy`; never logged
- [ ] Seed matches the blueprint (Empresa Demo / Sucursal Centro / the six employees + one admin) with Mon-Fri horarios
- [ ] README: new env vars, how to generate the secret, how to run with the `dev` profile, demo login codes (no PIN)

### Tests and docs
- [ ] All tests listed in Step 8 pass with `.\mvnw.cmd test` (no local Postgres, no `.env`)
- [ ] Existing 10 Stage 0 tests still pass (moved to the shared `@IntegrationTest` annotation)
- [ ] CHANGELOG entry `## [date]` (coder)
- [ ] ER diagram by architect before coding; security audit after implementation
- [ ] Manual run in the user's terminal (Step 9)

## Implementation Steps

Paths are relative to the repo root. Steps run in order.

### Step 1 - Dependencies and configuration
1. **`backend/pom.xml`** (modify). Add these two dependencies, without versions (Boot-managed):
   - `org.springframework.boot:spring-boot-starter-oauth2-resource-server`. Comment: "renamed to spring-boot-starter-security-oauth2-resource-server in Boot 4".
   - `com.github.ben-manes.caffeine:caffeine`
2. **`backend/.env.example`** (modify). Append:
   ```
   # JWT signing key: base64, at least 32 random bytes (48 recommended). Generate with
   # PowerShell: $b=New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
   # Git Bash:   openssl rand -base64 48
   JWT_SECRET=
   # Dev profile only: shared PIN for all seeded demo users. Exactly 8 digits; must not be
   # all one digit, a sequence (12345678, 87654321), a repeated block (12121212, 12341234)
   # or a date (DDMMYYYY, MMDDYYYY, YYYYMMDD). Startup fails with a clear message otherwise.
   DEV_SEED_PIN=
   ```
3. **`backend/src/main/resources/application.yml`** (modify). Add:
   - `spring.mvc.problemdetails.enabled: true`
   - `app.jwt.secret: ${JWT_SECRET:}`. The empty default lets `JwtProperties` fail with a clear message instead of a placeholder error.
   - `app.jwt.issuer: asistencia-backend`, `app.jwt.ttl-empleado: 8h`, `app.jwt.ttl-admin: 6h`, `app.jwt.clock-skew: 30s`
   - `app.security.bcrypt-strength: 12`
   - `app.security.lockout.max-intentos: 5`, `app.security.lockout.duracion: 15m`
   - `app.security.login-rate-limit.max-intentos: 20`, `app.security.login-rate-limit.ventana: 5m`
4. **`backend/src/main/resources/application-dev.yml`** (new):
   - `app.seed.pin: ${DEV_SEED_PIN:}`
   - `logging.level.cr.asistencia: DEBUG`
5. **`backend/src/test/resources/application-test.yml`** (new):
   - A fixed **test-only** base64 `app.jwt.secret`, with a comment saying it is not a real secret
   - `app.security.bcrypt-strength: 4`
   - Rate limit high enough for the tests (for example 1000) except in the limiter test, which overrides it

### Step 2 - Migration
6. **`backend/src/main/resources/db/migration/V2__initial_schema.sql`** (new): the tables in dependency order (empresa, sucursal, empleado, horario, marca, evidencia, ajuste), with the constraints and indexes from Design decisions. Name constraints explicitly (e.g. `fk_empleado_sucursal_empresa`, `ck_marca_estado`) so test assertions and future migrations can refer to them.

### Step 3 - Entities and repositories
7. `cr.asistencia.empresa`:
   - **`Empresa.java`**, **`EmpresaRepository.java`** (`JpaRepository` is fine here: empresa is the tenant root. Methods: `Optional<Empresa> findByCodigo(String)`.)
   - **`Sucursal.java`**, **`SucursalRepository.java`** (`Repository<Sucursal, Long>`: `save`, `findByIdAndEmpresaId`, `findAllByEmpresaIdOrderByNombre`)
8. `cr.asistencia.empleado`:
   - **`Rol.java`** (enum `EMPLEADO`, `ADMIN`)
   - **`Empleado.java`**
   - **`EmpleadoRepository.java`**:
     - `save`, `findByIdAndEmpresaId`, `findAllByEmpresaIdOrderByNombre`
     - `findByEmpresaIdAndCodigo` (login allow-list)
     - `@Modifying @Transactional` methods:
       - `registrarFallo(id, maxIntentos, bloqueoHasta)`: atomic increment / lock
       - `registrarExito(id)`
       - `registrarConsentimiento(id, empresaId, fecha)`: `WHERE consentimiento_fecha IS NULL`
   - **`Horario.java`**, **`HorarioRepository.java`** (`save`, `findAllByEmpresaIdAndEmpleadoId`)
9. `cr.asistencia.marca`:
   - **`TipoMarca.java`**, **`OrigenMarca.java`**, **`EstadoMarca.java`**, **`TipoEvidencia.java`** (enums)
   - **`Marca.java`**, **`Evidencia.java`**, **`Ajuste.java`**
   - **`MarcaRepository.java`**, **`EvidenciaRepository.java`**, **`AjusteRepository.java`**: `Repository<T, Long>` with `save` + `findByIdAndEmpresaId` only. No endpoints until Stages 2 and 4.
10. Mapping rules for every entity:
    - `@Table(name = "...")`
    - `@GeneratedValue(strategy = IDENTITY)`
    - `Instant` for timestamptz, `LocalTime` for time, `UUID` for uuid, `Integer` for integer, `Double` for double precision
    - FK columns as `Long` (no associations)
    - Protected no-arg constructor
    - No Lombok (not in the project)

### Step 4 - Auth core (`cr.asistencia.auth`)
11. **`JwtProperties.java`**: `@ConfigurationProperties("app.jwt") @Validated` record (secret, issuer, ttlEmpleado, ttlAdmin, clockSkew). Its method `SecretKey secretKey()`:
    - base64-decodes the secret
    - throws `IllegalStateException("JWT_SECRET must be base64 with at least 32 bytes ...")` if it is blank, not base64 or shorter than 32 bytes
    - is called eagerly from `JwtConfig` so startup fails fast
12. **`SecurityProperties.java`**: `@ConfigurationProperties("app.security")` record (bcryptStrength, lockout{maxIntentos, duracion}, loginRateLimit{maxIntentos, ventana}). Register both properties classes with `@ConfigurationPropertiesScan` on `AsistenciaApplication` (modify) or `@EnableConfigurationProperties` in `JwtConfig`.
13. **`JwtConfig.java`** (`@Configuration`), beans:
    - `PasswordEncoder`: `new BCryptPasswordEncoder(strength)`
    - `JwtEncoder`: `new NimbusJwtEncoder(new ImmutableSecret<>(key))`
    - `JwtDecoder`: `NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()` + `setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(clockSkew), new JwtIssuerValidator(issuer), claim validators requiring sub/empresaId/rol/ver))`
14. **`EmpleadoPrincipal.java`**: `record (Long empleadoId, Long empresaId, Rol rol)`.
15. **`EmpleadoAuthentication.java`**: extends `AbstractAuthenticationToken`. Holds the principal and the `Jwt` as credentials, and is authenticated.
16. **`EmpleadoJwtAuthenticationConverter.java`**: `Converter<Jwt, AbstractAuthenticationToken>`.
    - Parses `sub` / `empresaId` / `ver`; on a malformed value throws `InvalidBearerTokenException`.
    - `empleadoRepository.findByIdAndEmpresaId`
    - Checks `activo` and `token_version`.
    - Authorities `ROLE_<db rol>`.
17. **`TokenService.java`**: `EmitidoToken emitir(Empleado)` builds the claims using the `Clock` and the role-based TTL, adds `jti` = random UUID, and returns the token plus `expiraEn`.
18. **`PinPolicy.java`**:
    - `static final int LONGITUD = 8`.
    - `static boolean esValido(String pin)`: implements every rule in "PIN policy and brute-force budget":
      - `^\d{8}$`
      - not all the same digit
      - not a +1/-1 wrapping sequence
      - not an ABABABAB or ABCDABCD block
      - not a strict-valid date in DDMMYYYY / MMDDYYYY / YYYYMMDD with year 1900-2099
    - `static void exigir(String pin)` throws `IllegalArgumentException` with the Spanish message from that section. The message never includes the PIN.
19. **`LoginRateLimiter.java`**: Caffeine `Cache<String, AtomicInteger>`; `boolean intentar(String ip)`; `Duration reintentarEn()`. Constructor accepts a `Ticker` for tests.
20. **`LoginRequest.java`** (record + validation), **`LoginResponse.java`** (record with nested `EmpleadoInfo`, `EmpresaInfo`).
21. **`CredencialesInvalidasException.java`**, **`DemasiadosIntentosException.java`** (both carry no user data).
22. **`LoginService.java`**: implements the uniform flow from Design decisions.
    - Holds `dummyHash` computed in its constructor.
    - Not `@Transactional` as a whole: the failure counter must commit; the repository methods carry their own transactions.
    - Uses `Clock` for `now`.
23. **`AuthController.java`**: `@RestController @RequestMapping("/api/auth")`, `@PostMapping("/login")`. Gets the IP from `HttpServletRequest.getRemoteAddr()`.
24. **`LoginAwareBearerTokenResolver.java`**: wraps `DefaultBearerTokenResolver` and returns `null` for `POST /api/auth/login`.

### Step 5 - Security wiring
25. **`backend/src/main/java/cr/asistencia/config/SecurityConfig.java`** (modify). Keep everything from Stage 0, then:
    - Permit `POST /api/auth/login`.
    - `/api/admin/**` -> `hasRole("ADMIN")`.
    - `anyRequest().authenticated()` stays.
    - `.oauth2ResourceServer(o -> o.bearerTokenResolver(...).authenticationEntryPoint(new HttpStatusEntryPoint(UNAUTHORIZED)).accessDeniedHandler((req, res, e) -> res.setStatus(403)).jwt(j -> j.jwtAuthenticationConverter(converter)))`
    - Global `exceptionHandling` keeps the same 401 entry point and gets the same 403 handler.
    - Replace the "JWT filter arrives in Stage 1" comment.
26. **`cr/asistencia/common/ApiExceptionHandler.java`** (new): `@RestControllerAdvice` mapping:
    - `CredencialesInvalidasException` -> 401 ProblemDetail (`codigo=CREDENCIALES_INVALIDAS`)
    - `DemasiadosIntentosException` -> 429 + `Retry-After`
    - `MethodArgumentNotValidException` -> 400 (`codigo=SOLICITUD_INVALIDA`, no field values)
    - `RecursoNoEncontradoException` -> 404
27. **`cr/asistencia/common/RecursoNoEncontradoException.java`** (new).

### Step 6 - Profile and admin read endpoints (`cr.asistencia.empleado`)
28. **`PerfilResponse.java`**, **`MeController.java`**:
    - `GET /api/me` -> profile from `findByIdAndEmpresaId(principal...)` + empresa
    - `POST /api/me/consentimiento` -> 204
29. **`EmpleadoResumen.java`** (id, codigo, nombre, puesto, rol, activo, sucursalId; **no** pin_hash or lock fields), **`EmpleadoConsultaService.java`**, **`AdminEmpleadoController.java`**:
    - `GET /api/admin/empleados`
    - `GET /api/admin/empleados/{id}` (404 for another tenant)

### Step 7 - Dev seed (`cr.asistencia.seed`)
30. **`SeedProperties.java`** (`app.seed.pin`).
31. **`DevDataSeeder.java`**: `@Component @Profile("dev")`, an `ApplicationRunner`, `@Transactional`.
    - **Guards:**
      - Abort with an exception if the `prod` profile is also active.
      - `PinPolicy.exigir(pin)`. If it is missing or invalid, fail with: "DEV_SEED_PIN is required for the dev profile: exactly 8 digits, not a sequence, repeated pattern or date". The message never includes the value.
    - **Idempotent:** if empresa `DEMO` exists, log "Dev seed already present" and return. PINs are not reset; to change them, recreate the DB.
    - **Data:**
      - Empresa `DEMO` "Empresa Demo"
      - Sucursal "Sucursal Centro" (placeholder coordinates 9.9325, -84.0796, radius 100 m; see Open Question 10)
      - Employees:

        | Code | Name | Puesto | Rol |
        |------|------|--------|-----|
        | 1001 | María Rojas | Cajera | EMPLEADO |
        | 1002 | José Vargas | Bodega | EMPLEADO |
        | 1003 | Ana Chaves | Ventas | EMPLEADO |
        | 1004 | Luis Mora | Ventas | EMPLEADO |
        | 1005 | Karla Solís | Limpieza | EMPLEADO |
        | 1006 | Diego Arias | Bodega | EMPLEADO |
        | 9001 | Administrador Demo | Administración | ADMIN (sucursal null) |

      - All PINs: `passwordEncoder.encode(seedPin)`. `consentimiento_fecha` null.
      - Horarios Mon-Fri 08:00-17:00, tolerance 5 min. Karla 06:00-14:00.
    - **Logging:** only "Dev seed created: empresa DEMO, 7 empleados (codes 1001-1006, 9001)". **Never** the PIN or hashes.

### Step 8 - Tests (`backend/src/test/java/cr/asistencia/...`)
32. **`support/IntegrationTest.java`** (new meta-annotation): `@SpringBootTest @AutoConfigureMockMvc @AutoConfigureEmbeddedDatabase(type = POSTGRES, provider = ZONKY) @ActiveProfiles("test")`.
33. **`support/TestData.java`** (new `@Component` in the test sources, or a helper built from `JdbcTemplate` + `PasswordEncoder`):
    - Creates an empresa, sucursal and empleados with **random unique codes**, because the cached Spring context shares one DB across test classes.
    - Has a `login(...)` helper that returns a token.
34. **`support/JwtTestTokens.java`**: builds tokens with the real `JwtEncoder` (expired, wrong issuer, missing claims), a token signed with a *different* key, an `alg: none` token, and a payload-tampered token.
35. **`health/HealthEndpointTest.java`** and **`config/SecurityAndActuatorTest.java`** (modify): replace the three class annotations with `@IntegrationTest`. No assertion changes. Add to `SecurityAndActuatorTest`:
    - `GET /api/auth/login` -> not 200 (405 or 401)
    - `/api/admin/x` without a token -> 401
36. **`db/SchemaMigrationTest.java`**:
    - V2 is recorded and successful.
    - The 7 tables exist (`information_schema`).
    - The CHECK constraints reject a bad `rol`, `estado`, `origen`, `dia_semana` and lower-case `codigo`.
    - Duplicate `empresa.codigo`, duplicate `(empresa_id, codigo)`, duplicate `client_uuid` and duplicate `(empleado_id, dia_semana)` are rejected.
    - An EMPLEADO with a null sucursal is rejected.
    - `zona_horaria` defaults to `America/Costa_Rica`.
37. **`db/TenantConstraintsTest.java`** (JdbcTemplate, expects `DataIntegrityViolationException`). Each of these is rejected:
    - an empleado of empresa A with a sucursal of B
    - a horario of A with an empleado of B
    - a marca with an empleado or sucursal of B
    - evidencia / ajuste with a marca of B
    - an ajuste with an admin of B
    - deleting an empresa that has children (RESTRICT)
38. **`tenancy/TenantRepositoryConventionTest.java`** (plain unit test):
    - Reflects over the tenant-owned repository interfaces.
    - Asserts they do **not** extend `CrudRepository`/`JpaRepository`.
    - Asserts every declared method except `save` has a parameter named or typed for `empresaId` (method name contains `EmpresaId`), apart from the allow-list.
39. **`auth/PinPolicyTest.java`** (unit):
    - **Valid PINs pass:** e.g. `48207315`, `07391846` (leading zero), `31022000` (not a real date).
    - **Wrong length or format fails:** 6, 7 and 9 digits; letters; spaces; full-width digits; null and blank.
    - **Same digit fails:** `00000000`, `77777777`.
    - **Sequences fail:** `12345678`, `01234567`, `23456789`, `34567890` (wrap), `87654321`, `98765432`, `10987654` (wrap).
    - **Blocks fail:** `12121212`, `90909090`, `12341234`, `19901990`.
    - **Dates fail:** `15081990` (DDMMYYYY), `08151990` (MMDDYYYY), `19900815` (YYYYMMDD), `29022000` (leap day). `29021999` is not a date and passes unless another rule rejects it.
    - **Year boundaries:** `01011900` and `31122099` fail; `01011899` passes.
    - The `exigir` exception message does not contain the input.
40. **`auth/JwtPropertiesTest.java`** (unit):
    - Blank, non-base64 and 31-byte secrets are rejected with the documented message.
    - A 32-byte secret is accepted.
41. **`auth/LoginRateLimiterTest.java`** (unit with a fake Caffeine `Ticker`):
    - The limit applies per IP.
    - The window resets.
    - Different IPs are independent.
42. **`auth/LoginServiceTest.java`** (unit, Mockito):
    - Exactly one `passwordEncoder.matches` call for each of: unknown empresa, unknown empleado, inactive, locked, wrong PIN, correct PIN.
    - `registrarFallo` is **not** called for unknown, inactive or locked.
    - Codes are normalized (`" demo "` -> `DEMO`).
43. **`auth/LoginIntegrationTest.java`** (`@IntegrationTest`, MockMvc):
    - **Success:** 200, token, `expiraEn` around now + 8 h (EMPLEADO) or 6 h (ADMIN), `consentimientoPendiente=true`. The body does not contain `pinHash`.
    - **Generic failures:** wrong PIN, unknown empresa, unknown empleado and inactive all give 401 with an **identical body**.
    - **Lowercase codes:** `empresaCodigo` in lowercase still logs in.
    - **Validation:** missing fields -> 400 without echoing input; a 10 KB `pin` -> 400.
    - **Stale token:** a stale/invalid `Authorization` header on login still gets 200.
    - **Rate limit:** with `@TestPropertySource(app.security.login-rate-limit.max-intentos=3)`, the 4th attempt -> 429 + `Retry-After`.
44. **`auth/LockoutIntegrationTest.java`**:
    - 4 failures, then success -> counter reset.
    - 5 failures -> `bloqueado_hasta` set; the **correct** PIN now -> 401 with the same body.
    - After `bloqueado_hasta` is moved to the past via JdbcTemplate, the correct PIN -> 200.
    - Concurrent failures (5 threads) end with a consistent lock (no lost updates).
45. **`auth/JwtAuthenticationIntegrationTest.java`**. Valid token on `GET /api/me` -> 200. Each of these -> 401:
    - no token
    - expired token (beyond the skew)
    - wrong issuer
    - wrong key
    - `alg: none`
    - tampered payload (`empresaId` changed)
    - missing `empresaId`
    - employee deactivated after issue
    - `token_version` incremented after issue
    - `empresaId` claim not matching the DB row
46. **`empleado/MeControllerTest.java`**:
    - `/api/me` returns the caller's own data (incl. empresa `zonaHoraria`).
    - Consent sets the date once (idempotent: a second call keeps the first date); afterwards `consentimientoPendiente=false`.
47. **`empleado/AdminEmpleadoControllerTest.java`**:
    - EMPLEADO token -> 403.
    - ADMIN token -> 200 with only its own empresa's employees (empresa B rows absent).
    - `GET /{idOfB}` with an A admin token -> 404.
    - A role change in the DB (ADMIN -> EMPLEADO) takes effect on an already-issued token -> 403.
    - The response has no `pinHash`, `intentosFallidos` or `bloqueadoHasta`.
48. **`seed/DevSeedProfileTest.java`**:
    - (a) Under `@IntegrationTest` (`test` profile), the context has **no** `DevDataSeeder` bean and no `DEMO` empresa.
    - (b) A separate class `seed/DevSeedEnabledTest.java` uses `@SpringBootTest` + zonky + `@ActiveProfiles({"test","dev"})` + `app.seed.pin` set to a valid test value:
      - 1 empresa `DEMO`, 1 sucursal, 7 empleados (1 ADMIN), horarios present
      - Running the runner a second time leaves the counts unchanged.
      - Login of `DEMO`/`1001` with the seed PIN -> 200.
      - `pin_hash` starts with `$2`.
      - Captured output (`OutputCaptureExtension`) does not contain the PIN.
    - (c) Unit: the seeder with an invalid or empty PIN throws the documented message. The seeder with `prod` active throws.

### Step 9 - Verification
49. **In Claude's sandbox:** `cd backend; .\mvnw.cmd test`. All tests green: the 10 from Stage 0 plus the new ones. The coder reports the count.
50. **In the user's terminal** (Claude's sandbox cannot open Tomcat's loopback socket):
    1. Add `JWT_SECRET` (generated with the command in `.env.example`) and `DEV_SEED_PIN` to `backend/.env`. The user types these values; they are never pasted into chat, docs or logs.
    2. `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"`. The log shows Flyway "Successfully applied 1 migration ... v2" and "Dev seed created". Without `JWT_SECRET`, startup fails with the clear message.
    3. Log in:
       ```
       $r = Invoke-RestMethod -Method Post http://localhost:8080/api/auth/login -ContentType 'application/json' -Body (@{empresaCodigo='DEMO'; empleadoCodigo='1001'; pin=(Read-Host 'PIN')} | ConvertTo-Json)
       ```
       Then `Invoke-RestMethod http://localhost:8080/api/me -Headers @{Authorization="Bearer $($r.token)"}` shows María Rojas.
    4. Wrong PIN 5 times, then the correct PIN -> still 401. After 15 minutes it works again.
    5. Restart without the `dev` profile -> no seeding.
51. **README.md** (modify):
    - "Configuration" section: `JWT_SECRET`, `DEV_SEED_PIN` and how to generate the secret
    - "Dev data" section: profile command, empresa `DEMO`, codes 1001-1006 / 9001 (no PIN shown)
    - "Authentication" section: endpoint, lockout, token TTL
52. **CHANGELOG.md**: the coder adds a dated entry (Added: schema V2, auth, seed, endpoints; Changed: SecurityConfig, test annotations).

### Files the coder will create or modify

Modify:
- `backend/pom.xml`
- `backend/.env.example`
- `backend/src/main/resources/application.yml`
- `backend/src/main/java/cr/asistencia/AsistenciaApplication.java` (`@ConfigurationPropertiesScan`)
- `backend/src/main/java/cr/asistencia/config/SecurityConfig.java`
- `backend/src/test/java/cr/asistencia/health/HealthEndpointTest.java`
- `backend/src/test/java/cr/asistencia/config/SecurityAndActuatorTest.java`
- `README.md`
- `CHANGELOG.md`

Create, main (`backend/src/main/...`):
- `resources/application-dev.yml`
- `resources/db/migration/V2__initial_schema.sql`
- `java/cr/asistencia/common/ApiExceptionHandler.java`
- `java/cr/asistencia/common/RecursoNoEncontradoException.java`
- `java/cr/asistencia/empresa/`:
  - `Empresa.java`, `EmpresaRepository.java`
  - `Sucursal.java`, `SucursalRepository.java`
- `java/cr/asistencia/empleado/`:
  - `Rol.java`, `Empleado.java`, `EmpleadoRepository.java`
  - `Horario.java`, `HorarioRepository.java`
  - `PerfilResponse.java`, `MeController.java`
  - `EmpleadoResumen.java`, `EmpleadoConsultaService.java`, `AdminEmpleadoController.java`
- `java/cr/asistencia/marca/`:
  - Enums: `TipoMarca.java`, `OrigenMarca.java`, `EstadoMarca.java`, `TipoEvidencia.java`
  - Entities: `Marca.java`, `Evidencia.java`, `Ajuste.java`
  - Repositories: `MarcaRepository.java`, `EvidenciaRepository.java`, `AjusteRepository.java`
- `java/cr/asistencia/auth/`:
  - Configuration: `JwtProperties.java`, `SecurityProperties.java`, `JwtConfig.java`
  - Principal and token handling: `EmpleadoPrincipal.java`, `EmpleadoAuthentication.java`, `EmpleadoJwtAuthenticationConverter.java`, `TokenService.java`, `LoginAwareBearerTokenResolver.java`
  - Policy and limiter: `PinPolicy.java`, `LoginRateLimiter.java`
  - Login flow: `LoginRequest.java`, `LoginResponse.java`, `LoginService.java`, `AuthController.java`
  - Exceptions: `CredencialesInvalidasException.java`, `DemasiadosIntentosException.java`
- `java/cr/asistencia/seed/`:
  - `SeedProperties.java`, `DevDataSeeder.java`

Create, test (`backend/src/test/...`):
- `resources/application-test.yml`
- `java/cr/asistencia/support/`:
  - `IntegrationTest.java`, `TestData.java`, `JwtTestTokens.java`
- `java/cr/asistencia/db/`:
  - `SchemaMigrationTest.java`, `TenantConstraintsTest.java`
- `java/cr/asistencia/tenancy/TenantRepositoryConventionTest.java`
- `java/cr/asistencia/auth/`:
  - Unit tests: `PinPolicyTest.java`, `JwtPropertiesTest.java`, `LoginRateLimiterTest.java`, `LoginServiceTest.java`
  - Integration tests: `LoginIntegrationTest.java`, `LockoutIntegrationTest.java`, `JwtAuthenticationIntegrationTest.java`
- `java/cr/asistencia/empleado/`:
  - `MeControllerTest.java`, `AdminEmpleadoControllerTest.java`
- `java/cr/asistencia/seed/`:
  - `DevSeedProfileTest.java`, `DevSeedEnabledTest.java`

The user edits these themselves: `backend/.env` (add `JWT_SECRET`, `DEV_SEED_PIN`) and `docs/PROGRESS.md`. Note that the PROGRESS step "Flyway V1 schema" should read **V2**, because V1 is the Stage 0 baseline.

### Hand-off to the architect (before coding)
Write `docs/diagrams/stage-1-er.md`. There is no code yet, so draw from this plan, then refresh it after the coder finishes.
1. **Mermaid `erDiagram`** of the 7 tables:
   - all columns with types; PK/FK/UK markers
   - **composite FKs** `(x_id, empresa_id)` called out
   - enum value sets as comments
   - cardinalities:
     - empresa 1-N sucursal/empleado
     - sucursal 0..1-N empleado
     - empleado 1-N horario (max 7) and 1-N marca
     - marca 1-N evidencia, 1-N ajuste
     - empleado(ADMIN) 1-N ajuste
2. **Sequence diagram** of `POST /api/auth/login`: rate limiter -> empresa/empleado lookup -> uniform BCrypt -> lockout update -> TokenService.
3. **Sequence diagram** of an authenticated request: BearerTokenAuthenticationFilter -> NimbusJwtDecoder validators -> EmpleadoJwtAuthenticationConverter DB re-check -> controller using `principal.empresaId()` -> scoped repository.
4. **Layer diagram** of the packages: `config`, `common`, `auth`, `empresa`, `empleado`, `marca`, `seed`, `health`.

### Hand-off to the security-auditor (after implementation)
Focus on:
- **Tenant isolation:**
  - any repository or query path without `empresaId`
  - any DTO/request field that could set `empresaId`
  - 404 vs 403 leakage
  - composite FKs present in the migration
- **Login:**
  - enumeration via body, status or timing (dummy-hash path)
  - lockout bypass (concurrency, case/whitespace variants of the codes, the counter committing on failure)
  - rate-limiter memory bound and IP source (`getRemoteAddr`; `X-Forwarded-For` must not be trusted until Stage 5 configures the proxy)
- **JWT:**
  - algorithm pinned to HS256 (`alg: none` / RS-HS confusion)
  - secret length/entropy and fail-fast
  - issuer, expiry and skew validation
  - token not logged
  - `token_version` and `activo` re-check
  - role taken from the DB
- **Secrets hygiene:**
  - `.env.example` placeholders empty
  - the test secret clearly test-only
  - `DEV_SEED_PIN` never logged
  - the seeder impossible under `prod`
- **Error handling:** ProblemDetail bodies without stack traces or input echo; `server.error.include-stacktrace: never` still in place.
- **Sensitive fields:** never serialized (`pin_hash`, lock fields).
- **Dependencies:** new dependencies (resource-server starter, Caffeine) have no known CVEs at the managed versions.

## Edge Cases to Handle

- **Unknown company or employee timing.** Always do exactly one BCrypt `matches`, against a startup-computed dummy hash of the same strength. Unit-tested via call counts; no flaky wall-clock timing tests.
- **Lockout as a DoS vector.** An attacker who knows a code can lock a colleague for 15 minutes. Accepted: auto-unlock, admin unlock in Stage 4 (the columns already exist), and the per-IP limiter slows mass lockouts. This is documented in the README.
- **Locked message vs enumeration.** OWASP requires the same status and body, so locked accounts get the same 401. The generic message mentions the 15-minute wait so legitimate users are not confused.
- **Concurrent wrong PINs.** The atomic SQL increment (`SET intentos_fallidos = intentos_fallidos + 1`) avoids lost updates. Tested with 5 threads.
- **Failure counter rolled back.** If `LoginService` were `@Transactional` and threw, the increment would roll back. That is why the service is not transactional and the `@Modifying` repository methods commit on their own.
- **Shared office IP.** The per-IP limit is generous (20 per 5 min). Behind Caddy in Stage 5, every request appears to come from the proxy IP until `server.forward-headers-strategy` and trusted proxies are configured (record in the Stage 5 plan).
- **Stale token sent to login.** `BearerTokenAuthenticationFilter` would otherwise answer 401 on a `permitAll` path. Solved by `LoginAwareBearerTokenResolver`.
- **Employee deactivated, PIN changed or demoted while the token is valid.** The per-request DB check of `activo`, `token_version` and role handles it. Stage 4 PIN reset must increment `token_version`.
- **Token expires mid-shift.** The EMPLEADO TTL is 8 h, but a full day can be longer (the blueprint shows "Jornada completa · 9 h 05 min"). An employee who logged in at entrada may have to re-enter the PIN for salida. That is expected and acceptable; Stage 3 must handle a 401 on `POST /api/marcas` by asking for the PIN and retrying the queued mark, without losing it. Admins (6 h) re-login during long sessions.
- **8-digit keypad UX.** Entering 8 digits on a phone is slower and more error-prone. The Stage 3 designer should show 8 grouped dots (4+4) and give no hint about which digit is wrong.
- **Clock skew between server and token.** 30 s skew. The server uses the UTC `Clock` bean.
- **`JWT_SECRET` missing, short or non-base64.** Startup fails with an explicit message, never a silent random key (a random key would invalidate tokens on restart and hide misconfiguration). The secret is not given a default in `application.yml`.
- **Secret rotation.** Changing `JWT_SECRET` invalidates all tokens and users must re-enter their PIN. Acceptable; document it in the README.
- **Code normalization.** `" demo "` and `demo` both resolve to `DEMO`; the DB CHECK guarantees stored codes are upper-case. Use `Locale.ROOT` to avoid the Turkish-i problem.
- **Oversized or odd input.** `@Size` limits stop very long strings before they reach BCrypt (BCrypt only uses the first 72 bytes; Spring Security versions differ on rejecting longer input). PINs with non-digits just fail as wrong PINs and count as a failure.
- **Leading zeros.** PINs are strings (`"07391846"` is valid), never numbers. Codes are strings too (`"0001"` is not the same as `"1"`).
- **Why plain BCrypt, not `DelegatingPasswordEncoder`.** Kept simple. Should an algorithm change ever be needed, a migration can prefix existing hashes with `{bcrypt}`. `pin_hash varchar(100)` leaves room for that.
- **Hibernate validate mismatches.** Use `integer` (not `smallint`) and `double precision` with `Double`. `timestamptz` maps to `Instant`, and `text` maps to `String`. Enums are `varchar` + `@Enumerated(STRING)`.
- **Shared embedded DB across test classes.** The Spring test-context cache reuses one DB, so every test creates data with random codes and never asserts global row counts (except in the separate dev-seed context).
- **Seed running where it should not.** `@Profile("dev")` plus an explicit `prod` guard. The `test` profile never includes `dev`. Seeding is idempotent and never resets PINs.
- **`DEV_SEED_PIN` changed after seeding.** No effect, by design. The README says to recreate the dev DB (or Stage 4 admin will change PINs).
- **Accented seed names.** Java sources are UTF-8 (Boot parent sets `project.build.sourceEncoding`) and the DB is UTF8 (Stage 0 script).
- **ADMIN without sucursal.** Allowed by the CHECK. Stage 2 must decide whether ADMINs can clock in.
- **`marca.client_uuid` NOT NULL.** Stage 2 generates one server-side when the client does not send it (tablet/online). A global unique constraint is enough for UUIDv4.
- **Overnight shifts.** `hora_salida < hora_entrada` is allowed by the schema (only equality is rejected); Stage 2 defines the semantics.
- **Hard deletes.** `ON DELETE RESTRICT` everywhere. Deleting an employee with marks fails, which is intended (use `activo=false`). Stage 4 CRUD must deactivate rather than delete.
- **Time zone.** Instants are stored in UTC (`hibernate.jdbc.time_zone: UTC`). `empresa.zona_horaria` is used for "today" boundaries in Stage 2 and for display. An invalid zone is rejected in the app (`ZoneId.of`).
- **Boot 4 migration.** Rename the resource-server starter. The `UserDetailsServiceAutoConfiguration` exclusion import will move packages. Re-run all tests; the encoder construction is already 7.x-compatible.

## Open Questions (RESOLVED 2026-10-21)

1. **JWT library.** Resolved: **Spring Security OAuth2 Resource Server** (Nimbus, HS256), not jjwt.
2. **Token storage in the PWA.** Resolved: **`Authorization: Bearer` header, token kept in IndexedDB**, with a strict CSP in Stage 5. The httpOnly cookie was rejected.
3. **Token lifetime and refresh.** Resolved (user's choice): **8 h for EMPLEADO, 6 h for ADMIN, no refresh tokens.** The PWA asks for the PIN again when the token expires (see "Token expires mid-shift" in Edge Cases).
4. **PIN length.** Resolved (user's choice): **exactly 8 digits** (10^8 combinations). Rejected:
   - all one digit
   - ascending/descending sequences (with wrap)
   - repeated 2-digit and 4-digit blocks
   - calendar dates (DDMMYYYY / MMDDYYYY / YYYYMMDD, 1900-2099)

   The brute-force budget is rechecked in "PIN policy and brute-force budget".
5. **Lockout policy.** Resolved:
   - 5 failures -> 15 min auto-unlock
   - the same generic 401 as a wrong PIN
   - admin unlock in Stage 4
   - per-IP limit of 20 attempts per 5 min -> 429
   - no escalation for now; revisit in the Stage 5 OWASP review
6. **ID type.** Resolved: **bigint identity** primary keys.
7. **`empresa_id` on every tenant table** (adds it to horario, marca, evidencia, ajuste) with composite FKs. Resolved: **yes.**
8. **Per-request DB re-check + `empleado.token_version`.** Resolved: **yes.**
9. **Extra endpoints in Stage 1.** Resolved: **include** `GET /api/me`, `POST /api/me/consentimiento`, `GET /api/admin/empleados` and `GET /api/admin/empleados/{id}` (read-only). Full CRUD stays in Stage 4.
10. **Dev seed details.** Resolved:
    - one shared 8-digit `DEV_SEED_PIN` from `backend/.env`
    - activated with `-Dspring-boot.run.profiles=dev`
    - empresa `DEMO`, employees 1001-1006, admin 9001
    - Sucursal Centro at placeholder San José coordinates (9.9325, -84.0796, 100 m), overridable via optional `DEV_SEED_LAT` / `DEV_SEED_LNG` / `DEV_SEED_RADIO` added in Stage 2
11. **`empleado.sucursal_id` nullable only for ADMIN** (CHECK). Resolved: **yes.**
12. **BCrypt cost.** Resolved: **strength 12** in dev/prod, 4 in tests, configurable via `app.security.bcrypt-strength`.

## Amendments after architect review (2026-10-21)

These override anything above that conflicts with them. They come from the gaps listed in `docs/diagrams/stage-1-er.md`.

1. **Repository tenant convention (one rule).** Every repository method, other than those inherited from Spring Data, must declare a parameter named `empresaId` and use it in its query. The only exception is `EmpresaRepository.findByCodigo`, used by login. `TenantRepositoryConventionTest` checks parameter names by reflection; the Boot parent POM compiles with `-parameters`. So the login updates become `registrarFallo(id, empresaId, …)` and `registrarExito(id, empresaId)`, both with `WHERE id = :id AND empresa_id = :empresaId`. The same applies to `registrarConsentimiento(id, empresaId, fecha)`.
2. **Constant-time login across all failure paths.** Exactly one BCrypt comparison runs per login attempt:
   - Unknown empresa, unknown employee, or a locked account: compare against the dummy hash and return the generic 401. A locked account doesn't increment the counter or extend the lock.
   - Inactive employee or inactive empresa: compare against the real hash, ignore the result, return the generic 401, and don't increment the counter.
3. **`ajuste` audit row.** Add `campo` (text, NOT NULL, for example `estado` or `hora_servidor`) and `valor_nuevo` (text, NOT NULL). `valor_anterior` becomes nullable, so an adjustment can create a mark.
4. **Offline idempotency key is per tenant.** Use `UNIQUE (empresa_id, client_uuid)` on `marca` instead of a global unique on `client_uuid`.
5. **Company on/off switch.** Add `empresa.activo boolean NOT NULL DEFAULT true`. An inactive empresa fails login (amendment 2) and fails the per-request re-check (401). Add tests for both.
6. **No package cycle.** `EmpleadoPrincipal` lives in `cr.asistencia.common`, not `auth`.
7. **Nullable `sucursal_id` for ADMIN.** The composite FK isn't enforced when `sucursal_id` is NULL. This is intended, and the CHECK constraint covers non-admins.
8. **Security matchers.** `requestMatchers(HttpMethod, String...)` is allowed; the overload still exists in Security 7.
