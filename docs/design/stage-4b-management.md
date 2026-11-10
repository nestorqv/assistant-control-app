# Stage 4b - Management (branches, employees, schedules): UI/UX design spec

Status: DESIGNED 2026-11-03. Input to the `coder`. Spec only: no component, CSS or i18n file was written or changed.
Plan: `docs/plans/stage-4b-management.md` (read with "Open Questions: RESOLVED (2026-11-03, user)", D2, D5, D12-D14, "Frontend"). Base spec: `docs/design/stage-4-admin-panel.md` (called "4a spec" below: tokens 1.x, layout 2, screens 3.8-3.12, components 4, i18n 5.3, a11y 6). **This file refines 4a spec 3.8-3.12 and wins where it differs; everything not mentioned stays as in the 4a spec.** Section 9 lists conflicts with the plan (the plan was not changed).

## 0. Discovery (done first)

- Stack, unchanged: React 19, react-router 8 with declarative `BrowserRouter`, Vite 8, plain CSS (`frontend/src/index.css` plus `src/admin/admin.css`, `admin-content.css`, `admin-dialogs.css`), no Tailwind, no UI library, flat typed i18n (`i18n/adminResources.ts`, `adminEs`/`adminEn`, `{name}` placeholders, `.one`/`.other` plurals). No form library: native controlled inputs.
- Live in `frontend/src/admin/`: `AdminLayout` (nav is a `<ul>` of `NavLink`s with `AdminIcon` + `<span>`), `AdminIcon` (admin-only path map; **has no `users`, `building`, `lock` or `copy`**), `components/PageParts` (`PageHeading`, `QueryError`, `Pagination`), `ReasonField`, `CreateEntryDialog`, `ReviewActionDialog`, `Chips`, `LocalDateTimeField`, `useAdminQuery`, `useMediaQuery`. Shared: `ModalDialog`, `ConfirmDialog` (props `open,title,body: ReactNode,confirmLabel,cancelLabel,tone,busy,busyLabel,onConfirm,onCancel`; initial focus on cancel; destructive = `alertdialog`), `ErrorBox`, `Icon`.
- Existing `adm-` classes reused as they are: `adm-heading`, `adm-sub`, `adm-hint`, `adm-toolbar`, `adm-filters`, `adm-icon-button`, `adm-check`, `adm-dim`, `adm-error`, `adm-chip` (+ `--teal|amber|violet|redbrown|grey`), `adm-table-wrap`, `adm-table`, `adm-employee*`, `adm-avatar`, `adm-link-button`, `adm-cards`, `adm-card`, `adm-pager`; global `.button--primary|secondary|destructive|link`, `.notice(--warn|--error|--info)`, `.field`, `.modal`, `.visually-hidden`.
- **No new design token, no new colour pair, no new font size.** Every colour, size and contrast value below is one measured in 4a spec 1.2 / 1.4 / 1.5 (chip families, `#0F5F56` on white 7.52, `#1C1F1E` on `#E6F0EE` 14.29, red-brown chip text 7.68, amber 7.84, muted `#4A5250`). Section 1 lists the few new `adm-` classes.

## Contents

1. New CSS classes and the four new icons
2. Cross-cutting patterns (page states, errors, stale record, 429, focus, announcements)
3. Navigation
4. Branches
5. Employees: list, create form, PIN reveal
6. Employee detail: profile, access panel, PIN reset, last PIN reset
7. Weekly schedule editor and the unsaved-changes guard
8. Mobile (< 768 px) summary, accessibility checklist, i18n additions
9. Conflicts with the plan

Wireframe conventions as in 4a spec 3: `[ ]` button, `( )` chip, `▾` select, `☐/☑` checkbox, `◉/○` radio. **Examples use placeholders ("Nombre Apellido", code `ABC-123`, branch "Sucursal Uno"); no real-looking personal data.** All text is Spanish "usted" with the English twin in section 8 / 4a spec 5.3.

---

## 1. New CSS classes and the four new icons

### 1.1 Classes (prefix `adm-`, in `admin-content.css` / `admin-dialogs.css`; none in the employee CSS; no `style=` attributes)

| Class | Used by | Rules |
|---|---|---|
| `adm-segmented` | status filter of the employees list | `<fieldset>` with visually hidden `<legend>`; radios visually hidden but focusable, each `<label>` is a bordered segment (`1px solid var(--color-border)`, `min-height: var(--target-ctl)`). Checked segment: fill `--color-primary`, text white (7.52:1), `font-weight: 700` and underline. `:focus-visible` on the input draws the standard 3 px ring on the label (`input:focus-visible + span`). Forced colours: checked segment `Highlight`/`HighlightText`. |
| `adm-panel` | detail page sections | bordered card (`1px solid var(--color-border)`, `--radius-lg`, padding `--space-5`), h2 inside, no shadow. |
| `adm-form-grid` | profile form, branch form, create form | single column; `gap: var(--space-4)`; label above control; hint and error under the control. >= 1024 px inside a panel: still one column (panels are narrow). |
| `adm-note` | self / last-admin / admin-PIN notes, "no horario" hint | `notice notice--info` look with `info` icon; always visible text (never a tooltip). |
| `adm-schedule` | schedule table and the mobile fieldset cards | table cells `padding: .5rem .75rem`; inputs `min-height: var(--target-input)` below 1024 px, 40 px above; `adm-schedule__row--error` adds `2px` error-colour left border plus the inline text (never colour only). |
| `adm-pin` | PIN reveal | `.adm-pin__panel` background `--color-info-bg` (`#E6F0EE`), padding `--space-4`, radius `--radius-md`, `border: 1px solid var(--color-border)`; `.adm-pin__digits` monospace, `font-size: 2rem` (1.75rem below 30rem), `letter-spacing: .12em`, `user-select: all`, two groups of four separated by `1.25ch` gap, **`white-space: nowrap`** (4+4 never splits into a third line; at 320 px it fits: 8 glyphs x ~1.2rem + gap < 18rem). Contrast 14.29:1. |
| `adm-reason-group` | PIN-reset reason radios | `<fieldset>`; radios are the native control with a 24 px box inside a >= 44 px row (label is the hit area); one per row, no segmented look (5 items with long ES texts). |
| `adm-locked` | locked indicator | red-brown chip family (`adm-chip--redbrown`) with the `lock` icon and text "Bloqueado hasta {time}". |

### 1.2 Icons (added to `ADMIN_PATHS` in `src/admin/AdminIcon.tsx`; 24x24 viewBox, 2 px stroke, `currentColor`, the existing renderer, so the employee bundle is untouched)

The plan lists two new icons (`users`, `building`). The screens below also need **`lock`** (locked chip, section 6) and **`copy`** (the "Copiar PIN" button): neither exists in `AdminIcon.tsx` or `Icon.tsx` (see conflict C1). Paths, drawn to match the existing set (about 0.4 KB gzip together):

```tsx
users: (
  <>
    <circle cx="9" cy="8" r="3.5" />
    <path d="M2.5 20v-1a4.5 4.5 0 014.5-4.5h4a4.5 4.5 0 014.5 4.5v1M16 4.8a3.5 3.5 0 010 6.4M21.5 20v-1a4.5 4.5 0 00-3-4.2" />
  </>
),
building: <path d="M5 21V4h10v17M15 10h4v11M3 21h18M9 8h2M9 12h2M9 16h2" />,
lock: <path d="M6 11h12v9H6zM8.5 11V8a3.5 3.5 0 017 0v3" />,
copy: <path d="M9 9h11v11H9zM5 15V4h11" />,
```

Silhouettes are distinct from each other and from the existing icons (`users` = two heads; `building` = tall block with window ticks and a side wing; `lock` = body with shackle; `copy` = two offset squares). Icons are decorative (`aria-hidden`, `focusable="false"` already in the renderer); the adjacent text always carries the meaning. `users` and `building` are used only by the nav; `lock` by the locked chip; `copy` by "Copiar PIN".

---

## 2. Cross-cutting patterns

### 2.1 Page states (all three list/detail pages)

| State | Behaviour |
|---|---|
| Loading | `<p role="status" class="adm-hint">` with the `spinner` icon and the page text ("Cargando empleados…", "Cargando sucursales…", "Cargando empleado…"). The h1 and toolbar are already rendered; no skeleton, no shimmer. Spinner static under reduced motion. |
| Loaded | content. |
| Refreshing after a write | old data stays; the response of the write replaces the affected record (plan: "every successful write replaces the local view with the response"). No flash, no layout shift. |
| Empty | info panel with text and a primary action (employees: "Aún no hay empleados." + "Nuevo empleado"; branches: "Aún no hay sucursales." + "Nueva sucursal"). Focus is not moved. |
| Empty after filtering | "Ningún empleado coincide." + `Limpiar filtros` (`admin.review.clear`); no focus move. |
| Load error (network, 5xx, 503) | `QueryError` (`ErrorBox`, `role="alert"`) with the mapped text + "Reintentar" (`common.retry`). 503 uses `admin.error.serverBusy`. Previously loaded data, if any, stays visible and dimmed (`adm-dim`). |
| 404 on the detail page | panel "No se encontró lo que buscaba." + link "Volver a Empleados"; h1 reads "Empleado no encontrado" (generic, no id). |
| 401 | the existing `AdminReauthDialog`; typed form content is kept; nothing is replayed (4a spec 3.2). |
| 403 on a page (demoted while open) | the bare "Su sesión terminó" page of 4a spec 3.1. |

### 2.2 Error placement for writes (server text is always the fixed localized `error.*` text; **the client never appends the submitted name, code, note or PIN**)

| Server result | Where it shows | Focus |
|---|---|---|
| Client validation (all client rules mirror the server) | inline under the field, `aria-invalid`, `aria-describedby`; a multi-field form also lists the first problem in the field it focuses | first invalid field |
| 400 `INVALID_REQUEST` (should be caught client-side) | `ErrorBox` at the top of the dialog/section: `error.INVALID_REQUEST` | the box |
| 409 `EMPLOYEE_CODE_TAKEN` | under the **code** field (`error.EMPLOYEE_CODE_TAKEN` detail) | code field |
| 409 `BRANCH_NAME_TAKEN` | under the **name** field of the branch form | name field |
| 400 `BRANCH_REQUIRED`, 409 `BRANCH_INACTIVE` (create/edit) | under the **branch** select | branch select |
| 409 `BRANCH_INACTIVE` on "Activar empleado" | `ErrorBox` in the Access panel | the box |
| 409 `BRANCH_IN_USE` | `ErrorBox` above the branches list plus the row note (section 4.3) | the box |
| 409 `SELF_CHANGE_NOT_ALLOWED`, `LAST_ADMIN` | `ErrorBox` in the section that sent the request; the permanent note (6.3) already explains | the box |
| 409 `ADMIN_PIN_RESET_NOT_ALLOWED` | `ErrorBox` **inside the PIN-reset dialog** (it stays open); on closing, the detail reloads so the disabled state matches | the box |
| 409 `RECORD_CHANGED` | stale-record pattern 2.3 | the box |
| 429 `RATE_LIMITED` | `ErrorBox` with the text from `error.tooManyAttemptsMinutes` built from `Retry-After` (minutes, or seconds under 1 min), exactly as `TOO_MANY_ATTEMPTS`; the submit button **stays enabled** (the user may wait and retry; plan "429 on any write") | the box |
| 503 `SERVER_BUSY` | `ErrorBox` with `admin.error.serverBusy`; form kept; retry by resubmitting | the box |
| 404 on a write (record or branch gone / other tenant) | `ErrorBox` `error.NOT_FOUND`; list pages reload | the box |
| Network error on update / activate / deactivate / unlock / schedule | `ErrorBox` `error.network`; form kept; resubmitting after a lost success answers `RECORD_CHANGED` and the reload then shows the saved values (no silent double apply) | the box |
| Network error on **create** or **PIN reset** | create: `admin.employees.createUncertain` (no retry button; "revise la lista"); PIN reset: `error.network` box in the dialog with the same hint to check the "Último restablecimiento" line. No automatic retry (not idempotent) | the box |

One `ErrorBox` at a time per surface (it focuses itself); a new error replaces the previous one.

### 2.3 Stale record: 409 `RECORD_CHANGED`

Forms keep the `version` (or loaded week) of their last GET or write response.

```
┌ ErrorBox (role="alert") ─────────────────────────────────────────────┐
│ ⚠ Registro modificado                                                 │
│   Otra persona modificó este registro. Recargue e intente de nuevo.   │
│   Al recargar se descartan los cambios que no guardó.                 │
│   [ Recargar ]                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

- Texts: `error.RECORD_CHANGED` (title + detail), the extra line `admin.recordChanged.discards` (new key, conflict C4) and the button `admin.recordChanged.reload`.
- "Recargar" refetches the record (or the week), **replaces** the local form values with the fresh ones, clears dirty flags, and moves focus to the first field of the form (dialogs) or the h1 (pages). Polite status: "Datos actualizados." (`admin.recordChanged.reloaded`, new). Never auto-retries the write.
- **List rows** (branch activate / deactivate from the table, employee row has no write): there are no unsaved edits to lose, so the page refetches the list on its own (this is not a retry of the write) and shows the box **without** the "Recargar" button, with the line "Se actualizó la lista." in the same box (`admin.recordChanged.listUpdated`, new). Focus on the box.
- Schedule: same box inside the schedule section; "Recargar" refetches `GET .../schedule` and the grid.
- A dialog with a stale record stays open (branch edit) so the admin sees what changed after "Recargar" and can edit again.

### 2.4 Focus management (4b additions to 4a spec 6.2)

| Transition | Focus goes to |
|---|---|
| Route change (list, detail, branches) | the page h1 (`tabindex=-1`) |
| Open create/edit dialog | the dialog title (`tabindex=-1`), first Tab = first field |
| Dialog cancelled / closed | the opener |
| Create employee: 201 | PIN reveal title; on close the new employee's name link in the list (after the list refresh), or the h1 if the link is missing |
| Branch created / edited | dialog closes; focus to that row's "Editar" button (or the h1 if the row moved) |
| Branch activate | the same row's "Desactivar" button (the button swapped label) |
| Branch deactivate | the same row's "Activar" button |
| Employee profile saved | stays on "Guardar cambios" (it becomes `aria-disabled` again) |
| Unlock | "Restablecer PIN" (the unlock button disappears) |
| Deactivate employee | the swapped "Activar empleado" button |
| Activate employee | the swapped "Desactivar empleado" button |
| Reset PIN confirm | opens the reason dialog (title); success: PIN reveal title; on close "Restablecer PIN" (or `/login` h1 for a self reset) |
| Schedule saved | stays on "Guardar horario" (becomes `aria-disabled`) |
| Schedule copy applied | stays on "Copiar"; the polite status says "Revise y guarde" |
| Schedule validation fails | the error summary (`tabindex=-1`, `role="alert"`), whose links move to the field |
| `RECORD_CHANGED` / other error box | the box |
| Guard dialog "¿Salir sin guardar?" | initial focus "Seguir editando"; Esc and backdrop = stay; "Salir" navigates |
| Reload after a stale record | first field (dialog) or h1 (page) |

### 2.5 Announcements (polite, one visually hidden `aria-live="polite"` span per page or dialog, as in `ReasonField`/`ReviewActionDialog`; no toasts anywhere)

| Event | Text (ES) | Never contains |
|---|---|---|
| Employees count after search/filter (debounced 400 ms) | "5 empleados." | the search text |
| Employee created | "Empleado creado." | name, code, PIN, any digit of the PIN |
| Profile saved | "Cambios guardados." | |
| Unlocked / activated / deactivated | "Cuenta desbloqueada." / "Empleado activado." / "Empleado desactivado. Sus sesiones se cerraron." | |
| PIN reset | "PIN restablecido." | the PIN, the reason note |
| PIN copied | "PIN copiado." (inside the dialog only) | the PIN |
| Branch saved / activated / deactivated | "Sucursal guardada." / "Sucursal activada." / "Sucursal desactivada." | |
| Coordinates split | "Coordenadas aplicadas." | the values |
| Schedule saved / copied | "Horario guardado." / "Horario copiado. Revise y guarde." | |
| Stale record reloaded | "Datos actualizados." | |
| Note cleared | "Se quitó la nota porque no hay motivo." | the note |

---

## 3. Navigation

`AdminLayout` nav list gets two items after "Revisión" (4a spec 3.1, "Reportes" still not rendered until 4c):

```
 ▣ Asistencia   ✓ Revisión (3)   👥 Empleados   🏢 Sucursales
```

| Item | Route | Icon | Active rule |
|---|---|---|---|
| Empleados | `/admin/employees` | `users` | `NavLink` without `end`, so `/admin/employees/:id` keeps it active (`aria-current="page"` on both list and detail) |
| Sucursales | `/admin/branches` | `building` | `NavLink` |

- Same 44 px item, 20 px icon + text, active = teal fill + white text + bold + `aria-current` (not colour alone). Order in the DOM and tab order: Asistencia, Revisión, Empleados, Sucursales.
- < 768 px: the existing two-row wrap; four items fit in two rows at 320 px.
- **While the schedule is dirty (section 7.3)** every nav link, the "‹ Empleados" back link, "Mi marca" and "Cerrar sesión" call `confirmLeave()` before acting.
- Document titles (`PageHeading` `titleKey`): "Empleados · Panel del administrador", "Sucursales · Panel del administrador", and for the detail page the **generic** "Detalle del empleado · Panel del administrador" (`admin.employee.pageTitle`, new, conflict C5). **A name or code never goes into `document.title`, the URL, or the nav.**

---

## 4. Branches (`/admin/branches`)

### 4.1 List

```
 h1  Sucursales                                                     [+ Nueva sucursal]
 3 sucursales                                              (status text, not live)
 ┌─────────────────────────────────────────────────────────────────────────────────────┐
 │ Sucursal         Radio     Empleados activos   Estado          Acciones               │
 │ Sucursal Uno     100 m     8 (link)            (✓ Activa)      [Editar] [Desactivar]  │
 │ Sucursal Dos     150 m     0                   (· Inactiva)    [Editar] [Activar]     │
 │ Sucursal Tres    100 m     3 (link)            (✓ Activa)      [Editar] [Desactivar]  │
 │                  Tiene 3 empleados activos. Trasládelos o desactívelos primero.       │
 └─────────────────────────────────────────────────────────────────────────────────────┘
```

- `<table class="adm-table">`, hidden `<caption>` "Sucursales", `th scope="col"`, branch name in `th scope="row"`. Sort: name (server order). **No coordinates in the list** (they appear only in the form and the evidence dialog).
- Radio: "100 m". Active employees: a number; when > 0 a link to `/admin/employees?branch={id}` whose visible text is the number and whose accessible name continues it with a visually hidden suffix (`admin.branches.activeCountSuffix`, "8 empleados activos de esta sucursal"), so the visible text is a prefix of the name (WCAG 2.5.3) and no branch name is needed in the label.
- Estado chip: `adm-chip--teal` + `check-circle` "Activa"; `adm-chip--grey` + `circle` "Inactiva".
- Row actions (36 / 44 px, `button--secondary`): `Editar` (aria-label "Editar, {branch}" - starts with the visible word); `Desactivar` (4a destructive outline style, words only, no icon in the row to keep it compact) / `Activar` (secondary).
- **Branch with active employees**: `Desactivar` is `aria-disabled="true"`, `aria-describedby` points to the visible row note `admin.branches.inUse` ("Tiene {n} empleados activos. Trasládelos o desactívelos primero."). Click does nothing; stays focusable. The note is plain text under the row's name cell (`<p class="adm-hint">` inside the same `th`/cell), so it is read with the row.
- Deactivate (no active employees) opens `ConfirmDialog` destructive (4a spec 3.12 row "Desactivar sucursal"; branch name in the title is fine here, it is an in-panel confirm). Activate: no confirm. Both send `expectedVersion` of the row.
- Mobile: section 8.1.

States: loading / empty / error per 2.1; saving: the row's button shows `spinner` + busy label, `aria-busy`, other row buttons stay usable; `RECORD_CHANGED` per 2.3 (list variant); `BRANCH_IN_USE` race: box above the list + row note appears after the refetch.

### 4.2 Branch form dialog (create and edit)

`BranchFormDialog`, `ModalDialog` 32 rem (full screen < 768 px), h2 "Nueva sucursal" / "Editar sucursal". `closeOnBackdrop` false when the form has typed values (avoid accidental loss); Esc cancels (no confirm: the form is small).

```
┌────────────────────────────────────────────────┐
│ Nueva sucursal                             [✕] │
│ Nombre *            [                         ] │
│ Pegar coordenadas   [                         ] │
│   Copie las coordenadas desde Google Maps y    │
│   péguelas aquí; se separan solas.             │
│ Latitud *   [        ]   Longitud * [        ] │
│ Radio permitido (m) *  [100]                   │
│   De 10 a 5000 metros.                         │
│ [↗ Revisar ubicación en Google Maps]           │
│   Se abrirá Google Maps en una pestaña nueva y │
│   recibirá estas coordenadas.                  │
│ ⓘ Cambiar la ubicación o el radio solo afecta  │
│   las marcas futuras.                          │
│ [   Crear sucursal   ]   [ Cancelar ]          │
└────────────────────────────────────────────────┘
```

Edit mode adds under the title the read-only line "Empleados activos: {n}". Fields (all `autocomplete="off"`, visible `<label>`, `*` plus hidden "obligatorio", `aria-required`):

| Field | Control | Client rule (message key) | Notes |
|---|---|---|---|
| Nombre | text | after `cleanText` (NFC + trim) 1-120, no invisible characters (`admin.branches.error.name`, `admin.text.invisibleChars`) | no `maxLength` attribute (it would truncate silently); the message appears on blur and submit |
| Pegar coordenadas | text, `CoordinatesPasteField` | the 4a regex; comma decimal shows `admin.branches.pasteComma` and fills nothing | on a match: fills the two fields, clears itself, polite "Coordenadas aplicadas.", focus stays; **not sent** to the server and has no name attribute that is submitted |
| Latitud | `type="text" inputmode="decimal"` (not `number`: no spinner, no locale comma surprises) | finite, -90..90 (`admin.branches.error.lat`) | |
| Longitud | same | finite, -180..180 (`admin.branches.error.lng`) | |
| Radio | `type="text" inputmode="numeric"` | integer 10-5000 (`admin.branches.error.radius`) | default `100` on create |

- Latitude and longitude share a row at >= 24 rem width, stacked below.
- "Revisar ubicación en Google Maps": `ExternalMapLink`-style `<a target="_blank" rel="noopener noreferrer">` with `external-link` icon, URL built only from the two parsed numbers with `toFixed(6)`; `aria-disabled="true"` (no `href`) while the pair is invalid, and then it is skipped by announcing nothing extra. Hidden suffix "(se abre en una pestaña nueva)"; the privacy line is visible text tied with `aria-describedby`. (0,0) is allowed; the link makes the mistake visible.
- Validation: on blur per field and on submit; submit with errors focuses the first invalid field. The submit button is `aria-disabled` only while busy.
- Submit busy: `spinner` + "Guardando…"; inputs `readonly`; Cancel `aria-disabled`.
- Success: dialog closes; the response replaces/inserts the row; polite "Sucursal guardada."; focus per 2.4.
- Server errors: 2.2 (`BRANCH_NAME_TAKEN` under the name; `RECORD_CHANGED` in the dialog, edit mode).
- Dirty values survive a 401 re-auth (dialog stays mounted).

### 4.3 State summary

Loading / empty / error / saving / stale / 429 / 503 as in 2.1-2.3. Nothing is deleted anywhere (branches are only deactivated).

---

## 5. Employees

### 5.1 List (`/admin/employees`)

```
 h1  Empleados                                                       [+ Nuevo empleado]
 Buscar por nombre o código [                        ]
 Estado ( Todos | Activos | Inactivos | Bloqueados )    Sucursal [Todas ▾]
 12 empleados
 ┌───────────────────────────────────────────────────────────────────────────────────────┐
 │ Empleado             Rol            Sucursal       Estado                   Horario    │
 │ Nombre Apellido      Empleado       Sucursal Uno   (✓ Activo)               Sí         │
 │   ABC-123 · Cajera                                 Consentimiento: Sí                  │
 │ Nombre Apellido B    Empleado       Sucursal Uno   (✓ Activo) (🔒 Bloqueado hasta 10:30) Sí │
 │ Nombre Apellido C    Administrador  Sin sucursal   (✓ Activo)               No         │
 └───────────────────────────────────────────────────────────────────────────────────────┘
```

- Table semantics as 4a spec 6.5: hidden caption "Empleados", `th scope="col"`, employee cell = `th scope="row"` with `Avatar` (initials, `aria-hidden`), **the name as a link** to `/admin/employees/{id}` (text = name; no extra aria-label needed), under it "{code} · {job title}" muted 14 px. Role text "Empleado"/"Administrador" (text, no colour). Branch name or "Sin sucursal" (+ "(inactiva)" when `branchActive === false`). Estado cell: `Activo` teal chip / `Inactivo` grey chip; `Bloqueado hasta {time}` red-brown chip with `lock` icon (time = company zone `HH:mm`, from `lockedUntil`); under them "Consentimiento: Sí/No". Horario: "Sí"/"No" (text).
- **Search**: `<input type="search">` with visible label, `autocomplete="off"`; filters in memory by name and code, case- and accent-insensitive (`normalize('NFD')`, strip combining marks). **The text lives only in component state: never in the URL, `document.title`, storage or a log** (plan D14). Polite count after 400 ms of no typing.
- **Status filter**: `adm-segmented` radios Todos / Activos / Inactivos / Bloqueados (default Todos); "Bloqueados" = `locked`. State only (not URL) is fine; the branch filter is the only filter in the URL (`?branch=<id>`, an id).
- **Branch filter**: `<select>` "Todas las sucursales" + all branches (inactive suffixed "(inactiva)").
- "Limpiar filtros" appears when any filter is active; clears search, status and branch.
- Sort: name (server order). Count line is plain text; the polite span repeats it on change.
- The row has no actions; the name link is the only way in (keeps the table simple and the target large).

### 5.2 Create form (`EmployeeFormDialog`, mode `create`)

32 rem dialog, full screen < 768 px. Fields (all visible labels):

| Field | Control | Rules |
|---|---|---|
| Código de empleado * | text, `autocapitalize="characters"`, `spellcheck="false"`, CSS `text-transform: uppercase`; value uppercased/stripped on blur | `^[A-Z0-9-]{1,20}$` after uppercase (`admin.employee.error.code`); hint `admin.employee.codeHint` ("... No se puede cambiar después.") tied by `aria-describedby` |
| Nombre completo * | text | `cleanText`, 1-120, no invisible characters |
| Puesto (opcional) | text | 0-80, no invisible characters |
| Rol * | radio group (`fieldset` + legend) | "Empleado" with help `roleEmployeeHelp`; "Administrador" with help `roleAdminHelp` (help text in the label's `aria-describedby`); default Empleado |
| Sucursal | `<select>` of **active** branches, placeholder "Elija una sucursal" | required (`*`) when Rol = Empleado (`admin.employee.error.branch`); optional when Administrador, with the help `branchAdminHelp`; the label's required marker and `aria-required` switch with the role |

- Submit "Crear empleado" (primary); busy "Creando…". There is no PIN field: the PIN is generated by the server.
- **On 201**: the dialog closes, the list adds the row from `response.employee` (the PIN is **not** merged into any list/cache state), and `PinRevealDialog` opens (5.3). Polite page status "Empleado creado." is set when the reveal closes (the dialog's own span stays silent about the person).
- Server errors: 2.2. Uncertain create: 2.2 last row.
- Typed values are kept across a 401 re-auth and a 429/503 box.

### 5.3 PIN reveal (`PinRevealDialog`, used by create and PIN reset)

Spec of 4a spec 3.10 stands (28 rem, backdrop click disabled, Esc opens the inline "¿Cerrar sin copiar el PIN?" confirm in the footer with "Volver al PIN" initial focus and "Cerrar y descartar", no timers, hide/show toggle, copy button with 5 s revert, extra self-reset notice). 4b adds these **binding rules** (PII, plan D14/D5):

```
┌──────────────────────────────────────────────┐
│ Empleado creado                         (h2) │
│ PIN inicial de {name} ({code})               │   ← in-panel, admin-only text
│ ┌──────────────────────────────────────────┐ │
│ │            4827   1903        (example)  │ │   ← adm-pin__panel
│ └──────────────────────────────────────────┘ │
│ [⧉ Copiar PIN]   [Ocultar PIN]               │
│ ⚠ Este PIN se muestra una sola vez. Al cerrar│
│   esta ventana no podrá verlo de nuevo; si   │
│   se pierde, deberá restablecerlo.           │
│ Entregue el PIN a la persona en privado y    │
│ recomiéndele no compartirlo.                 │
│ [        Ya lo copié o anoté        ]        │
└──────────────────────────────────────────────┘
```

1. **Where the PIN may exist**: a single `useState<string>` in the component that owns the dialog, passed as prop `pin`, and **one** hidden spoken sibling (`PIN: 4 8 2 7, 1 9 0 3`, the 4a pattern). It is in **no** other DOM text, attribute (`aria-label`, `title`, `data-*`, `value`), live region, route, query/search param, storage, cache, error object, log call, toast or `document.title`.
2. **Do not** point the dialog's `aria-describedby` at the PIN panel (it would be read automatically on open, to anyone nearby). `aria-labelledby` = the h2 only. Initial focus on the h2 (`tabindex=-1`) as in 4a spec 3.10; the PIN is reached by arrowing/virtual cursor or by the first Tab landing on "Copiar PIN" then reading the previous element.
3. **Close** (button, or "Cerrar y descartar"): the owner sets `pin` to `''`, then unmounts. Also cleared if the component unmounts for any reason (route change, browser Back, sign-out). No state survives a reload (nothing is persisted), so reloading while open loses the PIN; the hint "si se pierde, deberá restablecerlo" already covers it.
4. **Announcements** never include the PIN or any of its digits: the dialog's polite span only says "PIN copiado." / the copy-failure notice; the page span says "Empleado creado." / "PIN restablecido." after close.
5. **Copy**: `navigator.clipboard.writeText` best effort; on failure the inline notice `admin.pin.copyFailed` (no digits). The clipboard may keep the PIN (accepted, known issue 4a 6.7 #4); the dialog does not promise otherwise.
6. **Self reset** (target = current user): the owner called `suspendSession(true)` **before** sending the reset (plan D5), the dialog shows the extra notice `admin.pin.selfReset`, and on close the session is cleared locally and the app navigates to `/login` (focus: login h1). If the reset fails, `suspendSession(false)` and the normal error box shows in the reset dialog. While suspended, the shell poll and the re-auth dialog/banner are off, so nothing can stack over this dialog.
7. **Non-self**: a 401 caused by token expiry may stack the re-auth dialog over the reveal dialog (it stays mounted underneath); on its close focus returns to the reveal title. The 60 s review-count poll is unaffected.
8. After close, focus: `Restablecer PIN` button on the detail page, or the new employee's name link in the list; self reset: `/login` h1.
9. Visual: the PIN panel text 14.29:1; the amber `notice--warn` has the `alert-triangle` icon and the words "se muestra una sola vez"; forced colours: panel gets a `ButtonText` border.

---

## 6. Employee detail (`/admin/employees/:id`)

Layout as 4a spec 3.9 (two columns >= 1024 px: "Perfil" and "Acceso y estado"; "Horario semanal" full width below; one column below 1024 px). Heading structure: h1 = the person's name (in-panel), h2 "Perfil", h2 "Acceso y estado", h2 "Horario semanal". `document.title` is generic (section 3).

```
 ‹ Empleados
 h1  Nombre Apellido                          (✓ Activo) (Empleado)
 ABC-123 · Cajera · Sucursal Uno
 ┌ Perfil ─────────────────────────────┐ ┌ Acceso y estado ───────────────────────────┐
 │ Código            ABC-123 (no editable)│ │ Estado         ✓ Activo                    │
 │ Nombre *          [                  ]│ │ Consentimiento Sí                           │
 │ Puesto            [                  ]│ │ Bloqueo        (🔒 Bloqueado hasta 10:30)   │
 │ Rol               ◉ Empleado          │ │                [Desbloquear]               │
 │                   ○ Administrador     │ │ [Restablecer PIN]                          │
 │ Sucursal *        [Sucursal Uno ▾]    │ │ Último restablecimiento de PIN: 3 nov 2026,│
 │ [Guardar cambios]                     │ │ 10:14 por {name} · Olvidó el PIN           │
 └──────────────────────────────────────┘ │ Nota: {note}                               │
                                          │ [Desactivar empleado]                      │
                                          │ ⓘ (self / last-admin / admin-PIN notes)    │
                                          └────────────────────────────────────────────┘
```

### 6.1 Profile form

- Fields as the create form (5.2) with the code as read-only text (`<dd>`/`<output>`-style text, not a disabled input, label "Código de empleado", hint `admin.employee.codeLocked`).
- Save "Guardar cambios": `aria-disabled="true"` while there are no changes (a hint is not announced); busy "Guardando…". Sends `expectedVersion` from the last view. Success: the response replaces the view and header; polite "Cambios guardados."; focus stays.
- **Role change** opens a `ConfirmDialog` before the PUT (4a spec 3.12: "Dar acceso de administrador" default tone; "Quitar el rol de administrador" destructive). The confirm title/body use `{name}` (in-panel). Cancel returns focus to "Guardar cambios".
- **Branch select**: active branches only; if the employee's current branch is inactive it is shown as the selected, `disabled` option "{name} (inactiva)" (`admin.employee.branchInactiveOption`) so the value is visible and other fields can be saved without changing it. Choosing another branch removes that option from the choices. (A11y note: a selected disabled option is announced "dimmed/unavailable" by some readers; the visible "(inactiva)" text explains it; accepted.)
- Role Empleado requires a branch (client message); switching an Administrador without branch to Empleado shows the branch error until one is chosen.
- Unsaved profile edits are **not** guarded on navigation (only the schedule is, plan D12); the form is short.
- Own account: the role radio "Empleado" is `aria-disabled` with `aria-describedby` to the self note (6.3); the radio stays focusable and a click does nothing.

### 6.2 Access panel (`AccessPanel`)

`<dl>` with Estado (chip), Consentimiento (Sí/No), Bloqueo (locked chip with `lock` icon and "Bloqueado hasta {time}" or "No"). Actions in this DOM order:

| Action | Visible / enabled when | Confirm | Success |
|---|---|---|---|
| Desbloquear | `locked` (button hidden otherwise) | none | the indicator disappears; "Cuenta desbloqueada."; focus to "Restablecer PIN" |
| Restablecer PIN | always visible; `aria-disabled` for the admin-target case (6.4) | `PinResetDialog` (6.5) | PIN reveal |
| Last PIN reset line | when `lastPinReset != null` (6.6) | - | - |
| Desactivar empleado | active | destructive `ConfirmDialog` | chip Inactivo, button swaps to "Activar empleado"; "Empleado desactivado. Sus sesiones se cerraron." |
| Activar empleado | inactive; no confirm | none | chip Activo, swaps back; "Empleado activado." |

All use `expectedVersion` where the API needs it (activate/deactivate); PIN reset and unlock send none. Busy state: the pressed button shows `spinner` + busy label, `aria-busy`; the others stay `aria-disabled` while any action is in flight (one write at a time per page).

### 6.3 Self and last-admin guards (4a spec 3.9.2 stands)

- Own account (`employee.code === identity.employeeCode`; the code is only compared, never rendered here beyond the existing subtitle): "Desactivar empleado" and the "Empleado" role radio are `aria-disabled="true"` with `aria-describedby` = the always-visible `adm-note` `admin.employee.selfNote`.
- Last active administrator (list says exactly one active ADMIN and it is this person): the same controls `aria-disabled` + `admin.employee.lastAdminNote`. A 409 `LAST_ADMIN` / `SELF_CHANGE_NOT_ALLOWED` from a race shows the fixed `error.*` box and reloads the view.
- Notes are plain visible text inside the Access panel (below the buttons), not tooltips. When both notes apply, the self note is shown first.

### 6.4 "Restablecer PIN" for another active admin (plan Q14)

Condition (client, from the loaded view; the server stays authoritative): target `role === ADMIN` **and** `active` **and** `!locked` **and** target is not the current user.

```
 [Restablecer PIN]   ← aria-disabled="true", aria-describedby → note
 ⓘ No puede restablecer el PIN de otro administrador activo, salvo que su cuenta esté bloqueada o desactivada.
```

- The button keeps its normal look plus the standard `aria-disabled` look (reduced contrast of the border only; **the text stays at >= 4.5:1**, the reason is the visible sentence, so the control is never an unexplained greyed-out thing). Click/Enter/Space do nothing; it remains in the tab order so a screen reader user hears the reason (`aria-describedby`).
- The note is `adm-note` `admin.pin.adminNotAllowed`, shown only in this condition, directly under the button.
- When the target is locked or inactive, or is oneself, the button is fully enabled and the note is absent. If the lock expires or the admin is re-activated while the page is open, the next reload/refresh updates it; a stale enabled button gets 409 `ADMIN_PIN_RESET_NOT_ALLOWED` shown inside the dialog (2.2).

### 6.5 PIN reset confirm with optional reason (`PinResetDialog`)

Replaces the plain `ConfirmDialog` for this action (plan Frontend). `ModalDialog` role `alertdialog` (destructive), 32 rem, full screen < 768 px. Title `admin.confirm.resetTitle` ("¿Restablecer el PIN de {name}?"), body `admin.confirm.resetBody`, own-account warning `admin.confirm.resetSelf` as a `notice--warn` with `alert-triangle`.

```
┌──────────────────────────────────────────────────┐
│ ¿Restablecer el PIN de {name}?                   │
│ Se generará un PIN nuevo y se mostrará una sola  │
│ vez. El PIN actual dejará de funcionar y se      │
│ cerrarán todas sus sesiones.                     │
│ ⚠ Es su propia cuenta: se cerrará su sesión al   │  ← only if self
│   terminar.                                      │
│ Motivo (opcional)                       (legend) │
│  ◉ Sin motivo                                    │
│  ○ Olvidó el PIN                                 │
│  ○ Cuenta bloqueada                              │
│  ○ Posible uso indebido                          │
│  ○ Otro                                          │
│ Nota (opcional)                                  │
│ [ textarea, disabled until a reason is chosen ]  │
│ Elija un motivo para poder escribir una nota.    │  ← replaced by the real hint once enabled
│ No escriba datos personales (cédula, teléfono,   │
│ PIN) ni números de 4 o más cifras.     0 / 200   │
│ [ Cancelar ]            [ Restablecer PIN ]      │
└──────────────────────────────────────────────────┘
```

Behaviour:

- **Reason group**: `<fieldset><legend>Motivo (opcional)</legend>` with 5 native radios in one `name` group; "Sin motivo" is checked by default. Arrow keys move and select (native). Values map to `reasonCode` (`NONE` sends no code).
- **Note**: `<label>` "Nota (opcional)" + `<textarea rows=3 maxLength=200>`, `autocomplete="off"`, **`disabled` while "Sin motivo" is selected** (`disabled`, not `readonly`, so it is not a tab stop and is announced as dimmed; the reason it is dimmed is the visible line `admin.pin.noteNeedsReason`, tied with `aria-describedby`). Choosing a preset enables it and swaps the description to `admin.pin.noteHint` (always visible once enabled; it is also the "no personal data" guidance). Counter "n / 200" is visible, `aria-hidden`, not live (the limit is in the hint/`maxLength`).
- Returning to "Sin motivo" with text in the note: the note is **cleared** and disabled, with the polite span "Se quitó la nota porque no hay motivo." (the server refuses a note without a code, and keeping hidden text that is not sent would be surprising).
- **Validation** (on blur of the note and on submit; focus to the note on error; messages never repeat the text):
  - invisible/control character (`hasUnsafeText`): `admin.text.invisibleChars`;
  - digit run (`hasDigitRun`: 4+ digits, separators space . - / allowed between digits): `admin.pin.noteDigits`;
  - over 200 after `cleanText`: `admin.pin.noteTooLong`.
  - An empty/whitespace note is simply "no note".
- **Buttons**: "Cancelar" is the initial focus (safe action, as every confirm); "Restablecer PIN" is the destructive-outline button, words only (4a spec 3.12). Busy: "Restableciendo…", `aria-busy`, inputs disabled; Cancel `aria-disabled`.
- Self reset: on submit, `suspendSession(true)` first (5.3 #6).
- On success the dialog closes and `PinRevealDialog` opens (title "PIN restablecido", `admin.pin.subtitleReset`); the detail view is replaced from `response.employee` (this refreshes the "last PIN reset" line and clears `locked`).
- Errors stay inside this dialog (2.2): `ADMIN_PIN_RESET_NOT_ALLOWED`, `RATE_LIMITED` (button stays enabled), `SERVER_BUSY`, network.
- The note text is held in dialog state only, sent once, and dropped on close; it is not shown in any announcement.

### 6.6 "Último restablecimiento de PIN" line

Shown in the Access panel directly under "Restablecer PIN" (and its admin note) when `lastPinReset != null`; absent otherwise (no "never" line). Plain text, muted 14 px, `overflow-wrap: anywhere`:

```
 Último restablecimiento de PIN: 3 nov 2026, 10:14 por {byName} · Olvidó el PIN
 Nota: {note}                                    ← only when note != null
```

- Date/time formatted in the company zone with the existing admin date-time helper; `{byName}` is the actor's name as returned by the API (admin-only response); the reason is the **translated preset label** (`admin.pin.reason.*`), never the raw code.
- Without a reason (`reasonCode == null`) the shorter text `admin.pin.lastResetNoReason` is used ("... por {byName}", no separator dot).
- The note is rendered as a **text node** (React default escaping), never as HTML or in an attribute, and on a separate muted line `admin.pin.lastResetNote`.
- It is a `<p>` (not a live region, not announced when it changes; the page status already says "PIN restablecido.").
- It appears only on the detail page, not in the list, and never in `document.title`, URLs or error messages.

---

## 7. Weekly schedule editor (`ScheduleEditor`)

### 7.1 Layout (>= 768 px; mobile in 8.1)

```
 h2  Horario semanal                              [Copiar horario de… ▾] [Copiar]
 Marque los días que trabaja. Si la salida es anterior a la entrada, el turno cruza la medianoche.
 ┌ error summary (role="alert", tabindex=-1): only after a failed save ─────────────────────┐
 │ Corrija estos datos para guardar el horario:                                              │
 │  • Martes: La entrada y la salida no pueden ser iguales.   (link to the field)            │
 └────────────────────────────────────────────────────────────────────────────────────────────┘
 ┌───────────┬─────────┬──────────┬──────────┬─────────────────┬───────────────────────────┐
 │ Día       │ Trabaja │ Entrada  │ Salida   │ Tolerancia (min)│ Nota                      │
 │ Lunes     │ ☑       │ [08:00]  │ [17:00]  │ [5]             │                           │
 │ Martes    │ ☑       │ [22:00]  │ [06:00]  │ [5]             │ 🕒 Turno nocturno          │
 │ …                                                                                         │
 │ Domingo   │ ☐       │ (disabled)                              │ Libre                     │
 └───────────┴─────────┴──────────┴──────────┴─────────────────┴───────────────────────────┘
 Las llegadas tarde ya registradas no cambian.                                 (always visible)
 Cambios sin guardar        [Guardar horario]   [Descartar cambios]
```

- Real `<table>` of 7 rows, ISO order Monday to Sunday; weekday names from `Intl.DateTimeFormat(lang, {weekday:'long'})` with a capitalised first letter (mapped from a fixed Monday date, no hard-coded names). `th scope="row"` = weekday; hidden caption = section title. Each control has a visually hidden `<label>` from the `*Aria` keys ("Trabaja el lunes", "Hora de entrada del lunes", "Hora de salida del lunes", "Tolerancia del lunes en minutos").
- Controls: checkbox; `<input type="time" step="60">`; grace `<input type="text" inputmode="numeric">` 0-240 (not `type=number`, to avoid spinners and wheel changes). Checking a day for the first time fills 08:00 / 17:00 / 5; unchecking disables the three inputs (`disabled`, not tab stops) **but keeps their values in state**, so re-checking restores them; saved data only includes checked days.
- Note column: "Libre" for unchecked days; for a checked day with `end < start` the `clock` icon + "Turno nocturno" (decorative icon, text carries it). `end = start` is an error, not a note.
- **Past lateness note** `admin.schedule.pastNote` is always visible under the grid, tied to the save button with `aria-describedby`.
- **Empty week allowed** (all unchecked): shows an `adm-note` "Sin días de trabajo: el empleado aparecerá como «Sin horario» en la asistencia." (`admin.schedule.noneHint`, new) and Save still works (sends `days: []`).
- Inactive employees and admins without a branch can have a schedule (no restriction in the UI).

### 7.2 Copy, dirty, save

- **Copy**: `<select>` "Copiar horario de…" (other employees; options are names, labelled by the select's label; no codes) + "Copiar" (secondary). Fetches `GET .../schedule` of the chosen employee and **replaces the whole grid locally**; does not save; polite "Horario copiado. Revise y guarde."; makes the editor dirty. "Copiar" is `aria-disabled` until an option is chosen. A copy over existing unsaved edits needs no confirm ("Descartar cambios" restores the loaded week). Errors: network/404 in an `ErrorBox` above the grid.
- **Dirty** = normalized grid (checked days only, with times/grace) differs from the last loaded/saved week. "Cambios sin guardar" is plain text beside the buttons, visible only while dirty and tied to "Guardar horario" by `aria-describedby` (not live, to avoid chatter). "Guardar horario" and "Descartar cambios" are `aria-disabled` while not dirty.
- **Validation on save** (nothing is blocked while typing): equal times (`admin.schedule.errorSame`), grace outside 0-240 or not a whole number (`errorGrace`), checked day with an empty time (`errorTime`). Errors appear inline under the row (`adm-schedule__row--error`, text + icon, `aria-invalid` on the offending inputs, `aria-describedby`) **and** in the summary; the summary gets focus and its links move focus to the first control of each problem.
- **Save**: `PUT {expected, days}`; busy: `spinner` + "Guardando…", inputs `readonly`/`disabled`; success: grid replaced from the response, dirty cleared, polite "Horario guardado.", the guard released. `RECORD_CHANGED` -> 2.3 (schedule variant). Other errors -> 2.2 (box above the grid; edits kept).
- **Descartar cambios**: restores the last loaded week, clears dirty/errors, no confirm (it is the undo); focus stays on the button (which becomes `aria-disabled`).

### 7.3 Unsaved-changes guard (no `useBlocker`; plan D12)

While the editor is dirty, `ScheduleEditor` calls `shell.setUnsavedChanges('schedule', true)`; the shell:

1. registers `beforeunload` (browser's native prompt for reload, tab close, typed URL);
2. makes these call `await confirmLeave()` before acting: the four nav links, the "‹ Empleados" back link, "Mi marca", "Cerrar sesión" (the guard runs **before** `LogoutConfirm` opens);
3. `confirmLeave()` opens the `ConfirmDialog` (default tone): title "¿Salir sin guardar?", body "Los cambios del horario se perderán.", confirm "Salir", cancel "Seguir editando" (initial focus). "Salir" resolves `true` and the original navigation proceeds; Cancel/Esc/backdrop resolves `false` and focus returns to the control that was activated.

Known and documented limit: the browser Back button and manually edited URLs are not intercepted (only `beforeunload` covers reload/close). The dirty flag is cleared on save, discard, successful reload and unmount. The language switcher does not navigate and is not guarded.

---

## 8. Mobile, accessibility checklist, i18n

### 8.1 Mobile (< 768 px; tablet 768-1023 keeps the tables with `--cell-pad-x: .75rem`)

React picks table or list with `useMediaQuery('(min-width: 48rem)')`, so only one is in the DOM (4a spec 3.3.6). No horizontal page scroll at 320 px or 400 % zoom. Long names wrap (`overflow-wrap: anywhere`).

| Screen | < 768 px |
|---|---|
| Nav | two-row wrap, four 44 px items |
| Employees list | toolbar stacks: search full width, status segmented control wraps to two lines (each segment >= 44 px), branch select full width, count line. Each employee is `<li><article aria-labelledby>`: h3 = name link (the whole name is the link), muted "{code} · {job title}", then a `<dl>` with Rol, Sucursal, Estado (chips wrap), Consentimiento, Horario. "Nuevo empleado" is a full-width primary button under the h1. |
| Branches list | cards: h3 name, chip, `<dl>` Radio / Empleados activos (link); actions `Editar` and `Desactivar/Activar` full-width, stacked, 44 px, with 8 px gap; the in-use note under the actions (and still the `aria-describedby` of the disabled button). |
| Create / edit / branch dialogs | full screen (`100dvh`), single column, footer buttons stacked (primary first, full width); the title scrolls with the content (no sticky footer, to keep focused fields visible). Lat/long stack. |
| Employee detail | one column: header (h1, chips, subtitle), Perfil, Acceso y estado, Horario. Action buttons full width, 44 px. |
| Schedule | no table: seven `<fieldset class="adm-schedule__day">` cards (legend = weekday). Inside: checkbox "Trabaja", then Entrada and Salida side by side (stacked below 22 rem), Tolerancia, note line. The error summary and per-day inline errors are the same; the dirty text and the two buttons stack, "Guardar horario" first. The copy select + button stack above the cards. |
| PIN reveal | full screen; digits `1.75rem`, 4+4 on **one** line (nowrap) down to 320 px; "Copiar PIN" and "Ocultar PIN" stack full width; the amber warning stays directly under the PIN. |
| PIN-reset dialog | full screen; radios one per row (>= 44 px each); note textarea full width; buttons stacked, "Cancelar" last in the visual order but **first in DOM/focus** as in every confirm (safe action). |
| Error boxes / notes | full width, wrap |

Touch targets: 44 px for coarse pointers and < 1024 px (the 4a `--target-ctl` rule); schedule time inputs 48 px.

### 8.2 Accessibility checklist for the coder (WCAG 2.2 AA)

- Landmarks and heading order per 4a spec 6.1; one `main`; h1 focus on route change; unique generic `document.title`.
- Every field has a visible label; required = `*` + hidden "obligatorio" + `aria-required`; hints/errors via `aria-describedby`; invalid = `aria-invalid="true"`; error text states the problem and the fix and never repeats the value.
- `aria-disabled` (focusable, reason visible) for guarded actions: deactivate branch with employees, self / last-admin controls, reset PIN of another active admin, "Guardar" without changes, "Copiar" without a choice. Native `disabled` only where the control should not be reachable (unchecked schedule day inputs, note before a reason).
- Radios and checkboxes are native; the segmented filter and the reason group keep the native arrow-key behaviour; `adm-segmented` shows the selected state with fill + weight + underline (not colour only).
- Colour is never the only signal: locked = red-brown + `lock` icon + words; inactive = grey + words; overnight = icon + words; schedule row error = border + text; PIN warning = amber + icon + words.
- Contrast: only existing measured pairs are used (chips 7.68-8.62:1, teal fill 7.52:1, PIN panel 14.29:1, muted text >= 7.2:1 on the lightest surface); the disabled/`aria-disabled` look must keep text >= 4.5:1 (only borders/fills dim).
- Target sizes 36 px (fine pointer, >= 1024 px) / 44 px otherwise; dialog buttons and nav always 44 px; 8 px between adjacent targets; schedule inputs 40 / 48 px.
- Focus ring `3px solid var(--color-focus)` everywhere, including the visually hidden radios of the segmented control (ring on the visible label). Focus is never obscured (no sticky bars).
- Reduced motion and forced colours as in 4a spec 1.8; spinner static with text.
- Stacked dialogs: only the top one is interactive (native `<dialog>`); closing returns focus to the previous dialog's last active element.
- No toasts; messages are persistent (`ErrorBox`) or polite statuses (2.5).
- Language: names are not given `lang`; weekday names and dates follow the UI language through `Intl`.

### 8.3 Accessibility issues found while designing

1. **Required PIN-reset note before reason** forces a disabled textarea; `disabled` hides it from the tab order and the explanation is only the linked hint. Mitigated with the always-visible `noteNeedsReason` line; if testing finds screen readers skip it, switch to `aria-disabled` + `readonly`.
2. **Selected disabled `<option>`** for a retired branch is announced as unavailable by some readers; mitigated by the visible "(inactiva)".
3. **`beforeunload` and the Back button**: the browser Back button is not guarded (declarative router). Documented; the "Cambios sin guardar" text remains visible.
4. **The PIN is spoken by screen readers** from the hidden sibling (inherent); and the OS clipboard may keep it (4a 6.7 #3, #4).
5. **Existing icon set lacked `lock` and `copy`** (C1).
6. **`type="time"` and `type="search"`** are native; support is good in current Chrome/Edge/Firefox; time inputs show the OS 12/24 h format.
7. **Disabled form controls** are excluded from the 4.5:1 requirement by WCAG, but the spec keeps the text legible anyway.

### 8.4 i18n: keys

All 4b keys from 4a spec 5.3 (`admin.employees.*`, `admin.employee.*`, `admin.confirm.*`, `admin.pin.*`, `admin.schedule.*`, `admin.branches.*`) are used as written, together with the plan's additions (`admin.text.invisibleChars`, `admin.recordChanged.reload`, `admin.employee.branchInactiveOption`, `admin.pin.reasonLegend`, `admin.pin.reason.NONE|FORGOT_PIN|ACCOUNT_LOCKED|POSSIBLE_MISUSE|OTHER`, `admin.pin.noteLabel`, `noteHint`, `noteDigits`, `noteTooLong`, `lastReset`, `adminNotAllowed`) and the shared `error.RATE_LIMITED`, `error.RECORD_CHANGED`, `error.ADMIN_PIN_RESET_NOT_ALLOWED`, `error.tooManyAttemptsMinutes`, `common.retry|cancel|close`, `admin.review.clear`, `admin.error.serverBusy`. "usted" everywhere (imperatives "Escriba", "Elija", "Trasládelos"); no "tú" forms.

**New keys this spec needs beyond the plan's list** (add to `adminEs`/`adminEn`; the parity and no-hardcoded-text tests then cover them):

| Key | ES (usted) | EN |
|---|---|---|
| `admin.employee.pageTitle` | Detalle del empleado | Employee details |
| `admin.employee.notFoundTitle` | Empleado no encontrado | Employee not found |
| `admin.pin.lastResetNoReason` | Último restablecimiento de PIN: {date} por {name} | Last PIN reset: {date} by {name} |
| `admin.pin.lastResetNote` | Nota: {note} | Note: {note} |
| `admin.pin.noteNeedsReason` | Elija un motivo para poder escribir una nota. | Choose a reason to be able to write a note. |
| `admin.pin.noteCleared` | Se quitó la nota porque no hay motivo. | The note was removed because there is no reason. |
| `admin.recordChanged.discards` | Al recargar se descartan los cambios que no guardó. | Reloading discards the changes you have not saved. |
| `admin.recordChanged.reloaded` | Datos actualizados. | Data updated. |
| `admin.recordChanged.listUpdated` | Se actualizó la lista. | The list was updated. |
| `admin.schedule.noneHint` | Sin días de trabajo: el empleado aparecerá como «Sin horario» en la asistencia. | No working days: the employee will appear as "Unscheduled" in attendance. |
| `admin.schedule.errorSummaryItem` | {day}: {message} | {day}: {message} |
| `admin.branches.activeCountSuffix` | empleados activos de esta sucursal | active employees of this branch |
| `admin.employees.statusFilterLegend` | Estado | Status |
| `admin.employees.clearFilters` | Limpiar filtros | Clear filters |

(No key is needed for the icons. `admin.employees.statusFilterLegend` and `clearFilters` may reuse `admin.employees.filter` and `admin.review.clear`; the coder keeps whichever avoids duplicate text, the parity test decides.) `admin.schedule.errorSummaryItem` and the weekday names are formatting only; weekday names come from `Intl`, not from keys.

Rules that apply to every string: no message contains a PIN, a code or a person's name as an **error or warning argument**; `{name}` appears only in the in-panel confirms/subtitles already defined in 4a spec 5.3 (`admin.confirm.*Title|Body`, `admin.pin.subtitle*`, `admin.pin.lastReset`), never in `role="alert"` text coming from the server, never in `document.title`.

---

## 9. Conflicts with the plan (plan not changed; main session decides)

- **C1. Icons: the plan adds only `users` and `building`; the screens also need `lock` and `copy`.** Neither is in `AdminIcon.tsx` or `Icon.tsx` (4a spec 1.3 lists them as new but they were not built). The locked chip (plan: "Bloqueado hasta" indicator) and "Copiar PIN" need them. Proposed: add all four to `ADMIN_PATHS` (paths in 1.2). Alternative without new icons: no lock icon (words only) and a text-only "Copiar PIN", which weakens the "colour is never the only signal" pairing for the locked chip.
- **C2. `document.title` for the detail page.** 4a spec 3.1 sets `"{page} · Panel del administrador"` from `titleKey`, and the detail h1 is the person's name. The plan says no name in `document.title`. Proposed: `PageHeading` gets a separate `heading` text (the name) from `titleKey` (generic `admin.employee.pageTitle`), so title and h1 differ. This needs a small `PageHeading` change.
- **C3. Plan `admin.pin.lastReset` has one shape** (`{date}`, `{name}`, `{reason}`) but `reasonCode` may be `null`. A second key `admin.pin.lastResetNoReason` and `admin.pin.lastResetNote` are needed (section 8.4).
- **C4. Plan says the stale-record box "text says so" (edits discarded)** but `error.RECORD_CHANGED` does not. Added `admin.recordChanged.discards`, `reloaded`, `listUpdated` (8.4). List pages auto-refetch (no edits to lose) instead of showing "Recargar"; this reads the plan's "never auto-retries" as "never auto-resends the write" - a refetch is not a retry. Please confirm.
- **C5. Plan Frontend names the PIN-reset reason radio "Sin motivo" as default and the note "enabled only after a preset"**; it does not say what happens when the user goes back to "Sin motivo" with text typed. Proposed: clear the note, disable it, announce it (6.5). The server would reject a note without a code.
- **C6. D12 guard scope.** Plan lists "admin nav links, back link and header links". This spec includes "Cerrar sesión" (guard first, then `LogoutConfirm`) and "Mi marca" as the header links, and leaves the language switcher unguarded. Unsaved **profile** edits are not guarded (plan guards only the schedule).
- **C7. 4a spec 3.9.3 says "router blocker"** (`useBlocker`); the plan correctly replaces it with the shell guard. This spec follows the plan; the 4a spec line is superseded (7.3).
- **C8. Branch `Desactivar` UI uses the list's `activeEmployees`** for the `aria-disabled` state; the plan's invariant makes the server authoritative (`BRANCH_IN_USE`). The count can be stale up to the last list load; the 409 path is handled (2.2).
- **C9. 429 on writes keeps the submit enabled** (plan), while 4a spec 3.13 (export) disables the button until `Retry-After` passes. Followed the plan for 4b writes; the box shows the wait time. Mention if the main session wants one behaviour for both.
- **C10. Create/edit forms use `type="text" inputmode` for latitude, longitude, radius and grace** instead of `type="number"` (avoids wheel/spinner changes and locale-comma problems). The plan only says "paste regex ... Number.isFinite", so this is a refinement, not a conflict, but it differs from the plan's schedule line "`<input type="number" min=0 max=240>`" in 4a spec 3.9.3. Please confirm.
- **C11. `users` icon vs 4a evidence "Tablet local"**: the 4a spec reserves `building` for the "Tablet local" evidence label; reusing it for the Sucursales nav item is fine visually (different contexts) but they share one icon name.

No change to `CHANGELOG.md`, `docs/PROGRESS.md`, the plan, or any source file was made.

## 10. Conflict resolutions (main session, 2026-11-03)

These override sections 1–9 and the plan where they differ.

- **C1:** add the `lock` icon (locked chip). **"Copiar PIN" is removed** under the PII guardrail in `CLAUDE.md`: copying would leave the PIN in the OS clipboard and clipboard history. The admin reads the PIN from the reveal dialog. No `copy` icon is needed.
- **C2:** adopted. `PageHeading` gets a separate `documentTitle` (generic, e.g. "Empleado · Panel"); the `<h1>` may show the name.
- **C3:** adopted: `admin.pin.lastResetNoReason` and `admin.pin.lastResetNote`.
- **C4:** adopted. "Never auto-retries" means the UI never resends a write. Reloading a form after 409 `RECORD_CHANGED` says the edits are discarded; list pages may refetch on their own.
- **C5:** adopted. Going back to "Sin motivo" clears and disables the note.
- **C6:** adopted. The unsaved-changes guard also covers "Cerrar sesión" and "Mi marca".
- **C7:** adopted. The plan's shell guard replaces the 4a "router blocker".
- **C8:** adopted. The disabled "Desactivar" state comes from the list count; 409 `BRANCH_IN_USE` covers a stale count.
- **C9:** plan wins. A 429 on a write keeps the submit button enabled and shows the wait time from `Retry-After`.
- **C10:** adopted. Latitude, longitude, radius and grace use `type="text"` with `inputmode` and server-side validation (allows a minus sign and a decimal comma).
- **C11:** the branch nav uses a new, distinct `branch` icon; `building` keeps its 4a meaning ("Tablet local").
- **a11y (8.3):** for the disabled note, keep the linked hint visible next to the reason select. A retired branch shown as the current value uses a visible "(inactiva)" suffix, not a disabled selected option.
