# Stage 0 - Project foundation

Roadmap: `docs/plans/00-roadmap.md`. Blueprint: `Prototipo-Control-Asistencia.pdf`.
Status: PLANNED, open questions resolved 2026-10-13 (no code written yet).

## Summary

Turn the greenfield folder into a git monorepo with two independently runnable apps. Run `git init` but make **no commit**; the user decides when to commit. `backend/` is a Spring Boot 3.5.16 Maven project (Maven wrapper, Java 21 bytecode compiled on the local JDK 25). It includes web, data-jpa, validation, security, flyway (+ `flyway-database-postgresql`), actuator and the PostgreSQL driver. It connects to the already-installed native PostgreSQL 16 (Windows service `postgresql-x64-16`) using `DB_URL` / `DB_USER` / `DB_PASSWORD`. These are read from the real environment or from an optional, gitignored `backend/.env` imported with `spring.config.import=optional:file:.env[.properties]`. The backend exposes a public `GET /api/health` JSON endpoint, and a stateless `SecurityFilterChain` answers 401 for everything else. Flyway applies a no-op `V1__baseline.sql`. An integration test boots the full context against zonky embedded PostgreSQL 16 binaries (no Docker) and calls the endpoint over MockMvc. `frontend/` is scaffolded with `create-vite` (react-ts template, Vite 8) on **Node 24 LTS**. It adds vite-plugin-pwa 2 (Spanish manifest, placeholder icons, `/api` excluded from the SW navigation fallback), react-router 8 declarative routes `/` and `/admin`, and a Vite dev and preview proxy `/api -> http://localhost:8080`. A small `HealthStatus` component fetches `/api/health` and renders the result. Vitest 5 + Testing Library + jsdom 30 cover it with tests. `engine-strict=true` and `engines.node >=24.15.0` keep the toolchain consistent. All docs (README, plans, CHANGELOG) are in English; the UI text is Spanish.

## Verified versions (checked 2026-10-13 via WebFetch)

Local toolchain: Windows 11, JDK 25.0.1, Maven 3.9.14, **Node v24.20.0, npm 11.19.0**, Git 2.53. PostgreSQL 16 is installed as the Windows service `postgresql-x64-16`.

| Item | Version to use | Evidence / note |
|------|----------------|-----------------|
| Spring Boot | **3.5.16** (`spring-boot-starter-parent`) | Latest 3.5.x (released 2026-06-25). 3.5 OSS support ended 2026-06-30. Decision: stay on 3.5.16 and **migrate to 4.x before Stage 5** (see roadmap). Supports Java 17 up to and including Java 25; Maven >= 3.6.3. |
| Flyway | Boot-managed | PostgreSQL needs both `flyway-core` and `flyway-database-postgresql`. Default location `classpath:db/migration`. |
| zonky `embedded-database-spring-test` | **2.8.0** (test) | Supports Spring Boot 1.4.6 to 4.0.x. Windows amd64 supported; may need the **Microsoft Visual C++ 2013 Redistributable** (x64). |
| zonky `embedded-postgres` | **2.2.2** (test) | Required for `provider = ZONKY`. |
| `io.zonky.test.postgres:embedded-postgres-binaries-bom` | **latest 16.x** (16.9.0 was the newest seen on Maven Central search; coder confirms) | Imported in `<dependencyManagement>` so tests run PostgreSQL 16, the same major as dev. |
| Vite | **8.x** (8.3.4) | Node `^20.19.0 \|\| >=22.12.0`, OK on 24.20. |
| create-vite | 9.x (9.2.1) | Node `^20.19.0 \|\| >=22.12.0`, OK. |
| @vitejs/plugin-react | 6.x (6.1.2) | Node `^20.19.0 \|\| >=22.12.0`, peer `vite ^8`, OK. |
| react / react-dom | **>= 19.2.7** (19.3.0 current) | react-router 8 peer requirement. Make sure the scaffolded versions satisfy it. |
| vite-plugin-pwa | **^2.0.0** | peer `vite ^3 ... ^8`, Node `>=20.19.0`, OK. |
| @vite-pwa/assets-generator | ^2.0.0 (dev) | Node `>=20.19.0`; uses `sharp ^0.35`. Only to generate placeholder icons. |
| react-router | **^8.4.0** | Node `>=22.22.0`, peer react/react-dom `>=19.2.7`. Declarative mode (`BrowserRouter`, `Routes`, `Route`, `MemoryRouter`) is still supported and imported from `react-router`. `react-router-dom` no longer exists in v8, so do not install it. |
| vitest | **^5.0.4** | Node `^22.12.0 \|\| ^24.0.0 \|\| >=26.0.0`, peer `vite ^6.4 \|\| ^7 \|\| ^8`. Breaking changes vs. v4 that matter here: `clearMocks` defaults to `true`; config files are not searched in parent directories (run from `frontend/`); custom matcher typings changed (`Assertion<R, T>`). |
| jsdom | **^30.1.2** | Node `^22.22.2 \|\| ^24.15.0 \|\| >=26.0.0`, OK on 24.20. |
| @testing-library/react | ^16.3.x | Node >=18; peers react 18/19, `@testing-library/dom ^10`. |
| @testing-library/dom | ^10.4.x | Node >=18. |
| @testing-library/jest-dom | **not used in Stage 0** | 7.0.1 declares `interface Assertion<T>` for vitest. That likely conflicts with Vitest 5's two-parameter `Assertion<R, T>` typing. Tests use plain Vitest assertions plus Testing Library `findBy*` queries, which throw when an element is missing. Revisit when jest-dom ships Vitest 5 typings. |

Rule for the coder: install the majors listed above. If `npm install` with `engine-strict=true` rejects a package, or a peer conflict appears, stop and report it rather than forcing (`--force` / `--legacy-peer-deps` are not allowed). Record the exact installed versions in the CHANGELOG entry.

## Requirements Checklist

### Repository
- [ ] `git init -b main` at the project root. **Do not stage or commit anything**; the user will say when to commit.
- [ ] Root `.gitignore` covers:
  - Secrets: `.env`, `.env.*`, with `!.env.example` kept
  - Runtime and build output: `uploads/`, `node_modules/`, `target/`, `dist/`, `dev-dist/`, `coverage/`
  - IDE and OS files: `.idea/`, `*.iml`, `.vscode/*` except `extensions.json`, `Thumbs.db`, `.DS_Store`
  - Logs
  - `.claude/settings.local.json`
  - **`claude-agents-setup.zip`**
- [ ] Root `.gitattributes` for stable line endings (`mvnw` / `*.sh` LF, `*.cmd` / `*.ps1` CRLF, `* text=auto`)
- [ ] `README.md` **in English** with Windows setup steps (PowerShell + Git Bash variants)
- [ ] `CHANGELOG.md` (English) created by the coder with a `## [2026-10-13]` / `### Added` entry
- [ ] No secrets committed: only `.env.example` with placeholder values

### Database (dev)
- [ ] `scripts/db/create-dev-db.sql`: idempotent creation of role `asistencia_app` (LOGIN) and database `asistencia` (owner `asistencia_app`, UTF8, `template0`). Password passed as a psql variable, never hardcoded.
- [ ] `scripts/db/create-dev-db.ps1`: prompts for the app password (`Read-Host -AsSecureString`), locates `psql.exe`, runs the SQL as `postgres`
- [ ] README documents the service check (`Get-Service postgresql-x64-16`), the winget install (for other machines) and the script run

### Backend
- [ ] Spring Boot 3.5.16 Maven project in `backend/` with the Maven wrapper (Maven 3.9.14); groupId `cr.asistencia`, artifactId `asistencia-backend`, base package `cr.asistencia`
- [ ] Starters: web, data-jpa, validation, security, actuator; plus `flyway-core`, `flyway-database-postgresql`, `org.postgresql:postgresql` (runtime)
- [ ] Compiles to Java 21 bytecode (`<java.version>21</java.version>` -> `maven.compiler.release=21`) and runs on local JDK 25
- [ ] Datasource from `DB_URL` / `DB_USER` / `DB_PASSWORD`; optional `backend/.env` import; `backend/.env.example` committed (`.env` stays in `backend/`)
- [ ] `GET /api/health` -> 200 `application/json` `{ "status": "UP", "service": "asistencia-backend", "serverTime": "<ISO-8601 instant>" }`
- [ ] Security: `/api/health`, `/actuator/health`, `/error` public. Everything else returns **401**. Stateless, CSRF disabled (token API later), no form login / HTTP basic, no generated default user.
- [ ] Flyway runs `V1__baseline.sql` (no-op) on startup; `spring.jpa.hibernate.ddl-auto=validate`, `open-in-view=false`
- [ ] Test boots full context against zonky embedded PostgreSQL 16 and asserts: health 200 + JSON body, a non-public path returns 401, Flyway V1 recorded
- [ ] `.\mvnw.cmd test` passes with no running Postgres and no `.env`

### Frontend
- [ ] Vite 8 + React (>= 19.2.7) + TypeScript scaffold in `frontend/` (template leftovers removed)
- [ ] `.npmrc` with `engine-strict=true`; `package.json` `engines.node: ">=24.15.0"` (jsdom 30's floor on the 24 line)
- [ ] vite-plugin-pwa: `registerType: 'autoUpdate'`. Manifest has name "Control de Asistencia", short_name "Asistencia", `lang: 'es-CR'`, Spanish description, theme_color `#0F5F56`, background_color `#F2F2F0`, `display: 'standalone'`, `start_url: '/'`, and 192/512/maskable icons.
- [ ] `index.html`: `lang="es"`, theme-color meta `#0F5F56`, favicon, apple-touch-icon, Spanish title and description
- [ ] Placeholder "CA" icons in `frontend/public/`
- [ ] Workbox `navigateFallbackDenylist: [/^\/api\//]` so the service worker never answers API calls with `index.html`
- [ ] react-router 8 (declarative): `/` -> employee placeholder, `/admin` -> admin placeholder, `*` -> "Página no encontrada"
- [ ] Vite `server.proxy` and `preview.proxy`: `/api` -> `http://localhost:8080`
- [ ] Placeholder page calls `/api/health` and shows loading / OK (status + server time) / error states in Spanish
- [ ] Vitest 5 (jsdom 30, Testing Library) with passing tests; `npm test` runs once (`vitest run`)
- [ ] `npm run build` succeeds (`tsc -b` + `vite build`) and emits `manifest.webmanifest` + `sw.js`

### Done criteria
- [ ] Backend starts against local PG16 (`.\mvnw.cmd spring-boot:run`) and Flyway creates `flyway_schema_history` in `asistencia`
- [ ] Frontend starts (`npm run dev`), and `http://localhost:5173/` shows the health result obtained **through the proxy**
- [ ] `http://localhost:5173/admin` renders the admin placeholder
- [ ] `.\mvnw.cmd test` and `npm test` both pass
- [ ] CHANGELOG updated
- [ ] Nothing committed (`git status` shows untracked files only)

## Implementation Steps

Execute in order. Commands are given for PowerShell, with the Git Bash equivalent where it differs.

### Step 1 - Repository root
1. `git init -b main` in `C:\Users\NestorOsvaldoQuirosV\Documents\AI_project`. **No `git add` / `git commit`.**
2. Create **`.gitignore`**:
   - Secrets: `.env`, `.env.*`, `!.env.example`
   - Runtime: `uploads/`, `*.log`, `logs/`
   - Java: `target/`
   - Node: `node_modules/`, `dist/`, `dev-dist/`, `coverage/`, `*.tsbuildinfo`
   - IDE: `.idea/`, `*.iml`, `.vscode/*`, `!.vscode/extensions.json`
   - OS: `Thumbs.db`, `.DS_Store`
   - Claude: `.claude/settings.local.json`, `claude-agents-setup.zip`
   - Do not add any rule that would ignore `backend/.mvn/wrapper/maven-wrapper.properties`.
3. Create **`.gitattributes`**: `* text=auto`, `mvnw text eol=lf`, `*.sh text eol=lf`, `*.cmd text eol=crlf`, `*.ps1 text eol=crlf`, `*.png binary`, `*.ico binary`, `*.pdf binary`, `*.zip binary`.
4. Create **`README.md`** (English) with these sections:
   - Prerequisites: JDK 21+ (tested on 25), **Node 24 LTS (>= 24.15)**, Git, PostgreSQL 16, and the VC++ 2013 x64 redistributable (needed by embedded Postgres in tests).
   - PostgreSQL:
     - If already installed, check with `Get-Service postgresql-x64-16` and start it with `Start-Service postgresql-x64-16` if stopped.
     - On a new machine, run `winget install PostgreSQL.PostgreSQL.16 --interactive` so the wizard asks for the `postgres` superuser password, and keep port 5432.
     - `psql` lives in `C:\Program Files\PostgreSQL\16\bin`.
   - Create the dev DB: `.\scripts\db\create-dev-db.ps1`, or the manual `psql` command with `-v app_password=...`.
   - Configure the backend: `Copy-Item backend\.env.example backend\.env` (Git Bash: `cp backend/.env.example backend/.env`), then edit `DB_PASSWORD`.
   - Run the backend: `cd backend; .\mvnw.cmd spring-boot:run` (Git Bash: `./mvnw spring-boot:run`). Check `http://localhost:8080/api/health`.
   - Run the frontend: `cd frontend; npm install; npm run dev`. Open `http://localhost:5173`.
   - Tests: `.\mvnw.cmd test` and `npm test` (run from `frontend/`).
   - Testing the PWA: `npm run build; npm run preview` (the service worker is disabled in dev).
   - Troubleshooting: port 5432 busy, service stopped, embedded Postgres fails on Windows (VC++ 2013), ECONNREFUSED in the proxy, wrong Node version (`engine-strict`).
   - Project layout and a link to `docs/plans/00-roadmap.md`.
5. Create **`CHANGELOG.md`** (English) with `## [2026-10-13]` and `### Added`, listing the Stage 0 items and the installed versions. The coder writes this after implementing.

### Step 2 - Dev database scripts
6. **`scripts/db/create-dev-db.sql`** (run as `postgres`, with `-v app_password=...`):
   - Fail fast if `app_password` is not set: `\if :{?app_password} ... \else \echo ... \quit \endif`.
   - Create role `asistencia_app` with `LOGIN PASSWORD :'app_password'`, only if it does not exist. Use a `SELECT ... WHERE NOT EXISTS (...) \gexec` pattern, because `CREATE ROLE IF NOT EXISTS` does not exist.
   - Create database `asistencia` with `OWNER asistencia_app ENCODING 'UTF8' TEMPLATE template0`, only if it does not exist, using the `\gexec` pattern.
   - Comment: in PG15+ the `public` schema is owned by `pg_database_owner`, so the DB owner can create tables and no extra GRANT is needed.
   - Contains no real password.
7. **`scripts/db/create-dev-db.ps1`**:
   - Optional params: `-PsqlPath` (default `C:\Program Files\PostgreSQL\16\bin\psql.exe`, falling back to `psql` on PATH), `-DbHost localhost`, `-Port 5432`.
   - Prompts for the app password with `Read-Host -AsSecureString`.
   - Runs `psql -U postgres -h ... -p ... -v ON_ERROR_STOP=1 -v app_password=... -f create-dev-db.sql`. psql itself prompts for the `postgres` password.
   - Prints the `DB_URL` / `DB_USER` lines for `backend/.env`, but **not** the password.
   - Sets `$ErrorActionPreference = 'Stop'` and exits non-zero on psql failure.

### Step 3 - Backend skeleton (`backend/`)
Write the POM by hand rather than through start.spring.io, which may no longer offer 3.5.x.

8. **`backend/pom.xml`**:
   - Parent `org.springframework.boot:spring-boot-starter-parent:3.5.16`
   - `groupId cr.asistencia`, `artifactId asistencia-backend`, `version 0.0.1-SNAPSHOT`
   - `<java.version>21</java.version>`
   - Dependencies:
     - `spring-boot-starter-web`, `-data-jpa`, `-validation`, `-security`, `-actuator`
     - `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`
     - `org.postgresql:postgresql` (runtime)
     - Test scope: `spring-boot-starter-test`, `spring-security-test`, `io.zonky.test:embedded-database-spring-test:2.8.0`, `io.zonky.test:embedded-postgres:2.2.2`
   - `<dependencyManagement>`: import `io.zonky.test.postgres:embedded-postgres-binaries-bom:<latest 16.x>` (type `pom`, scope `import`)
   - Plugin `spring-boot-maven-plugin`
   - Put the zonky versions in `<properties>`
9. **Maven wrapper**: from `backend/`, run `mvn -N wrapper:wrapper "-Dmaven=3.9.14"`. This produces **`backend/mvnw`**, **`backend/mvnw.cmd`** and **`backend/.mvn/wrapper/maven-wrapper.properties`**. Do not hand-write these files.
10. **`backend/.env.example`**:
    ```
    # Copy to backend/.env (gitignored). Plain KEY=value, no quotes, no 'export'.
    DB_URL=jdbc:postgresql://localhost:5432/asistencia
    DB_USER=asistencia_app
    DB_PASSWORD=change-me
    ```
11. **`backend/src/main/resources/application.yml`**:
    - `spring.application.name: asistencia-backend`
    - `spring.config.import: optional:file:.env[.properties]` (relative to the working dir `backend/`)
    - `spring.datasource.url: ${DB_URL:jdbc:postgresql://localhost:5432/asistencia}`, `username: ${DB_USER:asistencia_app}`, `password: ${DB_PASSWORD:}`
    - `spring.jpa.hibernate.ddl-auto: validate`, `spring.jpa.open-in-view: false`, `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`
    - `spring.flyway.enabled: true`
    - `server.port: 8080`, `server.error.include-stacktrace: never`
    - `management.endpoints.web.exposure.include: health`, `management.endpoint.health.show-details: never`
12. **`backend/src/main/resources/db/migration/V1__baseline.sql`**: comment header ("Baseline - schema starts in V2 (Stage 1)") plus `SELECT 1;`.
13. **`backend/src/main/java/cr/asistencia/AsistenciaApplication.java`**: `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)` plus `main`.
14. **`backend/src/main/java/cr/asistencia/config/SecurityConfig.java`**: `@Configuration` + `@EnableWebSecurity` with one `SecurityFilterChain` bean:
    - `csrf.disable()`
    - `sessionManagement` STATELESS
    - `httpBasic.disable()`, `formLogin.disable()`, `logout.disable()`
    - `authorizeHttpRequests`: `GET /api/health`, `GET /actuator/health`, `/error` -> `permitAll()`; `anyRequest().authenticated()`
    - `exceptionHandling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))`
    - Comment: the JWT filter arrives in Stage 1.
15. **`backend/src/main/java/cr/asistencia/health/HealthResponse.java`**: `record HealthResponse(String status, String service, Instant serverTime)`.
16. **`backend/src/main/java/cr/asistencia/health/HealthController.java`**: `@RestController @RequestMapping("/api")`, `@GetMapping("/health")` returning `HealthResponse("UP", "asistencia-backend", Instant.now(clock))`, with the `Clock` injected.
17. **`backend/src/main/java/cr/asistencia/config/TimeConfig.java`**: `@Bean Clock clock()` returning `Clock.systemUTC()`.
18. **`backend/src/test/java/cr/asistencia/health/HealthEndpointTest.java`**:
    - `@SpringBootTest`, `@AutoConfigureMockMvc`, `@AutoConfigureEmbeddedDatabase(type = POSTGRES, provider = ZONKY)`
    - Tests:
      - `healthIsPublicAndReturnsJson`: GET `/api/health` -> 200, JSON, `$.status == "UP"`, `$.serverTime` exists.
      - `otherEndpointsRequireAuthentication`: GET `/api/anything` -> 401.
      - `flywayBaselineApplied`: `JdbcTemplate` count of `flyway_schema_history` rows with `version = '1' and success` == 1.
    - Must pass with no local Postgres running and no `backend/.env`.

### Step 4 - Frontend skeleton (`frontend/`)
19. From the root (Node 24.20): `npm create vite@latest frontend -- --template react-ts`. Then `cd frontend; npm install`. Confirm `react` / `react-dom` resolve to >= 19.2.7; if not, `npm install react@^19.2.7 react-dom@^19.2.7` (and matching `@types/react*`).
20. Delete template leftovers: `src/App.css`, `src/assets/react.svg`, `public/vite.svg`, and the counter demo. Keep the template's `eslint.config.js`, `tsconfig.json`, `tsconfig.app.json` and `tsconfig.node.json`.
21. Install dependencies, using current majors:
    - `npm install react-router@^8.4.0`
    - `npm install -D vite-plugin-pwa@^2.0.0 @vite-pwa/assets-generator@^2.0.0 vitest@^5.0.4 jsdom@^30.1.2 @testing-library/react@^16.3.0 @testing-library/dom@^10.4.0`
    - Do **not** install `react-router-dom` (removed in v8) or `@testing-library/jest-dom` (see Verified versions).
22. **`frontend/.npmrc`**: `engine-strict=true`.
23. **`frontend/package.json`** (modify the generated file):
    - `"engines": { "node": ">=24.15.0" }`
    - scripts: keep `dev`, `build` (`tsc -b && vite build`), `preview` and `lint`. Add `"test": "vitest run"`, `"test:watch": "vitest"` and `"generate-pwa-assets": "pwa-assets-generator"`.
24. **`frontend/public/logo.svg`**: placeholder "CA" mark (white "CA" on a `#0F5F56` rounded square).
25. **`frontend/pwa-assets.config.ts`**: `defineConfig({ preset: minimal2023Preset, images: ['public/logo.svg'] })`. Run `npm run generate-pwa-assets` once to produce:
    - **`public/favicon.ico`**
    - **`public/pwa-64x64.png`**
    - **`public/pwa-192x192.png`**
    - **`public/pwa-512x512.png`**
    - **`public/maskable-icon-512x512.png`**
    - **`public/apple-touch-icon-180x180.png`**
26. **`frontend/vite.config.ts`**: use `defineConfig` from `vitest/config`, with these plugins and settings:
    - `react()`
    - `VitePWA({ ... })` with:
      - `registerType: 'autoUpdate'`
      - `includeAssets: ['favicon.ico', 'apple-touch-icon-180x180.png', 'logo.svg']`
      - `manifest`:
        - `name: 'Control de Asistencia'`, `short_name: 'Asistencia'`
        - `description: 'Marcas de entrada y salida del personal'`
        - `lang: 'es-CR'`
        - `theme_color: '#0F5F56'`, `background_color: '#F2F2F0'`
        - `display: 'standalone'`, `start_url: '/'`, `scope: '/'`
        - `icons`: 64, 192, 512 (`any`), 512 maskable (`maskable`)
      - `workbox: { navigateFallbackDenylist: [/^\/api\//] }`
      - `devOptions: { enabled: false }`
    - `server: { port: 5173, strictPort: true, proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } }`
    - `preview: { proxy: <same> }`
    - `test: { environment: 'jsdom', globals: false, setupFiles: ['./src/test/setup.ts'], css: false }`. Vitest 5 already defaults `clearMocks` to `true`; do not rely on mock call history from setup files.
27. **`frontend/index.html`**:
    - `<html lang="es">`, `<title>Control de Asistencia</title>`
    - meta description
    - `<meta name="theme-color" content="#0F5F56">`
    - `<link rel="icon" href="/favicon.ico" sizes="48x48">`, `<link rel="icon" href="/logo.svg" type="image/svg+xml">`
    - `<link rel="apple-touch-icon" href="/apple-touch-icon-180x180.png">`
28. **`frontend/src/vite-env.d.ts`** (modify): add `/// <reference types="vite-plugin-pwa/client" />`.
29. **`frontend/tsconfig.app.json`** (modify only if needed) so `src/test/setup.ts` and `*.test.tsx` type-check with `vitest` imports.
30. **`frontend/src/api/health.ts`**:
    - `export type HealthResponse = { status: string; service: string; serverTime: string }`
    - `export async function fetchHealth(signal?: AbortSignal): Promise<HealthResponse>`: throw on non-`ok` with the HTTP status; check that `content-type` includes `json`.
31. **`frontend/src/components/HealthStatus.tsx`**:
    - Runs `fetchHealth` in a `useEffect` with an `AbortController` cleanup.
    - States: `loading` ("Verificando servidor..."), `ok` ("Servidor en línea" + server time formatted with `toLocaleString('es-CR', { timeZone: 'America/Costa_Rica' })`), `error` ("No se pudo contactar al servidor" + message).
    - Uses `role="status"` / `aria-live="polite"`.
32. **`frontend/src/pages/EmployeeHome.tsx`**: heading "Control de Asistencia", subtitle "Pantalla del empleado (en construcción)", `<HealthStatus />`, link to `/admin`.
33. **`frontend/src/pages/AdminHome.tsx`**: heading "Panel del administrador", "En construcción", `<HealthStatus />`, link to `/`.
34. **`frontend/src/pages/NotFound.tsx`**: "Página no encontrada" + link to `/`.
35. **`frontend/src/App.tsx`** (replace): `<Routes>` (from `react-router`) with `/` -> `EmployeeHome`, `/admin` -> `AdminHome`, `*` -> `NotFound`.
36. **`frontend/src/main.tsx`** (modify): wrap `<App />` in `<BrowserRouter>` imported from `react-router`, inside `StrictMode`.
37. **`frontend/src/index.css`** (replace): minimal base styles (system font stack, `#F2F2F0` background, `#1C1F1E` text, `#0F5F56` links).
38. **`frontend/src/test/setup.ts`**: `import { afterEach } from 'vitest'; import { cleanup } from '@testing-library/react'; afterEach(() => { cleanup(); vi.unstubAllGlobals(); })`. Import `vi` from `vitest` too. No jest-dom.
39. **`frontend/src/components/HealthStatus.test.tsx`**:
    - Case 1: stub `fetch` with `vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({...}), { headers: { 'content-type': 'application/json' } })))`. Render `<HealthStatus />` and `await screen.findByText(/Servidor en línea/)`. `findBy*` throws if the element is absent, so no jest-dom matcher is needed. Also `expect(fetch).toHaveBeenCalledWith('/api/health', expect.anything())`.
    - Case 2: a rejected fetch shows "No se pudo contactar al servidor".
40. **`frontend/src/App.test.tsx`**: renders `<MemoryRouter initialEntries={['/admin']}><App/></MemoryRouter>` (`MemoryRouter` from `react-router`) with `fetch` stubbed, and asserts the "Panel del administrador" heading via `findByRole('heading', ...)`.

### Step 5 - Verification (coder runs, tester re-runs)
41. `cd backend; .\mvnw.cmd test` -> green (embedded PG16, no Docker).
42. With `postgresql-x64-16` running, the DB script run and `backend/.env` filled: `.\mvnw.cmd spring-boot:run`. Check:
    - The log shows Flyway "Successfully applied 1 migration".
    - `curl.exe http://localhost:8080/api/health` -> 200 JSON.
    - `curl.exe -i http://localhost:8080/api/x` -> 401.
43. `cd frontend; npm test` -> green; `npm run lint` clean; `npm run build` emits `dist/manifest.webmanifest` and `dist/sw.js`.
44. `npm run dev`, open `http://localhost:5173/` -> "Servidor en línea" via the proxy. Open `/admin` -> admin placeholder.
45. Update `CHANGELOG.md`, then tick this checklist. Run `git status` to confirm nothing is committed and that `.env`, `claude-agents-setup.zip`, `node_modules/` and `target/` are not listed.

### Files the coder will create or modify

Root:
- `.gitignore` (new)
- `.gitattributes` (new)
- `README.md` (new)
- `CHANGELOG.md` (new)
- `scripts/db/create-dev-db.sql` (new)
- `scripts/db/create-dev-db.ps1` (new)

Backend:
- `backend/pom.xml` (new)
- `backend/mvnw` (generated by wrapper plugin)
- `backend/mvnw.cmd` (generated)
- `backend/.mvn/wrapper/maven-wrapper.properties` (generated)
- `backend/.env.example` (new)
- `backend/src/main/resources/application.yml` (new)
- `backend/src/main/resources/db/migration/V1__baseline.sql` (new)
- `backend/src/main/java/cr/asistencia/AsistenciaApplication.java` (new)
- `backend/src/main/java/cr/asistencia/config/SecurityConfig.java` (new)
- `backend/src/main/java/cr/asistencia/config/TimeConfig.java` (new)
- `backend/src/main/java/cr/asistencia/health/HealthController.java` (new)
- `backend/src/main/java/cr/asistencia/health/HealthResponse.java` (new)
- `backend/src/test/java/cr/asistencia/health/HealthEndpointTest.java` (new)

Frontend, generated by create-vite and kept as-is:
- `frontend/eslint.config.js`
- `frontend/tsconfig.json`
- `frontend/tsconfig.node.json`
- `frontend/package-lock.json`

Frontend, created or modified by the coder:
- `frontend/package.json` (generated, then modified)
- `frontend/tsconfig.app.json` (generated, modify if needed)
- `frontend/.npmrc` (new)
- `frontend/index.html` (generated, then modified)
- `frontend/vite.config.ts` (generated, then replaced)
- `frontend/pwa-assets.config.ts` (new)
- `frontend/public/logo.svg` (new)
- `frontend/public/favicon.ico` (generated by assets-generator)
- `frontend/public/pwa-64x64.png` (generated)
- `frontend/public/pwa-192x192.png` (generated)
- `frontend/public/pwa-512x512.png` (generated)
- `frontend/public/maskable-icon-512x512.png` (generated)
- `frontend/public/apple-touch-icon-180x180.png` (generated)
- `frontend/src/vite-env.d.ts` (modified)
- `frontend/src/main.tsx` (modified)
- `frontend/src/App.tsx` (replaced)
- `frontend/src/index.css` (replaced)
- `frontend/src/api/health.ts` (new)
- `frontend/src/components/HealthStatus.tsx` (new)
- `frontend/src/components/HealthStatus.test.tsx` (new)
- `frontend/src/pages/EmployeeHome.tsx` (new)
- `frontend/src/pages/AdminHome.tsx` (new)
- `frontend/src/pages/NotFound.tsx` (new)
- `frontend/src/test/setup.ts` (new)
- `frontend/src/App.test.tsx` (new)

Deleted from the template:
- `frontend/src/App.css`
- `frontend/src/assets/react.svg`
- `frontend/public/vite.svg`

## Edge Cases to Handle

- **Node version drift.** `engines.node >=24.15.0` + `engine-strict=true` make `npm install` fail fast on an older Node, for example another developer still on Node 20. The README states Node 24 LTS.
- **Vitest 5 changes.** `clearMocks` defaults to `true`. Config is not discovered from parent dirs, so always run `npm test` from `frontend/`. jest-dom typings may conflict, so they are not used in Stage 0.
- **react-router 8.** There is no `react-router-dom` package, and it needs React >= 19.2.7. An older scaffolded React would produce a peer conflict; upgrade React rather than forcing.
- **Spring Boot 3.5 is out of OSS support.** 3.5.16 is the last free patch. Accepted for development; migrating to 4.x is a prerequisite of Stage 5 (roadmap).
- **JDK 25 running Java 21 bytecode.** Use `release=21`. Mockito/Byte Buddy "dynamic agent loading" warnings on JDK 25 are harmless.
- **Embedded Postgres on Windows.** Needs the MSVC++ **2013** x64 redistributable. Antivirus can slow extraction into `%TEMP%`. The first run downloads binaries.
- **Missing or wrong `.env`.** Values are read literally (no quotes or `export`). The file is only found when the app runs from `backend/`. Real environment variables override it.
- **No DB password configured.** The app fails at startup with a PG authentication error. Do not add a silent fallback.
- **PostgreSQL service stopped or port 5432 taken.** Check with `Get-Service postgresql-x64-16` / `Start-Service postgresql-x64-16` / `netstat -ano | findstr 5432`.
- **psql not on PATH.** The PS1 script uses the default install path and accepts `-PsqlPath`.
- **Lost `postgres` superuser password.** The README briefly notes how to reset it via `pg_hba.conf`.
- **Re-running the DB script.** It is idempotent. It does not change an existing role's password; the script echoes that.
- **Database encoding on Windows.** `TEMPLATE template0 ENCODING 'UTF8'`, with no forced locale.
- **Security status codes.** Use `HttpStatusEntryPoint(401)`. `/error` must be permitted, otherwise real 404s and 500s turn into 401.
- **Service worker caching API calls.** `navigateFallbackDenylist` for `/api/`, and the dev SW disabled. Test the PWA via `npm run preview`.
- **Proxy resolves `localhost` to IPv6.** If ECONNREFUSED appears, switch the target to `http://127.0.0.1:8080`.
- **Backend down.** `HealthStatus` shows the error state. The content-type check avoids a JSON parse crash.
- **Line endings.** `.gitattributes` keeps `mvnw` LF and `.cmd`/`.ps1` CRLF.
- **PowerShell execution policy.** The README shows `powershell -ExecutionPolicy Bypass -File scripts\db\create-dev-db.ps1`.
- **Accidental commit.** The coder must not stage or commit. Before the user's first commit, `git status` must not list `.env`, `claude-agents-setup.zip` or `.claude/settings.local.json`.
- **Timezone.** The backend returns a UTC `Instant`. The frontend formats it in `America/Costa_Rica`.

## Open Questions (RESOLVED 2026-10-13)

1. **Spring Boot version.** Resolved: stay on **3.5.16** (option a). Migrating to Spring Boot 4.x is required before Stage 5 and is recorded in the roadmap.
2. **Node version.** Resolved: the user installed **Node 24 LTS** (v24.20.0, npm 11.19.0). The Node 20 pins are dropped. The plan now uses react-router 8, vitest 5 and jsdom 30, with `engines.node >=24.15.0` and `engine-strict=true`.
3. **Java package and Maven coordinates.** Resolved: base package `cr.asistencia`, groupId `cr.asistencia`, artifactId `asistencia-backend`.
4. **Branding.** Resolved: defaults accepted. Theme `#0F5F56`, background `#F2F2F0`, short_name "Asistencia", "CA" placeholder logo.
5. **`.env` location.** Resolved: `backend/.env` (template `backend/.env.example`).
6. **README language.** Resolved: README and docs in **English**. The UI stays in Spanish.
7. **Git.** Resolved: run `git init` but **make no commit**; the user will say when. `claude-agents-setup.zip` is gitignored.
8. **(Info) PostgreSQL.** PostgreSQL 16 is already installed natively as the Windows service `postgresql-x64-16`. The coder skips installation and only runs the DB creation script.
