# Security Audit Report — Control de Asistencia (Stage 2: Time-entry / check-in API with photo upload)

- **Date:** 2026-10-23
- **Auditor:** security-auditor agent (read-only)
- **Branch:** `stage1-2` (Stage 2 is uncommitted working-tree changes on top of `179b4b9`)
- **Scope:** `backend/src/main/**` Stage 2 changes:
  - `V3__time_entry_checkin.sql`, `application.yml`, `application-dev.yml`
  - `cr/attendance/storage/**`, `cr/attendance/timeentry/**`
  - `cr/attendance/common/**` (`ApiException`, `FixedWindowRateLimiter`, the `ApiExceptionHandler` changes)
  - the seed location changes, the new repository methods
  - `backend/.env.example`, `.gitignore`
- **Inputs:**
  - `docs/plans/stage-2-time-entry-api.md`, read with the precedence Amendments > "Open Questions: RESOLVED" > the rest, and its security-auditor focus list
  - `docs/diagrams/stage-2-time-entry.md`, `CLAUDE.md`
  - `securityAudit-report/stage-1-auth.md`
- **Method:**
  - I read the code and the configuration.
  - I checked the framework behaviour that matters here by disassembling the cached `hibernate-core-6.6.53.Final.jar` with `javap`.
  - I identified the runtime JDK from `java -version` and from `target/surefire-reports` (`java.version=25.0.1`).
- **Not done, per the constraints:**
  - I did not read `backend/.env` and did not connect to any database.
  - I ran no Maven build or tests and installed nothing.
  - I ran no online CVE database query.
- **Baseline:** the Stage 1 report's dependency set is unchanged. `backend/pom.xml` has had no dependency change since Stage 1 (only the 1b rename of groupId/artifactId), and Stage 2 adds **no new Maven dependencies**. The runtime JDK is **Oracle JDK 25.0.1** (2025-10-21). JDK 21.0.5 is also installed.

## Executive summary

Stage 2 is carefully built.
- **Storage keys** are generated on the server only. They are validated three times: by the Java regex, by the tenant prefix check plus the symlink-aware real-path check, and by the DB CHECK constraints. A client value can never become a path.
- **Uploads are re-encoded.** Every stored file is a fresh JPEG written with null metadata, so EXIF/GPS and polyglot payloads cannot survive. The client filename and content type are never read.
- **Photo downloads** are tenant- and employee-scoped, with one indistinguishable 404 for every miss. They are served as `image/jpeg` with `no-store, private`.
- **Consent** is enforced server-side, twice (once before decoding and again under the row lock), before any personal data is written.
- **Error bodies** carry no input echo, including the new catch-all 500 and the multipart 400.
- **Idempotency** never returns another employee's entry.

I found **no Critical or High issues**.

The two Medium findings are both about **decoding untrusted images**:
1. **M-1:** The decoder's limits (4096 px per side, 16 MP) still allow up to about 128 MiB of heap per request (a 16-bit RGBA PNG), or a progressive JPEG that takes a long time to decode. Nothing bounds how many decodes run at once. A handful of parallel requests from one ordinary employee can exhaust the heap and take the service down for every tenant.
2. **M-2:** The decoding runs on a JDK that has missed about a year of Critical Patch Updates. The JPEG decoder is native code.

The Low items:
- **Multipart parsing order.** The body is parsed eagerly, before the rate limiter and also on unauthenticated endpoints.
- **Unmanaged copies of personal data.** Raw uploads are spooled to an unmanaged temp directory, and orphan files fall outside retention.
- **Lock timeout.** Hibernate silently ignores the documented 5 s lock timeout on PostgreSQL.
- **Hours fraud.** An employee using the API directly can inflate hours and still get `VALID`.
- **Storage root.** There is no guard on where the storage root may point.
- **Access audit.** Photo access is not audited.

---

## Findings

### [MEDIUM] M-1 Image decoding can exhaust heap and CPU: limits allow ~128 MiB per decode, nothing bounds concurrency

- **File:**
  - `backend/src/main/java/cr/attendance/storage/PhotoProcessor.java#L79-L87`
  - `backend/src/main/resources/application.yml#L54-L55`
  - `backend/src/main/java/cr/attendance/timeentry/TimeEntryService.java#L59`
  - `backend/src/main/java/cr/attendance/common/FixedWindowRateLimiter.java#L27-L29`
- **Category:** OWASP (A04 Insecure Design / resource exhaustion)
- **Description:** The header check is a correct decompression-bomb guard against absurd sizes. It reads the dimensions before decoding and rejects anything over 4096 px per side or over 16,000,000 px. The remaining budget is still very large for a selfie, and two things make it worse:
  - **Memory.** `reader.read(0)` decodes at full resolution in the source sample format. A 16-bit RGBA PNG of 4096 × 3906 (15.99 MP, accepted) becomes a `TYPE_CUSTOM` ushort raster of 15.99 M × 4 × 2 ≈ **128 MiB**. An 8-bit RGBA PNG is about 64 MiB and an RGB JPEG about 48 MiB. A flat-colour image of that size deflates to roughly 100-150 KB, far under the 2 MB cap. `drawImage` then rescales a `TYPE_CUSTOM` source through the slow general path, which costs extra CPU.
  - **CPU.** The JDK JPEG reader (native IJG libjpeg) accepts progressive JPEGs with any number of scans. A crafted progressive file at the maximum dimensions is a known class of decoder CPU DoS. Each scan costs work proportional to the image's block count, and scan headers are only a few bytes each, so 2 MB holds a very large number of scans. There is no decode timeout and no `ImageReader.abort()` watchdog.
  - **No concurrency bound.** Decoding deliberately runs outside the employee lock, and the per-employee limiter counts attempts per 10 minutes. It does not limit how many run at once. One employee can send 30 POSTs in parallel and all 30 decode at the same time.

  The JVM has no `-Xmx` setting in the repo, so the default heap is 25 % of RAM. On a 2-4 GB VPS that is 512 MiB to 1 GiB, which 4 to 8 concurrent 16-bit PNG decodes fill. An `OutOfMemoryError` on Tomcat worker threads breaks unrelated requests and can leave the JVM unstable.
- **Evidence:**
  ```java
  if (width < MIN_DIMENSION || height < MIN_DIMENSION
          || width > props.maxInputDimension() || height > props.maxInputDimension()
          || (long) width * height > props.maxInputPixels()) {   // 4096 / 16,000,000
      throw invalid();
  }
  BufferedImage image = reader.read(0);   // full-resolution decode, no ImageReadParam, no subsampling
  ```
  ```java
  ProcessedPhoto photo = photoProcessor.process(form.photo());   // runs concurrently for every in-flight POST
  ```
- **Exploit scenario:** Any employee who has given consent and has a branch uses their own token and `curl`, with no special privileges. They generate a 4096 × 3900 16-bit RGBA PNG of one colour (about 120 KB) and fire 20 parallel `POST /api/time-entries`, each with a fresh `clientUuid`. All 20 pass the rate limiter (20 ≤ 30), the replay lookup and the consent pre-check, and decode at the same time. That needs about 2.5 GiB of heap, so the JVM throws `OutOfMemoryError` and every tenant's clock-ins fail at shift start. The attacker repeats this every 10 minutes. The requests are rejected with 409 before any row is written, so they leave almost no business-level trace.
- **Recommendation:**
  1. **Lower the limits to what a selfie needs.** The PWA sends about 1024 px. Set `max-input-dimension: 2048` and `max-input-pixels: 4194304` (4 MP).
  2. **Decode at reduced size.** Build an `ImageReadParam` and call `param.setSourceSubsampling(n, n, 0, 0)` with `n = ceil(longSide / (2 * outputMaxDimension))`, so the raster is never larger than about 2 × 1280 px. Pass it to `reader.read(0, param)`.
  3. **Reject the expensive formats before decoding.** The PWA's `canvas.toBlob` output is a baseline 8-bit JPEG, so a real selfie never uses them:
     - PNG with bit depth 16 (check `reader.getRawImageType(0)` sample size, or read IHDR byte 24)
     - interlaced PNG (IHDR byte 28)
     - progressive or arithmetic JPEG (scan the markers for SOF2/SOF3/SOF9-SOF15 before SOS)
  4. **Bound concurrency globally.** Put a `Semaphore` around `PhotoProcessor.process`, sized to about the number of cores. Use `tryAcquire` with a short timeout and return 503 with `Retry-After` when it is saturated. Optionally add a per-employee in-flight limit of 1-2, for example a Caffeine map of `AtomicInteger`.
  5. **Add a decode time budget.** Run the decode on a bounded executor with `Future.get(timeout)` and call `reader.abort()` on timeout.
  6. Set an explicit `-Xmx` and `-XX:+ExitOnOutOfMemoryError` in the Stage 5 service unit, so an OOM gives a clean restart instead of a half-broken JVM.
  7. **Tests:**
     - a 16-bit 4096 px PNG and a progressive JPEG are rejected as `INVALID_PHOTO` within 1 s
     - N parallel uploads above the semaphore size give 503 rather than OOM

### [MEDIUM] M-2 Untrusted images are decoded on an unpatched JDK (25.0.1, about four Critical Patch Updates behind), and no runtime baseline is defined

- **File:**
  - `backend/src/main/java/cr/attendance/storage/PhotoProcessor.java#L69-L87` (attack surface)
  - `backend/pom.xml#L21` (`java.version` 21, build target only)
  - runtime: `java -version` gives `25.0.1+8-LTS-27`, which `target/surefire-reports` confirms as the JVM the suite ran on. JDK `21.0.5` (2024-10) is also installed.
- **Category:** Dependencies (runtime)
- **Description:** Stage 2 makes the JDK's image decoders a remotely reachable parser of attacker-supplied bytes. `JPEGImageReader` is a thin wrapper over the JDK's bundled native IJG libjpeg (`libjavajpeg`), and `PNGImageReader` is Java code. Oracle and OpenJDK ship security fixes quarterly. The 2D/ImageIO component appears in CPU advisories regularly, and memory-safety bugs in native decoders are the classic route from "image upload" to remote code execution.
  - **Verified:** JDK 25.0.1 is the October 2025 release. As of today (2026-10-23) it lacks the January, April, July and October 2026 CPUs. JDK 21.0.5 is from October 2024 and is further behind.
  - **Not verified:** which specific 2D/ImageIO CVEs those updates fix. That needs the Oracle CPU advisories online, which I could not query read-only. I therefore rate this Medium rather than higher.
  - Nothing in the repo pins the production runtime. The build targets 21, the dev and test machines run 25, and Stage 5 has not chosen a JDK.
- **Evidence:** `name="java.version" value="25.0.1"` in `backend/target/surefire-reports/*.xml`. `JAVA_VERSION="21.0.5"` in `C:\Program Files\Java\jdk-21\release`.
- **Exploit scenario:** A CPU released after 25.0.1 fixes a heap overflow in the JPEG decoder. An employee uploads a crafted `FF D8 FF ...` file of about 50 KB. It passes the magic-byte sniff and the header dimension check, both of which run inside the vulnerable decoder anyway, and triggers the bug in `reader.read(0)` inside the API process. That process holds the DB credentials and `JWT_SECRET`, and can read every tenant's photos.
- **Recommendation:**
  1. Update the dev JDK now: the latest 25.0.x, or the latest 21.0.x if you keep 21 as the target.
  2. In the Stage 5 plan, pin a production runtime (for example Eclipse Temurin 21 or 25 LTS, latest patch). Make "apply the JDK CPU within 2 weeks of release" a runbook item, and log `Runtime.version()` at startup.
  3. Add the JDK to the Stage 5 dependency-scanning job (for example `osv-scanner` or Trivy on the container image), not only the Maven tree.
  4. Defence in depth: run the service as an unprivileged user with read-only root FS access except `UPLOADS_DIR`. M-1's limits also shrink the reachable decoder state.

### [LOW] L-1 The multipart body is parsed (and spooled to disk) before the rate limiter, and also on unauthenticated endpoints

- **File:**
  - `backend/src/main/resources/application.yml#L29-L33` (no `resolve-lazily`)
  - `backend/src/main/java/cr/attendance/timeentry/TimeEntryConfig.java#L25-L29`
  - `backend/src/main/java/cr/attendance/config/SecurityConfig.java#L37-L39`
- **Category:** Config / OWASP (A04)
- **Description:** Amendment 1 put the per-employee limiter "before binding and validation", and that holds. But `spring.servlet.multipart.resolve-lazily` defaults to `false`, so `DispatcherServlet.checkMultipart()` parses the whole multipart body *before* `getHandler()` and before any `HandlerInterceptor.preHandle()`. Tomcat therefore reads up to 4 MB and writes every part above `file-size-threshold` (256 KB) to a temp file before the limiter can say 429. Three consequences:
  - **Rate-limited requests still cost work.** A request that will be rejected with 429 still costs the full upload, the parsing and a temp-file write.
  - **Oversize uploads are never counted.** `MaxUploadSizeExceededException` is raised inside `checkMultipart`, before the interceptor, so an oversize upload never increments the counter.
  - **Unauthenticated requests are parsed too.** The parse runs for any request whose `Content-Type` starts with `multipart/`, on any path the security chain lets through. That includes the `permitAll` routes `POST /api/auth/login` and `/error` (any method), so an unauthenticated client can make the server spool about 4 MB of temp files per request, with 200 worker threads in parallel.

  The temp files are deleted at the end of the request, so the impact is bounded: bandwidth, I/O and a few hundred MB of transient disk.
- **Evidence:**
  ```yaml
  multipart:
    max-file-size: 3MB
    max-request-size: 4MB
    file-size-threshold: 256KB        # resolve-lazily not set -> eager parse in checkMultipart()
  ```
- **Exploit scenario:** An unauthenticated client loops over `POST /api/auth/login` with `Content-Type: multipart/form-data` and a 4 MB body made of one 3 MB part and filler parts. Each request is parsed and spooled to the temp dir, then rejected with 415. With 200 parallel connections this means about 800 MB of temp-disk churn and full use of the worker pool. The per-IP login limiter runs only inside the controller, so it never fires.
- **Recommendation:**
  1. Set `spring.servlet.multipart.resolve-lazily: true`. Parts are then parsed only when `@ModelAttribute` binding touches them, which is after authentication and after `TimeEntryRateLimitInterceptor`. For the JSON login endpoint, and for any non-multipart handler, the body is never parsed, and Tomcat swallows it up to `max-swallow-size`. `MaxUploadSizeExceededException` is still raised and still mapped to 413, now during binding.
  2. Add an integration test: with the limit at 3, the 4th POST gets 429 even when its body is oversize. Add a check that a multipart body sent to `/api/auth/login` leaves no `upload_*.tmp` behind (unit-level, with a spy on the resolver).
  3. Stage 5: set a request body limit at Caddy (`request_body max_size 5MB`) so oversize bodies never reach Tomcat.

### [LOW] L-2 Copies of personal data outside the managed store: raw uploads spooled to the system temp dir, and orphan photo files with no DB row

- **File:**
  - `backend/src/main/resources/application.yml#L29-L33` (no `spring.servlet.multipart.location`)
  - `backend/src/main/java/cr/attendance/timeentry/TimeEntryWriter.java#L119-L127`
  - `backend/src/main/java/cr/attendance/storage/LocalDiskStorageService.java#L98-L102`
- **Category:** Privacy (Ley 8968) / Config
- **Description:** The plan's Step 1 says to "set all of them explicitly", but `spring.servlet.multipart.location` is not set. Every part above 256 KB is therefore written as `upload_*.tmp` into embedded Tomcat's work directory under `java.io.tmpdir`. These are the **raw, pre-re-encode bytes**, so a non-PWA upload still carries its EXIF GPS there. They normally disappear at the end of the request, but they stay behind after a JVM crash or kill, and they sit on a shared system temp volume that is often world-listable on Linux and is not covered by backup exclusions or retention.

  Separately, a stored photo can end up with no `evidence` row in three ways:
  - **(a)** a crash between `storage.store` and commit (documented in the plan)
  - **(b)** `afterCompletion(STATUS_UNKNOWN)`: the code deletes on any status other than `COMMITTED`, so a commit whose outcome is unknown, but which actually succeeded, deletes the file of a committed row
  - **(c)** `storage.delete` failing, which is only logged at WARN, for example when Windows holds a file lock

  Files in case (a) and (c) are personal data that no retention job, access request or erasure request will ever find, because the Stage 5 retention job works from `evidence` rows. Case (b) is an integrity problem: the photo evidence is lost for a legal record.
- **Evidence:**
  ```java
  public void afterCompletion(int status) {
      if (status != STATUS_COMMITTED) {   // includes STATUS_UNKNOWN
          storage.delete(key);
      }
  }
  ```
- **Exploit scenario:** This is not attacker-driven; it is a compliance gap. After a few crashes in production, a host's `/tmp/tomcat.8080.*/work` and `uploads/<company>/<month>/` contain selfies, some with GPS EXIF, that are absent from the DB. A data-subject erasure request (Ley 8968, art. 7) cannot be fully honoured, and a server backup carries them indefinitely.
- **Recommendation:**
  1. Set `spring.servlet.multipart.location` to a dedicated directory such as `${UPLOADS_DIR}/../upload-tmp`, or a subfolder outside the photo root, on the same volume, with mode 0700. Empty it at startup with a `@PostConstruct` that deletes `upload_*.tmp`.
  2. Delete only on `STATUS_ROLLED_BACK`. On `STATUS_UNKNOWN`, log the key at WARN and leave the file for the sweeper.
  3. Make the Stage 5 orphan sweeper a go-live blocker. It should cover photo files with no `evidence` row older than 24 h, stale `.upload-*.tmp` files inside the photo root (created at `LocalDiskStorageService.java#L51`), and the multipart temp directory. It must log counts only.

### [LOW] L-3 The documented 5 s lock timeout is silently ignored on PostgreSQL, so lock waits are unbounded and each waiter holds a pooled connection

- **File:** `backend/src/main/java/cr/attendance/employee/EmployeeRepository.java#L23-L25`
- **Category:** Config / Availability
- **Description:** The plan says "the 5 s lock timeout gives 500 on pathological waits", and that bound does not exist. I disassembled `org.hibernate.dialect.PostgreSQLDialect.withTimeout(String, int)` in the resolved `hibernate-core-6.6.53.Final.jar`:
  - `0` gives `NOWAIT`
  - `-2` gives `SKIP LOCKED`
  - **every other value returns the plain `for update` string unchanged**

  The `jakarta.persistence.lock.timeout = 5000` hint is therefore dropped, and the SQL is `select ... for update` with no wait limit. No `lock_timeout` or `statement_timeout` is configured for the session either. Each POST waiting for the lock holds a Hikari connection, from a default pool of 10, for the whole wait. The Stage 1 login `UPDATE` on the same employee row also waits behind it. Today the lock holder's transaction is short (no user-controlled work inside), so this only matters when something stalls inside the transaction: a slow or full disk in `storage.store`, an fsync stall, or a DB hiccup. When it does, it spreads from one employee to the whole connection pool.
- **Evidence:**
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))   // no effect on PostgreSQL in Hibernate 6.6
  Optional<Employee> findWithLockByIdAndCompanyId(Long id, Long companyId);
  ```
  Bytecode: `lookupswitch { -2: skip locked; 0: nowait; default: return input }`.
- **Exploit scenario:** The disk holding `UPLOADS_DIR` stalls, for example a network mount or a full volume with retries. One employee's transaction blocks inside `storage.store` while holding the row lock. Their phone retries, and so do a few colleagues hitting the same stall. Ten waiting transactions take all 10 pooled connections, and the whole API, all tenants and login included, then hangs for 30 s per request on connection acquisition. Nothing ever times out the original wait.
- **Recommendation:**
  1. Bound the lock wait in SQL. At the start of `TimeEntryWriter.record`, run the native query `SET LOCAL lock_timeout = '5s'`, or set it for all connections: `spring.datasource.hikari.connection-init-sql: SET lock_timeout = '5s'`, or `?options=-c%20lock_timeout%3D5000` on the JDBC URL. Map `CannotAcquireLockException` / `PessimisticLockingFailureException` to 503 with `Retry-After` instead of the generic 500.
  2. Also set a server-side `statement_timeout` (for example 10 s) for the application role.
  3. Fix the comment and the plan text. Add a test that holds the row lock in a second transaction and asserts that the POST fails within about 6 s.

### [LOW] L-4 An employee calling the API directly can record inflated hours with `VALID` status; the "photo + review queue" mitigation never sees `VALID` entries

- **File:**
  - `backend/src/main/java/cr/attendance/timeentry/TimeEntryForm.java#L37-L39`
  - `backend/src/main/java/cr/attendance/timeentry/ReviewPolicy.java#L25-L39`, `#L43-L52`
  - `backend/src/main/java/cr/attendance/timeentry/ShiftResolver.java#L45-L51`
- **Category:** OWASP (A04 Insecure Design / business logic)
- **Description:** I confirmed that the server clock is authoritative:
  - `server_time`, `work_date` and `late_minutes` come only from `Instant.now(clock)` and the company zone (`TimeEntryWriter.java#L89-L117`)
  - `deviceTime` and `deviceSentAt` can only *add* review reasons

  But every signal that decides `VALID` is client-asserted, and the form makes the weakest choice easy:
  - **(a) Accuracy is optional.** `lat`/`lng` without `accuracy` give no `LOW_GPS_ACCURACY` and no other reason. Browsers always report `coords.accuracy`, so a missing accuracy is a strong sign of a scripted client. `accuracy=0`, which is physically implausible, is accepted silently too.
  - **(b) Hiding a late sync is trivial.** Sending `deviceTime = deviceSentAt = now` hides `SYNCED_LATE`.
  - **(c) The schedule end is not checked.** Inside the 16 h window, a CLOCK_OUT long after the scheduled `end_time` adds no reason.

  The plan's mitigation for GPS spoofing is "photo evidence plus the review queue". The queue only contains `PENDING_REVIEW` entries, so a crafted `VALID` entry's selfie is never looked at. The API also gives the attacker instant feedback (`status`, `reviewReasons`, `distanceMeters`, `radiusMeters`), but the one-pair-per-day rule limits each day to a single attempt.
- **Evidence:**
  ```java
  if (accuracy != null) { return lat != null && ... }   // accuracy may simply be omitted
  boolean lowAccuracy = in.accuracy() != null && in.accuracy() > maxGpsAccuracyMeters;
  ```
- **Exploit scenario:** The employee clocks in at the branch at 08:00 (`VALID`) and leaves at 12:00. At 22:30, at home, they read their token from the PWA's IndexedDB and run:
  ```
  curl -F type=CLOCK_OUT -F clientUuid=<new> -F deviceTime=<now> -F deviceSentAt=<now> -F lat=<branch lat> -F lng=<branch lng> -F photo=@selfie.jpg
  ```
  The entry is `VALID` with 14.5 h worked. No admin is prompted to look at the photo, which shows their living room. Repeated daily, this is payroll fraud that the Stage 4 dashboard presents as clean.
- **Recommendation:**
  1. When `lat`/`lng` are present, either require `accuracy` (400 `INVALID_REQUEST`) or add a review reason `NO_GPS_ACCURACY`. Also flag `accuracy < 1`.
  2. Add schedule-aware reasons computed at CLOCK_OUT, for example `CLOCK_OUT_AFTER_SCHEDULE` when `server_time > scheduled end + N min` (configurable, overnight-aware), and `UNSCHEDULED_DAY` if the company wants it.
  3. Stage 4: count only `VALID` time (or approved adjustments) in payroll totals. Show `PENDING_REVIEW` and `SHIFT_TOO_LONG` hours separately. Add a random sample of `VALID` entries for photo spot checks.
  4. Document the residual risk in the plan: GPS and device times are claims, not proof. The selfie is the only evidence that is hard to forge, and it only helps if someone looks at it.

### [LOW] L-5 The storage root is cwd-relative and not guarded against static document roots or the source tree

- **File:**
  - `backend/src/main/java/cr/attendance/storage/LocalDiskStorageService.java#L30-L41`
  - `backend/src/main/resources/application.yml#L52`
  - `backend/.env.example#L15-L16`
- **Category:** Config
- **Description:**
  - **cwd-relative default.** The default `uploads` resolves against the process working directory, not against `backend/`. Started from the repo root, or by a service manager whose cwd is `/` or `C:\Windows\System32`, photos silently land in another place: possibly the repo root (still gitignored), a system directory, or a directory that gets backed up or synced.
  - **Document roots.** Boot's embedded Tomcat uses the first existing `src/main/webapp`, `public` or `static` directory under the cwd as the servlet-context document root. `WebMvcAutoConfiguration` then serves it under `/**` as a `ServletContextResource("/")` static location, which needs only *any* valid token, not the owner's or the right tenant's. `.env.example` warns "Must not be inside src/ or a static folder", but nothing enforces it. `UPLOADS_DIR=static` would publish every tenant's photos to any authenticated user who knows or can obtain a key. Keys are random UUIDs and Tomcat directory listings are off, so exposure needs a leaked key.
- **Evidence:** `Path configured = Paths.get(props.root()).toAbsolutePath().normalize();` with only a writability check after it.
- **Exploit scenario:** An operator sets `UPLOADS_DIR=public` "so it is easy to find". Stage 4 later puts the storage key into an admin CSV export or a log line. Any employee of any tenant holding a key can then fetch `GET /1/2026-10/<uuid>.jpg` with their own token and bypass `PhotoService`'s tenant check entirely.
- **Recommendation:**
  1. Outside the `dev` and `test` profiles, require `app.storage.root` to be an absolute path and fail at startup otherwise.
  2. At startup, refuse a root that is inside, or is, any of these: `src/`, a directory named `static`, `public`, `webapp`, `META-INF/resources` or `resources`, the cwd's document-root candidates, or the classpath directories.
  3. Set `spring.web.resources.add-mappings: false`, since the API serves no static content. Static serving then cannot happen at all, whatever the cwd contains.
  4. Stage 5: on Linux, create the root as 0700, owned by the service user. `Files.createTempFile` already gives 0600 files on POSIX.

### [LOW] L-6 Photo access is neither audited nor throttled (admin bulk export, Ley 8968 accountability)

- **File:**
  - `backend/src/main/java/cr/attendance/timeentry/PhotoService.java#L35-L47`
  - `backend/src/main/java/cr/attendance/timeentry/AdminTimeEntryPhotoController.java#L23-L27`
- **Category:** Privacy (Ley 8968) / OWASP (A09 Security Logging and Monitoring Failures)
- **Description:** The authorization is correct: an admin sees only their own company's photos, and an employee only their own. However:
  - **No audit record.** Nothing records *who* viewed *which* photo. The only log line is the WARN for a missing file.
  - **No throttle.** There is no rate limit on either photo GET.
  - **Sequential ids.** `time_entry.id` is a sequential identity value, so an admin token can be scripted to download the whole company's photo archive in minutes.

  Selfies are the most sensitive data the product holds. Ley 8968 expects the controller to be able to show that access was limited to the stated purpose, and a stolen admin token (Stage 1 L-7, the IndexedDB/XSS risk) currently leaves no trace of mass access.
- **Evidence:** `photoFor(...)` returns the `Resource` with no logging. The `FixedWindowRateLimiter` is registered only for `POST /api/time-entries`.
- **Exploit scenario:** An admin token is stolen, for example through XSS on the future PWA or from a shared admin PC. The attacker loops over `GET /api/admin/time-entries/{1..N}/photo` and collects every employee's selfies. The company cannot later say whether, when or how much was accessed.
- **Recommendation:**
  1. Log one INFO audit line per successful photo read: `photo_access actor=<employeeId> role=<role> entry=<id> company=<id>`. Use ids only, with no key or path. Or, better for Stage 4/5, insert a row into an `audit_log` table.
  2. Add a per-actor limiter on the admin photo route, for example 300 per 10 minutes, using the same `FixedWindowRateLimiter`. Stage 4's dashboard should show thumbnails on demand, not prefetch them in bulk.
  3. Stage 5: alert on unusual photo-read volume per actor.

### [INFO] I-1 The catch-all 500 body is safe, but logs can carry more than intended

- **File:**
  - `backend/src/main/java/cr/attendance/common/ApiExceptionHandler.java#L62-L66`
  - `backend/src/main/java/cr/attendance/timeentry/TimeEntryService.java#L79`
  - `backend/src/main/java/cr/attendance/storage/LocalDiskStorageService.java#L63`, `#L101`
  - `backend/src/main/resources/application.yml#L8`
- **Category:** Privacy / Logging
- **Description:** The response bodies are fixed and leak nothing: `INTERNAL_ERROR` with the opaque `instance`. The logs are less controlled:
  - **pgjdbc server detail.** pgjdbc has `logServerErrorDetail=true` by default, so a `PSQLException` message includes the server's `Detail:`. For a CHECK violation that is `Failing row contains (...)`, which includes the latitude and longitude, and `TimeEntryService#L79` and the catch-all log it at ERROR. I found no input path that reaches a `time_entry` CHECK today, because the form validation mirrors them. This is latent.
  - **Absolute paths.** `IOException` messages from `store` and `delete` include the absolute file path. The plan says "never file paths beyond the key".
  - **Client aborts.** A client abort while a photo is streaming (`ClientAbortException` / `AsyncRequestNotUsableException`) reaches the catch-all, gets logged as an ERROR with a full stack trace, and then fails to write a body to the committed response. This is log noise that can hide real errors.
- **Recommendation:**
  1. Add `logServerErrorDetail=false` to the production JDBC URL.
  2. Log `e.getClass().getSimpleName()` plus the company id or key instead of the raw `IOException`.
  3. Add a dedicated `@ExceptionHandler(AsyncRequestNotUsableException.class)` that returns nothing and logs at DEBUG.

### [INFO] I-2 `ATOMIC_MOVE` does replace an existing target, and the write path follows symlinks

- **File:** `backend/src/main/java/cr/attendance/storage/LocalDiskStorageService.java#L50-L53`
- **Category:** Config
- **Description:** The plan says the write "never overwrites". `Files.move(..., ATOMIC_MOVE)` is `rename(2)` on POSIX and `MoveFileEx(MOVEFILE_REPLACE_EXISTING)` on Windows, and both replace an existing target. With a server-generated random UUIDv4 key (122 random bits) a collision is not realistic, so this is a documentation inaccuracy and not a vulnerability. Separately, `createDirectories(target.getParent())` and the write follow symlinks. Only `load` has the real-path guard. Exploiting this needs local write access to the storage root, which is outside the threat model.
- **Recommendation:** Correct the comment and the plan. Optionally check `Files.exists(target, NOFOLLOW_LINKS)` before the move, and apply the same `toRealPath().startsWith(root)` check to the parent directory in `store`.

### [INFO] I-3 `CLIENT_UUID_CONFLICT` is an existence oracle within a company (needs the UUID)

- **File:** `backend/src/main/java/cr/attendance/timeentry/TimeEntryResults.java#L29-L32`
- **Category:** OWASP (A01)
- **Description:** I verified that another employee's entry is never returned. The 409 body is the same for "other employee" and "same employee, other type", and contains no data from the original entry. A colleague can still learn whether a given `clientUuid` exists in the company, and a guessed UUID could be "squatted" so that the owner's replay fails with 409. Both need the target's UUIDv4, which is not feasible to guess if the PWA uses a CSPRNG. `clientUuid` appears only in the owner's own responses.
- **Recommendation:** Stage 3 must generate `clientUuid` with `crypto.randomUUID()`, never `Math.random()`. Do not expose other employees' `clientUuid` in Stage 4 admin APIs unless needed.

### [INFO] I-4 Oversize behaviour at the container edge (manual step 12.7 still pending)

- **File:** `backend/src/main/resources/application.yml#L31-L32`, `#L69-L70`
- **Category:** Config
- **Description:** The layers are consistent:
  - app check: 2 MB → 413 `PAYLOAD_TOO_LARGE`
  - `max-file-size` 3 MB / `max-request-size` 4 MB → `MaxUploadSizeExceededException` → 413 `PAYLOAD_TOO_LARGE`, through the overridden `handleExceptionInternal`
  - `max-swallow-size` 5 MB is above the request limit

  A body larger than about 5 MB still gets its connection closed, which clients may see as a reset rather than a 413. Exceeding `max-part-count: 10` is reported as 413 rather than 400, which is acceptable and documented. Under MockMvc only the app-level check is exercised, so the real-Tomcat behaviour depends on the user's manual step 12.7.
- **Recommendation:** Run step 12.7 and record the result in PROGRESS. Enforce the edge limit at Caddy in Stage 5 (see L-1).

### [INFO] I-5 The ImageIO reader is chosen from a global, classpath-driven registry

- **File:** `backend/src/main/java/cr/attendance/storage/PhotoProcessor.java#L69-L73`
- **Category:** Dependencies
- **Description:** `ImageIO.getImageReadersByFormatName(format).next()` picks the first registered SPI. Today only the JDK readers are on the classpath: no TwelveMonkeys, `jai-imageio` or PDFBox/JBIG2 in the pom. A future transitive dependency that registers an ImageIO plugin (report and PDF libraries often do) would silently change which parser handles untrusted bytes, along with its CVE profile and limits. I verified the good part: only the sniffed format's reader is used. There is no auto-detection through `ImageIO.read`, so the WBMP/TIFF/BMP readers, classic DoS surfaces, cannot be reached.
- **Recommendation:** Select the reader by class name. For example, filter the iterator for `com.sun.imageio.plugins.jpeg.JPEGImageReader` / `com.sun.imageio.plugins.png.PNGImageReader` and fail closed otherwise, or compare `reader.getOriginatingProvider().getVendorName()`. Add a test that asserts the class of the selected reader.

### [INFO] I-6 Raw coordinates are kept indefinitely; only photos have a retention plan

- **File:** `backend/src/main/java/cr/attendance/timeentry/TimeEntryWriter.java#L129-L132`; `time_entry.latitude/longitude` (V2)
- **Category:** Privacy (Ley 8968)
- **Description:** Full-precision `lat`/`lng` are stored on every entry. The plan's retention covers photo files only, and keeps time entries as legal records. The legal record needs the time, the status and perhaps `distance_meters`. It does not need a precise location history of each employee years later.
- **Recommendation:** Stage 5 retention should also set `latitude`/`longitude` (and `gps_accuracy_meters`) to NULL after N months, keeping `distance_meters`, `review_reasons` and the status. Note it in the privacy notice the consent screen shows (Stage 3). Note that `ck_time_entry_location_pair` allows clearing both together.

### [INFO] I-7 Dependencies: no new Maven dependencies in Stage 2

- **File:** `backend/pom.xml`
- **Category:** Dependencies
- **Description:** `git diff 5f50477 HEAD -- backend/pom.xml` shows only the Stage 1b rename, and the working tree does not modify the pom. Stage 2 uses only JDK `javax.imageio` / `java.awt` plus libraries already present (Caffeine 3.2.4 for the new limiter). The Stage 1 I-8 assessment therefore stands, and the only new dependency-like exposure is the JDK runtime (M-2). I ran no online CVE query.
- **Recommendation:** As in Stage 1 I-8: add `osv-scanner` or `dependency-check` in Stage 5, and include the JDK and container image (M-2).

---

## Checked and found OK

**File upload**
- **Content type and filename.** The client's part `Content-Type` and `getOriginalFilename()` are never read. A grep of `src/main` finds no `getOriginalFilename` / `getContentType` calls. The format comes from magic bytes only (`ImageFormatSniffer`): JPEG `FF D8 FF`, PNG with all 8 signature bytes. Short inputs and null are handled.
- **One parser, one format.** Only the reader for the sniffed format is used, so a PNG body labelled JPEG, or a JPEG body labelled PNG, cannot reach a different decoder.
- **Size before read.** The size check runs before the bytes are read: `photo.getSize() > 2 MB` gives 413 before `getBytes()`. An empty part gives `INVALID_PHOTO`.
- **Dimensions before decode.** The width and height are read from the header with `setInput(stream, seekForwardOnly=true, ignoreMetadata=true)` before any pixel allocation (but see M-1 for the size of the remaining budget).
- **Truncated or broken input.** A truncated image (warning listener) and any `IOException`/`RuntimeException` give `INVALID_PHOTO`, and the reader is disposed in `finally`.
- **Re-encode.** Every stored file is re-encoded. The image is drawn onto a fresh `TYPE_INT_RGB` canvas and written with `writer.write(null, new IIOImage(canvas, null, null), param)`: no stream metadata, no image metadata, no thumbnails. EXIF/GPS, ICC, comments, trailing data and polyglot payloads cannot survive. The output is always JPEG.
- **Re-encode happens before storage.** `TimeEntryWriter` stores only `photo.jpeg()` from `ProcessedPhoto`, and the raw bytes never reach `StorageService`.
- **No ImageIO temp files.** `ImageIO.setUseCache(false)` is set, so ImageIO creates no temp files, and `ImageIO.createImageInputStream(ByteArrayInputStream)` / `createImageOutputStream(ByteArrayOutputStream)` stay in memory.
- **Disk growth from stored photos.** Growth is bounded by the business rules, not only by the limiter. The unique index `(employee_id, work_date, type)` plus the sequence rules allow about 2 stored photos per employee per work date, each a 1280 px JPEG of at most about 1 MB. Rejected attempts store nothing or are deleted on rollback. The per-company quota remains a Stage 5 item.

**Storage**
- **Keys are server-generated.** `{companyId}/{yyyy-MM}/{UUID.randomUUID()}.jpg`. `clientUuid`, the filename and other request data are never used. `companyId` comes from the principal, and the month from `server_time` in the company zone.
- **Key validation on load.** `load` applies the strict regex (lowercase hex UUID with dashes in place, `[0-9]{1,18}` company), checks the tenant prefix (`startsWith(companyId + "/")`), checks that `normalize()` stays under the root, and checks that `toRealPath()` stays under the real root and is a regular file. That covers symlinks, `..`, absolute paths, backslashes and URL-encoded traversal. `delete` validates the key and its normalized path too.
- **DB backstops.** `ck_evidence_storage_key_format`, `ck_evidence_storage_key_company` (prefix equals `company_id`), `ck_evidence_storage_key_type` (only PHOTO carries a key) and `uq_evidence_time_entry_type`. A NULL PHOTO key is allowed for retention, and `PhotoService` and `hasPhoto` treat it as "no photo".
- **Atomic writes.** The temp file is created in the target directory (0600 on POSIX) and moved atomically, and the temp file is deleted on `IOException`. The storage I/O failure gives 500 `INTERNAL_ERROR` with no detail.
- **Rollback cleanup.** The rollback-delete synchronization is registered right after `store` inside the `@Transactional` proxy method, so it is active (see L-2 for `STATUS_UNKNOWN`).
- **Not served statically, not committed.** No `static`, `public`, `src/main/webapp` or `META-INF/resources` directory exists, so nothing under `uploads/` is served (see L-5 for the missing guard). `.gitignore` `uploads/` matches both `backend/uploads/` and the repo-root `uploads/` (`git check-ignore` verified). No image or upload file was ever committed outside `frontend/public` icons. Test uploads go to `target/test-uploads` (ignored).

**Photo download**
- **IDOR.** `findByIdAndCompanyId(id, principal.companyId())`, then an employee-ownership check on the employee route, then `findByCompanyIdAndTimeEntryIdAndType(companyId, ...)`, then `storage.load(companyId, key)`. Every miss (no entry, another tenant, another employee, no photo row, NULL key, file missing) throws the same `ResourceNotFoundException`, which gives the same 404 body with the opaque `instance`. `companyId` and `employeeId` come only from `EmployeePrincipal`.
- **Admin route.** It sits under `/api/admin/**`, which needs `hasRole("ADMIN")` with the role read from the DB per request, and passes `null` only for the employee filter, never for the company.
- **Path variable.** It is a `Long`: non-numeric input gives 400 through `handleExceptionInternal`, with no echo.
- **Response headers.** `Content-Type: image/jpeg` (always true, because every file was re-encoded), `Content-Length`, `Cache-Control: no-store, private`, and `Content-Disposition: inline; filename="photo.jpg"` (fixed name). Spring Security's default `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY` stay active. Its `CacheControlHeadersWriter` leaves an existing `Cache-Control` alone, so `no-store` is not weakened.
- **Logs.** The missing-file WARN logs only the entry id.

**Business logic, idempotency, concurrency**
- **The server clock is official.** `server_time = Instant.now(clock)`. `work_date` comes from the company zone (CLOCK_IN) or is inherited from the closed CLOCK_IN (CLOCK_OUT). `late_minutes` is computed from `server_time` and the schedule. `deviceTime` and `deviceSentAt` only feed `skew_seconds`, `SYNCED_LATE` and `DEVICE_CLOCK_AHEAD`. Both are range-checked (2000-2100), and `skew_seconds` is clamped to `int`.
- **Replays.** A replay is returned only for the same company, employee and type. Anything else gives 409 with no data from the original entry. The photo-less replay path only reads an existing entry, so it cannot create an entry without a photo: a new `clientUuid` without a photo gives `PHOTO_REQUIRED`. The photo-required check sits on the single write path, `TimeEntryService` → `TimeEntryWriter`.
- **One lock for all writes.** All writes go through `TimeEntryWriter.record`, which first takes `PESSIMISTIC_WRITE` on `employee (id, company_id)` and then re-checks idempotency, consent, branch and sequence under the lock.
- **Constraint backstops.** `uq_time_entry_company_client_uuid` is mapped to a fresh lookup followed by a replay or a 409, and `uq_time_entry_employee_work_date_type` to 409. An unknown violation is rethrown and gives a generic 500.
- **`SHIFT_TOO_LONG`.** A CLOCK_OUT for a shift older than 16 h is accepted but always `PENDING_REVIEW`. The lookback is limited to 48 h, so a CLOCK_OUT cannot attach to an arbitrarily old CLOCK_IN. A "forgotten" CLOCK_IN of an earlier day does not block the next day's CLOCK_IN. (See L-4 for inflation inside the 16 h window.)
- **Form input.** NaN, Infinity, out-of-range and half-pair coordinates are rejected (`Double.isFinite`), as are accuracy without a position, the nil UUID and comma decimals (a type mismatch, 400 with no echo). `TimeEntryForm.toString` and `TimeEntry.toString` mask the coordinates.
- **Rate limiter.** It is keyed `companyId:employeeId` from the authenticated principal, never from request data. The interceptor runs before `@ModelAttribute` binding and validation (but see L-1 for multipart parsing). Its path pattern and the handler mapping use the same parsed `RequestPath`, so encoded or trailing-slash variants cannot reach the handler while skipping the interceptor (trailing-slash matching is off in Spring 6, and the strict firewall rejects `;` and `//`). The cache is bounded at 10k entries.

**Authorization and tenancy**
- **Repository methods.** Every new method takes `companyId`: `findByCompanyIdAndClientUuid`, `findAllByCompanyIdAndEmployeeIdAndWorkDate...`, `findAllByCompanyIdAndEmployeeIdAndServerTimeAfter...`, `findByCompanyIdAndTimeEntryIdAndType`, `findWithLockByIdAndCompanyId`, `findByCompanyIdAndEmployeeIdAndDayOfWeek`, and `updateLocation(id, companyId, ...)`, which is dev-seed only and has `where ... and b.companyId = :companyId`. `CompanyRepository.findById(principal.companyId())` is the tenant root.
- **`today` endpoint.** It reads only the caller's data. Branch coordinates are not exposed: only the name and radius are.
- **Consent.** It is enforced server-side twice: the read-only pre-check before photo decoding, and again under the row lock. It runs before any file, row or evidence write, which closes Stage 1 I-3 item 2. The order matches Amendment 3.

**Error handling**
- **Fixed bodies.** `ApiException` carries only an enum code, and its message is the code name. Every ProblemDetail body is built from the bundle with no arguments, plus the opaque `instance`.
- **Multipart and size errors.** `MultipartException` gives 400 `INVALID_REQUEST`. `MaxUploadSizeExceededException` stays on the base class's more specific handler and gives 413 `PAYLOAD_TOO_LARGE`.
- **Catch-all.** The new catch-all 500 returns a fixed body and replaces the Boot error page, which echoes the request path.
- **Logs.** The new log lines contain only ids, type, status and reasons, the storage root at startup, and keys. They contain no coordinates, photo bytes, tokens or PINs (see I-1 for the exception-detail caveat).

**Config, secrets and seed**
- **`.env.example`.** The new variables (`UPLOADS_DIR`, `DEV_SEED_LAT/LNG/RADIUS`) are empty. No new secrets appear in the tracked or untracked files, and `git log --all` shows no committed `.env`. The only secret-like value is still the labelled test JWT key in `application-test.yml` (Stage 1 L-3, guarded).
- **Seed location.** It is dev-profile only, validated (pair, range, radius 10-5000, finite), and the coordinates are never logged. The `SeedProperties.toString` mask still hides the PIN.
- **V3 migration.** It is additive only. It backfills `work_date` in the company zone before `SET NOT NULL`, and the CHECK constraints mirror the app rules.

---

## Stage 1 follow-ups touched by this stage

| Stage 1 item | Status after Stage 2 |
|---|---|
| I-3 Consent before capture | **Partly closed.** Server-side block before any write (both pre-check and under lock). Versioning and revocation are still open (Stage 3/5). |
| L-6 No self-service logout on shared devices | **Still open, impact higher.** A token left on a shared device now allows clocking in or out as its owner. The selfie requirement makes this visible on review, but only for `PENDING_REVIEW` entries (see L-4). Stage 3 must add `POST /api/auth/logout` (bump `token_version`) and call it after each kiosk-mode mark. |
| L-7 Token in IndexedDB | **Still open, impact higher.** A stolen token now gives access to photos (see L-6 here). CSP and the related controls remain Stage 3/5 blockers. |
| I-1 URL-only admin authorization | Unchanged. The new admin controller is correctly under `/api/admin/**`. Method security is still recommended before Stage 4 adds admin endpoints. |
| M-2 / M-3 | Deferred to Stage 5 as agreed. The new POST limiter is per employee, so it is not affected by M-2's proxy/NAT issue. |

---

## Summary by severity

| Severity | Count | Findings |
|----------|-------|----------|
| Critical | 0 | none |
| High | 0 | none |
| Medium | 2 | M-1 Image decode heap/CPU exhaustion (128 MiB per decode, no concurrency bound); M-2 Untrusted images decoded on an unpatched JDK 25.0.1, no runtime baseline |
| Low | 6 | L-1 Multipart parsed eagerly before the limiter and on unauthenticated routes; L-2 Raw uploads in unmanaged temp dir and orphan photo files outside retention; L-3 5 s lock timeout ignored by Hibernate on PostgreSQL; L-4 Inflated hours can be `VALID` (client-asserted GPS, optional accuracy, no schedule-end check); L-5 Storage root cwd-relative and unguarded against static doc roots; L-6 Photo access not audited or throttled |
| Info | 7 | I-1 Logs can carry SQL row detail and paths; I-2 `ATOMIC_MOVE` replaces, write path follows symlinks; I-3 `CLIENT_UUID_CONFLICT` existence oracle (needs UUID); I-4 Oversize behaviour at the container edge; I-5 ImageIO reader chosen from global registry; I-6 Coordinates kept indefinitely; I-7 No new Maven dependencies |

**Recommended before release:**
- Fix M-1, at minimum items 1-4: lower limits, subsampled decode, reject 16-bit/interlaced/progressive input, and a global decode semaphore.
- Update the JDK (M-2).
- Apply L-1 (`resolve-lazily: true`) and L-3 (`lock_timeout`). Each is a one-line change and can be tested.
- Fold L-2, L-5 and L-6 into the Stage 5 go-live checklist, and L-4 into the Stage 4 plan.


---

## Remediation status (2026-10-23)

| Finding | Status | Where |
|---|---|---|
| M-1 Image decode exhaustion | **Fixed** | `storage/ImageHeaders.java` reads size and encoding from the file header and refuses 16-bit PNG, interlaced PNG, progressive/arithmetic/lossless JPEG and non-8-bit JPEG before any decode. `storage/PhotoProcessor.java` applies the limits and a process-wide decode `Semaphore` (`max-concurrent-decodes: 3`, `decode-acquire-timeout: 2s`); a saturated decoder gives 503 `SERVER_BUSY` with `Retry-After`. Limits lowered in `application.yml` to 2560 px per side and 6,000,000 px (`StorageProperties`). Subsampling was not added: with a 2560 px side cap and a 1280 px output the subsampling factor is always 1. Tests: `PhotoProcessorHardeningTest`, `PhotoDecodeBusyTest`, `StorageWiringIntegrationTest`. |
| M-2 Unpatched JDK 25.0.1 | **Deferred by user decision** | The user keeps JDK 25.0.1 locally for now. Update and pin the production runtime in Stage 5 (patch within 2 weeks of each CPU, log `Runtime.version()`, scan the JDK). |
| L-1 Multipart parsed before the limiter | **Fixed** | `spring.servlet.multipart.resolve-lazily: true` (`application.yml`). The per-employee limiter is now `timeentry/TimeEntryRateLimitFilter.java`, added in `config/SecurityConfig.java` right after `BearerTokenAuthenticationFilter`, so it runs before content-type matching and multipart parsing. 429 now beats 415/400; the interceptor was removed. Tests: `TimeEntryRateLimitIntegrationTest.aJsonBodyCountsTowardTheLimit` (re-enabled), `StorageWiringIntegrationTest`. Real-container behaviour is confirmed by the manual step 12.7. |
| L-2 Raw uploads in the system temp dir | **Fixed** (orphan photos: Stage 5) | `storage/MultipartTempDirConfig.java` sets the multipart location to `LocalDiskStorageService.tempDir()` (default `<root>/.upload-tmp`, or `app.storage.temp-dir`), created with mode 0700 where POSIX is available and emptied of `upload_*` files at startup. `TimeEntryWriter` now deletes the photo only on `STATUS_ROLLED_BACK` and keeps it (WARN) when the commit outcome is unknown. The sweeper for photo files without an `evidence` row and for stale `.upload-*.tmp` files stays a Stage 5 go-live item. Tests: `StorageRootGuardTest`, `StorageWiringIntegrationTest`. |
| L-3 Ignored lock timeout | **Fixed** | `TimeEntryWriter.record` runs `set_config('lock_timeout', ..., true)` (transaction-local) with `app.time-entry.lock-timeout` (default 5s) before taking the row lock; the ineffective hint was removed from `EmployeeRepository`. A lock timeout (`PessimisticLockingFailureException`) is mapped in `ApiExceptionHandler` to 503 `SERVER_BUSY` with `Retry-After: 5`. Test: `TimeEntryLockTimeoutIntegrationTest`. |
| L-4 Inflated hours as `VALID` | **Deferred to Stage 4** | Admin review and payroll rules. |
| L-5 Storage root | **Fixed** | `storage/LocalDiskStorageService.java` resolves the root to an absolute normalized path (and its real path) and logs it. `storage/StorageRootGuard.java` refuses roots under `src/main/resources`, `src/main/webapp`, `META-INF/resources`, `target/classes`, the working directory's `static`, `public` and `src/main/webapp`, and any `file:` entry of `spring.web.resources.static-locations`. Test: `StorageRootGuardTest`. |
| L-6 Photo access not audited | **Deferred to Stage 5** | Audit log and throttling of photo reads. |
