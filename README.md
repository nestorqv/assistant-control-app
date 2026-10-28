# Control de Asistencia

Attendance clock-in/out system for small companies in Costa Rica: an employee PWA plus an admin web panel. The UI is in Spanish and English (switchable, Spanish by default); the documentation is in English.

Roadmap and stage plans: [docs/plans/00-roadmap.md](docs/plans/00-roadmap.md).

## Prerequisites

- JDK 21 or newer (tested on 25). The backend compiles to Java 21 bytecode.
- Node 24 LTS (>= 24.15). `engine-strict=true` makes `npm install` fail on older versions.
- Git
- PostgreSQL 16
- Microsoft Visual C++ 2013 x64 redistributable (needed by the embedded Postgres used in backend tests on Windows)

The Maven wrapper (`backend/mvnw`) downloads Maven 3.9.14 itself.

## PostgreSQL

Check the service (PowerShell) and start it if stopped:

```powershell
Get-Service postgresql-x64-16
Start-Service postgresql-x64-16   # run as administrator
```

On a new machine, install it with the wizard so it asks for the `postgres` superuser password (keep port 5432):

```powershell
winget install PostgreSQL.PostgreSQL.16 --interactive
```

`psql` lives in `C:\Program Files\PostgreSQL\16\bin`.

### Create the dev database

This creates role `asistencia_app` and database `asistencia`. It is idempotent and never changes an existing role's password.

```powershell
.\scripts\db\create-dev-db.ps1
# if the execution policy blocks it:
powershell -ExecutionPolicy Bypass -File scripts\db\create-dev-db.ps1
```

You will be asked for the new app password, then psql asks for the `postgres` password. Manual alternative:

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U postgres -v ON_ERROR_STOP=1 -v app_password=YOUR_PASSWORD -f scripts\db\create-dev-db.sql
```

## Backend

Configure the connection (the real `backend/.env` is gitignored):

```powershell
Copy-Item backend\.env.example backend\.env
```

Git Bash: `cp backend/.env.example backend/.env`. Then edit `DB_PASSWORD`. Values are read literally: no quotes, no `export`. Real environment variables override the file, and the file is only found when the app runs from `backend/`.

### Configuration (secrets)

Add these two keys to `backend/.env`. Type the values yourself; never paste them into chat, docs or commits.

- `JWT_SECRET`: base64, at least 32 random bytes (48 recommended). The backend refuses to start without it. Generate one:

  ```powershell
  $b=New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
  ```

  Git Bash: `openssl rand -base64 48`. Changing it later invalidates every issued token, so users must enter their PIN again.
- `DEV_SEED_PIN`: only for the `dev` profile. The shared PIN of the demo users: exactly 8 digits, not all one digit, not a sequence (`12345678`, `87654321`), not a repeated block (`12121212`, `12341234`) and not a date (`DDMMYYYY`, `MMDDYYYY`, `YYYYMMDD`). Startup fails with a clear message otherwise.

Run:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Git Bash: `./mvnw spring-boot:run`. Check `http://localhost:8080/api/health`.

### Reset the dev schema

Stage 1b edited `V2__initial_schema.sql` in place, so an existing dev database must be reset once or startup fails with a Flyway checksum mismatch. Stop the backend, then (psql asks for the `asistencia_app` password):

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U asistencia_app -h localhost -d asistencia -v ON_ERROR_STOP=1 -f scripts\db\reset-dev-schema.sql
```

The database `asistencia` and the role `asistencia_app` keep their names. Flyway recreates the tables on the next start.

### Dev data

Run with the `dev` profile to create the demo company (idempotent; it never runs with `prod`):

```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

It creates company `DEMO` ("Empresa Demo") with branch "Sucursal Centro", employees `1001` to `1006` and admin `9001`, all with the PIN from `DEV_SEED_PIN`, and Monday to Friday schedules. Changing `DEV_SEED_PIN` afterwards has no effect; recreate the dev database to re-seed.

### Authentication

- `POST /api/auth/login` with `{"companyCode","employeeCode","pin"}` returns a Bearer JWT (HS256). Codes are case-insensitive.
- Token lifetime: 8 hours for employees, 6 hours for admins. There are no refresh tokens: when it expires, the user enters the PIN again.
- Every failure (unknown company or employee, inactive, locked, wrong PIN) returns the same 401. Five consecutive wrong PINs lock the employee for 15 minutes (automatic unlock). Someone who knows an employee code can lock that employee for 15 minutes; admin unlock arrives in Stage 4.
- More than 20 login attempts per 5 minutes from one IP return 429.
- `POST /api/auth/logout` (authenticated, 204) bumps the employee's `token_version`, which ends **all sessions of that employee on every device** (it is the only revocation mechanism). An expired token gets 401, which the PWA ignores. Offline, the PWA only wipes its local data; the token stays valid on the server until it expires.
- Other endpoints: `GET /api/me`, `POST /api/me/consent`, and `GET /api/admin/employees[/{id}]` (admin only). Roles are `EMPLOYEE` and `ADMIN`. Send `Authorization: Bearer <token>`.

### Check-in API

Stage 2. All endpoints need `Authorization: Bearer <token>`; the server clock and the company time zone decide the time and the work date, never the phone.

- `GET /api/time`: server time and company zone.
- `GET /api/time-entries/today`: state of the single clock button (`nextAction` `CLOCK_IN` / `CLOCK_OUT` / `NONE`, `blockedReason`, `workedMinutes`, schedule, branch).
- `POST /api/time-entries` (`multipart/form-data`): fields `type` (`CLOCK_IN`/`CLOCK_OUT`), `clientUuid`, `deviceTime` (ISO instant), optional `lat`, `lng` (dot decimals, both or neither), `accuracy`, `deviceSentAt`, and the required `photo` (baseline JPEG or non-interlaced 8-bit PNG, max 2 MB, at most 2560 px per side and 6 MP). 201 when created, 200 for a replay of the same `clientUuid` (same employee and type; no photo needed on a replay).
- `GET /api/time-entries/{id}/photo` (own photos) and `GET /api/admin/time-entries/{id}/photo` (admin, own company). Anything else is 404.

```powershell
curl.exe -s -X POST http://localhost:8080/api/time-entries -H "Authorization: Bearer $token" -F type=CLOCK_IN -F clientUuid=([guid]::NewGuid()) -F deviceTime=2026-10-22T13:58:00.000Z -F lat=9.9325 -F lng=-84.0796 -F accuracy=12 "-F photo=@selfie.jpg;type=image/jpeg"
```

Rules:

- Status is `VALID` or `PENDING_REVIEW`. Review reasons: `NO_GPS`, `OUTSIDE_GEOFENCE`, `LOW_GPS_ACCURACY` (above 100 m), `SYNCED_LATE`, `DEVICE_CLOCK_AHEAD`, `SHIFT_TOO_LONG`.
- One CLOCK_IN and one CLOCK_OUT per work date. A CLOCK_OUT closes the open CLOCK_IN (overnight shifts keep the start date). After 16 h the shift is "forgotten": a CLOCK_OUT is still accepted but flagged `SHIFT_TOO_LONG`, and a new day can be started.
- Lateness is stored at CLOCK_IN: counted from the scheduled start once the grace is exceeded.
- Consent (`POST /api/me/consent`) and a branch are required (403 `CONSENT_REQUIRED` / `NO_BRANCH_ASSIGNED`). The seeded admin `9001` has no branch.
- 30 POSTs per employee per 10 minutes, then 429 (checked before the body is parsed). When the server is busy (too many photos decoding at once, or the employee row lock is held for more than 5 s) the answer is 503 `SERVER_BUSY` with `Retry-After`; the client should retry with the same `clientUuid`.
- Photos are re-encoded to JPEG without EXIF and stored under `UPLOADS_DIR` (default `backend/uploads`, gitignored). Never commit uploads. The folder must not be inside `src/`, `static`, `public` or `webapp`: the backend refuses to start otherwise. Uploads are spooled to `<UPLOADS_DIR>/.upload-tmp` while a request is processed.
- To test the geofence at your location, set `DEV_SEED_LAT`, `DEV_SEED_LNG` (dot decimals) and optionally `DEV_SEED_RADIUS` (10 to 5000) in `backend/.env`; they are applied on every dev start, also to an already-seeded branch.
- New error codes: `CONSENT_REQUIRED`, `NO_BRANCH_ASSIGNED`, `ALREADY_CLOCKED_IN`, `NOT_CLOCKED_IN`, `SHIFT_ALREADY_COMPLETED`, `CLIENT_UUID_CONFLICT`, `PHOTO_REQUIRED`, `INVALID_PHOTO`, `PAYLOAD_TOO_LARGE`, `SERVER_BUSY`.

The database migrates to V3 automatically on the next start; no reset is needed.

## Employee PWA

Stage 3. The `/` route is the employee app: login with an 8-digit PIN keypad, the Ley 8968 consent screen, and one main screen with the clock button (`Marcar entrada` / `Marcar salida` / `Listo por hoy`) driven by `GET /api/time-entries/today` and a server clock.

- **One path for every clock action:** front-camera selfie (file input as the fallback), compressed on the phone to about 1024 px baseline JPEG, best GPS fix of up to 20 s, then an **IndexedDB outbox** that is sent strictly one at a time (oldest first), online or not. Retries reuse the same `clientUuid`; 200 and 201 both mean "recorded". A 401 pauses the queue and opens a PIN dialog; a final 4xx (for example `NOT_CLOCKED_IN`) becomes a card the user acknowledges (the photo is deleted at once).
- **Offline:** with a stored identity the app opens and queues entries even without signal (the identity and the last state are kept on the phone). Sending waits for the PIN once the phone is online. At most 8 unsent entries; at least 5 MB of free storage is required to queue one.
- **Where data lives:** the token and its expiry are in `sessionStorage` (gone when the app is closed); the non-secret identity, the queue and the last state are in IndexedDB; the PIN is never stored. Sign-out always asks first, warns when entries are unsent (they are deleted), calls `POST /api/auth/logout` best effort and wipes the device. One identity per device: signing in as another employee while the first has unsent entries needs a confirmation.
- **Location display:** the phone never computes a distance and never receives the branch coordinates. Before sending, the chip shows readiness and accuracy only ("Ubicación lista · ±12 m", or a warning when GPS is blocked, unavailable or above 100 m). After the server answers, each entry shows the server's result ("Dentro del área · a 35 m del local" / "Fuera del área · 1112 m · en revisión"); queued entries show "Pendiente de envío".
- **Time:** the clock shows server time (offset from `GET /api/time`, kept with the monotonic clock) and "(aprox.)" when it is only an estimate. Dates and times use the company time zone, never the phone's.
- **Updates:** the service worker updates on request ("Hay una nueva versión · Actualizar"), never in the middle of a capture. API responses and photos are never cached.
- **Consent text:** version `2026-10-v1` (selfies and exact coordinates kept 12 months, then deleted; time records kept as labour law requires; requests go to the company administrator). **Pending legal review**; it is revisited in Stage 5, together with the deletion job.

### Security headers

`frontend/securityHeaders.ts` is the single source of the response headers (`PROD_CSP`, `SECURITY_HEADERS`, `DEV_HEADERS`).

- `npm run preview` enforces `SECURITY_HEADERS`: a strict CSP (`script-src 'self'`, no `unsafe-*`, no `data:`, `img-src 'self' blob:`, `require-trusted-types-for 'script'`, `trusted-types default`), `Referrer-Policy: no-referrer`, `Permissions-Policy` (camera and geolocation for this origin only), `X-Content-Type-Options: nosniff` and `Cross-Origin-Opener-Policy: same-origin`.
- The dev server sends `DEV_HEADERS`: the same without the CSP (React Refresh and HMR need inline scripts and `ws:`).
- `src/security/trustedTypes.ts` installs the `default` Trusted Types policy, which only lets `/sw.js` through (service worker registration is a `TrustedScriptURL` sink).
- Stage 5 (Caddy) copies these strings and adds HSTS. Lint bans `dangerouslySetInnerHTML`, `eval` and `new Function`.

### Testing on a phone

> **Security note.** `npm run dev:phone` (and `preview:phone`) binds Vite to every network interface (`--host`), so anyone on the same Wi-Fi can reach the dev server and, through its `/api` proxy, your dev backend. Every LAN client also looks like `127.0.0.1` to the login rate limiter. Use it only on a trusted private Wi-Fi (never public, guest or shared networks), allow the firewall rule for the phone only if you can, and stop the server when you finish. The safer option is USB debugging: `adb reverse tcp:5173 tcp:5173` (or Chrome `chrome://inspect/#devices` port forwarding of `5173` to `localhost:5173`) lets the phone open `http://localhost:5173`, a secure context where camera and GPS work, with no LAN exposure, no firewall rule and no certificate warning.

Camera and GPS need a secure context, so the phone uses HTTPS. In `backend/.env` you can set `DEV_SEED_LAT`, `DEV_SEED_LNG` (dot decimals) and `DEV_SEED_RADIUS=100` to put the geofence where you are. PINs are typed at the prompts, never pasted into chat.

1. Backend (plain HTTP on the PC; the phone reaches it only through the Vite proxy). For a quick token-expiry test add `"-Dspring-boot.run.arguments=--app.jwt.ttl-employee=3m"`:
   ```powershell
   cd backend
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
   ```
2. Frontend over HTTPS on the LAN (self-signed certificate), in a second window:
   ```powershell
   cd frontend
   npm install
   npm run dev:phone        # prints  Network: https://192.168.x.y:5173/
   ```
   Find the PC's IP with `ipconfig` (IPv4 of the Wi-Fi adapter). PC and phone must be on the same Wi-Fi; set the network profile to Private (`Get-NetConnectionProfile`) and allow Node.js when Windows asks, or run as administrator: `New-NetFirewallRule -DisplayName "Vite 5173" -Direction Inbound -Protocol TCP -LocalPort 5173 -Profile Private -Action Allow`.
3. On the phone (Chrome) open `https://192.168.x.y:5173`; on "Your connection is not private" choose Advanced, then Proceed (expected for the dev certificate). Camera, GPS and the offline queue work; the **service worker does not** on an untrusted certificate (Chrome refuses it).
4. Sign in as `DEMO` / `1001` with the `DEV_SEED_PIN`; accept the consent screen (fresh employees only) and allow location. Clock in: the chip shows "Ubicación lista · ±N m" with no distance; after sending, the line under Entrada shows the server's distance and verdict.
5. Offline clock-out: turn on airplane mode, tap "Marcar salida", take the selfie. Expect the banner "Sin señal...", "Salida guardada · se enviará al reconectar" and "1 pendiente". Turn Wi-Fi back on: the entry is sent within seconds (do not reload while offline in dev; the dev server is unreachable).
6. Install, service worker and cold start offline need a trusted origin: connect the phone by USB with debugging on, then
   ```powershell
   cd frontend
   npm run build
   npm run preview          # http://localhost:4173 with the production CSP
   ```
   In PC Chrome open `chrome://inspect/#devices`, add port forwarding `4173` to `localhost:4173`, and open `http://localhost:4173` on the phone. Install the app, check that the console shows no Trusted Types or CSP violation, use airplane mode and open the app from the home screen. To see the update prompt, change any text, run `npm run build` again and reopen the app.
7. Token expiry: with the 3-minute TTL, clock in, wait 3 minutes, go offline, clock out, go back online. The PIN dialog appears with the codes pre-filled; after the PIN exactly one CLOCK_OUT exists on the server.
8. Sign out ends every session of that employee, including tokens issued earlier on the PC. Check camera denied (site settings), GPS denied (the entry becomes `NO_GPS` / `PENDING_REVIEW`), and optionally iPhone Safari (baseline JPEG check, camera behaviour).

`npm run preview:phone` serves the built app with the production headers over LAN HTTPS (the service worker will not register there either).

## Languages

- The UI has a language switcher (Español / English) in the header. The choice is saved in the browser; the default is the browser language when it is Spanish or English, otherwise Spanish.
- The frontend sends `Accept-Language` on every API call. The backend supports `es` and `en` and answers in Spanish when the header is missing or the language is not supported.
- Error bodies keep a stable English `code` (for example `INVALID_CREDENTIALS`); only `title` and `detail` are translated, and the response carries `Content-Language`.
- Texts live in `backend/src/main/resources/messages*.properties` and `frontend/src/i18n/resources.ts`.
- The installed-app name (PWA manifest) stays Spanish. Demo data (company, branch, people, job titles) stays Spanish.

## Frontend

```powershell
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Requests to `/api` are proxied to `http://localhost:8080`.

- `npm run dev:phone`: the same server over HTTPS on the LAN (`@vitejs/plugin-basic-ssl`) so a phone can use the camera and GPS. See "Testing on a phone".
- `npm run preview:phone`: the built app with the production headers over LAN HTTPS.
- `npm run lint` (oxlint), `npm run build`, `npm test`.

## Tests

```powershell
cd backend;  .\mvnw.cmd test     # embedded PostgreSQL, no running DB or .env needed
cd frontend; npm test            # run from frontend/
```

## Testing the PWA

The service worker is disabled in dev. Use:

```powershell
cd frontend
npm run build
npm run preview
```

`preview` serves `http://localhost:4173` with the production CSP. A real phone only registers the service worker on a trusted origin: use USB port forwarding to `localhost` (see "Testing on a phone"). Updates are prompted ("Hay una nueva versión · Actualizar"), and `/api` and photos are never cached.

## Troubleshooting

- Port 5432 busy or service stopped: `Get-Service postgresql-x64-16`, `Start-Service postgresql-x64-16`, `netstat -ano | findstr 5432`.
- Backend fails at startup with an authentication error: `DB_PASSWORD` in `backend/.env` is missing or wrong.
- Lost `postgres` password: temporarily set `pg_hba.conf` auth to `trust` for localhost, restart the service, run `ALTER USER postgres PASSWORD '...'`, then restore `scrypt`/`md5`.
- Backend tests fail to start embedded Postgres on Windows: install the Microsoft Visual C++ 2013 x64 redistributable. Antivirus can also slow the first extraction into `%TEMP%`.
- `ECONNREFUSED` in the Vite proxy: the backend is not running, or `localhost` resolves to IPv6; change the proxy target in `frontend/vite.config.ts` to `http://127.0.0.1:8080`.
- `npm install` fails with an engine error: use Node 24 LTS (>= 24.15).

## Layout

```
backend/    Spring Boot 3.5 (Maven, Flyway, Java 21 bytecode), package cr.attendance
frontend/   React + Vite + TypeScript PWA
scripts/db/ Dev database creation and reset scripts
docs/       Plans and progress
```
