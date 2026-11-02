# Stage 4 - Admin panel: UI/UX design spec

Status: DESIGNED 2026-10-28. Input to the `coder`. Plan: `docs/plans/stage-4-admin-panel.md` (read with its section "Open Questions: RESOLVED (2026-10-28, user)", which wins over earlier text). Blueprint: `docs/prototype/Prototipo-Control-Asistencia.pdf`, page 2 ("Panel del administrador"). **The PDF could not be rendered in this sandbox (`pdftoppm` is missing), so the layout follows the plan's textual description of the mockup** (date picker, 4 KPI cards, employee table with Entrada / Salida / Horas / Evidencia / Estado, "Exportar a Excel"). Compare the final visual against the PDF once and adjust spacing only.

Scope: spec only. No component or CSS file was written. The token block in section 1.9 is ready to paste at the end of `:root` in `frontend/src/index.css` (or into `admin.css`, see 8.3). Nothing outside `docs/design/` was changed.

Existing stack found in discovery (all reused, nothing new introduced):
- React 19, react-router 8, Vite 8, plain CSS in `frontend/src/index.css` (no Tailwind, no UI library), CSS custom properties already defined (Stage 3 section 1.8 is live in `:root`).
- Reusable components: `ModalDialog` (native `<dialog>`, `closeOnBackdrop` optional, Esc routed to `onCancel`, `data-autofocus`), `ConfirmDialog` (default / destructive tone, initial focus on cancel), `ErrorBox` (`role="alert"`, `tabindex=-1`), `Icon` (inline SVG; has `circle, dot-circle, check-circle, check, alert-triangle, x-circle, info, hourglass, map-pin, map-pin-off, camera, camera-off, wifi-off, refresh, log-in, log-out, trash, backspace, spinner`), `PinKeypad` / `PinDots`, `ReauthDialog`, `LogoutConfirm`, `LanguageSwitcher`, `Announcer`, `HealthStatus`, `.button--primary|secondary|destructive|link`, `.chip(--ok|--warn)`, `.notice(--warn|--error|--info)`, `.field`, `.modal`.
- Typed flat i18n (`'home.status.notYet'` style); `en` must have exactly the keys of `es`; `{name}` placeholders; plurals as `.one` / `.other`.

## Contents

1. Design tokens
2. Layout and responsive rules
3. Screen specs (3.1 shell, 3.2 re-auth, 3.3 dashboard, 3.4 evidence dialog, 3.5 review queue, 3.6 review action dialog, 3.7 create-entry dialog, 3.8 employees list, 3.9 employee detail, 3.10 PIN reveal, 3.11 branches, 3.12 confirm dialogs and guards, 3.13 export)
4. Component inventory
5. i18n (register, Stage 3 "tú" to "usted" table, admin key tables, error mapping, formats)
6. Accessibility notes
7. Motion and feedback
8. Conflicts with the plan and proposed resolutions (coder must read)

---

## 1. Design tokens

### 1.1 Reused tokens (no change)

All Stage 3 tokens stay as they are: `--color-bg #F2F2F0`, `--color-surface #FFF`, `--color-text #1C1F1E`, `--color-text-muted #4A5250`, `--color-primary #0F5F56`, `--color-primary-pressed #0B4A43`, `--color-info-bg #E6F0EE`, `--color-border #767B79`, error / warning / success pairs, `--color-focus #1C1F1E`, spacing `--space-1..8`, radii, `--target-min 44px`, `--target-input 48px`. Their contrast ratios are in Stage 3 section 1.1 and still hold. The admin panel adds only what a desktop table UI needs (below).

### 1.2 Day-status chip palette (mockup: teal, amber, violet, red-brown, grey) with measured contrast

Ratios were computed with the WCAG 2.2 relative-luminance formula (script `c4.mjs`, `d4.mjs` in the session scratchpad). Chip text is 14 px / 600, so the threshold is 4.5:1. Chip border is a UI boundary, threshold 3:1 against the surface it sits on (white table row or white card).

| Family | Text | Background | Border | Text / bg | Border / bg | Border / white | Border / page `#F2F2F0` |
|---|---|---|---|---|---|---|---|
| teal | `#0B4A43` | `#DDF0EB` | `#0F5F56` | **8.55** | 6.35 | 7.52 | 6.71 |
| amber | `#6B4000` | `#FFF0C2` | `#8F5C00` | **7.84** | 5.00 | 5.68 | 5.07 |
| violet | `#4A2A8C` | `#ECE6F8` | `#6A4CB0` | **8.62** | 5.27 | 6.42 | 5.72 |
| red-brown | `#7D2A14` | `#F8E3DB` | `#A4452A` | **7.68** | 4.91 | 6.06 | 5.41 |
| grey | `#3F4644` | `#E4E5E2` | `#767B79` | **7.65** | 3.40 | 4.30 | 3.84 |

All 15 text / background and all 15 border pairs pass. Other measured pairs used by the admin UI:

| Pair | Ratio |
|---|---|
| `#6B4000` (late line "Tarde 12 min") on white | 8.90 |
| `#4A5250` muted on `#EEF4F2` (row hover) | 7.21 |
| `#1C1F1E` on `#EEF4F2` (row hover) | 14.92 |
| `#A32020` on `#EEF4F2` | 6.77 |
| `#FFFFFF` on `#A32020` (nav badge) | 7.54 |
| `#FFFFFF` on `#0F5F56` (active nav item) | 7.52 |
| `#FFFFFF` on `#0B4A43` (pressed) | 10.12 |
| `#1C1F1E` on `#E6F0EE` (table header, PIN panel) | 14.29 |
| KPI accent borders on white: teal 7.52, amber (`#9A6200`) 5.10, violet 6.42, red-brown 6.06 | pass (>= 3:1) |
| `#9A6200` warning border on page `#F2F2F0` | 4.55 |

Row hover `#EEF4F2` against white is 1.11:1 on purpose: hover is decoration only, never the only signal of anything (rows are not selectable).

### 1.3 DayStatus to chip (11 values) - colour is never the only signal

Every chip has text, an icon with its own silhouette, and a family colour. `REJECTED` and `NOT_CLOCKED` share the red-brown family and are told apart by icon and words.

| `DayStatus` | Family | Icon (`Icon` name; **new** marked *) | ES text | EN text |
|---|---|---|---|---|
| `COMPLETE` | teal | `check-circle` | Completo | Complete |
| `ON_TIME` | teal | `dot-circle` | A tiempo | On time |
| `LATE` | amber | `clock`* | Tarde {minutes} min | Late {minutes} min |
| `OUTSIDE_AREA` | violet | `map-pin-off` | Fuera del área | Outside the area |
| `NEEDS_REVIEW` | violet | `eye`* | Por revisar | Needs review |
| `NOT_CLOCKED` | red-brown | `minus-circle`* | Sin marcar | Not clocked |
| `MISSING_CLOCK_OUT` | red-brown | `clock-alert`* | Sin salida | No clock-out |
| `REJECTED` | red-brown | `x-circle` | Rechazada | Rejected |
| `UNSCHEDULED` | grey | `help-circle`* | Sin horario | Unscheduled |
| `NOT_YET_DUE` | grey | `hourglass` | Aún no inicia | Not started yet |
| `DAY_OFF` | grey | `calendar-off`* | Libre | Day off |

Review-status chips (entry level, used in the queue, evidence dialog and history): `VALID` teal + `check-circle` "Válida"; `PENDING_REVIEW` violet + `eye` "En revisión"; `REJECTED` red-brown + `x-circle` "Rechazada" (existing `status.*` keys). Special chips: "Autoaprobado" amber + `alert-triangle`; "Registro manual" grey + `pencil`*; "Inactivo" grey; "Bloqueado hasta {time}" red-brown + `lock`*; "Activo" teal + `check-circle`.

New icons (inline SVG, 2 px stroke, `currentColor`, about 1 KB gzip together, part of the admin chunk): `clock`, `clock-alert`, `eye`, `minus-circle`, `help-circle`, `calendar-off`, `pencil`, `lock`, `external-link`, `users`, `building`, `file-spreadsheet`, `layout-dashboard`, `clipboard-check`, `copy`, `chevron-left`, `chevron-right`. Add them to a separate `src/admin/AdminIcon.tsx` path map (same `<Icon>` renderer) so the employee bundle does not grow.

### 1.4 Desktop type scale and density

System font stack stays (no web fonts: CSP `font-src 'self'`, bundle budget). `rem` only; no fixed heights on text containers; text must survive 200 % zoom and WCAG 1.4.12 spacing overrides. Minimum text size is 14 px.

| Token | Size | Weight | Use |
|---|---|---|---|
| `--fs-h1-admin` | 1.75rem (28 px) | 700 | page title (>= 1024 px; 1.5rem below) |
| `--fs-h2` | 1.25rem | 700 | section titles, dialog titles |
| `--fs-h3` | 1.0625rem (17 px) | 700 | card titles in the queue |
| `--fs-kpi` | 2rem (32 px) | 700 | KPI value, tabular numerals |
| `--fs-table` | 0.9375rem (15 px) | 400 / 600 | table body |
| `--fs-body` | 1rem | 400 | forms, dialogs |
| `--fs-small` | 0.875rem (14 px) | 400 / 600 | captions, chips, table header, hints |

Density tokens: `--row-min-h: 3.5rem` (56 px, two-line rows), `--cell-pad-y: 0.75rem`, `--cell-pad-x: 1rem`, `--sidebar-w: 15rem`, `--content-max: 80rem` (1280 px), `--page-pad: 1.5rem` (1rem below 768 px), `--photo-max-h: 30rem` (480 px).

Sticky table header is **not** used (a sticky row can obscure the focused cell: WCAG 2.4.11).

### 1.5 Target sizes

| Element | Fine pointer, >= 1024 px | Coarse pointer or < 1024 px |
|---|---|---|
| Buttons, links acting as buttons, selects, checkboxes (hit area) | **min 36 px** high (desktop density; above the WCAG 2.5.8 AA minimum of 24 px) | **44 px** |
| Text / date / time inputs | 40 px | 48 px |
| Table row action buttons ("Ver evidencia", "Registrar salida") | 36 px | 44 px |
| Dialog primary / secondary buttons | 44 px (always) | 44 px |
| Nav items | 44 px (always) | 44 px |

Implemented with one media query `@media (pointer: coarse), (max-width: 63.9375rem)` that sets `--target-ctl: 44px`, default `--target-ctl: 36px`. Adjacent targets keep an 8 px gap. This is a documented exception to the Stage 3 "44 px everywhere" rule (section 8.9).

### 1.6 Elevation, radii, borders

Same as Stage 3: borders, no card shadows; one dialog shadow. Cards and the table container: `1px solid var(--color-border)`... **except** decorative separators (row dividers) which use `#C9CECC` (1.59:1 on white, decorative only because rows are separated by content and padding too). Table container radius `--radius-md`, KPI card `--radius-lg`.

### 1.7 Focus ring and keyboard

Unchanged: `outline: 3px solid var(--color-focus); outline-offset: 2px` on `:focus-visible`, and the `[tabindex='-1']:focus` rule for programmatic targets (h1 after navigation, dialog titles). Active nav item is teal-filled on a white sidebar, so the ring (drawn outside) is measured against white (16.61:1). Table cells that contain a button show the ring on the button, not on the cell. `scroll-margin-block: 1rem` on rows and cards so a focused control is never hidden under the browser edge.

### 1.8 Forced colours, reduced motion, colour scheme

As Stage 3 (1.7): `color-scheme: light`; in `forced-colors: active` chips, cards, KPI cards, table container and notices get `border: 1px solid ButtonText` and icons use `currentColor`; `prefers-reduced-motion: reduce` removes spinner rotation (static icon plus text), dialog transitions and press transforms. Dark mode is out of scope.

### 1.9 CSS custom properties (paste into `:root`)

```css
:root {
  /* chip families: text / bg / border (all pairs measured in 1.2) */
  --chip-teal-fg: #0b4a43;     --chip-teal-bg: #ddf0eb;     --chip-teal-bd: #0f5f56;
  --chip-amber-fg: #6b4000;    --chip-amber-bg: #fff0c2;    --chip-amber-bd: #8f5c00;
  --chip-violet-fg: #4a2a8c;   --chip-violet-bg: #ece6f8;   --chip-violet-bd: #6a4cb0;
  --chip-redbrown-fg: #7d2a14; --chip-redbrown-bg: #f8e3db; --chip-redbrown-bd: #a4452a;
  --chip-grey-fg: #3f4644;     --chip-grey-bg: #e4e5e2;     --chip-grey-bd: #767b79;
  --color-row-hover: #eef4f2;  --color-divider: #c9cecc;
  --fs-h1-admin: 1.75rem; --fs-h3: 1.0625rem; --fs-kpi: 2rem; --fs-table: 0.9375rem;
  --row-min-h: 3.5rem; --cell-pad-y: 0.75rem; --cell-pad-x: 1rem;
  --sidebar-w: 15rem; --content-max: 80rem; --page-pad: 1.5rem; --photo-max-h: 30rem;
  --target-ctl: 36px;
}
@media (pointer: coarse), (max-width: 63.9375rem) {
  :root { --target-ctl: 44px; }
}
```

---

## 2. Layout and responsive rules

Desktop first. Designed at 1280 px, must work from 768 px (tablet) and stay usable down to 320 px (stacked, not optimized).

| Viewport | Shell | Dashboard | Dialogs |
|---|---|---|---|
| **>= 1024 px** | Header on top; left sidebar nav `--sidebar-w`; content column max `--content-max`, padding `--page-pad` | KPI cards 4 across; full table (6 columns) | Centered cards: evidence 56rem (two columns), action dialogs 32rem, PIN 28rem |
| **768 - 1023 px** | Header; **top tab bar** replaces the sidebar (links, one row, wraps if needed); content full width | KPI cards 2 x 2; full table, tighter padding (`--cell-pad-x: .75rem`) | Evidence 48rem two columns; others as above |
| **< 768 px** | Header wraps (brand row, then actions row); nav wraps into two rows of links | KPI cards 2 across (1 across below 30rem); **table becomes a stacked list** (section 3.3.6) | Full screen (`100dvh`), single column |

Rules:
- `main.admin-main` overrides the global `main { max-width: 40rem }` (see Stage 3 section 8.9). It has `max-width: var(--content-max)`, `padding: var(--page-pad)`, flex column, gap `--space-5`.
- No horizontal scroll of the page at 320 px or at 400 % zoom. The employees table (5 columns) and branches table become stacked lists below 768 px in the same way as the dashboard table. The schedule grid (section 3.9.3) becomes one card per weekday below 768 px.
- Long names and codes wrap (`overflow-wrap: anywhere`); table cells never truncate with ellipsis (the information would be lost for screen magnifier users).
- DOM and tab order of every page: skip link, header, nav, `main` (h1, toolbar, content).
- Landmarks per page: `header` (banner), `nav aria-label`, one `main`, `footer` (server status) as `contentinfo`.

Vertical structure of the shell at >= 1024 px:

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ [Saltar al contenido]  (visible on focus)                                                  │
├───────────────────────────────────────────────────────────────────────────────────────────┤
│ Control de Asistencia · DEMO              [↩ Mi marca] [Español|English] [⇥ Cerrar sesión] │  header
├───────────────┬───────────────────────────────────────────────────────────────────────────┤
│ ▣ Asistencia  │  h1 …                                                                       │
│ ✓ Revisión (3)│  toolbar                                                                    │
│ ☺ Empleados   │  content                                                                    │
│ ⌂ Sucursales  │                                                                             │
│ ▤ Reportes    │                                                                             │
│               │                                                                             │
│ ● Servidor en │                                                                             │
│   línea       │                                                                             │
└───────────────┴───────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Screen specs

Wireframe conventions: `[ ]` button, `( )` chip, `▾` select, `☐/☑` checkbox, `◉/○` radio, `…` omitted content. Text is Spanish ("usted"); every string exists in EN (section 5).

### 3.1 Admin shell (`AdminApp`, `AdminLayout`, `AdminNav`)

**Header** (`<header>`): brand text "Control de Asistencia" and the company name (from the dashboard response / identity; "—" until loaded); right group: **"Mi marca"** link (to `/`, icon `log-in`), `LanguageSwitcher` (unchanged), **"Cerrar sesión"** button (existing `header-button`, opens `LogoutConfirm`, which already warns about the admin's own unsent clock-ins). The branch filter is **not** in the header (section 8.4).

**Nav** (`<nav aria-label="Navegación principal">`, `<ul>` of links, `aria-current="page"` on the active one): Asistencia (dashboard), Revisión (with count badge), Empleados, Sucursales, Reportes. In 4a only Asistencia and Revisión are rendered; Empleados and Sucursales appear with 4b, Reportes with 4c (section 8.7). Each item: 20 px icon + text, 44 px high, active item teal fill + white text + bold (not colour alone: also the `aria-current` and bold weight).

**Review badge**: a pill after the label with the number (`99+` above 99), error colour `#A32020` with white bold text (7.54:1). The accessible text is a visually hidden suffix ("3 pendientes") so the link reads "Revisión, 3 pendientes". Zero hides the badge. Count comes from `GET /api/admin/review-queue/count`, loaded on shell mount, on `visibilitychange`, after any review action, and every 60 s while visible. Count changes are **not** announced by the badge (no live region on the nav); the page that caused the change announces it.

**Skip link**: first focusable element, visually hidden until focused, text "Saltar al contenido", targets `<main id="main" tabindex="-1">`.

**Footer**: `HealthStatus` in a `<footer>`.

**Document title**: each page sets `document.title = "{page} · Panel del administrador"` (WCAG 2.4.2) and focuses its `<h1 tabindex="-1">` on route change.

States of the shell:

| State | What the user sees |
|---|---|
| Loading the admin chunk | `Suspense` fallback: centered `role="status"` text "Cargando el panel…" with a spinner icon (static under reduced motion). |
| Chunk load failure / offline | `ChunkErrorBoundary`: error box (`role="alert"`) "No se pudo cargar el panel. Revise su conexión." + button "Reintentar" (full page reload). Header with language switcher is rendered by the outer app so the message can be switched to English. |
| Not signed in | `RequireAdmin` redirects to `/login?next=...` (no flash of the panel). |
| Signed in as EMPLOYEE | redirect to `/` (no message). |
| Session ended on the server (401 after demotion / deactivation, or 403) | Error box on a bare page "Su sesión terminó. Ingrese de nuevo." + button "Ir a ingresar". Not the re-auth dialog, because the PIN would not help (see 3.2). |
| Admin without branch | "Mi marca" link works; the employee screen shows the existing `NO_BRANCH_ASSIGNED` state with the link back to the panel. |

Accessibility: Section 6.1.

### 3.2 Admin re-auth dialog (`AdminReauthDialog`)

Opens over the current page on the first 401 that is a plain expired token (6 h, no refresh). Reuses `PinKeypad`, `PinDots`, identity `<dl>` and countdown from Stage 3 section 3.7; differences:

```
┌──────────────────────────────────────────┐
│ Ingrese su PIN                           │
│ Su sesión venció. Ingrese su PIN para    │
│ continuar. Lo que tenía en pantalla se   │
│ conserva; repita la acción al terminar.  │
│                                          │
│ Ana Mora                                 │
│ Empresa DEMO · Empleado 9001             │
│        ○ ○ ○ ○   ○ ○ ○ ○                 │
│        0 de 8 dígitos                    │
│  [1] [2] [3] / [4] [5] [6] / …           │
│ [          Ingresar          ]           │
│ [ Más tarde ]   [ ¿No es usted? ]        │
└──────────────────────────────────────────┘
```

| State | Behaviour |
|---|---|
| Default / submitting / wrong PIN / 429 / network | Identical to Stage 3 3.7, with admin texts (`admin.reauth.*`). |
| Success | Dialog closes, focus returns to the element that had it (or the page h1). Polite status "Sesión renovada. Repita la última acción." **No request is replayed automatically** (admin writes are not idempotent). |
| "Más tarde" / Esc | Closes. A non-modal warning banner is placed at the top of `main`: "Su sesión venció." + button "Ingresar PIN" that reopens the dialog. Failed requests keep showing their own errors. |
| "¿No es usted?" | Opens `LogoutConfirm`. |
| Pending form state | Dialogs (action dialog, forms) stay mounted underneath; their typed content is preserved. If a write dialog is open when the 401 arrives, the re-auth dialog stacks on top (second native `<dialog>`); closing it returns focus to the write dialog's submit button. |

### 3.3 Dashboard (`/admin`, `DashboardPage`)

URL state: `?date=YYYY-MM-DD&branch=<id>` (replace, not push), so reload and bookmark keep the view. Default: server `today`.

#### 3.3.1 Anatomy (>= 1024 px)

```
 h1  Asistencia de hoy                         ← "Asistencia del miércoles 28 de octubre" for other dates
 DEMO · Todas las sucursales                   ← 16 px muted
 ┌ toolbar ────────────────────────────────────────────────────────────────────────────────┐
 │ Fecha [28/10/2026 📅]  Sucursal [Todas las sucursales ▾]   [↻ Actualizar] ☑ Actualizar  │
 │                                                              automáticamente  [📄 Exportar a Excel]│
 │ Actualizado a las 10:13                                                                  │
 └──────────────────────────────────────────────────────────────────────────────────────────┘
 ┌ Presentes ─────┐ ┌ Llegadas tardías ┐ ┌ Sin marcar ──────┐ ┌ Por revisar ────────────────┐
 │ ✓              │ │ 🕒               │ │ ⊖                │ │ 👁                           │
 │ 9 de 12        │ │ 2                │ │ 3                │ │ 1                            │
 │ ▔▔▔ teal       │ │ ▔▔▔ amber        │ │ ▔▔▔ red-brown    │ │ 1 fuera del área ▔▔ violet  │
 │                │ │                  │ │ +1 aún no inician│ │ Abrir cola de revisión →    │
 └────────────────┘ └──────────────────┘ └──────────────────┘ └─────────────────────────────┘
 [caption visually hidden: Asistencia del miércoles 28 de octubre de 2026, DEMO, todas las sucursales, 12 empleados]
 ┌───────────────────────────────────────────────────────────────────────────────────────────┐
 │ Empleado             Entrada   Salida    Horas        Evidencia          Estado            │
 ├───────────────────────────────────────────────────────────────────────────────────────────┤
 │ (MR) María Rojas     07:58     —         en turno     📷 Foto + GPS      (✓ A tiempo)      │
 │      Cajera                                                                                │
 │ (JS) José Solano     08:17     —         en turno     📷 Foto + GPS      (🕒 Tarde 17 min)  │
 │ (AL) Ana Lobo        07:52     —         en turno     🏷 Tablet local    (● A tiempo)      │
 │ (LV) Luis Vargas     08:01     —         en turno     📍 GPS a 420 m     (⚲ Fuera del área)│
 │ (KM) Karla Méndez    06:00     14:04     8 h 04 min   📷 Foto + GPS      (✓ Completo)      │
 │                                                       Tarde 12 min  ← secondary line       │
 │ (DS) Diego Soto      —         —         —            —                  (⊖ Sin marcar)    │
 │                                                                            [Registrar entrada]│
 │ …                                                                                          │
 └───────────────────────────────────────────────────────────────────────────────────────────┘
 12 empleados                                                      (footer line: row count)
```

Header block details:
- `h1`: `admin.dashboard.titleToday` when `date === today`, otherwise `titleDate` with the long date. Under it a muted line "{company} · {branch | Todas las sucursales}".
- Date field: `<label>Fecha</label>` + `<input type="date">` (native; keyboard: arrow keys and typed digits). Also two small icon buttons "Día anterior" / "Día siguiente" (44/36 px, `chevron-left/right`) around it for fast navigation. A "Hoy" text button appears when the selected date is not today and jumps to the server `today`. Changing the date reloads; no "Aplicar" button.
- Branch select: `<select>` "Todas las sucursales" + active and inactive branches (inactive suffixed "(inactiva)"), from `GET /api/admin/branches`.
- "Actualizar" button (icon `refresh`); "Actualizar automáticamente" checkbox (default on; applies only when the date is today and the page is visible). Needed for WCAG 2.2.2 (section 8.5).
- "Actualizado a las 10:13" plain text (not live).
- "Exportar a Excel": shown from 4c only. It opens `ExportDialog` (3.13) with the selected date and branch pre-filled.

KPI cards (`<ul class="kpis">` of `<li>`; labels and values are read together in DOM order):

| Card | Label | Value | Subtitle | Accent |
|---|---|---|---|---|
| Presentes | `admin.kpi.present` | "9" + muted " de 12" | none | teal, icon `check-circle` |
| Llegadas tardías | `admin.kpi.late` | "2" | none | amber, icon `clock` |
| Sin marcar | `admin.kpi.notClocked` | "3" | "+1 aún no inician" only when `notYetDue > 0` | red-brown, icon `minus-circle` |
| Por revisar | `admin.kpi.toReview` | "1" | most frequent reason with count, e.g. "1 fuera del área" (`admin.kpi.toReviewReason` + `reviewReason.*` lower-cased in a sentence); empty when 0 | violet, icon `eye` |

- The "Por revisar" card is an `<a>` covering the card to `/admin/review?from={date}&to={date}`; it contains a visually hidden "Abrir cola de revisión" and a visible arrow icon. It is the only interactive card. Card text for the other three is plain.
- Layout: 4 columns >= 1024 px, 2 x 2 from 768 px, 1 column below 480 px. Accent is a 4 px top border in the family border colour (all >= 5.10:1 on white) plus the icon; value text is always `--color-text`.
- A card is "0" when empty. "Por revisar" for a date shows entries with `work_date` of that date only; the nav badge counts all pending entries (section 8.2), so the card subtitle on the date view says "de esta fecha" via the label `admin.kpi.toReviewHelp` ("Marcas de esta fecha").

Table (`AttendanceTable`, `<table>`): `<caption class="visually-hidden">`, `<thead>` with `th scope="col"`, each row `th scope="row"` holds the employee cell.

| Column | Content |
|---|---|
| Empleado | `th scope="row"`: 36 px circular avatar with initials (`aria-hidden`; bg `--color-info-bg`, text `--color-primary` 6.47:1), name (600), job title (muted 14 px). Inactive employee: chip "Inactivo" after the name. Name links nowhere in 4a (in 4b it links to the employee detail). |
| Entrada | `<time dateTime>` HH:mm (company zone) or "—". If edited: a pencil icon + "editada" (14 px muted) under the time and visually hidden "hora editada" in the cell. If `source=ADMIN`: no "editada"; the Evidence column shows "Registro manual". |
| Salida | same as Entrada |
| Horas | "en turno" (dot icon + text), "8 h 04 min" (`duration.hm`), or "—". Cell for `—` has visually hidden "sin horas". |
| Evidencia | one `<button>` (text button, icon + label) opening the evidence dialog for the clock-in (or the clock-out when there is no clock-in): "Foto + GPS" (`camera`), "GPS a 420 m" (`map-pin-off`, outside area), "Solo foto" (`camera`), "Registro manual" (`pencil`), "Tablet local" (`building`, reserved), "—" (no button). Accessible name: "Ver evidencia de María Rojas, entrada 07:58" (label-in-name satisfied: the visible text is not a prefix, so the button carries `aria-label` that **starts with the visible words**: e.g. "Foto + GPS, ver evidencia de María Rojas, entrada 07:58"). When both entries exist, the cell holds two stacked buttons "Entrada" / "Salida" only if the evidence differs; otherwise one button for the entry with a problem, else the clock-in. See 8.8 for the proposed simplification. |
| Estado | `DayStatusChip` (1.3), optional secondary line "Tarde 12 min" (amber `#6B4000`, 8.90:1 on white) when the day is `COMPLETE`/reviewed and `lateMinutes > 0`, and, on `NOT_CLOCKED` / `MISSING_CLOCK_OUT` rows, a small secondary button **"Registrar entrada"** / **"Registrar salida"** (opens `CreateEntryDialog`, 3.7). No separate actions column (section 8.6). |

Sort: server order (name; `DAY_OFF` last). No client sort in 4a (not in the mockup). Row hover `--color-row-hover`; rows are not clickable as a whole.

#### 3.3.2 State matrix

| State | What changes |
|---|---|
| Loading (first load) | KPI values show "…" (`aria-busy="true"` on the cards list); the table area shows a bordered placeholder with spinner and "Cargando asistencia…" (`role="status"`). No shimmer. |
| Loaded | as above. Focus stays on the control the user used. |
| Refreshing (date unchanged: manual, visibility, 60 s timer) | Old data stays, no flash, no layout shift. A spinner icon appears next to "Actualizar" and the button gets `aria-busy`. Rows are keyed by employee id so a focused button keeps focus. The update is **not** announced; "Actualizado a las 10:14" changes quietly. Auto refresh is paused while any dialog is open. |
| Date/branch changed | Table area dims to 60 % opacity over the old data until the new data arrives, then replaces it. Polite status once: "Asistencia del jueves 29 de octubre cargada. 12 empleados." |
| Empty (no employees with branch) | Info panel: "Aún no hay empleados con sucursal asignada." + link "Ir a Empleados" (4b; in 4a plain text). KPI cards show "0 de 0", "0", "0", "0". |
| Future date | Info notice above the table: "Fecha futura: aún no hay marcas." All rows `NOT_YET_DUE` or `DAY_OFF`. |
| Branch with no rows | Same as empty, text "Esta sucursal no tiene empleados activos." |
| Error: network / timeout / 5xx | Error box (`role="alert"`) above the table with `error.network` / `error.unknown` + "Reintentar". Previously loaded data stays visible and dimmed with the line "Datos de las 10:13" so the admin never reads a stale table as current. |
| Error: 400 `REPORT_TOO_LARGE` | Error box "Hay demasiados datos para mostrar. Elija una sucursal." Focus moves to the branch select. |
| Error: 401 | `AdminReauthDialog` (3.2). |
| Error: 429 / 503 | error box with `error.TOO_MANY_ATTEMPTS` / `error.SERVER_BUSY`, "Reintentar". |

#### 3.3.3 Hours and status rules the UI must not recompute

All statuses, KPI counts and worked minutes come from the server. The client only formats. Forbidden: deriving `DayStatus` or KPIs from rows in the browser.

#### 3.3.4 "editada" marker, original receipt time, self-approval

- Table: marker "editada" derived on the client as `source !== 'ADMIN' && effectiveTime !== serverTime` (to the second). It does **not** use `adjusted` (which is also true after a plain approval; section 8.10).
- Evidence dialog (3.4): shows both times - "Hora vigente 08:00 (editada)" and "Hora registrada 08:17 (recibida por el servidor)". The original receipt time is always visible there.
- "Autoaprobado" chip shows next to any adjustment whose admin is the same person as the employee (3.4 history, review card, `ReviewActionDialog` warning).

#### 3.3.5 Wireframe, 768 - 1023 px

Same table, 6 columns, `--cell-pad-x: .75rem`; the sidebar is replaced by a tab bar under the header:

```
 [Español|English] [Mi marca] [Cerrar sesión]
 Control de Asistencia · DEMO
 [Asistencia] [Revisión (3)] [Empleados] [Sucursales] [Reportes]      ← nav, links, wraps
 Asistencia de hoy
 Fecha [28/10/2026] Sucursal [Todas ▾] [↻] ☑ Auto   [Exportar a Excel]
 ┌ Presentes ─────────┐ ┌ Llegadas tardías ──┐
 │ 9 de 12            │ │ 2                  │
 └────────────────────┘ └────────────────────┘
 ┌ Sin marcar ────────┐ ┌ Por revisar ───────┐
 │ 3 · +1 aún no inic.│ │ 1 · 1 fuera del…   │
 └────────────────────┘ └────────────────────┘
 table (Empleado | Entrada | Salida | Horas | Evidencia | Estado)
```

#### 3.3.6 Stacked list, < 768 px (`AttendanceList`)

React picks the component with a `useMediaQuery('(min-width: 48rem)')` hook, so only one of table / list is in the DOM (a CSS-restyled table loses its table semantics in several screen readers).

```
 ┌──────────────────────────────┐
 │ (MR) María Rojas             │  ← <h3> or <strong> inside <li>/<article>
 │      Cajera                  │
 │ (✓ A tiempo)                 │
 │ Entrada 07:58 · Salida —     │  ← <dl> pairs: Entrada / Salida / Horas
 │ Horas: en turno              │
 │ [📷 Foto + GPS]              │
 └──────────────────────────────┘
```
`<ul>` of `<li><article aria-labelledby=name-id>`; the same buttons and chips; "Registrar …" button under the chip.

### 3.4 Evidence dialog (`EvidenceDialog`)

Opened from the dashboard evidence cell, the review queue ("Evidencia") and the history. A native modal (`ModalDialog`, `closeOnBackdrop`), 56rem wide at >= 1024 px (two columns), 48rem at tablet, full screen below 768 px (single column, photo first). Title (h2, focus on open): "Evidencia · María Rojas". Subtitle: "Entrada · miércoles 28 de octubre". Close button (X, 44 px, name "Cerrar").

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ Evidencia · María Rojas                                                 [✕]    │
│ Entrada · miércoles 28 de octubre                                              │
├───────────────────────────────┬────────────────────────────────────────────────┤
│ ┌───────────────────────────┐ │ Estado   (👁 En revisión) (⚲ Fuera del área)   │
│ │                           │ │          (Enviada con retraso)                 │
│ │      photo, 3:4           │ │ ─ Horas ──────────────────────────────────────  │
│ │      max 360 x 480        │ │ Hora vigente        08:00  (✎ editada)         │
│ │                           │ │ Hora registrada     08:17  recibida por el     │
│ │                           │ │                     servidor                   │
│ │                           │ │ Hora del teléfono   07:55 · 22 min de          │
│ └───────────────────────────┘ │                     diferencia                 │
│ Cada consulta de foto queda   │ Enviada con retraso                            │
│ registrada.                   │ Origen              Teléfono                   │
│                               │ ─ Ubicación ───────────────────────────────────  │
│                               │ Distancia           420 m de la sucursal       │
│                               │                     (radio actual 100 m)       │
│                               │ Precisión del GPS   ±12 m                      │
│                               │ Coordenadas         9.932500, -84.079600       │
│                               │ [↗ Ver en el mapa]  [↗ Ver la sucursal]        │
│                               │ Se abrirá Google Maps en una pestaña nueva y   │
│                               │ recibirá estas coordenadas.                    │
│                               │ ─ Otra marca del día ──────────────────────────  │
│                               │ Salida 17:03 · Válida   [Ver evidencia]        │
├───────────────────────────────┴────────────────────────────────────────────────┤
│ Historial de ajustes (2)                                                       │
│  • Aprobada · Ana Mora · 28 oct, 10:14  (Autoaprobado)                         │
│    Estado: En revisión → Válida                                                │
│    "Verificado con la foto"                                                    │
│  • Hora corregida · …                                                          │
├────────────────────────────────────────────────────────────────────────────────┤
│ [Aprobar] [Rechazar] [Corregir hora]  [Registrar salida]            [Cerrar]   │
└────────────────────────────────────────────────────────────────────────────────┘
```

Facts, in this DOM order (a `<dl>`, grouped by `<h3>`: "Horas", "Ubicación", "Otra marca del día"):
1. Status chip + review reason chips (`reviewReason.*`).
2. **Hora vigente** (`effectiveTime`), with the "editada" marker when it differs from the receipt time. **Hora registrada** (`serverTime`) always shown with the note "recibida por el servidor", so the original is never lost from view. **Hora del teléfono** with skew: "07:55 · 22 min de diferencia" (omitted when `deviceTime` is null, e.g. `source=ADMIN`). "Enviada con retraso" line when `syncedLate`.
3. **Origen**: Teléfono / Tablet / QR / Registro manual (`source`).
4. **Ubicación** (omitted for `source=ADMIN`, replaced by "Registro manual: sin foto ni ubicación." in a grey notice):
   - Distancia desde la sucursal: "420 m de la sucursal (radio actual 100 m)" with the inside / outside indicator reusing the `result--inside|outside` look (check-circle or alert-triangle + text).
   - Precisión del GPS: "±12 m", or "No disponible" when null.
   - **Exact coordinates** as text "9.932500, -84.079600" (monospace, `translate="no"`, selectable, 6 decimals, dot decimal). **This dialog is the only place coordinates appear.** If `location` is null: "Sin ubicación".
   - Two external links (`<a target="_blank" rel="noopener noreferrer">`, icon `external-link`, 44/36 px): "Ver en el mapa" → `https://www.google.com/maps/search/?api=1&query={lat},{lng}` (entry location, only when `location` exists) and "Ver la sucursal" → same URL with the branch coordinates, for comparison. Link text carries the name in its accessible name: "Ver en el mapa (se abre en una pestaña nueva)" via visually hidden suffix `admin.evidence.newTab`.
   - Privacy line under the links, 14 px muted: "Se abrirá Google Maps en una pestaña nueva y recibirá estas coordenadas." The link is a plain JSX `<a href>` whose URL is built only from numbers formatted with `toFixed(6)` (no user text goes into the URL).
5. **Otra marca del día** (`pair`): one line "Salida 17:03 · Válida" and a text button "Ver evidencia" that replaces the dialog content with that entry (re-fetch by id; focus to the title; photo of the previous entry revoked first). If no pair and the entry is a CLOCK_IN: "Sin salida registrada." + button "Registrar salida".
6. **Historial de ajustes** (below the columns, full width, `<h3>` + `<ol reversed>` newest first). Each item: action chip (`Aprobada`, `Rechazada`, `Hora corregida`, `Marca creada`), "por {adminName} · {date time}", **"Autoaprobado" chip** when the admin is the employee, one line per change (`field: old → new`, instants formatted in the company zone, status names translated, `late_minutes` as "Tarde: 17 min → 0 min"), and the reason in quotes. Empty: "Sin ajustes." 

Photo frame (`EvidencePhoto`): portrait 3:4, `max-height: var(--photo-max-h)` (480 px), `max-width: 22.5rem` (360 px), `object-fit: cover`, `border-radius: --radius-md`, 1 px border. Loaded only when the dialog opens (`apiBlob`); object URL revoked on close, on switching entry and on unmount. `alt="Selfie de María Rojas, entrada 07:58"`.

| Photo state | Frame content |
|---|---|
| Loading | Grey frame, spinner (static under reduced motion) and text "Cargando foto…" (`role="status"` inside the frame). |
| Loaded | Image. Below it the muted line "Cada consulta de foto queda registrada." |
| No photo (404, or `hasPhoto=false`) | Grey frame, `camera-off` icon, text "Sin foto". For `source=ADMIN`: "Registro manual: sin foto." |
| 429 (`TOO_MANY_ATTEMPTS`) | Frame with `alert-triangle`, "Se consultaron demasiadas fotos. Inténtelo de nuevo en {minutes} min." + "Reintentar" disabled until the time passes. The facts remain usable. |
| Network / 5xx | Frame with "No se pudo cargar la foto." + "Reintentar". |
| 401 | `AdminReauthDialog` on top; after success the user presses "Reintentar". |

Actions (footer, `modal__actions` row): the same three review actions as the queue, each enabled by the allowed transitions (plan table): **Aprobar** (from `PENDING_REVIEW`, or labelled **"Deshacer rechazo"** and mapped to approve when `REJECTED`), **Rechazar** (from `PENDING_REVIEW` or `VALID`, "revisión puntual"), **Corregir hora** (always). A not-allowed action is **omitted**, not disabled (nothing to explain). "Registrar salida" appears for a lone CLOCK_IN. Buttons open `ReviewActionDialog` / `CreateEntryDialog` stacked on top (second native dialog); on success the top dialog closes, this dialog refreshes with the returned `AdminEntryDetail`, and the polite status says "Marca aprobada." Focus returns to the button that opened the action dialog, or, if that button no longer exists after the state change, to the dialog title.

Dialog states: loading detail (title shows employee name from the opener; body "Cargando…"), loaded, detail error (404 `NOT_FOUND`: "Esta marca ya no existe." + Cerrar; network: error box + Reintentar), refresh after `ENTRY_CHANGED` (body reloads, polite "La marca cambió. Se actualizaron los datos.").

### 3.5 Review queue (`/admin/review`, `ReviewQueuePage`)

URL state: `?status=PENDING_REVIEW|REJECTED&from=&to=&employee=&page=`.

```
 h1  Revisión de marcas
 Revise las marcas que necesitan una decisión. Las más antiguas aparecen primero.
 ┌ filters ─────────────────────────────────────────────────────────────────────────┐
 │ Estado [En revisión ▾]  Desde [   ]  Hasta [   ]  Empleado [Todos ▾]  [Limpiar]  │
 └──────────────────────────────────────────────────────────────────────────────────┘
 63 marcas · mostrando 1 a 25                                    (status text, polite on change)
 ┌ card ────────────────────────────────────────────────────────────────────────────┐
 │ (LV) Luis Vargas · 1004 · Cajero                       martes 27 de octubre      │  ← h3
 │ Entrada 08:01 (editada)   Salida: sin registrar   Tarde 1 min                    │
 │ (⚲ Fuera del área · 420 m) (Enviada con retraso)                                 │
 │ Hora del teléfono 07:55 · 6 min de diferencia                                    │
 │ [👁 Evidencia] [✓ Aprobar] [✕ Rechazar] [✎ Corregir hora] [Registrar salida]     │
 └──────────────────────────────────────────────────────────────────────────────────┘
 … 25 cards …
 [‹ Anterior]   Página 1 de 3   [Siguiente ›]
```

- Filters: native `<select>` and `<input type="date">`, each labelled. "Estado": "En revisión" (default) / "Rechazadas" (to undo). Changing any filter reloads and resets to page 1; polite status "63 marcas." after load. Filters are `role="search"`-free; wrapped in `<form>` with `role="search"` is not needed (not a text search); use `<fieldset><legend class="visually-hidden">Filtros</legend>`.
- Card = `<article aria-labelledby>`, h3 is the employee name. Content per plan: entry type + effective time (+ "editada"), the pair line ("Salida 17:03" or "Salida: sin registrar"), reason chips, `lateMinutes` when > 0, distance for OUTSIDE_GEOFENCE ("420 m"; no coordinates), device time and skew when `syncedLate` or `DEVICE_CLOCK_AHEAD`, "Registro manual" never (admin entries are never pending).
- Buttons (>= 36/44 px): **Evidencia** (opens 3.4), **Aprobar** (primary), **Rechazar** (destructive outline), **Corregir hora** (secondary), **Registrar salida** only when the entry is a CLOCK_IN without pair (secondary). Each has an `aria-label` that starts with its visible word and adds the context: "Aprobar marca de Luis Vargas, entrada 08:01, martes 27 de octubre".
- Rows of `REJECTED` status show **Deshacer rechazo** (approve) instead of Aprobar and omit Rechazar.
- "Autoaprobado" warning: if the entry belongs to the signed-in admin, the card shows a small amber chip "Es su propia marca" (`admin.review.ownEntry`), so the admin sees it before acting (needs the flag, section 8.10).

| State | What the user sees |
|---|---|
| Loading | `role="status"` "Cargando marcas…" with spinner in the list area; filters usable. |
| Loaded | cards + pagination. |
| Empty (default filters) | Success panel with `check-circle`: "No hay marcas por revisar." (+ muted "Cuando una marca necesite una decisión, aparecerá aquí."). |
| Empty (with filters) | Info panel "Ninguna marca coincide con los filtros." + button "Limpiar filtros" (focus moves to it). |
| Error | Error box with Reintentar; filters stay. |
| After an action succeeds | The card is removed; polite status "Marca aprobada. Quedan 62 por revisar."; focus moves to the **h3 of the next card at the same position** (or, if the page is now empty, to the pagination "Anterior"/the empty panel heading; if the page empties but more pages exist, the list reloads and focus goes to the first h3). The nav badge count refreshes. |
| `ENTRY_CHANGED` (409) | The action dialog closes; the card is reloaded in place; error box above the list (not modal) "Otra persona modificó esta marca. Se actualizó la información; revísela e intente de nuevo." Focus moves to that card's h3. |
| Pagination | Previous / Next are `<button>`s (44 px) with the current page text; `aria-disabled` at the ends. After paging, focus goes to the list `h2` "Marcas" (`tabindex=-1`) and the status reads "Página 2 de 3." |

Page size fixed at 25 (the API maximum is 100; not a user choice in Stage 4).

### 3.6 Review action dialog (`ReviewActionDialog`)

One component, four modes: `approve`, `reject`, `correct`, `undoReject` (= approve). Native modal, 32rem. Title (h2) varies; below it a summary `<dl>`: Empleado, Marca ("Entrada · martes 27 de octubre"), Hora actual (effective), Estado actual (chip).

```
┌────────────────────────────────────────────────┐
│ Corregir hora                              [✕] │
│ Luis Vargas · 1004                             │
│ Entrada · martes 27 de octubre                 │
│ Hora actual 08:01 · Estado (👁 En revisión)    │
│                                                │
│ Nueva hora *                                   │
│ [27/10/2026 07:55 🕒]                          │
│ Hora de Costa Rica (America/Costa_Rica).       │
│ [Usar la hora del teléfono (07:55)]            │
│ ⓘ La hora recibida por el servidor (08:01) se  │
│   conserva. Esta corrección recalcula la       │
│   llegada tarde con el horario actual.         │
│                                                │
│ Motivo *  (5 a 500 caracteres)                 │
│ Motivos frecuentes:                            │
│ [Hora del teléfono incorrecta] [Falla de la    │
│  cámara o de la ubicación]                     │
│ ┌────────────────────────────────────────────┐ │
│ │                                            │ │
│ └────────────────────────────────────────────┘ │
│ 0 / 500                                        │
│                                                │
│ ⚠ Esta marca es suya. Quedará registrada como  │
│   Autoaprobado.                    (only if own)│
│ [      Guardar corrección      ]               │
│ [          Cancelar            ]               │
└────────────────────────────────────────────────┘
```

Mode details:

| Mode | Title | Extra content | Submit | Notes |
|---|---|---|---|---|
| `approve` | "Aprobar marca" | If the entry has `SYNCED_LATE` or `DEVICE_CLOCK_AHEAD`: info notice "Aprobar no cambia la hora. Si corresponde, use «Corregir hora»." | "Aprobar marca" (primary) | Status becomes `VALID`. |
| `undoReject` | "Deshacer rechazo" | Info "La marca volverá a contar en las horas." | "Deshacer rechazo" | Same API as approve. |
| `reject` | "Rechazar marca" | Warning notice when the entry is already `VALID`: "Esta marca ya estaba válida. Al rechazarla dejará de contar en las horas." Always: "La marca queda en el historial." | "Rechazar marca" (destructive outline, `x-circle` icon) | Initial focus on the reason field. |
| `correct` | "Corregir hora" | `datetime-local` "Nueva hora" pre-filled with the current effective time **in the company zone**; zone label under it; "Usar la hora del teléfono ({time})" button when `deviceTime` exists (fills the field; polite status "Hora del teléfono aplicada."); for a CLOCK_IN the lateness note; for a CLOCK_OUT: "Debe ser posterior a la entrada de {time}." Field `min`/`max` attributes mirror the window (not after now; CLOCK_OUT after the pair's in) as guidance only; the server decides. | "Guardar corrección" (primary) | Becomes `VALID` if it was not (info line "La marca quedará como Válida."). |

Reason field (`ReasonField`): `<label>` "Motivo" + required marker (`*` plus visually hidden "obligatorio"; `aria-required`), `<textarea rows=3 maxlength=500>`, counter "n / 500" (not live), hint "Entre 5 y 500 caracteres." (`aria-describedby`). A group "Motivos frecuentes" (`role="group"` + `aria-labelledby`) of toggle-free text buttons: clicking one **replaces** the textarea content with the preset text, keeps focus on the preset button, and the dialog's polite span says "Motivo completado." The text stays editable. Presets per mode in 5.3 (`admin.reasonPreset.*`, 7 in total).

Validation (on submit, not on every keystroke): empty / shorter than 5 trimmed characters: inline error under the field "Escriba un motivo de al menos 5 caracteres." (`role="alert"` text, `aria-invalid`, focus to the field). Correct mode: empty time: "Indique la nueva hora." Equal to current: client message "La nueva hora es igual a la actual." (the server would answer `INVALID_STATUS_CHANGE`).

| State | Behaviour |
|---|---|
| Default | Submit enabled (`aria-disabled` only while busy). |
| Submitting | Submit shows spinner + "Guardando…", `aria-busy`, inputs `readonly`, Cancel `aria-disabled`. Request carries `expectedStatus` (+ `expectedEffectiveTime` for correct). |
| Success | Dialog closes; caller announces and moves focus (3.4 / 3.5). |
| 409 `ENTRY_CHANGED` | Close and reload as in 3.5 (from the evidence dialog: stay, refresh content, polite "La marca cambió. Se actualizaron los datos."). |
| 409 `INVALID_STATUS_CHANGE` | Error box in the dialog "La marca ya está en ese estado o no puede cambiar de esa forma." (`error.INVALID_STATUS_CHANGE`), focus to it; the dialog stays so the text typed is kept. |
| 400 `INVALID_ENTRY_TIME` | Error under the time field (`error.INVALID_ENTRY_TIME`), focus to the field. |
| 400 `INVALID_REQUEST` | Error box. |
| 404 | "Esta marca ya no existe." and close. |
| 429 / 503 / network / 401 | `ErrorBox` text; 401 opens re-auth on top and the typed text is preserved. |

Self-approval notice: amber `notice--warn` with `alert-triangle` and the text "Esta marca es suya. La acción quedará registrada como Autoaprobado." Shown for all four modes when the entry's employee is the signed-in admin. It does not block the action (resolved Q4).

### 3.7 Create-entry dialog (`CreateEntryDialog`)

Opened from dashboard rows `NOT_CLOCKED` / `MISSING_CLOCK_OUT`, from a queue card with a lone CLOCK_IN, and from the evidence dialog. The **type is determined by the context** (a row without clock-in creates a CLOCK_IN; a row with only a clock-in creates a CLOCK_OUT), so it is shown as read-only text, not a choice.

```
┌────────────────────────────────────────────────┐
│ Registrar salida                           [✕] │
│ Diego Soto · 1006                              │
│ Jornada del martes 27 de octubre               │
│ Entrada registrada: 08:02                      │
│                                                │
│ Hora de la salida *                            │
│ [27/10/2026 17:00 🕒]   ← default: scheduled end│
│ Hora de Costa Rica (America/Costa_Rica).       │
│ ⓘ Se guardará como «Registro manual», sin foto │
│   ni ubicación, y quedará en el historial.     │
│                                                │
│ Motivo * (5 a 500 caracteres)                  │
│ Motivos frecuentes: [Olvidó marcar la salida]  │
│  [Falla de la cámara o de la ubicación]        │
│  [Autorizado por la jefatura]                  │
│ [ textarea ]                                   │
│ [      Registrar salida      ]  [ Cancelar ]   │
└────────────────────────────────────────────────┘
```

- Fields: time `datetime-local` (default: schedule start for a clock-in, schedule end for a clock-out, date part = `workDate`; when there is no schedule, `workDate` 08:00 / 17:00 left as editable defaults but the field is flagged by the hint "No hay horario: indique la hora."), reason. Employee and work date are read-only text (they cannot be changed here).
- Inactive employee: info "Este empleado está inactivo; la corrección es histórica." Employee without branch: the dialog opens with an error box `error.NO_BRANCH_ASSIGNED` and no submit (the server would answer 409; the client knows from `branchName == null`).
- Today's clock-in created for someone with a broken camera: info line "El empleado podrá marcar su salida desde su teléfono." (shown when `workDate` is today and type is CLOCK_IN).
- Errors: `ENTRY_ALREADY_EXISTS` -> error box "Este empleado ya tiene esa marca para el día seleccionado." + button "Actualizar tabla" (closes and reloads); `CLOCK_IN_REQUIRED` -> error box; `INVALID_ENTRY_TIME` -> under the field; `NO_BRANCH_ASSIGNED` -> error box; others as 3.6.
- Success: dialog closes, polite status "Salida registrada para Diego Soto.", the dashboard reloads and focus moves to that row's evidence button (or, if gone, the row's name cell has no focus target, so the table `caption`-adjacent h1).

### 3.8 Employees list (`/admin/employees`, 4b)

```
 h1  Empleados                                                       [+ Nuevo empleado]
 Buscar [ nombre o código            ]   Estado ◉ Todos ○ Activos ○ Inactivos ○ Bloqueados  Sucursal [Todas ▾]
 12 empleados
 ┌───────────────────────────────────────────────────────────────────────────────────────┐
 │ Empleado          Rol            Sucursal   Estado                  Horario            │
 │ (MR) María Rojas  Empleado       Central    (✓ Activo)              Sí                 │
 │      1001 · Cajera                          Consentimiento: Sí                         │
 │ (JS) José Solano  Empleado       Central    (✓ Activo) (🔒 Bloqueado hasta 10:30)  Sí  │
 │ (AM) Ana Mora     Administrador  Sin sucursal (✓ Activo)            No                  │
 └───────────────────────────────────────────────────────────────────────────────────────┘
```

- Employee name is a link to `/admin/employees/{id}`. The row shows code, job title, role ("Empleado" / "Administrador"), branch name or "Sin sucursal", active chip, **locked indicator** ("Bloqueado hasta 10:30", red-brown + `lock`), consent ("Consentimiento: Sí/No"), has schedule ("Sí/No").
- Search is client-side, diacritic- and case-insensitive (`normalize('NFD')`), on name and code. The result count updates a polite status "5 empleados" (debounced 400 ms). The status filter is a `role="group"` of radio inputs styled as a segmented control (visible text; the selected one filled + bold + underline, not colour alone). Branch filter is a `<select>`; the URL accepts `?branch=` (used by the branch page link).
- Below 768 px: stacked cards.
- States: loading ("Cargando empleados…"), empty company ("Aún no hay empleados." + button "Nuevo empleado"), empty filter result ("Ningún empleado coincide." + "Limpiar filtros"), error (box + Reintentar).
- "Nuevo empleado" opens `EmployeeFormDialog` (create): fields Código de empleado (hint "Letras mayúsculas, números y guiones, máximo 20. No se puede cambiar después."; the field uppercases as typed via CSS `text-transform` plus value normalisation on blur), Nombre completo, Puesto (optional), Rol (radio group with descriptions: Empleado - "Marca entrada y salida desde su teléfono."; Administrador - "Accede a este panel, a las fotos y a las ubicaciones de todos los empleados."), Sucursal (`<select>` of **active** branches; required for Empleado; optional for Administrador; helper "Un administrador sin sucursal no puede marcar."). Client validation messages in 5.3. Submit "Crear empleado". Server errors: `EMPLOYEE_CODE_TAKEN` under the code field; `BRANCH_REQUIRED` / `BRANCH_INACTIVE` under the branch field; 429 error box. **On 201 the PIN reveal opens immediately (3.10)**; the list refreshes behind it.
- Uncertain create (network drop after submit): error box `admin.employees.createUncertain` "No se pudo confirmar si el empleado fue creado. Revise la lista; si aparece, restablezca su PIN." (the PIN could not be shown).

### 3.9 Employee detail (`/admin/employees/:id`, 4b)

```
 ‹ Empleados
 h1  María Rojas                           (✓ Activo) (Empleado)
 1001 · Cajera · Sucursal Central
 ┌ Perfil ─────────────────────────────┐ ┌ Acceso y estado ───────────────────────────┐
 │ Código            1001  (no editable)│ │ Estado         ✓ Activo                    │
 │ Nombre *          [María Rojas      ]│ │ Consentimiento Sí                           │
 │ Puesto            [Cajera           ]│ │ Bloqueo        🔒 Bloqueado hasta 10:30     │
 │ Rol               ◉ Empleado          │ │                [Desbloquear]               │
 │                   ○ Administrador     │ │ [Restablecer PIN]                          │
 │ Sucursal          [Central ▾]         │ │ [Desactivar empleado]                      │
 │ [Guardar cambios]                     │ │                                            │
 └──────────────────────────────────────┘ └────────────────────────────────────────────┘
 ┌ Horario semanal ──────────────────────────────────────────────────────────────────────┐
 │ (schedule grid, 3.9.3)                                                                │
 └───────────────────────────────────────────────────────────────────────────────────────┘
```

Two columns at >= 1024 px, one column below. Sections are `<section aria-labelledby>` with h2.

#### 3.9.1 Profile form
Fields as in create, code read-only. Save button "Guardar cambios" (disabled via `aria-disabled` while there are no changes; "Sin cambios" is not announced). Success: polite status "Cambios guardados." and the page header refreshes; focus stays on the button. A role change shows a confirm (3.12). Server errors map as in 3.8.

#### 3.9.2 Access and status actions

| Action | Visible when | Confirm | After success |
|---|---|---|---|
| Desbloquear | locked (`lockedUntil > now`) | none (low risk, reversible) | indicator disappears; status "Cuenta desbloqueada." Focus moves to "Restablecer PIN". |
| Restablecer PIN | always | yes (3.12) | PIN reveal (3.10) |
| Desactivar empleado | active | yes (destructive, 3.12) | chip becomes Inactivo; button becomes "Activar empleado"; status "Empleado desactivado. Sus sesiones se cerraron." |
| Activar empleado | inactive | none | status "Empleado activado." |

**Guards on self and last admin** (also enforced by the server; the UI explains instead of hiding):
- Own account: "Desactivar empleado" and changing the role to Empleado are `aria-disabled="true"` with `aria-describedby` pointing to a visible note: "Esta es su propia cuenta. No puede desactivarse ni quitarse el rol de administrador." (key `error.SELF_CHANGE_NOT_ALLOWED` text). The note is shown permanently on the own detail page (info notice), not only on hover.
- Last active administrator (computed from the list: exactly one active ADMIN and it is this person): same controls `aria-disabled` with note "Es el único administrador activo. Nombre a otro administrador antes de desactivarlo o cambiar su rol." (`admin.employee.lastAdminNote`). If another admin deactivates them in the meantime, the 409 `LAST_ADMIN` box shows the same text.
- "Restablecer PIN" on one's own account stays allowed but the confirm adds a warning (3.12).
- Click on an `aria-disabled` control does nothing; it stays focusable so the reason is read.

#### 3.9.3 Weekly schedule (`ScheduleEditor`)

A real table of 7 rows, ISO order Monday to Sunday, weekday names from `Intl` in the UI language (first letter capitalised).

```
 Horario semanal                              [Copiar horario de… ▾] [Copiar]
 Marque los días que trabaja. Si la salida es anterior a la entrada, el turno cruza la medianoche.
 ┌───────────┬─────────┬──────────┬──────────┬────────────────┬──────────────────────────┐
 │ Día       │ Trabaja │ Entrada  │ Salida   │ Tolerancia (min)│ Nota                     │
 ├───────────┼─────────┼──────────┼──────────┼────────────────┼──────────────────────────┤
 │ Lunes     │ ☑       │ [08:00]  │ [17:00]  │ [5]            │                          │
 │ Martes    │ ☑       │ [22:00]  │ [06:00]  │ [5]            │ ☾ Turno nocturno         │
 │ …         │         │          │          │                │                          │
 │ Sábado    │ ☐       │ [  ]     │ [  ]     │ [ ]            │ Libre                    │
 │ Domingo   │ ☐       │          │          │                │ Libre                    │
 └───────────┴─────────┴──────────┴──────────┴────────────────┴──────────────────────────┘
 Cambios sin guardar        [Guardar horario]   [Descartar cambios]
```

- Row header `th scope="row"` = weekday. Each control has a visually hidden `<label>`: "Trabaja el lunes", "Hora de entrada del lunes", "Hora de salida del lunes", "Tolerancia del lunes en minutos". Unchecked rows disable their time and grace inputs (`disabled`; they are not tab stops) and show "Libre" in the note column.
- Inputs: `<input type="time">` (step 60, 24 h shown by the OS locale), `<input type="number" min=0 max=240 inputmode=numeric>`. Defaults when a day is checked: 08:00, 17:00, 5.
- Validation on save: start equal to end: error under the row "La entrada y la salida no pueden ser iguales." (`aria-invalid` on both, focus to the first invalid time); grace outside 0-240: "La tolerancia debe estar entre 0 y 240 minutos."; a checked day with an empty time: "Indique la hora." Errors are listed in an error summary (`role="alert"`, list of links to the fields) at the top of the section and also inline.
- "Copiar horario de…": a `<select>` of other employees + "Copiar" button; fetches `GET .../schedule` of that employee and fills the grid (client-side, not saved). Polite status "Horario copiado. Revise y guarde."
- "Cambios sin guardar" is plain text next to the buttons, visible only while dirty. Leaving the page while dirty asks via a native `beforeunload` (browser dialog) and a router blocker with `ConfirmDialog` ("¿Salir sin guardar? Los cambios del horario se perderán." / "Salir" / "Seguir editando").
- Save: `PUT` whole week, polite status "Horario guardado." and a muted note "Las llegadas tarde ya registradas no cambian." (`admin.schedule.pastNote`, always visible under the grid).
- Below 768 px: each weekday is a `<fieldset>` card (legend = weekday) with the same controls stacked.

States of the detail page: loading ("Cargando empleado…"), 404 (error panel "No se encontró lo que buscaba." + link "Volver a Empleados"), saving states per form, error boxes.

### 3.10 PIN reveal dialog (`PinRevealDialog`)

Shown after "Crear empleado" (title "Empleado creado") and after "Restablecer PIN" (title "PIN restablecido"). A native modal, 28rem, **not dismissible by backdrop click**; Esc does not close it directly (it opens the inline confirmation below). The PIN exists only in the component state of this dialog and is dropped (set to `''`, then unmounted) on close.

```
┌──────────────────────────────────────────────┐
│ Empleado creado                              │
│ PIN inicial de María Rojas (1001)            │
│                                              │
│ ┌──────────────────────────────────────────┐ │
│ │        4 8 2 7    1 9 0 3                │ │  ← 2rem monospace, 14.29:1 on #E6F0EE
│ └──────────────────────────────────────────┘ │
│ [📋 Copiar PIN]   [Ocultar PIN]              │
│                                              │
│ ⚠ Este PIN se muestra una sola vez. Al cerrar│
│   esta ventana no podrá verlo de nuevo; si   │
│   se pierde, deberá restablecerlo.           │
│                                              │
│ Entregue el PIN a la persona en privado y    │
│ recomiéndele no compartirlo. Su cuenta       │
│ bloquea los intentos fallidos repetidos.     │
│                                              │
│ [        Ya lo copié o anoté        ]        │
└──────────────────────────────────────────────┘
```

- Warning is an amber `notice--warn` with `alert-triangle` and **words**: "Este PIN se muestra una sola vez." (never colour alone). It sits directly under the PIN so it is read right after it.
- PIN text: two groups of four digits, monospace, `letter-spacing: .12em`, `user-select: all` (one click selects both groups), `translate="no"`, `autocomplete` irrelevant. The visible digits are `aria-hidden`; a visually hidden sibling reads them digit by digit with a pause: "PIN: 4 8 2 7, 1 9 0 3". This is the one place in the app where PIN digits exist in the DOM; they are in no live region and in no attribute other than that sibling.
- "Ocultar PIN" toggles to bullets ("•••• ••••", hidden sibling reads "PIN oculto") and the button becomes "Mostrar PIN"; default shown. Useful on a shared screen.
- "Copiar PIN": `navigator.clipboard.writeText` (best effort). On success the button text becomes "PIN copiado" with `check`, polite status inside the dialog "PIN copiado." (reverts after 5 s). On failure (insecure context, permission): inline notice "No se pudo copiar. Seleccione el PIN y cópielo manualmente." The clipboard may keep the PIN; the dialog text for the close button does not promise otherwise.
- Close button: "Ya lo copié o anoté" (primary, full width). It is the explicit acknowledgement. Esc or the window-level cancel opens an inline confirm **inside the same dialog** replacing the footer: "¿Cerrar sin copiar el PIN? No podrá verlo de nuevo." with [Volver al PIN] (primary, initial focus) and [Cerrar y descartar] (destructive outline). No timeouts (WCAG 2.2.1): the dialog stays open until the admin acts.
- Initial focus: the dialog title (`tabindex=-1`), so a screen reader reads title, name and then the PIN as the user arrows through; the first Tab lands on "Copiar PIN".
- Self reset: extra warning notice under the PIN "Es su propia cuenta: al cerrar esta ventana se cerrará su sesión y deberá ingresar con este PIN." After close the app signs out and goes to `/login` with focus on the login h1.
- After close: focus returns to the opener (detail page "Restablecer PIN" button, or the new employee's name link in the list). Polite status on the page: "PIN restablecido." / "Empleado creado." — the status text never includes the PIN or any digit.

### 3.11 Branches (`/admin/branches`, 4b)

```
 h1  Sucursales                                                     [+ Nueva sucursal]
 ┌─────────────────────────────────────────────────────────────────────────────────────┐
 │ Sucursal      Radio     Empleados activos   Estado             Acciones               │
 │ Central       100 m     8                    (✓ Activa)         [Editar] [Desactivar]  │
 │ Heredia       150 m     0                    (· Inactiva)       [Editar] [Activar]     │
 └─────────────────────────────────────────────────────────────────────────────────────┘
```

The list shows **no coordinates** (decision: only the evidence dialog and the branch form show them). "Empleados activos" is a link to `/admin/employees?branch={id}`.

`BranchFormDialog` (create and edit), 32rem:

```
┌────────────────────────────────────────────────┐
│ Nueva sucursal                             [✕] │
│ Nombre *            [Central                 ] │
│ Pegar coordenadas   [9.9325, -84.0796        ] │
│   Copie las coordenadas desde Google Maps y    │
│   péguelas aquí; se separan solas.             │
│ Latitud *   [ 9.9325 ]   Longitud * [-84.0796 ]│
│ Radio permitido (m) *  [100]   (10 a 5000)     │
│ [↗ Revisar ubicación en Google Maps]           │
│ ⓘ Cambiar la ubicación o el radio solo afecta  │
│   las marcas futuras.                          │
│ [   Crear sucursal   ]   [ Cancelar ]          │
└────────────────────────────────────────────────┘
```

- "Pegar coordenadas" is a dedicated text field. On input, if the value matches `^\s*-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?\s*$`, latitude and longitude are filled, the field is cleared and the polite span says "Coordenadas aplicadas." A comma decimal ("9,9325") shows under the field "Use punto como separador decimal, por ejemplo 9.9325." and fills nothing.
- Latitude -90..90, longitude -180..180, radius integer 10-5000, name 1-120. Inline errors under each field; the server `BRANCH_NAME_TAKEN` shows under the name field.
- "Revisar ubicación en Google Maps" is an external link (`aria-disabled` while the pair is invalid): the branch is company configuration, not personal data; it helps catch a hemisphere typo. Same privacy line as 3.4.
- Edit mode adds the read-only line "Empleados activos: 8". Active/inactive is changed only by the row actions.
- **Deactivate**: if `activeEmployees > 0` the button is `aria-disabled` with a visible note in the row/card "Tiene {n} empleados activos. Trasládelos o desactívelos primero." (409 `BRANCH_IN_USE` shows the same). Otherwise a destructive confirm (3.12). Activate has no confirm.
- States: loading, empty ("Aún no hay sucursales." + button), error, saving.

### 3.12 Confirm dialogs and guards (4b) - matrix

All use the existing `ConfirmDialog` (focus on the safe action, Esc and backdrop cancel, destructive tone = `alertdialog` + error outline + words) unless stated. The destructive button label always says what happens; none is just "Aceptar".

| Action | Tone | Title | Body | Confirm | Cancel | Busy label |
|---|---|---|---|---|---|---|
| Desactivar empleado | destructive | ¿Desactivar a {name}? | {name} no podrá ingresar ni marcar, y se cerrarán sus sesiones abiertas. Su historial se conserva. Puede activarlo de nuevo cuando quiera. | Desactivar | Cancelar | Desactivando… |
| Restablecer PIN | destructive | ¿Restablecer el PIN de {name}? | Se generará un PIN nuevo y se mostrará una sola vez. El PIN actual dejará de funcionar y se cerrarán todas sus sesiones. | Restablecer PIN | Cancelar | Restableciendo… |
| Restablecer PIN (own account) | destructive | same | same + warning notice "Es su propia cuenta: se cerrará su sesión al terminar." | Restablecer PIN | Cancelar | Restableciendo… |
| Cambiar rol a Administrador | default | ¿Dar acceso de administrador a {name}? | Podrá ver las fotos, las ubicaciones y los datos de todos los empleados, y aprobar o corregir marcas. | Hacer administrador | Cancelar | Guardando… |
| Cambiar rol a Empleado | destructive | ¿Quitar el rol de administrador a {name}? | Perderá el acceso a este panel y se cerrarán sus sesiones. | Quitar rol | Cancelar | Guardando… |
| Desactivar sucursal | destructive | ¿Desactivar {branch}? | No se podrá asignar a nuevos empleados. Las marcas anteriores se conservan. Puede activarla de nuevo. | Desactivar | Cancelar | Desactivando… |
| Salir del horario sin guardar | default | ¿Salir sin guardar? | Los cambios del horario se perderán. | Salir | Seguir editando | - |
| Cerrar sesión | existing `LogoutConfirm` | | | | | |

Decided as **no confirm** (reversible or no data loss): approve / reject / correct (their dialog with a mandatory reason is the confirmation), Activar empleado, Activar sucursal, Desbloquear, schedule save, create entry (has its own dialog).

Guards summary (UI; the server stays authoritative):

| Guard | UI behaviour | Server code on race |
|---|---|---|
| Self deactivate / self demote | controls `aria-disabled` + visible reason | `SELF_CHANGE_NOT_ALLOWED` |
| Last active admin | controls `aria-disabled` + visible reason (client-computed from the employee list) | `LAST_ADMIN` |
| Branch with active employees | deactivate `aria-disabled` + reason + link | `BRANCH_IN_USE` |
| Inactive branch as employee branch | not offered in the select (only active branches) | `BRANCH_INACTIVE` |
| Employee without branch | role Empleado requires a branch (client) | `BRANCH_REQUIRED` |

### 3.13 Export (4c): `ExportForm`, `ExportDialog`, `ReportsPage`

The same `ExportForm` is used inline on `/admin/reports` and inside `ExportDialog` (opened from the dashboard "Exportar a Excel" button, pre-filled with `from = to = selected date` and the selected branch).

```
 h1  Reportes
 ┌ Exportar asistencia a Excel ────────────────────────────────────────────────────────┐
 │ Desde * [01/10/2026 📅]   Hasta * [15/10/2026 📅]   Sucursal [Todas las sucursales ▾] │
 │ Atajos: [Mes actual] [Mes anterior] [Primera quincena] [Segunda quincena] [Últimos 7 días]
 │ Período: 15 días (máximo 62).                                                        │
 │ [📄 Descargar Excel]                                                                │
 │ ┌ Qué incluye ┐                                                                      │
 │ • Resumen, Detalle, Ajustes e Info (4 hojas).                                        │
 │ • Las horas aprobadas y las pendientes de revisión van por separado.                 │
 │ • No se clasifican horas extra; el archivo solo informa los minutos fuera de horario.│
 │ • No incluye fotos ni coordenadas.                                                   │
 └─────────────────────────────────────────────────────────────────────────────────────┘
```

Dialog variant: title "Exportar a Excel", the same fields and button, plus "Cancelar".

- Inputs: two `<input type="date">` (labelled, required), branch `<select>`. Presets compute dates in the **company zone** from the server `today` (provided by the review-count response, section 8.11). The live summary line "Período: 15 días (máximo 62)." is plain text updated on change (not live; the error below is the alert).
- Validation (on change and on submit): `to < from` -> "La fecha final debe ser igual o posterior a la inicial."; more than 62 inclusive days -> "El período no puede superar 62 días (elegidos: {days})."; empty -> "Indique la fecha." Inline under the field, `aria-invalid`, focus to the first invalid field on submit. The button stays enabled so the errors can be heard (`aria-disabled` only while generating).

| State | What the user sees |
|---|---|
| Idle | form + explanation. |
| Invalid | inline errors (above). |
| Generating | Button: spinner + "Generando el archivo…", `aria-busy`, inputs `readonly`; a secondary "Cancelar" button aborts the request (`AbortController`). Muted line "Puede tardar unos segundos." Timeout 60 s -> `error.timeout`. |
| Success | Browser download starts (`<a download>` created in code, 3.13 note). Status panel (success colours, `check-circle`): "Archivo descargado: asistencia_DEMO_2026-10-01_2026-10-15.xlsx." plus button "Generar de nuevo". The blob is released right away (no retained PII). In the dialog variant: the dialog closes and the page's polite status says "Exportación lista." |
| `DATE_RANGE_TOO_LARGE` (400) | error box (should be caught client-side) `error.DATE_RANGE_TOO_LARGE`. |
| `REPORT_TOO_LARGE` (400) | error box "Hay demasiados datos. Reduzca el período o elija una sucursal." Focus to the error. |
| 429 | error box `error.tooManyAttemptsMinutes` (10 exports / 10 min) using `Retry-After`; button `aria-disabled` until then (visual countdown `aria-hidden`; one polite status when ready). |
| 401 | `AdminReauthDialog`; the admin presses "Descargar Excel" again. |
| Network / 5xx | error box + the button re-enabled. |
| Offline | warning notice "Necesita conexión para exportar." and the button `aria-disabled`. |

Download markup: a hidden `<a download>` is created with `document.createElement('a')`, clicked and removed; `URL.revokeObjectURL` on the next tick. No `window.open`, no `innerHTML`.

---

## 4. Component inventory

All components live in `src/admin/` and import only from existing shared components. Props are TypeScript shapes for the coder. Every string comes from `useAdminI18n()`; none is hard-coded. Components that move focus forward a ref. No component sets a `style` attribute: sizes and colours are classes.

| # | Component | Purpose | Key props | Reuses | New CSS classes (prefix `adm-`) |
|---|---|---|---|---|---|
| 1 | `AdminApp` | lazy route tree, `Suspense`, `ChunkErrorBoundary` | - | router | - |
| 2 | `AdminLayout` | header, nav, `main`, footer, re-auth host | `children` | `LanguageSwitcher`, `LogoutConfirm`, `HealthStatus` | `adm-shell`, `adm-header`, `adm-main` |
| 3 | `AdminNav` | nav with badge | `{ pending: number; items: NavItem[] }` | `Icon` | `adm-nav`, `adm-nav__link`, `adm-badge` |
| 4 | `SkipLink` | skip to `#main` | - | - | `adm-skip` |
| 5 | `RequireAdmin` | guard | - | - | - |
| 6 | `AdminReauthDialog` | 3.2 | `{ open; identity; busy; errorKey?; retryAt?; onSubmit; onLater; onNotYou }` | `ModalDialog`, `PinKeypad`, `PinDots`, `ErrorBox` | - |
| 7 | `ChunkErrorBoundary` | chunk failure | - | `ErrorBox` | - |
| 8 | `PageHeading` | h1 with focus, sets `document.title` | `{ titleKey; subtitle?: ReactNode }` | - | `adm-h1` |
| 9 | `DateField` | label + `<input type="date">` + prev/next/today | `{ value; onChange; max?; label }` | `.field` | `adm-datefield` |
| 10 | `BranchFilter` | `<select>` of branches | `{ branches; value: number | null; onChange }` | `.field` | - |
| 11 | `KpiCards` | 4 cards | `{ kpis: Kpis; date: string; loading: boolean }` | `Icon` | `adm-kpis`, `adm-kpi`, `adm-kpi--teal|amber|violet|redbrown` |
| 12 | `AttendanceTable` | table >= 768 px | `{ rows: DashboardRow[]; caption: string; onEvidence(row, which); onCreate(row, type) }` | - | `adm-table`, `adm-table__scroll` (no overflow needed; wrapper for border/radius) |
| 13 | `AttendanceList` | stacked list < 768 px | same props | - | `adm-cards` |
| 14 | `Avatar` | initials circle | `{ name: string }` | - | `adm-avatar` |
| 15 | `DayStatusChip` | chip by `DayStatus` | `{ status: DayStatus; minutes?: number }` | `Icon` | `adm-chip`, `adm-chip--teal|amber|violet|redbrown|grey` |
| 16 | `EntryStatusChip` | VALID / PENDING / REJECTED | `{ status: TimeEntryStatus }` | - | `adm-chip` |
| 17 | `EvidenceButton` | evidence cell button | `{ kind: 'photoGps'|'gpsAt'|'photoOnly'|'manual'|'tablet'|'none'; meters?; entryLabel; onClick }` | `Icon` | `adm-link-button` |
| 18 | `TimeCell` | time + "editada" marker | `{ iso: string|null; edited: boolean; zone: string }` | `Icon` | `adm-time` |
| 19 | `EvidenceDialog` | 3.4 | `{ entryId: number; onClose; onChanged(): void; onOpenEntry(id) }` | `ModalDialog` | `adm-evidence`, `adm-facts` |
| 20 | `EvidencePhoto` | photo frame and its states | `{ entryId: number; alt: string; hasPhoto: boolean }` | `apiBlob` | `adm-photo` |
| 21 | `AdjustmentHistory` | history list | `{ items: AdjustmentView[]; zone }` | `EntryStatusChip` | `adm-history` |
| 22 | `ExternalMapLink` | Google link + new-tab text | `{ lat: number; lng: number; labelKey }` | `Icon` | `adm-link-button` |
| 23 | `ReviewQueuePage` / `ReviewCard` | 3.5 | `ReviewCard: { item: ReviewItem; onEvidence; onAction(mode); onCreate }` | `EntryStatusChip`, `DayStatusChip` | `adm-review-card` |
| 24 | `Pagination` | prev / next | `{ page; pages; onPage }` | `.button` | `adm-pager` |
| 25 | `ReviewActionDialog` | 3.6 | `{ mode: 'approve'|'reject'|'correct'|'undoReject'; entry: ReviewTarget; ownEntry: boolean; onDone(detail); onCancel }` | `ModalDialog`, `ErrorBox` | - |
| 26 | `ReasonField` | presets + textarea + counter | `{ value; onChange; presets: PresetKey[]; error?: string; busy }` | `.field` | `adm-presets` |
| 27 | `LocalDateTimeField` | `datetime-local` + zone label | `{ value; onChange; label; zone; min?; max?; error? }` | `.field` | - |
| 28 | `CreateEntryDialog` | 3.7 | `{ employee; type; workDate; scheduleDefault?; onDone; onCancel }` | `ModalDialog` | - |
| 29 | `EmployeesPage` / `EmployeeTable` / `EmployeeFilters` | 3.8 | - | `DayStatusChip`-like chips | `adm-segmented` |
| 30 | `EmployeeFormDialog` / `EmployeeForm` | create and profile | `{ mode: 'create'|'edit'; branches; initial?; onSaved }` | `.field`, `ModalDialog` | - |
| 31 | `EmployeeDetailPage` / `AccessPanel` | 3.9 | - | `ConfirmDialog` | `adm-panel` |
| 32 | `ScheduleEditor` | 3.9.3 | `{ value: ScheduleRow[]; onChange; errors; disabled }` | - | `adm-schedule` |
| 33 | `PinRevealDialog` | 3.10 | `{ title; employeeName; employeeCode; pin: string; selfReset: boolean; onClose: () => void }` | `ModalDialog` | `adm-pin` |
| 34 | `BranchesPage` / `BranchFormDialog` | 3.11 | - | `ConfirmDialog` | - |
| 35 | `CoordinatesPasteField` | paste splitter | `{ onApply(lat, lng); }` | `.field` | - |
| 36 | `ExportForm` / `ExportDialog` / `ReportsPage` | 3.13 | `ExportForm: { initial: {from; to; branchId}; today: string; onDone }` | `ModalDialog`, `ErrorBox` | `adm-presets` |
| 37 | `StatusMessage` | page-level polite `role="status"` region | `{ message: string }` | - | `visually-hidden` |
| 38 | `useAdminQuery` | load / reload / error / abort | hook | - | - |
| 39 | `useMediaQuery` | `matchMedia` hook | hook | - | - |

Notes on selected components:

**`DayStatusChip`**: `<span class="adm-chip adm-chip--teal">` with `<Icon>` (decorative) + text; `LATE` receives `minutes`. Min height 28 px (inside a row, not an independent target), radius pill, 1 px border family colour, 14 px / 600. It is not interactive.

**`TimeCell`**: `<time dateTime={iso}>08:00</time>` and, if `edited`, `<span class="adm-edited"><Icon name="pencil"/> editada</span>` plus visually hidden text "hora editada". When `iso` is null: `—` (`aria-hidden`) + visually hidden "Sin registrar" (reuses `home.notRecorded`).

**`EvidencePhoto`**: owns the object URL (`useEffect` cleanup revokes it), requests the blob only when mounted, aborts on unmount, never retries automatically (each fetch is audited and rate-limited: 300 per 10 min).

**`ReasonField`**: preset buttons are `type="button"`; presets list passed by the caller; value is trimmed on submit; counter is `aria-hidden` (the limit is in the hint, which is the described-by text).

**`PinRevealDialog`**: `pin` is received as a prop from a parent state that is cleared in `onClose` **before** unmounting; the parent keeps no other copy; no `console`, no `sessionStorage`, no URL.

**Table semantics**: `AttendanceTable` markup contract: `<table class="adm-table"><caption class="visually-hidden">…</caption><thead><tr><th scope="col">…`; employee name in `<th scope="row">`; no `role` overrides; no nested tables.

---

## 5. i18n

### 5.1 Conventions

- Admin strings live in `src/i18n/adminResources.ts` (`adminEs`, `adminEn: Record<AdminMessageKey, string>`), loaded only with the admin chunk. Shared keys already in `resources.ts` are reused (never duplicated): `common.*`, `language.*`, `logout.*`, `reauth.company`, `reauth.employee`, `reviewReason.*`, `status.*`, `duration.*`, `home.notRecorded`, `error.*`. The new `reviewReason.*` and `error.*` keys below go into `resources.ts` because the contract test and the employee PWA read them.
- Placeholders `{name}`; plurals `.one` / `.other`.
- **Spanish register: "usted" everywhere, admin panel and employee PWA** (plan section "Open Questions: RESOLVED", Q14). English is neutral second person. Imperatives use the usted form ("Ingrese", "Revise", "Contacte").
- Keys intentionally identical in ES and EN (add to `SAME_IN_BOTH`): `admin.dashboard.subtitle`, `admin.evidence.accuracyValue`, `admin.history.change`, `admin.kpi.toReviewReason`, `admin.review.distance`, `admin.reports.cancel` is **not** identical (Cancelar / Cancel). The coder lets the parity test decide.
- Server `detail` strings are never shown; the UI maps `code` to its own text (5.5).
- Words from the plan's `SPANISH_WORDS` list (`Presentes|tardías|Sin marcar|revisar|Aprobar|Rechazar|Sucursal|Exportar|Horario`) must not appear as literals in TSX.

### 5.2 Stage 3 Spanish strings: "tú" to "usted" (every affected key in `frontend/src/i18n/resources.ts`)

EN text is unchanged for all rows. "Same length" is respected within a few characters. The coder also updates the header comment of `resources.ts` (line 4: `Spanish copy uses "tu"...` becomes `Spanish copy uses "usted"...`).

| Key | Current (tú) | Proposed (usted) |
|---|---|---|
| `login.error.companyRequired` | Escribe tu código de empresa. | Escriba su código de empresa. |
| `login.error.employeeRequired` | Escribe tu código de empleado. | Escriba su código de empleado. |
| `login.error.codeFormat` | Usa solo letras, números y guiones (máximo 20). | Use solo letras, números y guiones (máximo 20). |
| `login.offline` | Necesitas conexión a internet para iniciar sesión. | Necesita conexión a internet para iniciar sesión. |
| `login.retryReady` | Ya puedes intentarlo de nuevo. | Ya puede intentarlo de nuevo. |
| `consent.title` | Tus datos y tu consentimiento | Sus datos y su consentimiento |
| `consent.intro` | {company} usa esta aplicación para registrar tu asistencia. Antes de marcar, lee cómo se tratan tus datos (Ley 8968, …). | {company} usa esta aplicación para registrar su asistencia. Antes de marcar, lea cómo se tratan sus datos (Ley 8968, …). |
| `consent.whatBody` | …una selfie, tu ubicación GPS (latitud, longitud y precisión) y la hora de tu teléfono. | …una selfie, su ubicación GPS (latitud, longitud y precisión) y la hora de su teléfono. |
| `consent.rightsTitle` | Tus derechos | Sus derechos |
| `consent.rightsBody` | Puedes acceder a tus datos, rectificarlos, cancelarlos y oponerte a su tratamiento. Envía tu solicitud al administrador de tu empresa. | Puede acceder a sus datos, rectificarlos, cancelarlos y oponerse a su tratamiento. Envíe su solicitud al administrador de su empresa. |
| `consent.blockedNote` | Sin tu consentimiento no puedes marcar entrada ni salida. | Sin su consentimiento no puede marcar entrada ni salida. |
| `consent.declinedTitle` | No se puede continuar sin tu consentimiento | No se puede continuar sin su consentimiento |
| `consent.declinedBody` | Sin aceptar no puedes marcar entrada ni salida. Puedes cerrar sesión o volver y aceptar cuando quieras. Si tienes dudas, consulta a tu administrador. | Sin aceptar no puede marcar entrada ni salida. Puede cerrar sesión o volver y aceptar cuando desee. Si tiene dudas, consulte a su administrador. |
| `consent.offline` | Necesitas conexión para aceptar. Vuelve a intentarlo cuando tengas señal. | Necesita conexión para aceptar. Vuelva a intentarlo cuando tenga señal. |
| `home.status.notYet` | Aún no has marcado hoy | Aún no ha marcado hoy |
| `home.status.workedSoFar` | Llevas {duration} | Lleva {duration} |
| `home.status.noBranch` | No tienes sucursal asignada. Consulta a tu administrador. | No tiene sucursal asignada. Consulte a su administrador. |
| `home.status.needsConnection` | Conéctate para cargar tu estado | Conéctese para cargar su estado |
| `home.missingClockOut` | No marcaste la salida del {date}. Tu administrador lo revisará. | No marcó la salida del {date}. Su administrador lo revisará. |
| `home.review.rejected` | Rechazada. Contacta a tu administrador. | Rechazada. Contacte a su administrador. |
| `evidence.selfie.blocked` | Cámara bloqueada · no podrás marcar sin selfie | Cámara bloqueada · no podrá marcar sin selfie |
| `camera.title` | Tu selfie | Su selfie |
| `camera.forIn` | Para marcar tu entrada | Para marcar su entrada |
| `camera.forOut` | Para marcar tu salida | Para marcar su salida |
| `camera.explain` | Para marcar necesitamos una selfie y tu ubicación. Tu teléfono te pedirá permiso para usar la cámara y la ubicación. | Para marcar necesitamos una selfie y su ubicación. Su teléfono le pedirá permiso para usar la cámara y la ubicación. |
| `camera.framing` | Centra tu cara en el encuadre. | Centre su cara en el encuadre. |
| `camera.reviewTitle` | Revisa tu selfie | Revise su selfie |
| `camera.reviewAlt` | Vista previa de tu selfie | Vista previa de su selfie |
| `camera.captured` | Foto tomada. Revisa tu selfie. | Foto tomada. Revise su selfie. |
| `camera.useHintIn` | Al usar la foto se registra tu entrada. | Al usar la foto se registra su entrada. |
| `camera.useHintOut` | Al usar la foto se registra tu salida. | Al usar la foto se registra su salida. |
| `camera.deniedHelp` | Para marcar necesitas permitir la cámara: | Para marcar necesita permitir la cámara: |
| `camera.deniedHelpAndroid` | Android (Chrome): toca el candado junto a la dirección, luego Permisos, Cámara y elige Permitir. | Android (Chrome): toque el candado junto a la dirección, luego Permisos, Cámara y elija Permitir. |
| `camera.deniedHelpIos` | iPhone (Safari): toca aA en la barra de dirección, luego Configuración del sitio web, Cámara y elige Permitir. | iPhone (Safari): toque aA en la barra de dirección, luego Configuración del sitio web, Cámara y elija Permitir. |
| `camera.contactAdmin` | Sin selfie no se puede marcar. Si no puedes habilitar la cámara, contacta a tu administrador. | Sin selfie no se puede marcar. Si no puede habilitar la cámara, contacte a su administrador. |
| `camera.busy` | La cámara está en uso por otra aplicación. Ciérrala e inténtalo de nuevo. | La cámara está en uso por otra aplicación. Ciérrela e inténtelo de nuevo. |
| `camera.cannotClock` | No se puede marcar sin selfie. Contacta a tu administrador. | No se puede marcar sin selfie. Contacte a su administrador. |
| `camera.photoTooLarge` | La foto es demasiado grande. Vuelve a tomarla. | La foto es demasiado grande. Vuelva a tomarla. |
| `camera.photoUnsupported` | No se pudo preparar la foto. Vuelve a tomarla. | No se pudo preparar la foto. Vuelva a tomarla. |
| `offline.restoredSending` | Conexión restablecida. Enviando tus marcas… | Conexión restablecida. Enviando sus marcas… |
| `offline.queueFull` | Tienes demasiadas marcas sin enviar. Conéctate a internet para enviarlas. | Tiene demasiadas marcas sin enviar. Conéctese a internet para enviarlas. |
| `offline.storageFull` | No hay espacio para guardar la marca. Libera espacio en el teléfono o conéctate e inténtalo de nuevo. | No hay espacio para guardar la marca. Libere espacio en el teléfono o conéctese e inténtelo de nuevo. |
| `announce.sessionRestored` | Sesión iniciada. Enviando tus marcas… | Sesión iniciada. Enviando sus marcas… |
| `announce.cameraBlocked` | Sin permiso de cámara. No puedes marcar sin selfie. | Sin permiso de cámara. No puede marcar sin selfie. |
| `reauth.title` | Ingresa tu PIN | Ingrese su PIN |
| `reauth.body` | Tu sesión venció. Ingresa tu PIN para continuar. Tus marcas guardadas se conservan y se enviarán al ingresar. | Su sesión venció. Ingrese su PIN para continuar. Sus marcas guardadas se conservan y se enviarán al ingresar. |
| `reauth.enter` | Ingresa tu PIN para enviar | Ingrese su PIN para enviar |
| `reauth.notYou` | ¿No eres tú? | ¿No es usted? |
| `logout.confirmBody` | Tendrás que ingresar tu PIN de nuevo. | Tendrá que ingresar su PIN de nuevo. |
| `logout.pendingKept.one` | Tienes 1 marca sin enviar. Se queda guardada en este teléfono y se enviará cuando vuelvas a iniciar sesión. | Tiene 1 marca sin enviar. Se queda guardada en este teléfono y se enviará cuando vuelva a iniciar sesión. |
| `logout.pendingKept.other` | Tienes {n} marcas sin enviar. Se quedan guardadas en este teléfono y se enviarán cuando vuelvas a iniciar sesión. | Tiene {n} marcas sin enviar. Se quedan guardadas en este teléfono y se enviarán cuando vuelva a iniciar sesión. |
| `failure.title.CLOCK_IN` | No se pudo registrar tu entrada | No se pudo registrar su entrada |
| `failure.title.CLOCK_OUT` | No se pudo registrar tu salida | No se pudo registrar su salida |
| `failure.contactAdmin` | Contacta a tu administrador. | Contacte a su administrador. |
| `error.INVALID_CREDENTIALS` | Código o PIN incorrectos. Si fallaste varias veces, espera 15 minutos o contacta a tu administrador. | Código o PIN incorrectos. Si falló varias veces, espere 15 minutos o contacte a su administrador. |
| `error.TOO_MANY_ATTEMPTS` | Demasiados intentos. Inténtalo de nuevo en unos minutos. | Demasiados intentos. Inténtelo de nuevo en unos minutos. |
| `error.tooManyAttemptsMinutes` | Demasiados intentos. Inténtalo de nuevo en {minutes} min. | Demasiados intentos. Inténtelo de nuevo en {minutes} min. |
| `error.tooManyAttemptsSeconds` | Demasiados intentos. Inténtalo de nuevo en {seconds} s. | Demasiados intentos. Inténtelo de nuevo en {seconds} s. |
| `error.INVALID_REQUEST` | Hay datos incorrectos. Revisa e inténtalo de nuevo. | Hay datos incorrectos. Revise e inténtelo de nuevo. |
| `error.METHOD_NOT_ALLOWED`, `error.NOT_ACCEPTABLE`, `error.UNSUPPORTED_MEDIA_TYPE`, `error.INTERNAL_ERROR` (4 keys, same text) | Ocurrió un error inesperado. Inténtalo de nuevo; si continúa, contacta a tu administrador. | Ocurrió un error inesperado. Inténtelo de nuevo; si continúa, contacte a su administrador. |
| `error.NOT_FOUND` | No se encontró lo que buscabas. | No se encontró lo que buscaba. |
| `error.CONSENT_REQUIRED` | Debes aceptar el tratamiento de tus datos para marcar. | Debe aceptar el tratamiento de sus datos para marcar. |
| `error.NO_BRANCH_ASSIGNED` | No tienes sucursal asignada. Contacta a tu administrador. | No tiene sucursal asignada. Contacte a su administrador. |
| `error.ALREADY_CLOCKED_IN` | Ya tenías una entrada registrada, así que esta marca no se guardó. | Ya tenía una entrada registrada, así que esta marca no se guardó. |
| `error.NOT_CLOCKED_IN` | No tienes una entrada abierta, así que no se pudo registrar la salida. | No tiene una entrada abierta, así que no se pudo registrar la salida. |
| `error.SHIFT_ALREADY_COMPLETED` | Tu jornada de hoy ya estaba completa, así que esta marca no se guardó. | Su jornada de hoy ya estaba completa, así que esta marca no se guardó. |
| `error.CLIENT_UUID_CONFLICT` | Esta marca no se pudo registrar por un conflicto interno. Vuelve a marcar. | Esta marca no se pudo registrar por un conflicto interno. Vuelva a marcar. |
| `error.PHOTO_REQUIRED` | Falta la selfie. Vuelve a marcar con una foto. | Falta la selfie. Vuelva a marcar con una foto. |
| `error.INVALID_PHOTO` | La foto no es válida. Vuelve a tomar la selfie. | La foto no es válida. Vuelva a tomar la selfie. |
| `error.PAYLOAD_TOO_LARGE` | La foto es demasiado grande. Vuelve a tomar la selfie. | La foto es demasiado grande. Vuelva a tomar la selfie. |
| `error.network` | No se pudo conectar con el servidor. Revisa tu conexión e inténtalo de nuevo. | No se pudo conectar con el servidor. Revise su conexión e inténtelo de nuevo. |
| `error.timeout` | El servidor tardó demasiado en responder. Inténtalo de nuevo. | El servidor tardó demasiado en responder. Inténtelo de nuevo. |
| `error.sessionExpired` | Tu sesión venció. Ingresa tu PIN. | Su sesión venció. Ingrese su PIN. |
| `error.forbidden` | No tienes permiso para hacer esto. | No tiene permiso para hacer esto. |
| `error.unknown` | Ocurrió un error inesperado. Inténtalo de nuevo. | Ocurrió un error inesperado. Inténtelo de nuevo. |

The coder counts the exact number of changed keys when editing (about 65, counting the four grouped error keys and the plural pair); the compiler enforces `en` parity. Keys with **no** change needed (already neutral or third person): `login.switchBody`, `login.title`, all `consent.*Title/Body` not listed, `home.status.onShift`, `home.late`, `home.lastShift`, `camera.denied`, `camera.notFound`, `camera.unsupported`, `camera.useNativeCamera`, `reviewReason.*`, `update.*`, `failure.expired`, `error.INVALID_PIN`, `error.SERVER_BUSY`.

New PWA key for the corrected-time note (plan: "ajustada por tu administrador"):

| Key | ES | EN |
|---|---|---|
| `home.adjusted` | Hora ajustada por su administrador | Time adjusted by your administrator |

New `reviewReason.*` keys (resolved Q8, shown to the employee and the admin):

| Key | ES | EN |
|---|---|---|
| `reviewReason.NO_GPS_ACCURACY` | Precisión de ubicación no válida | Invalid location accuracy |
| `reviewReason.CLOCK_OUT_AFTER_SCHEDULE` | Salida después del horario | Clock-out after schedule |

Other Spanish text to convert (not in `resources.ts`): the consent text lives in `resources.ts` (covered above); test assertions that contain Spanish copy (listed in section 8.3); `docs/design/stage-3-employee-pwa.md` quotes the old strings in wireframes and the key tables (that file is superseded for Spanish copy by this section; update its wireframes when convenient, no code depends on it); the `README` consent note if it quotes "tu". The backend `messages_es.properties` already uses "usted" (verify only).

**Regression test for "tú" forms** (plan: "only unambiguous forms"). Finding: the plan's pattern `\btú\b` can never match in JavaScript, because `\b` is ASCII-only and `ú` is a non-word character (no boundary exists after it), so "¿No eres tú?" would have passed unnoticed. Use Unicode-aware lookarounds with the `u` flag instead:

```
(?<![\p{L}\p{N}_])(tú|tu|tus|ti|te|tienes|puedes|quieres|debes|necesitas|eres|llevas|tenías|estás|tendrás|podrás|fallaste|marcaste|ingresa|escribe|revisa|contacta|vuelve|elige|toca|centra|libera|conéctate|inténtalo|ciérrala|espera)(?![\p{L}\p{N}_])
```
Not included on purpose (legitimate third person in the current text): `usa` ("{company} usa esta aplicación"), `consulta` (noun), `lee`. The coder runs the pattern over the final `es` block and the admin `es` block, and over `messages_es.properties`.

### 5.3 Admin key tables (`adminResources.ts`)

Spanish in "usted". `{x}` placeholders. Plurals as `.one` / `.other`.

**Navigation and shell**

| Key | ES | EN |
|---|---|---|
| `admin.shell.title` | Panel del administrador | Admin panel |
| `admin.shell.loading` | Cargando el panel… | Loading the panel… |
| `admin.shell.company` | Empresa | Company |
| `admin.nav.label` | Navegación principal | Main navigation |
| `admin.nav.skipToContent` | Saltar al contenido | Skip to content |
| `admin.nav.dashboard` | Asistencia | Attendance |
| `admin.nav.review` | Revisión | Review |
| `admin.nav.employees` | Empleados | Employees |
| `admin.nav.branches` | Sucursales | Branches |
| `admin.nav.reports` | Reportes | Reports |
| `admin.nav.myClock` | Mi marca | My clock |
| `admin.nav.reviewCount.one` | 1 pendiente | 1 pending |
| `admin.nav.reviewCount.other` | {n} pendientes | {n} pending |
| `admin.chunkError` | No se pudo cargar el panel. Revise su conexión. | The panel could not be loaded. Check your connection. |
| `admin.sessionEnded` | Su sesión terminó. Ingrese de nuevo. | Your session has ended. Sign in again. |
| `admin.sessionEnded.action` | Ir a ingresar | Go to sign in |
| `admin.reauth.title` | Ingrese su PIN | Enter your PIN |
| `admin.reauth.body` | Su sesión venció. Ingrese su PIN para continuar. Lo que tenía en pantalla se conserva; repita la acción al terminar. | Your session expired. Enter your PIN to continue. What you had on screen is kept; repeat the action when done. |
| `admin.reauth.done` | Sesión renovada. Repita la última acción. | Session renewed. Repeat your last action. |
| `admin.reauth.banner` | Su sesión venció. | Your session expired. |
| `admin.reauth.bannerAction` | Ingresar PIN | Enter PIN |

**Dashboard, KPIs, table**

| Key | ES | EN |
|---|---|---|
| `admin.dashboard.titleToday` | Asistencia de hoy | Today's attendance |
| `admin.dashboard.titleDate` | Asistencia del {date} | Attendance for {date} |
| `admin.dashboard.subtitle` | {company} · {branch} | {company} · {branch} |
| `admin.dashboard.allBranches` | Todas las sucursales | All branches |
| `admin.dashboard.date` | Fecha | Date |
| `admin.dashboard.prevDay` | Día anterior | Previous day |
| `admin.dashboard.nextDay` | Día siguiente | Next day |
| `admin.dashboard.today` | Hoy | Today |
| `admin.dashboard.branch` | Sucursal | Branch |
| `admin.dashboard.refresh` | Actualizar | Refresh |
| `admin.dashboard.autoRefresh` | Actualizar automáticamente | Refresh automatically |
| `admin.dashboard.updatedAt` | Actualizado a las {time} | Updated at {time} |
| `admin.dashboard.staleData` | Datos de las {time} | Data from {time} |
| `admin.dashboard.export` | Exportar a Excel | Export to Excel |
| `admin.dashboard.loading` | Cargando asistencia… | Loading attendance… |
| `admin.dashboard.loaded.one` | Asistencia del {date} cargada. 1 empleado. | Attendance for {date} loaded. 1 employee. |
| `admin.dashboard.loaded.other` | Asistencia del {date} cargada. {n} empleados. | Attendance for {date} loaded. {n} employees. |
| `admin.dashboard.count.one` | 1 empleado | 1 employee |
| `admin.dashboard.count.other` | {n} empleados | {n} employees |
| `admin.dashboard.empty` | Aún no hay empleados con sucursal asignada. | There are no employees with an assigned branch yet. |
| `admin.dashboard.emptyBranch` | Esta sucursal no tiene empleados activos. | This branch has no active employees. |
| `admin.dashboard.goEmployees` | Ir a Empleados | Go to Employees |
| `admin.dashboard.futureDate` | Fecha futura: aún no hay marcas. | Future date: there are no entries yet. |
| `admin.dashboard.tooLarge` | Hay demasiados datos para mostrar. Elija una sucursal. | There is too much data to show. Choose a branch. |
| `admin.kpi.present` | Presentes | Present |
| `admin.kpi.ofTotal` | de {n} | of {n} |
| `admin.kpi.late` | Llegadas tardías | Late arrivals |
| `admin.kpi.notClocked` | Sin marcar | Not clocked |
| `admin.kpi.notYetDue` | +{n} aún no inician | +{n} not started yet |
| `admin.kpi.toReview` | Por revisar | To review |
| `admin.kpi.toReviewHelp` | Marcas de esta fecha | Entries of this date |
| `admin.kpi.toReviewReason` | {n} {reason} | {n} {reason} |
| `admin.kpi.openReview` | Abrir cola de revisión | Open review queue |
| `admin.table.caption` | Asistencia del {date}, {branch}, {count} | Attendance for {date}, {branch}, {count} |
| `admin.table.employee` | Empleado | Employee |
| `admin.table.clockIn` | Entrada | Clock-in |
| `admin.table.clockOut` | Salida | Clock-out |
| `admin.table.hours` | Horas | Hours |
| `admin.table.evidence` | Evidencia | Evidence |
| `admin.table.status` | Estado | Status |
| `admin.table.inProgress` | en turno | on shift |
| `admin.table.noHours` | sin horas | no hours |
| `admin.table.edited` | editada | edited |
| `admin.table.editedSr` | hora editada | time edited |
| `admin.table.inactive` | Inactivo | Inactive |
| `admin.table.lateLine` | Tarde {minutes} min | Late {minutes} min |
| `admin.table.createIn` | Registrar entrada | Record clock-in |
| `admin.table.createOut` | Registrar salida | Record clock-out |
| `admin.table.createInFor` | Registrar entrada de {name} | Record clock-in for {name} |
| `admin.table.createOutFor` | Registrar salida de {name} | Record clock-out for {name} |

**Day status chips** (`admin.dayStatus.*`): `REJECTED` Rechazada / Rejected; `OUTSIDE_AREA` Fuera del área / Outside the area; `NEEDS_REVIEW` Por revisar / Needs review; `MISSING_CLOCK_OUT` Sin salida / No clock-out; `COMPLETE` Completo / Complete; `LATE` Tarde {minutes} min / Late {minutes} min; `ON_TIME` A tiempo / On time; `UNSCHEDULED` Sin horario / Unscheduled; `NOT_YET_DUE` Aún no inicia / Not started yet; `NOT_CLOCKED` Sin marcar / Not clocked; `DAY_OFF` Libre / Day off (11 keys, exact texts from the plan).

**Evidence dialog**

| Key | ES | EN |
|---|---|---|
| `admin.evidence.title` | Evidencia · {name} | Evidence · {name} |
| `admin.evidence.subtitleIn` | Entrada · {date} | Clock-in · {date} |
| `admin.evidence.subtitleOut` | Salida · {date} | Clock-out · {date} |
| `admin.evidence.photoAndGps` | Foto + GPS | Photo + GPS |
| `admin.evidence.gpsAt` | GPS a {meters} m | GPS at {meters} m |
| `admin.evidence.photoOnly` | Solo foto | Photo only |
| `admin.evidence.manual` | Registro manual | Manual entry |
| `admin.evidence.tablet` | Tablet local | Local tablet |
| `admin.evidence.none` | Sin evidencia | No evidence |
| `admin.evidence.viewAria` | {label}, ver evidencia de {name}, {type} {time} | {label}, view evidence of {name}, {type} {time} |
| `admin.evidence.photoAlt` | Selfie de {name}, {type} {time} | Selfie of {name}, {type} {time} |
| `admin.evidence.photoLoading` | Cargando foto… | Loading photo… |
| `admin.evidence.noPhoto` | Sin foto | No photo |
| `admin.evidence.noPhotoManual` | Registro manual: sin foto. | Manual entry: no photo. |
| `admin.evidence.photoLogged` | Cada consulta de foto queda registrada. | Every photo view is logged. |
| `admin.evidence.photoError` | No se pudo cargar la foto. | The photo could not be loaded. |
| `admin.evidence.photoLimit` | Se consultaron demasiadas fotos. Inténtelo de nuevo en {minutes} min. | Too many photos were viewed. Try again in {minutes} min. |
| `admin.evidence.status` | Estado | Status |
| `admin.evidence.hoursTitle` | Horas | Times |
| `admin.evidence.effective` | Hora vigente | Effective time |
| `admin.evidence.received` | Hora registrada | Recorded time |
| `admin.evidence.receivedNote` | recibida por el servidor | received by the server |
| `admin.evidence.deviceTime` | Hora del teléfono | Phone time |
| `admin.evidence.skew` | {time} · {minutes} min de diferencia | {time} · {minutes} min difference |
| `admin.evidence.syncedLate` | Enviada con retraso | Sent late |
| `admin.evidence.source` | Origen | Source |
| `admin.evidence.source.MOBILE` | Teléfono | Phone |
| `admin.evidence.source.TABLET` | Tablet | Tablet |
| `admin.evidence.source.QR` | Código QR | QR code |
| `admin.evidence.source.ADMIN` | Registro manual | Manual entry |
| `admin.evidence.locationTitle` | Ubicación | Location |
| `admin.evidence.distance` | Distancia de la sucursal | Distance from the branch |
| `admin.evidence.distanceValue` | {meters} m de {branch} (radio actual {radius} m) | {meters} m from {branch} (current radius {radius} m) |
| `admin.evidence.inside` | Dentro del área | Inside the area |
| `admin.evidence.outside` | Fuera del área | Outside the area |
| `admin.evidence.accuracy` | Precisión del GPS | GPS accuracy |
| `admin.evidence.accuracyValue` | ±{meters} m | ±{meters} m |
| `admin.evidence.accuracyUnknown` | No disponible | Not available |
| `admin.evidence.coordinates` | Coordenadas | Coordinates |
| `admin.evidence.noLocation` | Sin ubicación | No location |
| `admin.evidence.mapLink` | Ver en el mapa | View on map |
| `admin.evidence.branchMapLink` | Ver la sucursal | View the branch |
| `admin.evidence.newTab` | (se abre en una pestaña nueva) | (opens in a new tab) |
| `admin.evidence.mapPrivacy` | Se abrirá Google Maps en una pestaña nueva y recibirá estas coordenadas. | Google Maps will open in a new tab and will receive these coordinates. |
| `admin.evidence.manualNotice` | Registro manual: sin foto ni ubicación. | Manual entry: no photo or location. |
| `admin.evidence.pairTitle` | Otra marca del día | Other entry of the day |
| `admin.evidence.pairValue` | {type} {time} · {status} | {type} {time} · {status} |
| `admin.evidence.pairNone` | Sin salida registrada. | No clock-out recorded. |
| `admin.evidence.pairView` | Ver evidencia | View evidence |
| `admin.evidence.history` | Historial de ajustes | Adjustment history |
| `admin.evidence.historyEmpty` | Sin ajustes. | No adjustments. |
| `admin.evidence.notFound` | Esta marca ya no existe. | This entry no longer exists. |
| `admin.evidence.changed` | La marca cambió. Se actualizaron los datos. | The entry changed. The data was updated. |
| `admin.history.by` | por {name} · {date} | by {name} · {date} |
| `admin.history.selfApproved` | Autoaprobado | Self-approved |
| `admin.history.change` | {field}: {old} → {new} | {field}: {old} → {new} |
| `admin.history.none` | sin valor | no value |
| `admin.adjustmentAction.APPROVE` | Aprobada | Approved |
| `admin.adjustmentAction.REJECT` | Rechazada | Rejected |
| `admin.adjustmentAction.CORRECT_TIME` | Hora corregida | Time corrected |
| `admin.adjustmentAction.CREATE_ENTRY` | Marca creada | Entry created |
| `admin.adjustmentField.status` | Estado | Status |
| `admin.adjustmentField.effective_time` | Hora vigente | Effective time |
| `admin.adjustmentField.late_minutes` | Minutos de retraso | Minutes late |
| `admin.adjustmentField.entry` | Marca | Entry |

(`Autoaprobado` is also the exact export column / chip term required by the plan; `admin.history.selfApproved` ES text is "Autoaprobado".)

**Review queue and action dialogs**

| Key | ES | EN |
|---|---|---|
| `admin.review.title` | Revisión de marcas | Entry review |
| `admin.review.intro` | Revise las marcas que necesitan una decisión. Las más antiguas aparecen primero. | Review the entries that need a decision. The oldest appear first. |
| `admin.review.filters` | Filtros | Filters |
| `admin.review.status` | Estado | Status |
| `admin.review.statusPending` | En revisión | Under review |
| `admin.review.statusRejected` | Rechazadas | Rejected |
| `admin.review.from` | Desde | From |
| `admin.review.to` | Hasta | To |
| `admin.review.employee` | Empleado | Employee |
| `admin.review.allEmployees` | Todos | All |
| `admin.review.clear` | Limpiar filtros | Clear filters |
| `admin.review.list` | Marcas | Entries |
| `admin.review.count.one` | 1 marca | 1 entry |
| `admin.review.count.other` | {n} marcas | {n} entries |
| `admin.review.loading` | Cargando marcas… | Loading entries… |
| `admin.review.empty` | No hay marcas por revisar. | There are no entries to review. |
| `admin.review.emptyHelp` | Cuando una marca necesite una decisión, aparecerá aquí. | When an entry needs a decision, it will appear here. |
| `admin.review.emptyFiltered` | Ninguna marca coincide con los filtros. | No entry matches the filters. |
| `admin.review.pairOut` | Salida {time} | Clock-out {time} |
| `admin.review.pairIn` | Entrada {time} | Clock-in {time} |
| `admin.review.pairNone` | Sin registrar | Not recorded |
| `admin.review.deviceLine` | Hora del teléfono {time} · {minutes} min de diferencia | Phone time {time} · {minutes} min difference |
| `admin.review.distance` | {meters} m | {meters} m |
| `admin.review.ownEntry` | Es su propia marca | It is your own entry |
| `admin.review.evidence` | Evidencia | Evidence |
| `admin.review.approve` | Aprobar | Approve |
| `admin.review.reject` | Rechazar | Reject |
| `admin.review.correct` | Corregir hora | Correct time |
| `admin.review.undoReject` | Deshacer rechazo | Undo rejection |
| `admin.review.create` | Registrar salida | Record clock-out |
| `admin.review.actionAria` | {action} marca de {name}, {type} {time}, {date} | {action} entry of {name}, {type} {time}, {date} |
| `admin.review.approved` | Marca aprobada. | Entry approved. |
| `admin.review.rejected` | Marca rechazada. | Entry rejected. |
| `admin.review.corrected` | Hora corregida. | Time corrected. |
| `admin.review.undone` | Rechazo deshecho. | Rejection undone. |
| `admin.review.remaining.one` | Queda 1 por revisar. | 1 left to review. |
| `admin.review.remaining.other` | Quedan {n} por revisar. | {n} left to review. |
| `admin.review.changedBox` | Otra persona modificó esta marca. Se actualizó la información; revísela e intente de nuevo. | Someone else changed this entry. The information was updated; review it and try again. |
| `admin.review.page` | Página {page} de {pages} | Page {page} of {pages} |
| `admin.review.prev` | Anterior | Previous |
| `admin.review.next` | Siguiente | Next |
| `admin.action.titleApprove` | Aprobar marca | Approve entry |
| `admin.action.titleReject` | Rechazar marca | Reject entry |
| `admin.action.titleCorrect` | Corregir hora | Correct time |
| `admin.action.titleUndo` | Deshacer rechazo | Undo rejection |
| `admin.action.currentTime` | Hora actual | Current time |
| `admin.action.currentStatus` | Estado actual | Current status |
| `admin.action.newTime` | Nueva hora | New time |
| `admin.action.zone` | Hora de {zone} | Time in {zone} |
| `admin.action.useDeviceTime` | Usar la hora del teléfono ({time}) | Use the phone time ({time}) |
| `admin.action.deviceTimeApplied` | Hora del teléfono aplicada. | Phone time applied. |
| `admin.action.keepsReceived` | La hora recibida por el servidor ({time}) se conserva. | The time received by the server ({time}) is kept. |
| `admin.action.recalcLate` | Esta corrección recalcula la llegada tarde con el horario actual. | This correction recalculates lateness using the current schedule. |
| `admin.action.afterIn` | Debe ser posterior a la entrada de {time}. | It must be after the clock-in at {time}. |
| `admin.action.becomesValid` | La marca quedará como Válida. | The entry will become Valid. |
| `admin.action.approveHint` | Aprobar no cambia la hora. Si corresponde, use «Corregir hora». | Approving does not change the time. If needed, use "Correct time". |
| `admin.action.undoHint` | La marca volverá a contar en las horas. | The entry will count toward hours again. |
| `admin.action.rejectKeepsHistory` | La marca queda en el historial. | The entry stays in the history. |
| `admin.action.rejectValidWarning` | Esta marca ya estaba válida. Al rechazarla dejará de contar en las horas. | This entry was already valid. Rejecting it removes it from the hours. |
| `admin.action.ownWarning` | Esta marca es suya. La acción quedará registrada como Autoaprobado. | This entry is yours. The action will be recorded as Self-approved. |
| `admin.action.submitApprove` | Aprobar marca | Approve entry |
| `admin.action.submitReject` | Rechazar marca | Reject entry |
| `admin.action.submitCorrect` | Guardar corrección | Save correction |
| `admin.action.submitUndo` | Deshacer rechazo | Undo rejection |
| `admin.action.saving` | Guardando… | Saving… |
| `admin.action.errorTimeRequired` | Indique la nueva hora. | Enter the new time. |
| `admin.action.errorTimeSame` | La nueva hora es igual a la actual. | The new time is the same as the current one. |
| `admin.reason.label` | Motivo | Reason |
| `admin.reason.hint` | Entre 5 y 500 caracteres. | Between 5 and 500 characters. |
| `admin.reason.presets` | Motivos frecuentes | Common reasons |
| `admin.reason.filled` | Motivo completado. | Reason filled in. |
| `admin.reason.error` | Escriba un motivo de al menos 5 caracteres. | Enter a reason of at least 5 characters. |
| `admin.reasonPreset.verifiedPhoto` | Verificado con la foto | Verified with the photo |
| `admin.reasonPreset.authorizedBySupervisor` | Autorizado por la jefatura | Authorized by the supervisor |
| `admin.reasonPreset.deviceFailure` | Falla de la cámara o de la ubicación | Camera or location failure |
| `admin.reasonPreset.forgotClockOut` | Olvidó marcar la salida | Forgot to clock out |
| `admin.reasonPreset.phoneClockWrong` | Hora del teléfono incorrecta | Phone clock was wrong |
| `admin.reasonPreset.mistakenEntry` | Marca hecha por error | Entry made by mistake |
| `admin.reasonPreset.photoMismatch` | La foto no corresponde al empleado | The photo does not match the employee |
| `admin.required` | obligatorio | required |

Preset mapping: approve = verifiedPhoto, authorizedBySupervisor, deviceFailure; reject = mistakenEntry, photoMismatch; correct = phoneClockWrong, forgotClockOut, authorizedBySupervisor; create = forgotClockOut, deviceFailure, authorizedBySupervisor. The stored reason is exactly what was submitted (no key stored).

**Create-entry dialog**

| Key | ES | EN |
|---|---|---|
| `admin.create.titleIn` | Registrar entrada | Record clock-in |
| `admin.create.titleOut` | Registrar salida | Record clock-out |
| `admin.create.workDay` | Jornada del {date} | Work day of {date} |
| `admin.create.existingIn` | Entrada registrada: {time} | Clock-in recorded: {time} |
| `admin.create.timeIn` | Hora de la entrada | Clock-in time |
| `admin.create.timeOut` | Hora de la salida | Clock-out time |
| `admin.create.noSchedule` | No hay horario: indique la hora. | There is no schedule: enter the time. |
| `admin.create.manualNotice` | Se guardará como «Registro manual», sin foto ni ubicación, y quedará en el historial. | It will be saved as "Manual entry", without photo or location, and recorded in the history. |
| `admin.create.inactive` | Este empleado está inactivo; la corrección es histórica. | This employee is inactive; this is a historical correction. |
| `admin.create.employeeCanClockOut` | El empleado podrá marcar su salida desde su teléfono. | The employee can clock out from their phone. |
| `admin.create.submitIn` | Registrar entrada | Record clock-in |
| `admin.create.submitOut` | Registrar salida | Record clock-out |
| `admin.create.refresh` | Actualizar tabla | Refresh table |
| `admin.create.doneIn` | Entrada registrada para {name}. | Clock-in recorded for {name}. |
| `admin.create.doneOut` | Salida registrada para {name}. | Clock-out recorded for {name}. |

**Employees, detail, schedule, PIN**

| Key | ES | EN |
|---|---|---|
| `admin.employees.title` | Empleados | Employees |
| `admin.employees.new` | Nuevo empleado | New employee |
| `admin.employees.search` | Buscar por nombre o código | Search by name or code |
| `admin.employees.filter` | Estado | Status |
| `admin.employees.filterAll` | Todos | All |
| `admin.employees.filterActive` | Activos | Active |
| `admin.employees.filterInactive` | Inactivos | Inactive |
| `admin.employees.filterLocked` | Bloqueados | Locked |
| `admin.employees.branchFilter` | Sucursal | Branch |
| `admin.employees.count.one` | 1 empleado | 1 employee |
| `admin.employees.count.other` | {n} empleados | {n} employees |
| `admin.employees.loading` | Cargando empleados… | Loading employees… |
| `admin.employees.empty` | Aún no hay empleados. | There are no employees yet. |
| `admin.employees.emptyFiltered` | Ningún empleado coincide. | No employee matches. |
| `admin.employees.col.employee` | Empleado | Employee |
| `admin.employees.col.role` | Rol | Role |
| `admin.employees.col.branch` | Sucursal | Branch |
| `admin.employees.col.status` | Estado | Status |
| `admin.employees.col.schedule` | Horario | Schedule |
| `admin.employees.noBranch` | Sin sucursal | No branch |
| `admin.employees.consent` | Consentimiento: {value} | Consent: {value} |
| `admin.employees.yes` | Sí | Yes |
| `admin.employees.no` | No | No |
| `admin.employees.createUncertain` | No se pudo confirmar si el empleado fue creado. Revise la lista; si aparece, restablezca su PIN. | We could not confirm that the employee was created. Check the list; if it appears, reset the PIN. |
| `admin.employee.back` | Empleados | Employees |
| `admin.employee.profile` | Perfil | Profile |
| `admin.employee.code` | Código de empleado | Employee code |
| `admin.employee.codeHint` | Letras mayúsculas, números y guiones, máximo 20. No se puede cambiar después. | Capital letters, numbers and hyphens, maximum 20. It cannot be changed later. |
| `admin.employee.codeLocked` | No se puede cambiar | Cannot be changed |
| `admin.employee.name` | Nombre completo | Full name |
| `admin.employee.jobTitle` | Puesto (opcional) | Job title (optional) |
| `admin.employee.role` | Rol | Role |
| `admin.employee.roleEmployee` | Empleado | Employee |
| `admin.employee.roleEmployeeHelp` | Marca entrada y salida desde su teléfono. | Clocks in and out from their phone. |
| `admin.employee.roleAdmin` | Administrador | Administrator |
| `admin.employee.roleAdminHelp` | Accede a este panel, a las fotos y a las ubicaciones de todos los empleados. | Accesses this panel and the photos and locations of all employees. |
| `admin.employee.branch` | Sucursal | Branch |
| `admin.employee.branchPlaceholder` | Elija una sucursal | Choose a branch |
| `admin.employee.branchAdminHelp` | Un administrador sin sucursal no puede marcar. | An administrator without a branch cannot clock in. |
| `admin.employee.create` | Crear empleado | Create employee |
| `admin.employee.creating` | Creando… | Creating… |
| `admin.employee.created` | Empleado creado. | Employee created. |
| `admin.employee.save` | Guardar cambios | Save changes |
| `admin.employee.saved` | Cambios guardados. | Changes saved. |
| `admin.employee.access` | Acceso y estado | Access and status |
| `admin.employee.stateActive` | Activo | Active |
| `admin.employee.stateInactive` | Inactivo | Inactive |
| `admin.employee.locked` | Bloqueado hasta {time} | Locked until {time} |
| `admin.employee.unlock` | Desbloquear | Unlock |
| `admin.employee.unlocked` | Cuenta desbloqueada. | Account unlocked. |
| `admin.employee.resetPin` | Restablecer PIN | Reset PIN |
| `admin.employee.deactivate` | Desactivar empleado | Deactivate employee |
| `admin.employee.activate` | Activar empleado | Activate employee |
| `admin.employee.deactivated` | Empleado desactivado. Sus sesiones se cerraron. | Employee deactivated. Their sessions were closed. |
| `admin.employee.activated` | Empleado activado. | Employee activated. |
| `admin.employee.selfNote` | Esta es su propia cuenta. No puede desactivarse ni quitarse el rol de administrador. | This is your own account. You cannot deactivate yourself or remove your administrator role. |
| `admin.employee.lastAdminNote` | Es el único administrador activo. Nombre a otro administrador antes de desactivarlo o cambiar su rol. | This is the only active administrator. Appoint another administrator before deactivating them or changing their role. |
| `admin.employee.notFound` | No se encontró lo que buscaba. | We could not find what you were looking for. |
| `admin.employee.error.code` | Escriba un código de letras mayúsculas, números y guiones (máximo 20). | Enter a code of capital letters, numbers and hyphens (maximum 20). |
| `admin.employee.error.name` | Escriba el nombre (máximo 120 caracteres). | Enter the name (maximum 120 characters). |
| `admin.employee.error.jobTitle` | El puesto admite hasta 80 caracteres. | The job title allows up to 80 characters. |
| `admin.employee.error.branch` | Elija una sucursal para un empleado. | Choose a branch for an employee. |
| `admin.confirm.deactivateTitle` | ¿Desactivar a {name}? | Deactivate {name}? |
| `admin.confirm.deactivateBody` | {name} no podrá ingresar ni marcar, y se cerrarán sus sesiones abiertas. Su historial se conserva. Puede activarlo de nuevo cuando quiera. | {name} will not be able to sign in or clock, and their open sessions will be closed. Their history is kept. You can activate them again at any time. |
| `admin.confirm.deactivate` | Desactivar | Deactivate |
| `admin.confirm.deactivating` | Desactivando… | Deactivating… |
| `admin.confirm.resetTitle` | ¿Restablecer el PIN de {name}? | Reset the PIN of {name}? |
| `admin.confirm.resetBody` | Se generará un PIN nuevo y se mostrará una sola vez. El PIN actual dejará de funcionar y se cerrarán todas sus sesiones. | A new PIN will be generated and shown only once. The current PIN will stop working and all their sessions will be closed. |
| `admin.confirm.resetSelf` | Es su propia cuenta: se cerrará su sesión al terminar. | This is your own account: you will be signed out when you finish. |
| `admin.confirm.reset` | Restablecer PIN | Reset PIN |
| `admin.confirm.resetting` | Restableciendo… | Resetting… |
| `admin.confirm.makeAdminTitle` | ¿Dar acceso de administrador a {name}? | Give {name} administrator access? |
| `admin.confirm.makeAdminBody` | Podrá ver las fotos, las ubicaciones y los datos de todos los empleados, y aprobar o corregir marcas. | They will be able to see the photos, locations and data of all employees, and approve or correct entries. |
| `admin.confirm.makeAdmin` | Hacer administrador | Make administrator |
| `admin.confirm.removeAdminTitle` | ¿Quitar el rol de administrador a {name}? | Remove the administrator role from {name}? |
| `admin.confirm.removeAdminBody` | Perderá el acceso a este panel y se cerrarán sus sesiones. | They will lose access to this panel and their sessions will be closed. |
| `admin.confirm.removeAdmin` | Quitar rol | Remove role |
| `admin.confirm.saving` | Guardando… | Saving… |
| `admin.confirm.leaveTitle` | ¿Salir sin guardar? | Leave without saving? |
| `admin.confirm.leaveBody` | Los cambios del horario se perderán. | The schedule changes will be lost. |
| `admin.confirm.leave` | Salir | Leave |
| `admin.confirm.stay` | Seguir editando | Keep editing |
| `admin.pin.titleCreated` | Empleado creado | Employee created |
| `admin.pin.titleReset` | PIN restablecido | PIN reset |
| `admin.pin.subtitle` | PIN inicial de {name} ({code}) | Initial PIN of {name} ({code}) |
| `admin.pin.subtitleReset` | PIN nuevo de {name} ({code}) | New PIN of {name} ({code}) |
| `admin.pin.spoken` | PIN: {digits} | PIN: {digits} |
| `admin.pin.hiddenSpoken` | PIN oculto | PIN hidden |
| `admin.pin.copy` | Copiar PIN | Copy PIN |
| `admin.pin.copied` | PIN copiado | PIN copied |
| `admin.pin.copiedStatus` | PIN copiado. | PIN copied. |
| `admin.pin.copyFailed` | No se pudo copiar. Seleccione el PIN y cópielo manualmente. | Could not copy. Select the PIN and copy it manually. |
| `admin.pin.hide` | Ocultar PIN | Hide PIN |
| `admin.pin.show` | Mostrar PIN | Show PIN |
| `admin.pin.warning` | Este PIN se muestra una sola vez. Al cerrar esta ventana no podrá verlo de nuevo; si se pierde, deberá restablecerlo. | This PIN is shown only once. When you close this window you will not be able to see it again; if it is lost, you must reset it. |
| `admin.pin.handover` | Entregue el PIN a la persona en privado y recomiéndele no compartirlo. | Give the PIN to the person in private and advise them not to share it. |
| `admin.pin.selfReset` | Es su propia cuenta: al cerrar esta ventana se cerrará su sesión y deberá ingresar con este PIN. | This is your own account: closing this window will sign you out and you must sign in with this PIN. |
| `admin.pin.done` | Ya lo copié o anoté | I have copied or written it down |
| `admin.pin.confirmClose` | ¿Cerrar sin copiar el PIN? No podrá verlo de nuevo. | Close without copying the PIN? You will not be able to see it again. |
| `admin.pin.backToPin` | Volver al PIN | Back to the PIN |
| `admin.pin.discard` | Cerrar y descartar | Close and discard |
| `admin.pin.statusCreated` | Empleado creado. | Employee created. |
| `admin.pin.statusReset` | PIN restablecido. | PIN reset. |
| `admin.schedule.title` | Horario semanal | Weekly schedule |
| `admin.schedule.intro` | Marque los días que trabaja. Si la salida es anterior a la entrada, el turno cruza la medianoche. | Check the days worked. If the end is earlier than the start, the shift crosses midnight. |
| `admin.schedule.day` | Día | Day |
| `admin.schedule.worksDay` | Trabaja | Works |
| `admin.schedule.start` | Entrada | Start |
| `admin.schedule.end` | Salida | End |
| `admin.schedule.grace` | Tolerancia (min) | Grace (min) |
| `admin.schedule.note` | Nota | Note |
| `admin.schedule.dayOff` | Libre | Day off |
| `admin.schedule.overnight` | Turno nocturno | Overnight shift |
| `admin.schedule.worksAria` | Trabaja el {day} | Works on {day} |
| `admin.schedule.startAria` | Hora de entrada del {day} | Start time on {day} |
| `admin.schedule.endAria` | Hora de salida del {day} | End time on {day} |
| `admin.schedule.graceAria` | Tolerancia del {day} en minutos | Grace on {day} in minutes |
| `admin.schedule.copyFrom` | Copiar horario de… | Copy schedule from… |
| `admin.schedule.copy` | Copiar | Copy |
| `admin.schedule.copied` | Horario copiado. Revise y guarde. | Schedule copied. Review and save. |
| `admin.schedule.dirty` | Cambios sin guardar | Unsaved changes |
| `admin.schedule.save` | Guardar horario | Save schedule |
| `admin.schedule.discard` | Descartar cambios | Discard changes |
| `admin.schedule.saved` | Horario guardado. | Schedule saved. |
| `admin.schedule.pastNote` | Las llegadas tarde ya registradas no cambian. | Lateness already recorded does not change. |
| `admin.schedule.errorSummary` | Corrija estos datos para guardar el horario: | Fix these items to save the schedule: |
| `admin.schedule.errorSame` | La entrada y la salida no pueden ser iguales. | The start and end cannot be the same. |
| `admin.schedule.errorGrace` | La tolerancia debe estar entre 0 y 240 minutos. | The grace must be between 0 and 240 minutes. |
| `admin.schedule.errorTime` | Indique la hora. | Enter the time. |

**Branches**

| Key | ES | EN |
|---|---|---|
| `admin.branches.title` | Sucursales | Branches |
| `admin.branches.new` | Nueva sucursal | New branch |
| `admin.branches.edit` | Editar | Edit |
| `admin.branches.editTitle` | Editar sucursal | Edit branch |
| `admin.branches.col.name` | Sucursal | Branch |
| `admin.branches.col.radius` | Radio | Radius |
| `admin.branches.col.employees` | Empleados activos | Active employees |
| `admin.branches.col.status` | Estado | Status |
| `admin.branches.col.actions` | Acciones | Actions |
| `admin.branches.active` | Activa | Active |
| `admin.branches.inactive` | Inactiva | Inactive |
| `admin.branches.deactivate` | Desactivar | Deactivate |
| `admin.branches.activate` | Activar | Activate |
| `admin.branches.inUse` | Tiene {n} empleados activos. Trasládelos o desactívelos primero. | It has {n} active employees. Move or deactivate them first. |
| `admin.branches.empty` | Aún no hay sucursales. | There are no branches yet. |
| `admin.branches.loading` | Cargando sucursales… | Loading branches… |
| `admin.branches.name` | Nombre | Name |
| `admin.branches.paste` | Pegar coordenadas | Paste coordinates |
| `admin.branches.pasteHint` | Copie las coordenadas desde Google Maps y péguelas aquí; se separan solas. | Copy the coordinates from Google Maps and paste them here; they split automatically. |
| `admin.branches.pasteApplied` | Coordenadas aplicadas. | Coordinates applied. |
| `admin.branches.pasteComma` | Use punto como separador decimal, por ejemplo 9.9325. | Use a dot as the decimal separator, for example 9.9325. |
| `admin.branches.lat` | Latitud | Latitude |
| `admin.branches.lng` | Longitud | Longitude |
| `admin.branches.radius` | Radio permitido (m) | Allowed radius (m) |
| `admin.branches.radiusHint` | De 10 a 5000 metros. | From 10 to 5000 meters. |
| `admin.branches.check` | Revisar ubicación en Google Maps | Check location on Google Maps |
| `admin.branches.futureOnly` | Cambiar la ubicación o el radio solo afecta las marcas futuras. | Changing the location or radius only affects future entries. |
| `admin.branches.create` | Crear sucursal | Create branch |
| `admin.branches.save` | Guardar sucursal | Save branch |
| `admin.branches.saved` | Sucursal guardada. | Branch saved. |
| `admin.branches.activated` | Sucursal activada. | Branch activated. |
| `admin.branches.deactivated` | Sucursal desactivada. | Branch deactivated. |
| `admin.branches.confirmDeactivateTitle` | ¿Desactivar {branch}? | Deactivate {branch}? |
| `admin.branches.confirmDeactivateBody` | No se podrá asignar a nuevos empleados. Las marcas anteriores se conservan. Puede activarla de nuevo. | It can no longer be assigned to employees. Previous entries are kept. You can activate it again. |
| `admin.branches.error.name` | Escriba el nombre (máximo 120 caracteres). | Enter the name (maximum 120 characters). |
| `admin.branches.error.lat` | La latitud debe estar entre -90 y 90. | Latitude must be between -90 and 90. |
| `admin.branches.error.lng` | La longitud debe estar entre -180 y 180. | Longitude must be between -180 and 180. |
| `admin.branches.error.radius` | El radio debe ser un número entero entre 10 y 5000. | The radius must be a whole number between 10 and 5000. |

**Reports / export**

| Key | ES | EN |
|---|---|---|
| `admin.reports.title` | Reportes | Reports |
| `admin.reports.exportTitle` | Exportar asistencia a Excel | Export attendance to Excel |
| `admin.reports.dialogTitle` | Exportar a Excel | Export to Excel |
| `admin.reports.from` | Desde | From |
| `admin.reports.to` | Hasta | To |
| `admin.reports.branch` | Sucursal | Branch |
| `admin.reports.presets` | Atajos | Shortcuts |
| `admin.reports.presetMonth` | Mes actual | This month |
| `admin.reports.presetPrevMonth` | Mes anterior | Previous month |
| `admin.reports.presetFirstHalf` | Primera quincena | First half of the month |
| `admin.reports.presetSecondHalf` | Segunda quincena | Second half of the month |
| `admin.reports.presetWeek` | Últimos 7 días | Last 7 days |
| `admin.reports.period` | Período: {days} días (máximo 62). | Period: {days} days (maximum 62). |
| `admin.reports.download` | Descargar Excel | Download Excel |
| `admin.reports.generating` | Generando el archivo… | Generating the file… |
| `admin.reports.mayTake` | Puede tardar unos segundos. | It may take a few seconds. |
| `admin.reports.cancel` | Cancelar | Cancel |
| `admin.reports.done` | Archivo descargado: {file}. | File downloaded: {file}. |
| `admin.reports.doneStatus` | Exportación lista. | Export ready. |
| `admin.reports.again` | Generar de nuevo | Generate again |
| `admin.reports.includes` | Qué incluye | What it includes |
| `admin.reports.include1` | Resumen, Detalle, Ajustes e Info (4 hojas). | Summary, Detail, Adjustments and Info (4 sheets). |
| `admin.reports.include2` | Las horas aprobadas y las pendientes de revisión van por separado. | Approved hours and hours pending review are shown separately. |
| `admin.reports.include3` | No se clasifican horas extra; el archivo solo informa los minutos fuera de horario. | Overtime is not classified; the file only reports minutes outside the schedule. |
| `admin.reports.include4` | No incluye fotos ni coordenadas. | It does not include photos or coordinates. |
| `admin.reports.offline` | Necesita conexión para exportar. | You need a connection to export. |
| `admin.reports.errorRange` | La fecha final debe ser igual o posterior a la inicial. | The end date must be on or after the start date. |
| `admin.reports.errorTooLong` | El período no puede superar 62 días (elegidos: {days}). | The period cannot exceed 62 days (chosen: {days}). |
| `admin.reports.errorDate` | Indique la fecha. | Enter the date. |

**Shared admin strings:** `admin.close` Cerrar / Close (reuse `common.close`), `admin.retry` (reuse `common.retry`), `admin.yes/no` above. `admin.date.long` etc. are formats, not strings.

### 5.4 New `error.*` keys in `resources.ts` (14 backend codes, "usted")

| Key | ES | EN |
|---|---|---|
| `error.ENTRY_CHANGED` | Otra persona modificó esta marca. Se actualizó la información; revísela e intente de nuevo. | Someone else changed this entry. The information was updated; review it and try again. |
| `error.INVALID_STATUS_CHANGE` | La marca ya está en ese estado o no puede cambiar de esa forma. | The entry is already in that state or cannot change that way. |
| `error.ENTRY_ALREADY_EXISTS` | Este empleado ya tiene esa marca para el día seleccionado. | This employee already has that entry for the selected day. |
| `error.CLOCK_IN_REQUIRED` | Registre la entrada de ese día antes de la salida. | Record the clock-in for that day before the clock-out. |
| `error.INVALID_ENTRY_TIME` | La hora está en el futuro, fuera de la jornada o no es posterior a la entrada. | The time is in the future, outside the work day, or not after the clock-in. |
| `error.EMPLOYEE_CODE_TAKEN` | Otro empleado de esta empresa ya usa ese código. | Another employee of this company already uses that code. |
| `error.BRANCH_NAME_TAKEN` | Otra sucursal ya tiene ese nombre. | Another branch already has that name. |
| `error.BRANCH_REQUIRED` | Los empleados deben tener una sucursal. | Employees must have a branch. |
| `error.BRANCH_INACTIVE` | La sucursal seleccionada está inactiva. | The selected branch is inactive. |
| `error.BRANCH_IN_USE` | Traslade o desactive a sus empleados antes de desactivar la sucursal. | Move or deactivate its employees before deactivating the branch. |
| `error.LAST_ADMIN` | La empresa debe conservar al menos un administrador activo. | The company must keep at least one active administrator. |
| `error.SELF_CHANGE_NOT_ALLOWED` | No puede desactivarse ni quitarse el rol de administrador. | You cannot deactivate yourself or remove your own administrator role. |
| `error.DATE_RANGE_TOO_LARGE` | Elija un período de máximo 62 días, con el inicio antes del fin. | Choose a period of at most 62 days, with the start before the end. |
| `error.REPORT_TOO_LARGE` | Hay demasiados datos. Reduzca el período o elija una sucursal. | There is too much data. Narrow the period or choose a branch. |

Code to display (admin): all 409/400 above appear as an `ErrorBox` in the dialog or form, except those bound to a field (`EMPLOYEE_CODE_TAKEN` and `BRANCH_NAME_TAKEN` under their field; `INVALID_ENTRY_TIME` under the time field; `BRANCH_REQUIRED` / `BRANCH_INACTIVE` under the branch select; `DATE_RANGE_TOO_LARGE` under the "Hasta" field). `ENTRY_CHANGED` triggers a reload as described in 3.5/3.6. `TOO_MANY_ATTEMPTS` (429) uses `error.tooManyAttemptsMinutes` with `Retry-After`. `SERVER_BUSY` (503): `error.SERVER_BUSY` and the user presses Reintentar (the existing text says "Lo intentaremos de nuevo automáticamente" which is false for admin writes; proposed admin-only override `admin.error.serverBusy` "El servidor está ocupado. Inténtelo de nuevo en unos segundos." / "The server is busy. Try again in a few seconds."; see 8.12).

### 5.5 Date, time and number formats

All formatting through `Intl` with `timeZone` = identity `timeZone` (the company zone), never the admin PC zone; locales `es-CR` / `en-US`; `hourCycle: 'h23'`; first letter of a heading date upper-cased with `toLocaleUpperCase(locale)`.

| Item | Options | ES | EN |
|---|---|---|---|
| Dashboard title date | `{weekday:'long', day:'numeric', month:'long'}` | miércoles 28 de octubre | Wednesday, October 28 |
| Date in captions and queue cards | `{weekday:'long', day:'numeric', month:'long', year:'numeric'}` | miércoles 28 de octubre de 2026 | Wednesday, October 28, 2026 |
| Short date (history) | `{day:'numeric', month:'short'}` + `, ` + time | 28 oct, 10:14 | Oct 28, 10:14 |
| Table times | `{hour:'2-digit', minute:'2-digit'}` | 07:58 | 07:58 |
| Hours | `duration.hm` | 8 h 04 min | 8 h 04 min |
| `LocalDate` strings (`workDate`, `date`) | format at noon UTC with `timeZone:'UTC'` so the day never shifts (Stage 3 rule) | | |
| `datetime-local` value | `YYYY-MM-DDTHH:mm`, built from an instant with the company zone (`Intl.DateTimeFormat.formatToParts`), parsed back as a company-local string and sent as `localTime` | | |
| Zone label | `Intl.DateTimeFormat(locale,{timeZone, timeZoneName:'long'})` part + IANA id | Hora estándar central (America/Costa_Rica) | Central Standard Time (America/Costa_Rica) |
| Distance / accuracy | integer, `useGrouping:false` | 420 m · ±12 m | same |
| Coordinates | `toFixed(6)`, dot decimal in both languages | 9.932500, -84.079600 | same |
| Date range days | inclusive count of `YYYY-MM-DD` strings | 15 días | 15 days |
| Weekday names (schedule) | `{weekday:'long'}` on fixed reference dates, capitalised | Lunes… | Monday… |

Test fixtures: `2026-10-08T05:30:00Z` in `America/Costa_Rica` reads "7 de octubre" (Stage 3), and a stubbed browser zone `Asia/Tokyo` must not change any output.

---

## 6. Accessibility notes

Target: WCAG 2.2 AA (2.4.11 focus not obscured, 2.5.8 target size, 3.3.7 redundant entry, 3.3.8 accessible authentication, 2.2.2 pause / stop / hide).

### 6.1 Landmarks and headings

| Screen | Order |
|---|---|
| Every page | skip link > `header` > `nav aria-label` > `main` (h1) > `footer`. One `<main id="main" tabindex="-1">`. |
| Dashboard | h1 "Asistencia de hoy" > (toolbar) > KPI `ul` (no headings inside cards) > table (caption hidden) |
| Evidence dialog | h2 title > h3 "Horas", h3 "Ubicación", h3 "Otra marca del día", h3 "Historial de ajustes" |
| Review | h1 > h2 "Marcas" (list heading, `tabindex=-1`) > h3 per card (employee name) |
| Employee detail | h1 name > h2 "Perfil", h2 "Acceso y estado", h2 "Horario semanal" |
| Dialogs | the title is the first heading (h2) |

### 6.2 Focus order and management

| Transition | Focus goes to |
|---|---|
| Route change | the page h1 (`tabindex=-1`, visible ring) |
| Skip link activated | `#main` |
| Dashboard date / branch change | stays on the control; polite status after load |
| Dashboard auto-refresh | no focus change, no announcement; rows keyed by id |
| Open evidence / action dialog | dialog title (evidence), reason field (reject), time field (correct), reason field (approve, create) |
| Close dialog (cancel) | the opener |
| Action success from the queue | next card's h3, or the empty state / list heading |
| Action success from the evidence dialog | the same dialog refreshed; focus on the title if the opener vanished |
| Create entry success | the row's evidence button, or the h1 |
| `ENTRY_CHANGED` | the reloaded card's h3 |
| Form validation error | first invalid field; error summary (schedule) gets focus first |
| Server error in a dialog | the `ErrorBox` (it focuses itself) |
| Employee created / PIN reset | PIN dialog title; on close the opener (or the new employee's link) |
| Page-level destructive confirm cancelled | the button that opened it |
| Deactivate success | the status h2/"Activar empleado" button |
| Re-auth dialog closes | previously focused element |
| Export success | stays on the "Descargar Excel" button; status text announces |

Tab order inside dialogs follows the visual order (title > content > primary > secondary). Native `<dialog>` supplies the trap, inertness and Esc. Stacked dialogs: only the top one is interactive.

### 6.3 Announcements

Each admin page owns one polite `role="status"` region (`StatusMessage`), visually hidden. Dialogs use their own local `aria-live="polite"` span (modal inertness would swallow the page's region), exactly as in Stage 3.

| Event | Text |
|---|---|
| Dashboard loaded after date / branch change | "Asistencia del jueves 29 de octubre cargada. 12 empleados." |
| Review list loaded / filter changed | "63 marcas." |
| Entry approved / rejected / corrected | "Marca aprobada. Quedan 62 por revisar." |
| Entry created | "Salida registrada para Diego Soto." |
| Reason preset applied (dialog) | "Motivo completado." |
| Phone time applied (dialog) | "Hora del teléfono aplicada." |
| Employee created / PIN reset / saved / unlocked / deactivated | "Empleado creado." / "PIN restablecido." / "Cambios guardados." / "Cuenta desbloqueada." / "Empleado desactivado. Sus sesiones se cerraron." (never any digit of a PIN) |
| Schedule saved / copied | "Horario guardado." / "Horario copiado. Revise y guarde." |
| Coordinates split | "Coordenadas aplicadas." |
| Export ready | "Exportación lista." |
| Session renewed | "Sesión renovada. Repita la última acción." |
| Employee search | "5 empleados." (debounced) |

Errors use `role="alert"` through `ErrorBox` (one at a time). The nav badge, KPI values and the "Actualizado a las…" text are **not** live.

### 6.4 Colour is never the only signal

| Meaning | Signal 1 | Signal 2 | Signal 3 |
|---|---|---|---|
| Day status (11) | family colour | distinct icon (1.3) | chip text |
| Entry status | family colour | icon | text "Válida / En revisión / Rechazada" |
| KPI category | top border colour | icon | label text |
| Late | amber | clock icon | "Tarde 17 min" |
| Edited time | none | pencil icon | "editada" |
| Self-approval | amber | alert-triangle icon | "Autoaprobado" |
| Locked account | red-brown | lock icon | "Bloqueado hasta 10:30" |
| Active nav item | teal fill | bold | `aria-current="page"` |
| Selected filter | fill | bold + underline | native radio state |
| Disabled guard | grey | visible reason sentence | `aria-disabled` + `aria-describedby` |
| One-time PIN warning | amber box | alert-triangle icon | "se muestra una sola vez" |
| Photo missing | grey frame | camera-off icon | "Sin foto" |

`forced-colors: active`: chips, cards, notices keep a 1 px border; icons use `currentColor`.

### 6.5 Table semantics

- `AttendanceTable`: `<caption>` (hidden) names date, branch and count; `th scope="col"`; employee in `th scope="row"`; no `role="grid"` (not an editable grid, so arrow-key grid navigation is not promised). Tab moves between the buttons in cells.
- Empty cells are `—` with a visually hidden meaning ("Sin registrar", "sin horas"); the evidence cell with no evidence contains `—` (`aria-hidden`) and hidden text "Sin evidencia".
- Evidence button accessible name begins with its visible text (WCAG 2.5.3): "Foto + GPS, ver evidencia de María Rojas, entrada 07:58".
- Below 768 px the list uses `ul` / `article` / `dl`, never a CSS-reflowed table.
- No sortable columns, so no `aria-sort`. If sorting is added later, headers become buttons with `aria-sort`.

### 6.6 Forms and dialogs

- Every field has a visible `<label>`; required fields use a visible `*` plus hidden "obligatorio" and `aria-required`; hints are tied with `aria-describedby`; errors are inline text under the field (`aria-invalid`, text starts with the problem and states the fix), not colour-only (icon + text).
- `datetime-local` and `date` are native controls: operable by keyboard in current Chrome, Edge and Firefox; the format hint is the zone label plus the browser's own placeholder. The company zone is always printed next to a time field because the control displays no zone.
- Reason presets are real buttons, not custom radio widgets.
- Autocomplete: `off` on code, name and reason; the PIN keypad is never an input.
- Time limits: none. The PIN dialog never auto-closes; the 60 s auto-refresh is optional (checkbox, default on) and pauses while a dialog is open or the tab is hidden - this satisfies 2.2.2 for the only auto-updating content.
- 3.3.7 redundant entry: the re-auth dialog shows stored codes; the action dialogs prefill employee, date and current time; the export dialog prefills date and branch.
- 3.3.8 accessible authentication: re-auth uses the existing keypad; no cognitive test; the PIN is generated by the system, so the admin does not invent or memorise one.
- Destructive buttons are outlined with an icon and a specific verb; the safe action is the initial focus of every confirm.

### 6.7 Known accessibility issues found while designing

1. **Auto-refreshing dashboard (plan: every 60 s)** triggers WCAG 2.2.2 (pause / stop / hide). Resolved with the checkbox and silent in-place updates (8.5).
2. **Native `datetime-local`** has weak and inconsistent screen reader support in Firefox and Safari desktop, and shows no zone. Mitigated with the zone label and hint; alternative (split date and time inputs) is possible if testing finds problems.
3. **The PIN reveal necessarily exposes digits in the DOM.** Limited to one hidden sibling, dropped on close. A screen reader user hears the PIN; this is inherent to the feature.
4. **`navigator.clipboard`** may leave the PIN in the clipboard history of the OS; the UI cannot clear it.
5. **Generic external-link text** "Ver en el mapa" is not self-describing; mitigated with the hidden new-tab suffix and the privacy line.
6. **Plan's `\btú\b` regression test cannot match** (ASCII `\b`); replaced (5.2).
7. **Stage 3 design said "no skip link"**; the admin shell has a header, a nav and a language switcher before the content, so a skip link is required.
8. **Row hover and zebra striping** are decorative only; row boundaries rely on text and spacing (divider colour 1.59:1 is not relied upon).
9. **Desktop target size of 36 px** is below the project's 44 px rule (8.9) but above WCAG 2.5.8 (24 px); coarse pointers and < 1024 px get 44 px.
10. **The mockup PDF could not be verified visually** (no renderer in the sandbox).

---

## 7. Motion and feedback

- Press state, spinner, dialog transitions and reduced-motion handling are exactly Stage 3 section 7 (80 ms scale on buttons, 150 ms dialog fade; all removed under `prefers-reduced-motion`; spinner static with text).
- Refresh: the table never flashes. A refresh updates in place; only the date / branch change dims the old table to 60 % opacity until the new data replaces it (no animation under reduced motion beyond the opacity change, which is instant).
- Row removal in the review queue: instant (no collapse animation) so the focus move is immediate.
- Copy feedback: the "PIN copiado" label swap is instant; reverts after 5 s.
- No haptics in the admin panel (desktop). No shake on error, no toasts that disappear (every message is either persistent or announced through the status region; there are no auto-dismissing toasts).

---

## 8. Conflicts with the plan and proposed resolutions (coder must read)

1. **Map link provider (plan Decisions item 3, CSP section, Security item 6, tests vs resolved Q6).** The plan text still says OpenStreetMap; resolved Q6 says Google Maps. **Resolution:** Google Maps link `https://www.google.com/maps/search/?api=1&query={lat},{lng}` as a plain `<a target="_blank" rel="noopener noreferrer">`, built from numbers only (`toFixed(6)`), never embedded. Add a visible one-line disclosure that coordinates go to Google when the link is clicked (`admin.evidence.mapPrivacy`) and a hidden "(se abre en una pestaña nueva)". The branch link ("Ver la sucursal") and the branch-form check link use the same URL pattern. `Referrer-Policy: no-referrer` stays. CSP unchanged. Tests and the CSP paragraph that mention `openstreetmap.org` must be renamed to `google.com/maps`.
2. **Review-queue badge vs queue default range (plan: `review-queue/count` counts all pending, `GET /review-queue` defaults to the last 31 days).** A badge of 12 can sit above a list of 9. **Resolution:** the queue defaults to **no lower date bound for `PENDING_REVIEW`** (the `from`/`to` filters are optional narrowing); the 31-day default stays for `REJECTED`. If the backend keeps the 31-day default, the page must show an info notice "Hay {n} marcas más antiguas fuera de este período" with a button to widen the range. The dashboard KPI card keeps its per-date meaning and says so in its subtitle (`admin.kpi.toReviewHelp`).
3. **Register "usted" everywhere** (resolved Q14) overrides plan hand-off item 10 ("PWA keeps tú") and the Stage 3 design note 5.1 ("tú in Spanish throughout"). **Resolution:** section 5.2 lists every affected key (about 65); `home.adjusted` added; coder updates `resources.ts` and these existing test files with Spanish assertions: `App.routes.test.tsx`, `pages/EmployeeHome.a11y.test.tsx`, `pages/LoginPage.test.tsx`, `pages/ConsentPage.test.tsx`, `pages/EmployeeHome.test.tsx`, `pages/EmployeeHome.capture.test.tsx`, `sync/reauth.test.tsx`, `components/LogoutConfirm.test.tsx`, `components/EntryResult.test.tsx` (55 matching lines found by search). The Stage 3 design doc is not edited by this stage; add a "superseded for Spanish copy by stage-4" line to it when convenient.
4. **Branch filter placement (plan: top header).** The review queue and employee pages have no branch parameter, so a global header filter would silently do nothing on them. **Resolution:** the branch select lives in the dashboard toolbar (and the Reports form) and the URL (`?branch=`); the header keeps company, "Mi marca", language, logout.
5. **Auto-refresh (plan: every 60 s with no way to stop it).** Violates WCAG 2.2.2. **Resolution:** an "Actualizar automáticamente" checkbox (default on), updates in place without announcement or focus change, paused while any dialog is open or the tab is hidden, plus the manual "Actualizar" button and "Actualizado a las…" text.
6. **"Registrar entrada/salida" placement (plan: a dashboard row action, but the mockup has no actions column).** With 7 columns the table does not fit 768 px. **Resolution:** no actions column; the secondary button sits under the status chip in the Estado cell on `NOT_CLOCKED` / `MISSING_CLOCK_OUT` rows.
7. **Export entry point (plan: Reports page; in 4a/4b the dashboard button links to it, in 4c it downloads the single day directly; user brief: "export dialog").** **Resolution:** from 4c the dashboard button opens `ExportDialog` (same `ExportForm`, pre-filled with the selected date as `from = to` and the selected branch, editable up to 62 days) so the admin sees the range limit and errors; `/admin/reports` shows the same form inline. In 4a and 4b the "Exportar a Excel" button and the "Reportes" nav item are **not rendered** (a button that goes nowhere is worse than none); page 2 of the mockup shows the button, so it appears when 4c ships. If the user wants the mockup parity in the 4a demo, render it `aria-disabled` with the visible hint "Disponible próximamente".
8. **Evidence cell with two entries (plan: one button "Foto + GPS").** A day has two entries that can have different evidence. **Resolution:** one button per row, targeting the entry that needs attention (a `PENDING_REVIEW` or `REJECTED` one), otherwise the clock-in; inside the dialog the "Otra marca del día" section switches to the other entry. The row's `DayStatus` already tells which one is wrong.
9. **Target size (project rule: 44 px; plan: >= 24 px on desktop, 44 px on tablets via media query).** **Resolution:** 36 px controls with a fine pointer at >= 1024 px, 44 px for coarse pointers or < 1024 px, 44 px always for dialog buttons and nav (1.5). Documented exception to Stage 3's rule.
10. **Data the UI needs that the plan's API shapes lack.**
    - `selfApproved: boolean` on each `adjustments[]` item (or `adminId` plus the employee id) - the plan's `adminName` alone cannot tell the UI that the admin is the employee. Also `ownEntry: boolean` on `ReviewItem` / `AdminEntryDetail` (employee id equals the principal id) so the dialogs can warn before the action; alternatively expose `employeeId` of the principal in the identity.
    - "editada" must be derived from `effectiveTime !== serverTime` (and `source !== 'ADMIN'`), **not** from `adjusted`, which is also true after a plain approval and would label an approved-only entry as edited. The PWA note `home.adjusted` should use the same derivation (or the API should redefine `EntrySummary.adjusted` as "time corrected"), otherwise employees see "Hora ajustada" after a simple approval.
    - Employee list needs the principal's own id only to detect the self guard; comparing `code` with the identity's employee code is enough because both are from the same company.
11. **Today's date for the Reports page.** The plan says defaults come from the server, but the Reports page calls no endpoint that returns `today`. **Resolution:** add `today` (company-local `LocalDate`) and `timeZone` to the `GET /api/admin/review-queue/count` response (`{ pending, today }`), which the shell already calls; the presets and defaults use it.
12. **`error.SERVER_BUSY` text says the app retries automatically**, which is true for the employee outbox but false for admin writes. **Resolution:** admin-only key `admin.error.serverBusy` (5.4); the employee key stays.
13. **Reason presets: plan lists 6, this design finalizes 7** (`admin.reasonPreset.*`: verifiedPhoto, authorizedBySupervisor, deviceFailure, forgotClockOut, phoneClockWrong, mistakenEntry, photoMismatch), mapped per action (5.3). The stored reason is the free text submitted.
14. **Hours format.** The mockup shows "8 h 04"; the existing `duration.hm` gives "8 h 04 min". **Resolution:** reuse `duration.hm` (clearer for screen readers, consistent with the PWA); the visual difference is only the unit suffix.
15. **PIN reveal dismissal.** The plan says only "dropped from state on close". **Resolution:** backdrop click disabled; Esc opens an inline confirmation; no timer; explicit "Ya lo copié o anoté" button; optional hide / show toggle; self-reset signs the admin out on close (3.10).
16. **New review reasons in the employee PWA** (resolved Q8 adopted `NO_GPS_ACCURACY` and `CLOCK_OUT_AFTER_SCHEDULE`): the PWA's `reviewReason.*` map and `contract.test.ts` need the two keys (5.2) so an employee whose entry carries them sees a translated reason instead of a raw code.
17. **Admin chunk icons and CSS budget.** The 17 new icons and the admin CSS go in the admin chunk (`AdminIcon.tsx`, `admin.css`); chip, table and KPI classes use the `adm-` prefix so no admin rule loads in the employee CSS. The tokens in 1.9 are tiny (about 0.3 KB gzip) and may live in `index.css` (shared) or at the top of `admin.css`; prefer `admin.css` to keep the employee CSS budget (<= 10 KB) untouched.
18. **Server footer and `HealthStatus`.** Moves into the admin `<footer>`; no visual conflict. The admin Spanish key "Servidor en línea" already exists in `health.online`.
19. **Review queue filter for employees** loads the employee list lazily when the "Empleado" select receives focus (avoids an extra request on every queue visit); fall back to showing "Todos" until loaded.
20. **Anything not designed because the plan defers it:** audit-log viewer, bulk approve, idle auto-lock, dark mode, column sorting and search on the dashboard table. Dashboard search is cheap and valuable above ~50 employees; recommended as a "Later" item (client-side, accent-insensitive, same pattern as the employees list).

---

Files created: `docs/design/stage-4-admin-panel.md` (this file). Files changed: none. No component or CSS was implemented.
