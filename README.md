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
