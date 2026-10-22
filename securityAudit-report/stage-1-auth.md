> Note (2026-10-22): identifiers in this report predate the Stage 1b English rename; see `docs/naming-inventory.md` for the mapping. Findings are unchanged.

# Security Audit Report — Control de Asistencia (Stage 1: Data model + authentication)

- **Date:** 2026-10-21
- **Auditor:** security-auditor agent (read-only)
- **Scope:** `backend/src/main/**`, `V2__initial_schema.sql`, `application.yml`, `application-dev.yml`, `backend/pom.xml`, `backend/.env.example`, `.gitignore`, `backend/src/test/resources/**`. Inputs: `docs/plans/stage-1-data-model-auth.md` (including "Amendments after architect review" and the auditor focus list), `docs/diagrams/stage-1-er.md`, `CLAUDE.md`.
- **Out of scope / not done:** I did not read `backend/.env` and did not connect to any database. I made no network installs. I resolved dependencies offline from the local Maven cache (`mvnw -o dependency:list`). I checked framework behavior by disassembling the cached jars with `javap`, not by guessing.
- **Baseline:** Spring Boot 3.5.16, Spring Security 6.5.11, Spring Framework 6.2.19, Nimbus JOSE+JWT 9.37.4, Hibernate 6.6.53, Flyway 11.7.2, Caffeine 3.2.4, Tomcat 10.1.55, PostgreSQL JDBC 42.7.11.

## Executive summary

Stage 1 is solidly built. The JWT decoder is pinned to HS256, and the role, `activo` and `token_version` are re-read from the database on every request. Tenant isolation holds at three layers: composite FKs, `empresaId`-scoped repositories, and the principal-only `empresaId`. Error bodies are generic, and no PIN, hash or token is logged. I found **no Critical or High issues**.

The main weaknesses are in the brute-force defenses:

1. The lockout is check-then-act. A concurrent burst gets far more than 5 guesses per lock window, and a correct guess inside the burst clears the lock (M-1).
2. The per-IP limiter keys on the raw `getRemoteAddr()`. Behind the planned reverse proxy it turns into one global bucket for every tenant. Over IPv6 it is trivially bypassed (M-2).
3. Lockout is a cheap, targeted denial of service against a colleague's clock-in (M-3). This is an accepted risk, but it matters for an attendance product.

The Low items are hardening issues: `exp` is not required, a residual timing side channel, input echoed in one 400 body, a negative-only prod guard on the seeder, no self-service revocation, and the forward-looking IndexedDB/XSS exposure.

---

## Findings

### [MEDIUM] M-1 Lockout race (TOCTOU): concurrent bursts exceed the 5-guess budget, and a correct guess clears the lock

- **File:** `backend/src/main/java/cr/asistencia/auth/LoginService.java#L56-L60`, `#L76-L85`; `backend/src/main/java/cr/asistencia/empleado/EmpleadoRepository.java#L40-L46`
- **Category:** OWASP (A07 Identification and Authentication Failures)
- **Description:** The lock state is read from the entity loaded at the start of the request (L56-57). BCrypt then runs, which takes about 250 ms at cost 12. Only afterwards is the failure recorded (L78). The atomic `registrarFallo` UPDATE is correct: PostgreSQL re-evaluates the `WHERE bloqueado_hasta ...` predicate under the row lock, so the counter never loses updates and the 5-thread test passes. However, every request that loaded the row *before* the lock committed still gets its PIN evaluated, and the success path does not re-check the lock. `registrarExito` (L43) unconditionally sets `bloqueado_hasta = NULL`. So the effective number of guesses per lock cycle is "requests in flight when the 5th failure commits", not 5. With Tomcat's default 200 worker threads, a single burst can evaluate up to about 200 guesses. A correct guess among them returns a token and unlocks the account.
- **Evidence:**
  ```java
  boolean bloqueado = empleado != null && empleado.getBloqueadoHasta() != null
          && empleado.getBloqueadoHasta().isAfter(ahora);          // read once, before BCrypt
  ...
  boolean pinCorrecto = passwordEncoder.matches(pin, hash);      // ~250 ms window
  ...
  empleadoRepository.registrarExito(empleado.getId(), empleado.getEmpresaId()); // no lock re-check
  ```
  ```sql
  update Empleado e set e.intentosFallidos = 0, e.bloqueadoHasta = null
   where e.id = :id and e.empresaId = :empresaId          -- clears an active lock
  ```
- **Exploit scenario:** The attacker knows `DEMO`/`1001`. From 10 IPv4 addresses (20 requests each, under the per-IP limit), or from one IPv6 /64 (see M-2), they fire 200 concurrent logins with different PINs. All 200 read "not locked". Five failures lock the account, but the other 195 are still compared. Repeated every 15 minutes, that is about 19,200 guesses per day instead of the 480 per day the plan budgets for: roughly 40 times more. Each burst also has a chance to land the right PIN and silently reset the lock. Ten digits still put a full search at years per account, so this is not High. But it breaks the stated invariant, and the gap grows with server capacity.
- **Recommendation:**
  1. Make the decision atomic with the guess accounting. Before BCrypt, *reserve* an attempt with a conditional UPDATE, for example `update empleado set intentos_fallidos = intentos_fallidos + 1 where id=:id and empresa_id=:empresaId and (bloqueado_hasta is null or bloqueado_hasta <= :ahora) and intentos_fallidos < :max returning intentos_fallidos` (native query). If no row is updated, treat the account as locked and compare against the dummy hash. On success, reset the counter. On failure, set `bloqueado_hasta` once the reserved count reaches the maximum.
  2. Or, more simply, re-check on success: `registrarExito` should become `... where id=:id and empresa_id=:empresaId and (bloqueado_hasta is null or bloqueado_hasta <= :ahora)`. If it updates 0 rows, return the generic 401 and do not issue a token. This closes the "correct guess clears the lock" part. It does not cap the number of guesses.
  3. Optionally add a per-account in-memory concurrency guard (one in-flight login per `empresaId:codigo`, Caffeine plus a semaphore), which removes the burst entirely.
  4. Add a test: N concurrent wrong PINs plus 1 correct PIN after the lock is committed must give a 401, and at most `maxIntentos` comparisons against the real hash.

### [MEDIUM] M-2 Per-IP login limiter keyed on raw `getRemoteAddr()`: global DoS behind a proxy, shared-NAT false positives, IPv6 bypass, BCrypt CPU exhaustion

- **File:** `backend/src/main/java/cr/asistencia/auth/AuthController.java#L25-L28`; `backend/src/main/java/cr/asistencia/auth/LoginRateLimiter.java#L19-L28`; `backend/src/main/resources/application.yml#L38-L40` (no `server.forward-headers-strategy`)
- **Category:** OWASP (A07) / Config
- **Description:** Not trusting `X-Forwarded-For` today is the correct choice (verified: there is no `forward-headers-strategy` and no XFF parsing). Spoofing XFF currently has no effect. But the key itself causes three problems:
  - **Behind Caddy (Stage 5):** every request has the proxy's address, so the 20-per-5-minute budget becomes **one bucket for the whole installation, across all tenants**. Twenty requests from anyone block every company's logins for 5 minutes. Note that Spring Boot switches to `NATIVE` forward-header handling on its own when it detects a cloud platform (Kubernetes, Heroku, Cloud Foundry, Azure App Service). Tomcat's `RemoteIpValve` then trusts XFF from any private-range peer. Whether XFF is trusted can therefore change with the deployment target, not only with explicit config.
  - **Shared office NAT:** a 25-person company that clocks in at 08:00 on the office Wi-Fi exceeds 20 logins in 5 minutes (each user logs in once per day because of the 8 h TTL), and legitimate staff get a 429.
  - **IPv6:** the key is the full 128-bit address. One host with a /64 has 2^64 keys, so the per-IP layer gives no protection. Every rotated address still costs about 250 ms of BCrypt CPU, because unknown accounts also hash, by design. That makes login a cheap CPU exhaustion vector. The 10,000-entry bound keeps memory safe, but entries can be pushed out by key flooding.
- **Evidence:**
  ```java
  String ip = http.getRemoteAddr();
  if (!rateLimiter.intentar(ip)) { ... }
  ```
  ```java
  .maximumSize(10_000).expireAfterWrite(ventana)
  return intentos.get(ip, k -> new AtomicInteger()).incrementAndGet() <= maxIntentos;
  ```
- **Exploit scenario:** (a) After Stage 5 deployment behind Caddy, a script sends 20 junk logins every 5 minutes, and nobody in any tenant can log in to clock in. (b) An attacker on an IPv6 VPS rotates source addresses within its /64 and sends unlimited logins. This drives M-1 bursts and saturates CPU with BCrypt.
- **Recommendation:**
  1. Stage 5 (blocking for go-live): set `server.forward-headers-strategy: native`. Restrict `server.tomcat.remoteip.internal-proxies` to the Caddy address only, not the default private ranges. Configure Caddy to *overwrite* client XFF rather than append it (Caddy's default without `trusted_proxies`). Set `forward-headers-strategy` explicitly (`none` until then) so cloud auto-detection cannot silently turn on XFF trust.
  2. Normalize the key: IPv4 → /32 (or /24 as a second, looser bucket); IPv6 → /64.
  3. Add a second limiter keyed on `empresaCodigo` (normalized), sized per tenant, plus a small global concurrency cap on BCrypt work (for example a semaphore of 2 × cores, returning 429/503 when it is full) to bound CPU.
  4. Re-size the per-IP budget for shift-start bursts (for example 60 per 5 minutes per /24) and rely on the per-account lockout for guessing resistance. Consider a tablet/kiosk device token in Stage 3 so shared-office logins do not all share one anonymous bucket.
  5. Return the *remaining* window in `Retry-After` instead of the full window (`ApiExceptionHandler.java#L33`). This is minor.

### [MEDIUM] M-3 Targeted lockout denial of service against clock-in (accepted risk, high business impact)

- **File:** `backend/src/main/java/cr/asistencia/auth/LoginService.java#L76-L83`; `backend/src/main/resources/application.yml#L35-L37`
- **Category:** OWASP (A07) / Availability
- **Description:** Five wrong PINs lock any employee for 15 minutes. The account is identified by two low-entropy, often sequential identifiers (`DEMO` / `1001`…`1006`), which coworkers know. The plan and README accept this risk. In an attendance system, though, lockout at shift start means a missed or late *entrada*, which feeds into pay and labor disputes. One IPv4 address can lock 4 employees per limiter window, so a whole small company can be kept locked. With IPv6 (M-2), there is no practical limit.
- **Evidence:** `max-intentos: 5`, `duracion: 15m`. There is no unlock path yet (admin unlock arrives in Stage 4), and there is no alerting.
- **Exploit scenario:** A disgruntled employee runs a loop at 07:50 every weekday that sends 5 wrong PINs for each colleague's code every 15 minutes. Nobody else can clock in. Every failure returns the same generic 401, so victims cannot tell they are being targeted.
- **Recommendation:**
  1. Stage 4: admin unlock, plus a visible "account locked N times today" indicator, plus a log or metric alert when more than X lockouts happen per empresa per hour.
  2. Consider OWASP "device cookies" or a Stage 3 device token: failures from an unknown device lock only that device/IP for that account, so the owner's trusted phone or the office tablet is not locked out.
  3. Consider progressive delays (for example 1 s, 2 s, 4 s … capped) before the hard lock, or a lock that grows only after repeated lock cycles.
  4. Stage 2: let an admin record a manual *marca* with an `ajuste` reason when lockout prevented clock-in, so the legal record is not lost.

### [LOW] L-1 Residual timing side channel allows enumeration of empresa and employee codes

- **File:** `backend/src/main/java/cr/asistencia/auth/LoginService.java#L51-L53`, `#L76-L85`
- **Category:** OWASP (A07) / Privacy
- **Description:** BCrypt is uniform: exactly one `matches` on every path, with the dummy hash at the same cost (verified, and unit-tested by call count). Database work is not uniform:
  - Unknown empresa: 1 SELECT.
  - Unknown employee: 2 SELECTs.
  - Existing, active, unlocked employee with a wrong PIN: 2 SELECTs **plus a committed UPDATE** (WAL flush with `synchronous_commit=on`).

  These differences are ms-scale against roughly 250 ms of BCrypt jitter, so exploiting them takes many samples. The lockout limits samples per account (4 per 15 minutes), but not samples per *empresa code*, where the only difference is one indexed SELECT.
- **Evidence:** `empresa.flatMap(e -> empleadoRepository.findByEmpresaIdAndCodigo(...))` short-circuits on an unknown empresa. `registrarFallo(...)` runs only on the wrong-PIN path.
- **Exploit scenario:** A competitor measures median latency over a few thousand requests from several IPs to learn which company codes are customers (tenant enumeration). Employee-code enumeration is less valuable because codes are typically sequential.
- **Recommendation:** Accept for now, but cheaply flatten:
  1. Always run the employee lookup, using a sentinel `empresaId` of `-1` when the empresa is unknown.
  2. Move `registrarFallo` after the response is decided and run it asynchronously on a bounded executor, or pad every failure response to a fixed minimum duration (for example 400 ms) with `Thread.sleep(remaining)`.
  3. If `bcrypt-strength` is ever changed, existing hashes keep their old cost while `dummyHash` uses the new one. Record that a strength change needs a re-hash on next successful login, otherwise the dummy-hash path becomes distinguishable.

### [LOW] L-2 Decoder does not require `exp` (or `iat`); a signed token without `exp` never expires

- **File:** `backend/src/main/java/cr/asistencia/auth/JwtConfig.java#L44-L50`
- **Category:** OWASP (A07) / JWT
- **Description:** `setJwtValidator(...)` replaces the defaults with `JwtTimestampValidator` + issuer + `sub`/`empresaId`/`rol`/`ver` presence checks. I disassembled `JwtTimestampValidator` in the cached `spring-security-oauth2-jose-6.5.11.jar`: it checks `exp` and `nbf` **only if they are present** (`ifnull` branches). Spring's `NimbusJwtDecoder` also disables Nimbus's own claims verifier. `TokenService` always sets `exp`, so this is defense in depth only. Any future token minted without `exp` (a bug, a test helper copied into main, a Stage 4 "kiosk" token) would be valid forever, limited only by `token_version`.
- **Evidence:** `requerido("sub"), requerido("empresaId"), requerido("rol"), requerido("ver")`, with no `exp` or `iat`. `JwtHardeningIntegrationTest` has no "missing exp" case.
- **Exploit scenario:** A later change issues a token without `exp`. If it leaks from a shared tablet, it stays valid until an admin bumps `token_version`.
- **Recommendation:**
  1. Add `requerido("exp")` and `requerido("iat")`. Optionally add a validator that rejects `exp - iat > max(ttlEmpleado, ttlAdmin)` and `iat > now + skew`.
  2. Add `requerido("jti")` for consistency with the plan.
  3. Add integration tests for a missing `exp` and an overlong lifetime.

### [LOW] L-3 JWT secret is only length-checked; known or low-entropy values are accepted

- **File:** `backend/src/main/java/cr/asistencia/auth/JwtProperties.java#L28-L41`; `backend/src/test/resources/application-test.yml#L4`
- **Category:** Secrets
- **Description:** The startup check correctly fails fast on a blank, non-base64 or shorter-than-32-byte secret. Verified: there is no default in `application.yml` and no random fallback. It does not reject *weak but long* values. The committed test secret is publicly known and decodes to ASCII `test-only-not-a-real-secret-test-only-not-a-real-secret`. It would pass the check if someone copied it into a real `.env`. Values like base64 of 32 × `'a'` also pass. Anyone with a known secret can forge tokens for any `sub`/`empresaId`. The DB re-check still requires a real employee id, but ids are sequential bigints.
- **Evidence:** `if (bytes.length < MIN_SECRET_BYTES) throw ...` is the only quality gate.
- **Exploit scenario:** An operator copies `application-test.yml`'s secret into production "to make it start". An attacker who has read the public repo forges an ADMIN token for `sub=1, empresaId=1`.
- **Recommendation:**
  1. Denylist the committed test secret, failing startup unless the active profile is `test`.
  2. Add a simple entropy floor, for example at least 16 distinct byte values and not all bytes printable ASCII.
  3. Document a rotation procedure. The plan already notes that rotation logs everyone out.

### [LOW] L-4 400 body for a type mismatch echoes the submitted value

- **File:** `backend/src/main/java/cr/asistencia/common/ApiExceptionHandler.java#L18` (inherits `handleTypeMismatch`); reachable via `backend/src/main/java/cr/asistencia/empleado/AdminEmpleadoController.java#L27`
- **Category:** OWASP (A05 Security Misconfiguration) / Error handling
- **Description:** The plan requires that "validation 400s never echo the submitted input". `ApiExceptionHandler` overrides only `handleMethodArgumentNotValid`. I disassembled `ResponseEntityExceptionHandler.handleTypeMismatch` in the cached spring-webmvc 6.2.19 jar: it builds `detail` from `getPropertyName()` and `getValue()`, i.e. `Failed to convert 'id' with value: '<input>'`. `GET /api/admin/empleados/<script>` therefore reflects the input in `application/problem+json`. This is not XSS today (JSON content type plus `nosniff`), and it is reachable only with an ADMIN token, but it breaks the stated rule and will spread to every future `@PathVariable`/`@RequestParam` (Stage 2 dates, UUIDs).
- **Evidence:** No `handleTypeMismatch`, `handleHttpMessageNotReadable` or `handleMissingServletRequestParameter` overrides.
- **Recommendation:** Override `handleExceptionInternal(...)`, or each relevant handler, so that every 4xx `ProblemDetail` produced by the base class is replaced by a fixed Spanish message plus a `codigo` (for example `SOLICITUD_INVALIDA`), keeping only the status. Add a test: `GET /api/admin/empleados/abc<x>` returns 400 and the body does not contain `abc`.

### [LOW] L-5 Dev seeder's prod guard is negative-only, and no `prod` profile exists

- **File:** `backend/src/main/java/cr/asistencia/seed/DevDataSeeder.java#L26-L28`, `#L75-L77`; `backend/src/main/resources/` (only `application.yml` and `application-dev.yml`)
- **Category:** Config
- **Description:** The seeder runs when `dev` is active and aborts only if `prod` is *also* active. Nothing defines or requires a `prod` profile, so a production host started with `SPRING_PROFILES_ACTIVE=dev` (copy-pasted run command, IDE run config, leftover env var) plus a `DEV_SEED_PIN` (for example from a dev `.env` copied to the server) would create tenant `DEMO` with an **ADMIN `9001`** whose PIN is the shared dev PIN. It would also turn on DEBUG logging for `cr.asistencia`. Tenant isolation limits the blast radius to the fake tenant. It still means a known admin credential exists in production, and it gets worse once Stage 4 adds admin-level actions or cross-tenant tooling. Separately, all 7 seeded users share one BCrypt hash (same salt). That is fine for dev, but it confirms the seed must never touch real data.
- **Evidence:** `@Profile("dev")` plus `if (environment.acceptsProfiles(Profiles.of("prod"))) throw ...`.
- **Exploit scenario:** An operator deploys with the dev run command. Anyone who knows the README codes (`DEMO`/`9001`) and the team's habitual dev PIN logs in as an admin.
- **Recommendation:**
  1. Make the guard positive: require an explicit `app.seed.enabled=true` (false by default and absent from every committed config), *and* refuse to run unless the JDBC URL host is `localhost`/`127.0.0.1`/`::1`.
  2. Add `application-prod.yml` now, even if minimal, so production has a named profile, and make the app refuse to start if `dev` and `prod` are both active, or if neither `dev`, `test` nor `prod` is active.
  3. Optionally exclude `cr.asistencia.seed` from the production artifact with a Maven profile.

### [LOW] L-6 No self-service token revocation (logout) for shared devices

- **File:** `backend/src/main/java/cr/asistencia/auth/TokenService.java#L31-L47`; `backend/src/main/java/cr/asistencia/config/SecurityConfig.java#L34` (logout disabled; no replacement endpoint)
- **Category:** OWASP (A07)
- **Description:** `token_version` is a good kill switch, and I verified that it is checked on every request. Only a database change can bump it, though, and Stage 4 admin tooling does not exist yet. The product has `TABLET` and shared-phone origins, and the token lives 8 h (6 h for admin). A user who logs in on a shared tablet cannot invalidate their token. Deleting it client-side does not help if it was already copied.
- **Exploit scenario:** An employee logs in on the shared front-desk tablet. The next person uses the stored token to read the first employee's profile and, from Stage 2, to clock in or out as them.
- **Recommendation:** Add `POST /api/auth/logout` (authenticated) that runs `update empleado set token_version = token_version + 1 where id=:id and empresa_id=:empresaId` and returns 204. Stage 3 should call it on logout and after each kiosk-mode mark. Consider a shorter TTL for tokens obtained in tablet/kiosk mode.

### [LOW] L-7 Forward-looking: bearer token in IndexedDB is readable by any XSS

- **File:** `docs/plans/stage-1-data-model-auth.md#L213-L225` (decision OQ 2); enforced in Stage 3/5
- **Category:** OWASP (A03 Injection / XSS impact)
- **Description:** The decision is reasonable for an offline-first PWA and documented with mitigations. The residual risk: one XSS anywhere on the origin can read the token from IndexedDB and send it to the attacker. The token then works from anywhere for up to 8 h, unlike an httpOnly cookie where XSS can only act in-page. Stage 2 adds photos and GPS (Ley 8968 personal data), which raises the impact.
- **Recommendation (Stage 3/5 checklist):**
  1. Strict CSP: `default-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; connect-src 'self'`, with no `unsafe-inline`/`unsafe-eval`, plus `require-trusted-types-for 'script'`.
  2. Lint ban on `dangerouslySetInnerHTML`, `innerHTML` and `eval`, with no third-party scripts or CDNs, and SRI for anything static.
  3. Keep the API and the UI on the same origin and do not enable CORS, or allow-list exactly one origin.
  4. Use L-6 logout and `token_version` bumps on suspicious activity. Consider binding the token to the device (a `cnf`/device-id claim checked against a non-exportable WebCrypto key) if the risk grows.

### [INFO] I-1 Admin authorization is only enforced at the URL layer

- **File:** `backend/src/main/java/cr/asistencia/config/SecurityConfig.java#L40`; `backend/src/main/java/cr/asistencia/empleado/AdminEmpleadoController.java#L11-L13`
- **Description:** `/api/admin/**` → `hasRole("ADMIN")` is correct and tested, including the role being taken from the database. There is no second layer. A future admin controller mapped outside `/api/admin` (for example `/api/empleados/{id}/pin` in Stage 4) would only require authentication.
- **Recommendation:** Add `@EnableMethodSecurity` and `@PreAuthorize("hasRole('ADMIN')")` at class level on admin controllers. Add an ArchUnit/reflection test that every controller in an admin package carries it.

### [INFO] I-2 Unscoped `save()` and no database row-level security (forward-looking for Stage 4 writes)

- **File:** `backend/src/main/java/cr/asistencia/empleado/EmpleadoRepository.java#L14` (and the `save` of every tenant repository); `backend/src/main/java/cr/asistencia/empresa/EmpresaRepository.java#L7`
- **Description:** `save(entity)` with a non-null id does `merge` → `UPDATE ... WHERE id=?` without `empresa_id`. This cannot be exploited today: ids are generated, entities have no id constructor or setters, and no update endpoint exists. The composite FKs stop cross-tenant *references* but not a cross-tenant overwrite of a row's own columns. `EmpresaRepository` is a full `JpaRepository` (`findAll`, `deleteAll`), which is acceptable for the tenant root but easy to misuse in a controller.
- **Recommendation:** Stage 4 update flows must load with `findByIdAndEmpresaId(id, principal.empresaId())` and then mutate the managed entity. They must never build an entity from a DTO that carries an id. Add that rule to `TenantRepositoryConventionTest`'s documentation. Consider PostgreSQL RLS (`SET app.empresa_id` per transaction) as a fourth layer before multi-tenant go-live.

### [INFO] I-3 Ley 8968 consent record is minimal

- **File:** `backend/src/main/java/cr/asistencia/empleado/MeController.java#L43-L48`; `V2__initial_schema.sql#L44`
- **Description:** Consent is a single `timestamptz`. There is no consent text version, no withdrawal path, and no record of which purposes (photo, GPS) were accepted. The PRODHAB guidance under Ley 8968 expects informed, specific and revocable consent that the controller can demonstrate.
- **Recommendation:** Before Stage 2 captures photos or GPS:
  1. Add `consentimiento_version`, optionally a `consentimiento` history table (who, when, version, granted or revoked), and `DELETE /api/me/consentimiento` (revocation).
  2. Block photo/GPS capture server-side while consent is pending.

### [INFO] I-4 Security headers, HSTS and CSP

- **File:** `backend/src/main/java/cr/asistencia/config/SecurityConfig.java#L28-L49`
- **Description:** Spring Security's default headers are active, because they are not disabled: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store`, `X-XSS-Protection: 0`. That is appropriate for a JSON API. HSTS is sent only on HTTPS requests. Behind a TLS-terminating proxy without forward headers (M-2), Spring sees HTTP and will not send it. There is no CSP, which is fine for an API but needed for the PWA.
- **Recommendation:** In Stage 5, set HSTS (`max-age=31536000; includeSubDomains`) and the CSP from L-7 at Caddy, plus `Referrer-Policy: no-referrer` and `Permissions-Policy: camera=(self), geolocation=(self)`.

### [INFO] I-5 No `aud` claim

- **File:** `backend/src/main/java/cr/asistencia/auth/TokenService.java#L34-L43`
- **Description:** Tokens carry no audience. It is harmless with one service and one key. If the same `JWT_SECRET` is ever reused by another service (for example a reporting worker), tokens become interchangeable between them.
- **Recommendation:** Add `aud=asistencia-api` and a `JwtClaimValidator` for it now, while it costs nothing.

### [INFO] I-6 Framework DEBUG logging could capture rejected login fields

- **File:** `backend/src/main/resources/application-dev.yml#L5-L7`; `backend/src/main/java/cr/asistencia/auth/LoginRequest.java#L11-L15`
- **Description:** I verified that the application never logs the PIN: `LoginRequest.toString` and `SeedProperties.toString` mask it, and the login logs contain only database-sourced codes plus the IP. `MethodArgumentNotValidException.getMessage()` does include `rejected value [...]`. It is logged only if `org.springframework.web` is set to DEBUG, and it only covers fields that failed validation (a blank PIN or one longer than 12 characters). Dev sets DEBUG only for `cr.asistencia`.
- **Recommendation:** Keep framework loggers at INFO in every committed profile. Note in the Stage 5 runbook that turning on `org.springframework.web=DEBUG` in production can log rejected inputs.

### [INFO] I-7 `.env.example` keeps a non-empty `DB_PASSWORD=change-me`

- **File:** `backend/.env.example#L4`
- **Description:** This is from Stage 0. `JWT_SECRET=` and `DEV_SEED_PIN=` are correctly empty. `change-me` is a placeholder, not a real secret, but it is the kind of value that ends up deployed.
- **Recommendation:** Make it `DB_PASSWORD=` with a comment, matching the new entries.

### [INFO] I-8 Dependencies: no known-vulnerable versions found in the resolved runtime tree

- **File:** `backend/pom.xml`
- **Description:** I resolved the tree offline. The new dependencies are Boot-managed, with no version overrides: `spring-boot-starter-oauth2-resource-server` 3.5.16 → Spring Security 6.5.11, `nimbus-jose-jwt` **9.37.4**, and `caffeine` 3.2.4. To my knowledge (cutoff mid-2026), 9.37.4 includes the fix for CVE-2025-53864 (deeply nested JSON DoS), and the earlier CVE-2023-52428 (PBES2 DoS) was fixed in 9.37.2. I know of no open advisories for Caffeine 3.2.4, Tomcat 10.1.55, Jackson 2.21.4, Logback 1.5.34, PostgreSQL JDBC 42.7.11, Flyway 11.7.2 or Hibernate 6.6.53. I could not run an online CVE database query read-only.
- **Recommendation:** Add OWASP `dependency-check-maven` (or GitHub Dependabot / `osv-scanner`) to CI in Stage 5, and keep tracking Boot 3.5.x patch releases.

---

## Checked and found OK

**Authentication**
- Exactly one `PasswordEncoder.matches` per attempt on every path (unknown empresa, unknown employee, locked → dummy hash; inactive employee or empresa → real hash, result ignored, no increment). The dummy hash uses the same strength and is created at startup (`LoginService.java#L43`, `#L59-L60`). `LoginServiceTest` covers this by call count.
- One status and body for every failure: 401 ProblemDetail `CREDENCIALES_INVALIDAS` with no input echo (`ApiExceptionHandler.java#L25-L28`). The locked case is the same as wrong-PIN.
- The failure counter is one atomic JPQL UPDATE in its own transaction (`EmpleadoRepository.java#L25-L38`), and `LoginService` is not `@Transactional`, so it commits on 401. A locked account is not incremented or extended. No lost updates under concurrency (the predicate is re-evaluated under the row lock). See M-1 for the separate check-then-act gap.
- Codes are normalized with `trim().toUpperCase(Locale.ROOT)`. The DB regex (`^[A-Z0-9-]...`) blocks Unicode case-folding tricks.
- Input bounds: `@NotBlank` and `@Size(max=20/20/12)`. The PIN never reaches BCrypt's 72-byte limit.
- The per-IP limiter is memory-bounded (10k entries), runs before database and BCrypt work, and does not trust XFF (see M-2 for its limits).
- PIN policy: 8 digits; same-digit, wrapping sequence, ABAB/ABCD blocks and strict-date checks are correct. The message never contains the PIN.

**JWT**
- Algorithm pinned: `NimbusJwtDecoder.withSecretKey(key).macAlgorithm(HS256)`. Nimbus rejects `alg=none` (plain JWT) and any algorithm other than HS256, including HS384/HS512 with the same key (tested). RS/HS confusion is not possible: no public keys or JWKS are configured.
- Issuer validated; `nbf` and `exp` validated when present with a 30 s skew; `sub`, `empresaId`, `rol` and `ver` required (see L-2 for `exp`).
- The secret fails fast at startup if blank, non-base64 or under 32 bytes. There is no default and no random fallback (`JwtProperties.java#L28-L41`; `application.yml#L28`).
- Per-request DB re-check: the employee is loaded by `(sub, empresaId)`, so a subject from another empresa is rejected (tested). Employee and empresa must be `activo` and `ver` must equal `token_version`. Authorities come from the **DB** role. Malformed claims → 401, not 500 (`EmpleadoJwtAuthenticationConverter.java#L35-L66`).
- Tokens in the query string are not accepted (`DefaultBearerTokenResolver` default, tested). A stale token on login is ignored (`LoginAwareBearerTokenResolver`).
- TTL 8 h / 6 h, `jti` random UUID, signed with the injected UTC `Clock`. No refresh tokens.

**Authorization and tenant isolation**
- `/api/admin/empleados/{id}` uses `findByIdAndEmpresaId(id, principal.empresaId())`; another tenant's id returns 404 (`EmpleadoConsultaService.java#L23-L27`). There is no IDOR.
- `empresaId` always comes from `EmpleadoPrincipal`, which is built from the DB row. No DTO or request field carries `empresaId` (`LoginRequest`, `EmpleadoResumen`, `PerfilResponse`, `LoginResponse`).
- Every tenant repository extends bare `Repository<T, Long>` with `empresaId`-scoped finders only. The single documented exception is `EmpresaRepository.findByCodigo`. The reflection convention test is present.
- Composite FKs are present for every child relation: empleado→sucursal, horario→empleado, marca→empleado, marca→sucursal, evidencia→marca, ajuste→marca, ajuste→admin. Each parent has `UNIQUE (id, empresa_id)`. `ON DELETE RESTRICT` everywhere. `UNIQUE (empresa_id, client_uuid)` per amendment 4.
- Sensitive fields are never serialized: the DTOs have no `pin_hash`, `intentos_fallidos`, `bloqueado_hasta` or `token_version`.

**Error handling and logging**
- `server.error.include-stacktrace: never`; Boot's defaults for `include-message` and `include-binding-errors` are `never`. The validation 400 body is generic. The 403 handler is an empty body, and the 401 entry point sends no `WWW-Authenticate` header.
- No PIN, hash or token is logged anywhere in `main`. The seeder logs only a fixed summary.
- **Log injection: not possible today.** Login logs interpolate only `empresa.getCodigo()` / `empleado.getCodigo()` read from the DB (constrained by `^[A-Z0-9-]` CHECKs) and `getRemoteAddr()` (a Tomcat-parsed socket address). The unknown-account path logs only the IP, never the user-supplied codes. Keep this rule if XFF is trusted later: log the parsed IP, not the raw header.

**Config and secrets**
- Actuator: only `health` exposed, `show-details: never`; other endpoints are unexposed and also behind `authenticated()`.
- CSRF disabled with `STATELESS` sessions, plus no form login, HTTP Basic or logout. This is correct for a bearer-only API with no cookies. No CORS configuration, so cross-origin browser calls are denied by default.
- `UserDetailsServiceAutoConfiguration` is excluded, so there is no generated default password.
- `.gitignore` covers `.env` and `.env.*` (except `.env.example`). `git check-ignore` confirms `backend/.env` is ignored. `git log --all --name-only` shows no `.env` was ever committed.
- Grep of tracked and untracked files found no real secrets. The only secret-like value is the clearly labeled test-only JWT key in `src/test/resources/application-test.yml`, which is not packaged into the jar (see L-3 for the denylist). Test PINs (`48207315`, `11112222`) are test fixtures only. `JWT_SECRET=` and `DEV_SEED_PIN=` in `.env.example` are empty. The README shows the demo codes but no PIN.
- `DEV_SEED_PIN` is never logged, and `SeedProperties.toString` is masked. The seeder is `@Profile("dev")`, idempotent, and refuses to run with `prod` active (see L-5).
- `ddl-auto: validate` and `open-in-view: false`.

---

## Summary by severity

| Severity | Count | Findings |
|----------|-------|----------|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 3 | M-1 Lockout TOCTOU race; M-2 Per-IP limiter key (proxy/NAT/IPv6/CPU); M-3 Targeted lockout DoS of clock-in |
| Low | 7 | L-1 Residual timing enumeration; L-2 `exp`/`iat` not required; L-3 Secret entropy and known-value denylist; L-4 Type-mismatch 400 echoes input; L-5 Seeder prod guard negative-only; L-6 No self-service revocation; L-7 Token in IndexedDB (XSS, forward-looking) |
| Info | 8 | I-1 URL-only admin authorization; I-2 Unscoped `save()` / no RLS; I-3 Ley 8968 consent record; I-4 Headers/HSTS/CSP; I-5 No `aud`; I-6 Framework DEBUG may log rejected inputs; I-7 `DB_PASSWORD=change-me`; I-8 Dependencies OK |

**Recommended before release:** fix M-1 (at minimum the lock re-check on success), and make M-2 items 1 and 3 Stage 5 go-live blockers. Add L-2 and L-4; both are small changes with tests.

---

## Remediation status (2026-10-21)

**Fixed (backend, covered by tests):**

| Finding | Status | Where |
|---------|--------|-------|
| M-1 Lockout race | Fixed. `registrarExito` now has `AND (bloqueado_hasta IS NULL OR bloqueado_hasta <= :ahora)`; 0 rows updated means the login fails with the generic 401 and no token. A success never clears an active lock. Still exactly one BCrypt compare per attempt. | `auth/LoginService.java`, `empleado/EmpleadoRepository.java`; tests `auth/LockoutRaceIntegrationTest`, `auth/LoginServiceTest` |
| L-2 `exp` / `iat` not required | Fixed. Both are required next to `iss`, `sub`, `empresaId`, `rol`, `ver`. | `auth/JwtConfig.java`; tests `auth/JwtHardeningIntegrationTest` |
| L-3 Weak JWT secret | Fixed. Startup rejects the committed test secret unless the `test` profile is active, and any secret with fewer than 8 distinct byte values. Messages never contain the secret. | `auth/JwtProperties.java`, `auth/JwtConfig.java`; tests `auth/JwtPropertiesTest`, `auth/JwtConfigTest` |
| L-4 400 echoes input | Fixed. `ApiExceptionHandler.handleExceptionInternal` replaces every base-class body (validation, unreadable body, type mismatch, 405, 415, 406, missing parameter) with a fixed message and a stable `codigo`. | `common/ApiExceptionHandler.java`; tests `common/ApiExceptionHandlerTest`, `empleado/AdminEmpleadoControllerTest`, `auth/LoginHardeningIntegrationTest` |
| L-5 Seeder guard | Fixed. The seeder runs only when `dev` is explicitly active and fails startup when `prod` is also active. | `seed/DevDataSeeder.java`; tests `seed/DevSeedProfileTest` |

**Deferred to Stage 5 (agreed direction):**

- **M-2** (per-IP limiter key): rate-limit by empresa + empleado and by IP network block (/64 for IPv6, /24 for IPv4); trust `X-Forwarded-For` only from the Caddy proxy.
- **M-3** (targeted lockout DoS): replace the hard 15-minute lock with growing delays (1 s up to about 1 min) plus an admin alert.

Also fixed outside the original findings: `GET /api/health` and `/actuator/health` no longer return 401 when the client sends a stale bearer token (`auth/LoginAwareBearerTokenResolver.java`), and `horario` times are no longer shifted by the JVM time zone (`empleado/Horario.java`).
