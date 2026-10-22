# Control de Asistencia

Attendance clock-in/out system for small companies in Costa Rica: an employee PWA plus an admin web panel. The UI is in Spanish; the documentation is in English.

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

### Dev data

Run with the `dev` profile to create the demo company (idempotent; it never runs with `prod`):

```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

It creates company `DEMO` ("Empresa Demo") with branch "Sucursal Centro", employees `1001` to `1006` and admin `9001`, all with the PIN from `DEV_SEED_PIN`, and Monday to Friday schedules. Changing `DEV_SEED_PIN` afterwards has no effect; recreate the dev database to re-seed.

### Authentication

- `POST /api/auth/login` with `{"empresaCodigo","empleadoCodigo","pin"}` returns a Bearer JWT (HS256). Codes are case-insensitive.
- Token lifetime: 8 hours for employees, 6 hours for admins. There are no refresh tokens: when it expires, the user enters the PIN again.
- Every failure (unknown company or employee, inactive, locked, wrong PIN) returns the same 401. Five consecutive wrong PINs lock the employee for 15 minutes (automatic unlock). Someone who knows an employee code can lock that employee for 15 minutes; admin unlock arrives in Stage 4.
- More than 20 login attempts per 5 minutes from one IP return 429.
- Other endpoints: `GET /api/me`, `POST /api/me/consentimiento`, and `GET /api/admin/empleados[/{id}]` (admin only). Send `Authorization: Bearer <token>`.

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
backend/    Spring Boot 3.5 (Maven, Flyway, Java 21 bytecode), package cr.asistencia
frontend/   React + Vite + TypeScript PWA
scripts/db/ Dev database creation scripts
docs/       Plans and progress
```
