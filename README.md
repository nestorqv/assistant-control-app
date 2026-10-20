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

Run:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Git Bash: `./mvnw spring-boot:run`. Check `http://localhost:8080/api/health`.

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
