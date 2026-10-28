# Stage 3 - Employee PWA: UI/UX design spec

Status: DESIGNED 2026-10-26. Input to the `coder`. Plan: `docs/plans/stage-3-employee-pwa.md` (read with its final section "Open Questions: RESOLVED", which wins). Blueprint: `docs/prototype/Prototipo-Control-Asistencia.pdf`, page 1. The PDF could not be rendered in the sandbox, so the layout follows the textual description in the brief. Re-check the final visual against the PDF.

Scope: spec only. No component or CSS file was written; the token block in section 1.8 is ready to paste into `frontend/src/index.css`. Nothing outside `docs/design/` was changed.

Existing stack found in discovery (all reused, nothing new introduced):
- React 19, react-router 8, Vite 8, plain CSS in `frontend/src/index.css` (no Tailwind or UI library), no CSS variables yet, hard-coded hex values `#f2f2f0`, `#1c1f1e`, `#0f5f56`.
- Existing patterns: `.visually-hidden`, `.app-header`, `.language-switcher` (44 px targets, 1 px teal border, `aria-pressed` shown by fill + bold + underline, focus ring `3px solid #1c1f1e` with 2 px offset), a polite `aria-live` span that is not `role="status"`.
- Typed flat i18n keys (`'home.status.notYet'` style) in `frontend/src/i18n/resources.ts`, where `en` must have exactly the keys of `es`. `{name}` placeholders. Every new string below follows this.

## Contents

1. Design tokens
2. Layout and responsive rules
3. Screen specs
4. Component inventory
5. i18n: keys, error mapping, date and time formats
6. Accessibility notes
7. Motion and feedback
8. Decisions that differ from or extend the plan (coder must read)

---

## 1. Design tokens

### 1.1 Colors and verified contrast

Ratios were computed with the WCAG 2.2 relative-luminance formula (script in the scratchpad, `c.mjs` and `d.mjs`). Thresholds: normal text 4.5:1, large text (>= 24 px, or >= 18.66 px bold) 3:1, UI components and graphics 3:1.

| Token | Hex | Use |
|---|---|---|
| `--color-bg` | `#F2F2F0` | page background (blueprint) |
| `--color-surface` | `#FFFFFF` | cards, keypad keys, dialogs |
| `--color-text` | `#1C1F1E` | body text |
| `--color-text-muted` | `#4A5250` | secondary text, captions |
| `--color-primary` | `#0F5F56` | brand teal: day header, primary button, links, filled dots |
| `--color-primary-pressed` | `#0B4A43` | pressed primary button |
| `--color-on-primary` | `#FFFFFF` | text on teal |
| `--color-on-primary-muted` | `#DCEEEA` | secondary text on teal (date) |
| `--color-info-bg` | `#E6F0EE` | chips, update banner |
| `--color-border` | `#767B79` | input, keypad, chip boundaries |
| `--color-error` | `#A32020` | error text and icon |
| `--color-error-bg` | `#FDECEA` | error box |
| `--color-warning` | `#7A4B00` | warning text and icon |
| `--color-warning-bg` | `#FFF4D6` | warning box |
| `--color-warning-border` | `#9A6200` | warning box border |
| `--color-success` | `#0B5D2E` | inside-area text and icon |
| `--color-success-bg` | `#E3F3E8` | inside-area box |
| `--color-disabled-bg` | `#E4E5E2` | "Listo por hoy" button |
| `--color-disabled-text` | `#4F5654` | disabled button text |
| `--color-key-pressed` | `#D9E6E3` | pressed keypad key |
| `--color-focus` | `#1C1F1E` | focus ring |
| `--color-backdrop` | `rgba(28,31,30,.6)` | dialog backdrop |

Computed contrast, text and icons on `#F2F2F0` (the page):

| Foreground | Ratio | Result |
|---|---|---|
| `#1C1F1E` text | 14.82:1 | pass AA and AAA |
| `#4A5250` muted | 7.17:1 | pass |
| `#0F5F56` teal (links, headings) | 6.71:1 | pass |
| `#A32020` error | 6.73:1 | pass |
| `#7A4B00` warning | 6.61:1 | pass |
| `#0B5D2E` success | 7.15:1 | pass |
| `#767B79` border (UI) | 3.84:1 | pass (>= 3:1) |
| `#1C1F1E` focus ring | 14.82:1 | pass (>= 3:1) |

On `#0F5F56` (teal band and primary button):

| Foreground | Ratio | Result |
|---|---|---|
| `#FFFFFF` | 7.52:1 | pass |
| `#F2F2F0` | 6.71:1 | pass |
| `#DCEEEA` (date line) | 6.25:1 | pass |
| `#D6EAE6` | 6.01:1 | pass |
| `#C9E3DF` | 5.57:1 | pass (lowest allowed on teal) |
| `#1C1F1E` text | **2.21:1** | **FAIL. Never put dark text on teal.** |
| `#FFFFFF` on pressed `#0B4A43` | 10.12:1 | pass |

On white `#FFFFFF`: text 16.61, muted 8.03, teal 7.52, error 7.54, warning 7.41, success 8.02, border `#767B79` 4.30. All pass.

Tinted surfaces (text on its own background):

| Pair | Ratio |
|---|---|
| `#7A4B00` on `#FFF4D6` (warning) | 6.76 |
| `#1C1F1E` on `#FFF4D6` | 15.16 |
| `#A32020` on `#FDECEA` (error) | 6.59 |
| `#1C1F1E` on `#FDECEA` | 14.53 |
| `#0B5D2E` on `#E3F3E8` (inside) | 6.97 |
| `#0F5F56` on `#E3F3E8` | 6.54 |
| `#0F5F56` on `#E6F0EE` (info) | 6.47 |
| `#1C1F1E` on `#E6F0EE` | 14.29 |
| `#4F5654` on `#E4E5E2` (disabled button text) | 5.95 |
| `#4A5250` on `#E4E5E2` | 6.35 |
| `#1C1F1E` on `#D9E6E3` (pressed key) | 12.96 |
| `#9A6200` border on `#FFF4D6` | 4.65 (>= 3:1) |
| `#767B79` border on `#E4E5E2` (disabled button) | 3.40 (>= 3:1) |

Rules:
- Disabled "Listo por hoy" keeps 5.95:1 even though WCAG exempts disabled controls (plan requirement of >= 4.5:1).
- Color is never the only signal. Every state pairs color with an icon shape and text (section 6.4).
- The existing `a { color: #0f5f56 }` passes on both bg and white.

### 1.2 Type scale

System font stack stays (`system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif`). No web fonts (CSP `font-src 'self'`, bundle budget).

| Token | Size | Weight | Line height | Use |
|---|---|---|---|---|
| `--fs-clock` | `clamp(3rem, 14vw, 3.5rem)` (48 to 56 px) | 700 | 1.1 | server clock, `font-variant-numeric: tabular-nums` |
| `--fs-h1` | 1.5rem (24 px) | 700 | 1.2 | greeting, page titles |
| `--fs-h2` | 1.25rem (20 px) | 700 | 1.25 | status line, section titles |
| `--fs-button` | 1.25rem (20 px) | 700 | 1.2 | primary button |
| `--fs-lead` | 1.125rem (18 px) | 600 | 1.4 | entry times |
| `--fs-body` | 1rem (16 px) | 400 | 1.5 | body, banners, results |
| `--fs-small` | 0.875rem (14 px) | 400 or 600 | 1.4 | captions, chips, "EVIDENCIA" label |

Minimum text size is 14 px. Use `rem` only so browser text-size settings and 200% zoom work. No fixed heights on text containers (use `min-height`). Text must survive WCAG 1.4.12 text-spacing overrides.

### 1.3 Spacing (4 px scale)

`--space-1` 0.25rem, `--space-2` 0.5rem, `--space-3` 0.75rem, `--space-4` 1rem, `--space-5` 1.5rem, `--space-6` 2rem, `--space-8` 3rem. Screen side padding 1rem (16 px) at every width. Vertical rhythm between blocks `--space-4`; inside cards `--space-4`.

### 1.4 Radii and elevation

`--radius-sm` 0.375rem (matches the existing language switcher), `--radius-md` 0.75rem (chips, keys, inputs, banners), `--radius-lg` 1rem (cards, primary button), `--radius-pill` 999px (chips, dots). Elevation: borders only, plus one soft shadow on dialogs (`0 8px 24px rgba(28,31,30,.25)`). No shadows on cards (low-end GPUs, and borders are stronger in sunlight).

### 1.5 Targets

| Element | Minimum | Spec value |
|---|---|---|
| Any interactive control | 44 x 44 px (project rule) | 44 |
| Text inputs | 48 px high | 48 |
| Header buttons (language, logout) | 44 | 44 |
| PIN keypad keys | 48 recommended | **56 high**, fluid width >= 80 px at 320 wide (plan value, above the 48 recommendation) |
| Primary clock button | 48 recommended | **64 high**, full width (plan value) |
| Shutter button | | 72 x 72 |
| Gap between targets | 8 px | `--space-3` (12 px) in the keypad |

No spec value goes below 48 px for the keypad and primary button. The coder may not shrink them under 48.

### 1.6 Focus ring

`outline: 3px solid var(--color-focus); outline-offset: 2px` on `:focus-visible` for every control (extends the existing pattern). The ring is drawn outside the control, so it is measured against the surrounding page or surface (14.82:1 or 16.61:1), not against the teal fill. For any control placed on a teal or dark surface use `.on-dark:focus-visible { outline-color: #fff }` (7.52:1 on teal). Programmatic focus targets (`h1`, `h2`, dialog title) use `tabindex="-1"` and `:focus { outline: none }` is **not** allowed on them; show the same ring so keyboard and switch users see where they are.

Never hide focus on keypad keys after a press (touch devices set `:focus-visible` rarely, so the ring is only for keyboard). Scroll targets get `scroll-margin-block: 1rem`. No sticky element overlaps focus (WCAG 2.4.11): banners are in normal flow, not sticky.

### 1.7 Reduced motion and other media

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; }
}
```
- Spinners become a static icon plus the text (section 7). Press feedback keeps the color change but drops the scale transform. The shutter flash is dropped. Haptics: see 7.4.
- `meta color-scheme: light`. Dark mode is out of scope for Stage 3 (the blueprint is light only). `@media (forced-colors: active)`: borders on all controls use `1px solid ButtonText` and icons use `currentColor` so state icons survive high contrast mode.

### 1.8 CSS custom properties (paste into `index.css`)

```css
:root {
  --color-bg: #f2f2f0;           --color-surface: #fff;
  --color-text: #1c1f1e;         --color-text-muted: #4a5250;
  --color-primary: #0f5f56;      --color-primary-pressed: #0b4a43;
  --color-on-primary: #fff;      --color-on-primary-muted: #dceeea;
  --color-info-bg: #e6f0ee;      --color-border: #767b79;
  --color-error: #a32020;        --color-error-bg: #fdecea;
  --color-warning: #7a4b00;      --color-warning-bg: #fff4d6;   --color-warning-border: #9a6200;
  --color-success: #0b5d2e;      --color-success-bg: #e3f3e8;
  --color-disabled-bg: #e4e5e2;  --color-disabled-text: #4f5654;
  --color-key-pressed: #d9e6e3;  --color-focus: #1c1f1e;        --color-backdrop: rgb(28 31 30 / 0.6);
  --fs-clock: clamp(3rem, 14vw, 3.5rem); --fs-h1: 1.5rem; --fs-h2: 1.25rem;
  --fs-button: 1.25rem; --fs-lead: 1.125rem; --fs-body: 1rem; --fs-small: 0.875rem;
  --space-1: .25rem; --space-2: .5rem; --space-3: .75rem; --space-4: 1rem; --space-5: 1.5rem; --space-6: 2rem; --space-8: 3rem;
  --radius-sm: .375rem; --radius-md: .75rem; --radius-lg: 1rem; --radius-pill: 999px;
  --target-min: 44px; --target-input: 48px; --key-h: 56px; --primary-h: 64px; --shutter: 72px;
}
```
Replace the hard-coded hex values in existing rules with these variables (same values, no visual change).

---

## 2. Layout and responsive rules

- Mobile first, designed from **320 px** wide. Baseline mockup 360 x 640.
- Employee screens use a new wrapper class `.screen` on `<main>`: `max-width: 30rem` (480 px), centered, `padding: 0 1rem 2rem`. The existing global `main { max-width: 40rem; padding: 1.5rem 1rem }` stays for `/admin` and NotFound; `.screen` overrides it (see section 8, item 9).
- Breakpoints (min-width): base (320), 30rem (480: keypad keys may grow to 88 px wide, primary button radius stays), **48rem (768, tablet)**: content stays one 30rem column, centered, with `margin-top: 2rem`; dialogs become centered cards (max-width 28rem; SelfieCapture 36rem) instead of full screen; keypad key height 64. No multi-column layout is needed in Stage 3. Landscape phones: the SelfieCapture dialog uses `100dvh` and its controls are in a side column when `(orientation: landscape) and (max-height: 30rem)`.
- No horizontal scroll at 320 px or at 400% zoom (reflow, WCAG 1.4.10). Long names and codes wrap (`overflow-wrap: anywhere`).
- Safe areas: `padding-bottom: max(2rem, env(safe-area-inset-bottom))` on `.screen` (installed PWA on notched phones).

Vertical order of the main screen (top to bottom). This is also the DOM and tab order:

```
AppHeader      language switcher, logout button
Banners        FailureCard(s), reauth/pending row, OfflineBanner, UpdatePrompt
Day card       date, greeting (teal band)
Server clock   label + HH:mm
Status line    h2 + worked duration
Notices        missing clock-out, last shift, review, late
Evidence       chips (hidden when nothing can be captured)
Primary button
Entry times    Entrada / Salida + results
Footer note    "Sin señal, la marca se guarda..." (static text)
```

---

## 3. Screen specs

All wireframes are 36 characters wide, about the 360 px baseline. `[ ]` is a button, `( )` a chip, `●` a filled dot, `○` an empty dot.

Copy keys are in section 5. In wireframes the text is Spanish; every string exists in EN too.

### 3.1 Login (`/login`)

Element order: h1, optional offline note, company code field, employee code field, PIN group (label, dots, progress, keypad), error alert, submit, countdown.

```
 [Español|English]                       <- AppHeader (no logout: no session)
 
 Iniciar sesión                          <- h1 (focus on entry)
 
 Código de empresa
 [ DEMO                          ]       <- input 48 px, autocapitalize=characters
 Código de empleado
 [ 1001                          ]
 
 PIN
        ○ ○ ○ ○   ○ ○ ○ ○                <- dots 4 + 4, aria-hidden
        0 de 8 dígitos                   <- visible too (14 px muted)
 
   [  1  ] [  2  ] [  3  ]               <- keys 56 px high, 12 px gap
   [  4  ] [  5  ] [  6  ]
   [  7  ] [  8  ] [  9  ]
   [Borrar][  0  ] [  ⌫  ]
    todo
 
 [        Ingresar        ]              <- aria-disabled until 8 digits
```

States:

| State | What changes |
|---|---|
| Empty | codes empty (company code pre-filled from `lastCompanyCode`), 0 dots filled, submit `aria-disabled="true"`, progress "0 de 8 dígitos". |
| Typing | one dot fills per digit, progress text updates (announcement is debounced 400 ms, section 6.3). The dot group has a larger gap between 4th and 5th. At 8 dots submit becomes enabled (no auto-submit). Digits are never shown. |
| Field error (client) | submit with an empty or malformed code: an inline error under that field (`role="alert"` text, `aria-invalid="true"`, `aria-describedby`), focus goes to the first invalid field. Text: `login.error.*`. |
| Submitting | keypad keys and inputs get `aria-disabled`/`disabled`, submit shows a spinner icon and "Ingresando…", `aria-busy="true"` on the form. The submit button stays focused. |
| 401 `INVALID_CREDENTIALS` | PIN cleared (all dots empty), codes kept, error box above the submit button (`role="alert"`, error colors, alert icon): "Código o PIN incorrectos. Si fallaste varias veces, espera 15 minutos o contacta a tu administrador." Focus moves to the error box (`tabindex="-1"`). The text never says which field was wrong, and is the same for a locked account. |
| 429 `TOO_MANY_ATTEMPTS` | Error box: "Demasiados intentos. Inténtalo de nuevo en 5 min." (minutes from `Retry-After`, rounded up; below 60 s use the seconds key). Below it a visual countdown `4:59` (`aria-hidden`, updated every second, so the screen reader does not hear 300 updates). Keypad and submit are `aria-disabled` while counting. At zero: controls re-enable and the polite announcer says "Ya puedes intentarlo de nuevo." Focus stays where it was. The countdown is computed from a stored `retryAt` timestamp (monotonic clock), so a screen lock does not stretch it. |
| Network error / timeout | Error box: "No se pudo conectar con el servidor. Revisa tu conexión e inténtalo de nuevo." PIN cleared, codes kept. |
| Offline (`navigator.onLine === false`, no stored identity) | A warning box at the top: "Necesitas conexión a internet para iniciar sesión." Submit stays `aria-disabled` with that text as its `aria-describedby`. Typing still works (so the user can prepare). When the connection returns the polite announcer says "Conexión restablecida." and the box disappears. A cold open with a stored identity never shows this screen; it goes to `/` and works offline (3.3.11). |
| Success | go to `/consent` if `consentPending`, otherwise `/` (focus: that page's h1). |

Notes:
- The PIN is a custom keypad, not an `<input>`. Nothing is written to a text field, so there is nothing for the browser to save or autofill, and the PIN never touches a DOM value. Keep a hidden form-less design: the `<form>` wraps the code fields and submit button, and the keypad buttons are `type="button"`.
- Hardware keyboard: digits `0`-`9` and `Numpad0-9` append, `Backspace` deletes one, `Delete` or `Escape` clears all, `Enter` submits when 8 digits are in (and `Enter` in a code field moves to the next field or submits when valid). Listeners are attached to the keypad group only when it contains focus, so they cannot swallow typing in the code fields.
- Autocomplete off on both code fields, `inputmode="text"`, `enterkeyhint="next"`.
- Footer line: none. Keep the screen short so the keypad and submit fit a 360 x 640 viewport without scrolling when the codes are pre-filled (budget: h1 56 + fields 2 x 84 + dots 56 + keypad 4 x 68 + submit 56 = about 610 px; compress the header to fit; at 320 x 568 scrolling is acceptable).

### 3.2 Consent (`/consent`)

Order: h1, intro, 7 sections (each h2 plus paragraph), version note, blocked note, actions.

```
 [Español|English]  [Cerrar sesión]
 
 Tus datos y tu consentimiento           <- h1
 DEMO usa esta aplicación para registrar
 tu asistencia. Antes de marcar, lee ... 
 
 Quién es el responsable                 <- h2
 DEMO es la responsable de estos datos.
 Qué datos se guardan
 ...selfie, ubicación GPS, hora...
 Para qué se usan
 Quién puede verlos
 Cuánto tiempo se conservan
 Selfies y coordenadas: 12 meses...
 Tus derechos
 ... envía tu solicitud al administrador
 
 ⓘ Sin tu consentimiento no puedes marcar
 entrada ni salida.                      <- info box
 Versión del texto: 2026-10-v1           <- 14 px muted
 
 [          Acepto          ]            <- primary 64 px
 [        No acepto         ]            <- secondary (outlined) 56 px
```

States:

| State | What changes |
|---|---|
| Default | Both actions enabled when online. Sections are always expanded (no accordion: simpler for screen readers, no hidden content). |
| Accepting | "Acepto" shows spinner and "Guardando…", both buttons `aria-disabled`, `aria-busy`. On 204: reload `today`, go to `/`, resume the flush, focus the main h1. |
| Error on accept | Error box `role="alert"` above the actions with the `error.*` text for the code or client kind, focus moves to it. Buttons re-enable. |
| Offline | Warning box above the actions: "Necesitas conexión para aceptar. Vuelve a intentarlo cuando tengas señal." "Acepto" is `aria-disabled` with that text as `aria-describedby`. "No acepto" still works. Announced politely when connectivity changes. |
| Declined | The two buttons are replaced by an explanation panel (`role="region"`, `aria-labelledby` its h2, focus moves to that h2): title "No se puede continuar sin tu consentimiento", body, and two buttons: **"Cerrar sesión"** (secondary, opens the logout confirm) and **"Volver"** (primary, returns to the full text with focus on the "Acepto" button). The decline is not sent to the server. |

Remarks:
- The "pending legal review" mark lives in the README, not on screen. The visible "Versión del texto" line lets an admin identify what was shown.
- Header on this page shows the language switcher and the logout button (a session exists), so a shared-phone user can leave without accepting.
- Heading order: h1, then seven h2.
- The text scrolls with the page; actions are at the end, not sticky (reflow at 400% zoom and no obscured focus).

### 3.3 Main screen (`/`)

#### 3.3.1 Anatomy (before clocking, online, fresh day)

```
 [Español|English]       [Cerrar sesión]  <- AppHeader (44 px buttons)
 ┌──────────────────────────────────┐
 │ Miércoles, 7 de octubre          │  teal band, white text
 │ Hola, María                      │  <- h1
 └──────────────────────────────────┘
   Hora del servidor
   07:58                          (aprox.)   <- 56 px bold, <time>
 
   ⊘ Aún no has marcado hoy              <- h2 status line (icon + text)
 
   EVIDENCIA                             <- 14 px label, h2
   (📷 Tomar selfie) (📍 Ubicación lista · ±12 m)
 
 [          Marcar entrada          ]    <- 64 px teal
 
   Entrada        Salida
   --:--          --:--
 
   Sin señal, la marca se guarda y se
   envía al reconectar.                  <- footer note, always visible
```

The footer note is always visible static text (mockup). The `OfflineBanner` is a separate, louder element at the top, shown only when offline.

Header on teal: only date (16 px, `--color-on-primary-muted`, 6.25:1) and greeting (24 px white bold, 7.52:1). The date is a `<p>` before the h1 in the DOM so the greeting is the heading but the date is read first as context. No interactive element sits on the teal band.

#### 3.3.2 State matrix

Notation: Status = h2 text. Button text. Evidence = chips shown. Under = EntryTimes content.

| State (from `effectiveState`) | Status line (icon) | Button | Evidence | Entry times area |
|---|---|---|---|---|
| **A. Before clocking** (`CLOCK_IN`, nothing pending) | "Aún no has marcado hoy" (empty circle) | **Marcar entrada** (enabled) | selfie + location | Entrada `--:--` / Salida `--:--` |
| **B. In shift** (`CLOCK_OUT`) | "En turno desde 07:58" (play/dot icon) + second line "Llevas 2 h 15 min" | **Marcar salida** | selfie + location | Entrada `07:58` + result line (+ late, + badge) / Salida `--:--` |
| **C. Day complete** (`NONE`) | "Jornada completa · 9 h 05 min" (check icon) | **Listo por hoy** (disabled, check icon) | hidden | both times, each with result line |
| **D. Queued clock-in** (offline, pending `CLOCK_IN`) | "Entrada guardada · se enviará al reconectar" (hourglass) | **Marcar salida** (enabled) | selfie + location | Entrada `07:58` + "Pendiente de envío" (hourglass) / Salida `--:--`. Pending counter "1 pendiente" |
| **E. Queued clock-out** (pending `CLOCK_OUT`) | "Salida guardada · se enviará al reconectar" (hourglass) | **Listo por hoy** (disabled) | hidden | Entrada (own result) / Salida `17:03` + "Pendiente de envío". Counter |
| **F. Review** (`PENDING_REVIEW` entry) | as A/B/C | as A/B/C | as A/B/C | result line plus a review badge under the time: "En revisión: Fuera del área, Enviada con retraso" |
| **G. Missing clock-out** (`missingClockOut` / `forgottenWorkDate`) | as A (usually) | as A | as A | notice above the status: "No marcaste la salida del martes 6 de octubre. Tu administrador lo revisará." |
| **H. Overnight last shift** (`lastCompleted` < 12 h) | as A | **Marcar entrada** | as A | secondary line under the status: "Último turno: mar 6 oct · 22:01–06:03 · 8 h 02 min" |
| **I. Consent pending** | route guard sends to `/consent`; the main screen never renders for this state | | | |
| **J. NO_BRANCH_ASSIGNED** | "No tienes sucursal asignada. Consulta a tu administrador." (info icon) | **Marcar entrada** shown disabled (aria-disabled, with the status line as `aria-describedby`) | hidden | Entrada/Salida `--:--`. If `role = ADMIN`: link "Ir al panel del administrador" under the button |
| **K. Loading, no snapshot, offline** | "Conéctate para cargar tu estado" (warning icon) | disabled | hidden | hidden |
| **L. Loading, online** | skeleton-free: "Cargando…" in the status h2 position | disabled | hidden | hidden |
| **M. Failure card** | overlay element above the day card, see 3.3.4 | state unchanged | | |

Wireframes of the key states:

State B, in shift, with a review badge and a late arrival:

```
 ┌──────────────────────────────────┐
 │ Miércoles, 7 de octubre          │
 │ Hola, María                      │
 └──────────────────────────────────┘
   Hora del servidor
   10:13
 
   ● En turno desde 07:58                <- h2
     Llevas 2 h 15 min                   <- 16 px, not live
 
   (📷 Tomar selfie) (📍 Ubicación lista · ±9 m)
 
 [          Marcar salida           ]
 
   Entrada                  Salida
   07:58                    --:--
   ⚠ Fuera del área · 1112 m · en revisión
   ┌──────────────────────────────┐
   │ ⚠ En revisión: Fuera del área,│  <- ReviewNotice (warning box)
   │   Enviada con retraso         │
   └──────────────────────────────┘
   🕒 Llegada tarde: 8 min
```

State D, queued clock-in, offline:

```
 ┌ ⚠ Sin señal, la marca se guarda y se envía al reconectar. ┐  <- OfflineBanner
 ┌──────────────────────────────────┐
 │ Miércoles, 7 de octubre          │
 │ Hola, María                      │
 └──────────────────────────────────┘
   Hora del servidor
   07:58 (aprox.)
 
   ⏳ Entrada guardada · se enviará       <- h2
      al reconectar
   [ 1 pendiente ]                       <- PendingCounter chip
 
 [          Marcar salida           ]
 
   Entrada                  Salida
   07:58                    --:--
   ⏳ Pendiente de envío
```

State C, day complete:

```
   ✓ Jornada completa · 9 h 05 min       <- h2
 [   ✓  Listo por hoy   ]                <- disabled look, grey
   Entrada                  Salida
   07:58                    17:03
   ✓ Dentro del área ·      ✓ Dentro del área ·
     a 35 m del local         a 41 m del local
```

State J, no branch:

```
   ⓘ No tienes sucursal asignada.
     Consulta a tu administrador.        <- h2
 [   Marcar entrada   ]  (disabled look)
   Ir al panel del administrador         <- link, only ADMIN
```

State G / H notices:

```
 ┌ ⚠ No marcaste la salida del martes 6 de octubre. Tu administrador lo revisará. ┐   (warning box)
   Último turno: mar 6 oct · 22:01–06:03 · 8 h 02 min       (muted, 14 px)
```

#### 3.3.3 Entry result and review rules (resolved decision 2)

Under each time (Entrada, Salida), `EntryResult` shows exactly one line. Source: the POST response first, then `today.clockIn/clockOut` after a reload.

| Data | Icon | Text ES | Text EN | Colors |
|---|---|---|---|---|
| queued, waiting | hourglass | Pendiente de envío | Pending send | muted text on bg |
| queued, request in flight | spinner (static under reduced motion) | Enviando… | Sending… | muted |
| `insideGeofence = true` | check-circle | Dentro del área · a 35 m del local | Inside the area · 35 m from the branch | success on success-bg |
| `insideGeofence = false` | alert-triangle | Fuera del área · 1112 m · en revisión | Outside the area · 1112 m · under review | warning on warning-bg |
| `insideGeofence = null` | map-pin-off | Sin ubicación · en revisión | No location · under review | warning on warning-bg |

- Distance is an integer, no thousands separator (`1112 m`), same in ES and EN.
- The pre-send chip never shows a distance (resolved decision 2). The word "local" in "a 35 m del local" only appears here.
- If `status = PENDING_REVIEW` a `ReviewNotice` badge follows: "En revisión: {reasons}" with reasons translated and joined with ", ". If the only reason already is in the result line (for example OUTSIDE_GEOFENCE) the badge still lists it; the redundancy is accepted because the result line also carries the distance. `REJECTED` shows "Rechazada" with an x-circle icon and the line "Contacta a tu administrador." in the same box.
- `lateMinutes > 0`: "Llegada tarde: 8 min" with a clock icon, under Entrada only.
- Each of the two columns has its own result region so a screen reader reads: "Entrada, 07:58, Dentro del área, a 35 m del local".

DOM for entry times is a description list so it reads in pairs:

```html
<section aria-labelledby="entries-h">
  <h2 id="entries-h" class="visually-hidden">Marcas de hoy</h2>
  <dl class="entry-times">
    <div><dt>Entrada</dt><dd><time>07:58</time><p class="result">…</p></dd></div>
    <div><dt>Salida</dt><dd>…</dd></div>
  </dl>
</section>
```

#### 3.3.4 Failure card (final failure of a queued item)

```
 ┌ ✖ No se pudo registrar tu salida ─────┐   role="alert", error colors
 │ Marca de las 17:03                    │
 │ No tienes una entrada abierta, así que│
 │ no se pudo registrar la salida.       │
 │ Contacta a tu administrador.          │
 │ [        Entendido        ]           │
 └────────────────────────────────────────┘
```

- One card per failed item, oldest first. Appears at the top of `main` (above the day card) so it is the first thing seen and the first tab stop after the header.
- `role="alert"` on the card announces it once on insertion. The body text is the translated message for the final `code` (section 5.4) plus the fixed "Contacta a tu administrador." line (not a second alert).
- "Entendido" deletes the item, resumes the flush, reloads `today`; focus moves to the status h2. If another failure card follows, focus moves to its "Entendido" instead.
- The flush is paused until acknowledged. The card persists across reloads (the failed item is in IndexedDB).

#### 3.3.5 Pending counter, "pending since", queue-full, approximate clock

- **PendingCounter**: a chip under the status line when `pending > 0`: "1 pendiente" / "3 pendientes" with a hourglass icon. When `needsReauth` it becomes a button "Ingresa tu PIN para enviar" that reopens the `ReauthDialog` (see 3.7).
- **Pending since**: if the oldest pending item is older than 24 h the chip adds a second line: "Pendiente desde 6 oct, 07:58".
- **Queue full (8 items) / storage full**: a refused capture shows an error box (`role="alert"`, error colors) above the button with `offline.queueFull` or `offline.storageFull`, focus stays on the primary button. No dialog opens.
- **Approximate clock**: when the estimate is flagged approximate the clock shows "(aprox.)" (14 px muted, to the right of the time) inside the same `<time>` region so it is read after the time: "07:58 aprox."

#### 3.3.6 Disabled primary button

The "Listo por hoy" and the no-branch/loading button use `aria-disabled="true"` (not the `disabled` attribute), so keyboard and screen-reader users can still land on it and hear why. Click and Enter do nothing. Visually: `--color-disabled-bg`, `--color-disabled-text`, 1 px border `--color-border`, a check icon for "Listo por hoy" and no pressed effect. In J and K the status h2 is its `aria-describedby`.

### 3.4 Selfie capture flow (`SelfieCapture`, a lazy modal `<dialog>`)

Opened by the primary button. Full screen on phones (`100dvh`, no radius), centered card at >= 768 px. Title (dialog `aria-labelledby`): "Tu selfie" and below it "Para marcar tu entrada" (or "salida"). The dialog is a single component with these steps:

| Step | Entry condition | Content | Primary / secondary action |
|---|---|---|---|
| 0. Explainer | Permissions API says camera or location is `prompt` (or the API is missing and it is the first capture on this device) | text `camera.explain` | **Continuar** / Cancelar |
| 1. Opening | after Continuar, waiting for `getUserMedia` | spinner + "Abriendo la cámara…" | Cancelar |
| 2. Preview | camera running | live mirrored video, framing hint, LocationChip, shutter | **Tomar foto** (72 px round button with label) / Cancelar |
| 3. Review | after the shutter | still image, location chip, hint "Al usar la foto se registra tu entrada." | **Usar foto** / Repetir |
| 4. Processing | after Usar foto: compression and enqueue | spinner + "Procesando la foto…" | none (Cancelar disabled during the short work) |
| 5. Waiting for location | no fix yet when the user taps Usar foto | "Obteniendo ubicación…" with spinner; after 5 s a secondary button "Marcar sin ubicación" appears | Cancelar |
| 6. Error states | see 3.5 | message + actions | varies |
| 7. File fallback | camera impossible and user opts in | native file picker (hidden input) | on a file: goes to step 3 |

```
Step 2: preview                          Step 3: review
┌──────────────────────────────────┐    ┌──────────────────────────────────┐
│ Tu selfie                     [✕]│    │ Revisa tu selfie              [✕]│
│ Para marcar tu entrada           │    │ Para marcar tu entrada           │
│ (📍 Ubicación lista · ±12 m)     │    │ (📍 Ubicación lista · ±12 m)     │
│ ┌────────────────────────────┐   │    │ ┌────────────────────────────┐   │
│ │                            │   │    │ │                            │   │
│ │      (live video,          │   │    │ │     (captured photo)       │   │
│ │       mirrored)            │   │    │ │                            │   │
│ │                            │   │    │ │                            │   │
│ └────────────────────────────┘   │    │ └────────────────────────────┘   │
│ Centra tu cara en el encuadre.   │    │ Al usar la foto se registra      │
│                                  │    │ tu entrada.                      │
│          ( ◉ Tomar foto )        │    │ [ Usar foto ]  [ Repetir ]       │
│          [ Cancelar ]            │    │                                  │
└──────────────────────────────────┘    └──────────────────────────────────┘
```

Behavior details:
- The close icon button "✕" (44 px, accessible name "Cancelar") sits top right. Esc also cancels. Cancelling stops every camera track, revokes object URLs, stops the GPS watch (unless the main screen's warm-up owns it), and returns focus to the primary button.
- **Preview is mirrored by CSS** (`transform: scaleX(-1)`), the captured image is not. The review image therefore looks flipped compared to the preview. Add the muted caption on step 3: "La foto se guarda sin espejo." (`camera.notMirrored`) so the user is not surprised.
- Video: `playsinline muted autoplay`, `aria-label` "Vista previa de la cámara", `object-fit: cover`, aspect box 3:4 (portrait) or 4:3 (landscape), max height `55dvh`.
- Review image: `alt` = "Vista previa de tu selfie" (`camera.reviewAlt`). The image is shown from an object URL (`blob:` allowed by the CSP) and is revoked when the step ends.
- The shutter is a real `<button>` with visible text "Tomar foto" under or inside a round 72 px control (never an icon-only button), so a switch or voice user can say "Tomar foto".
- Step 3 gives equal weight to "Usar foto" (primary, teal) and "Repetir" (secondary). "Usar foto" is the commit action, so its hint line states what happens. It is not enabled while the photo is still being decoded.
- Step 5: if the fix arrives, the dialog continues to step 4 on its own. If the user taps "Marcar sin ubicación", it continues with `NO_GPS`. After the 20 s timeout it continues on its own with the best fix so far or none.
- The GPS watch starts when the explainer is dismissed (or at once when no explainer is needed), in parallel with the camera. See section 8, item 4.
- The dialog body has a polite `aria-live` span (not `role="status"`) that announces step changes: "Cámara lista", "Foto tomada. Revisa tu selfie", "Procesando la foto…".
- Focus: step 0 focus on "Continuar"; step 1 on the dialog title (`tabindex=-1`); step 2 on "Tomar foto"; step 3 on "Usar foto"; step 5 on "Cancelar"; error steps on the error message (`tabindex=-1`, `role="alert"`). On close, focus returns to the opener.

### 3.5 Permission flows and denied states

#### Camera

| Situation | Dialog content | Actions |
|---|---|---|
| Permission not yet decided | Step 0 explainer: "Para marcar necesitamos una selfie y tu ubicación. Tu teléfono te pedirá permiso para usar la cámara y la ubicación." Then the browser prompt appears over the dialog. | Continuar, Cancelar |
| Denied (`NotAllowedError`) | Error box: "No tenemos permiso para usar la cámara." + "Para marcar necesitas permitir la cámara:" + two short instructions (Android Chrome, iPhone Safari, labelled) + the always-visible line "Sin selfie no se puede marcar. Si no puedes habilitar la cámara, contacta a tu administrador." | **Reintentar** (primary), **Usar la cámara del teléfono** (secondary, opens the file input), Cancelar |
| No camera (`NotFoundError`) | "No se encontró una cámara en este teléfono." + the contact line | Usar la cámara del teléfono, Cancelar |
| Busy (`NotReadableError`) | "La cámara está en uso por otra aplicación. Ciérrala e inténtalo de nuevo." | Reintentar, Usar la cámara del teléfono, Cancelar |
| Unsupported (no `mediaDevices`, or insecure origin) | "Este navegador no puede abrir la cámara." | Usar la cámara del teléfono, Cancelar |
| Unknown/Overconstrained | same as unsupported text | same |
| Both camera and file path unusable (the file input yields no image twice, or the photo is unsupported/too large and the user gives up) | Terminal: "No se puede marcar sin selfie. Contacta a tu administrador." | Cancelar only |
| Photo too large / unsupported (`photoTooLarge`, `photoUnsupported`) | the matching error text | Repetir (back to step 2 or the file picker), Cancelar |

```
Camera denied
┌──────────────────────────────────┐
│ Tu selfie                     [✕]│
│ ┌ ✖ No tenemos permiso para usar │
│ │   la cámara.                   │   role="alert", error box
│ │ Para marcar necesitas permitir │
│ │ la cámara:                     │
│ │ • Android (Chrome): toca el    │
│ │   candado junto a la dirección,│
│ │   Permisos, Cámara, Permitir.  │
│ │ • iPhone (Safari): toca aA,    │
│ │   Configuración del sitio web, │
│ │   Cámara, Permitir.            │
│ └────────────────────────────────┘
│ Sin selfie no se puede marcar. Si│
│ no puedes habilitar la cámara,   │
│ contacta a tu administrador.     │
│ [ Reintentar ]                   │
│ [ Usar la cámara del teléfono ]  │
│ [ Cancelar ]                     │
└──────────────────────────────────┘
```

- Both platform instructions are always shown (platform sniffing is unreliable and the user may have a differently branded browser).
- The selfie chip on the main screen turns to the warning state (3.3.7) as soon as the Permissions API reports `denied`, so the employee knows before tapping. The button stays enabled: tapping it leads to the denied step and the file fallback.
- The "Usar la cámara del teléfono" button triggers a hidden `<input type="file" accept="image/*" capture="user">`. It is a real `<button>` with a text label that calls `input.click()`; the input itself is `tabindex=-1` and `aria-hidden`.

#### Location (GPS)

GPS denied or unavailable never blocks clocking; it warns that the entry will be reviewed.

| Situation | Where | What the user sees |
|---|---|---|
| Not asked yet, permission unknown | main chip | "Ubicación: se pedirá al marcar" (neutral chip, pin icon) |
| Searching | main chip, dialog chip | "Buscando ubicación…" (spinner icon, static under reduced motion) |
| Ready, accuracy <= 100 m | chip | "Ubicación lista · ±12 m" (check icon, success style) |
| Accuracy > 100 m | chip | "Ubicación imprecisa · ±150 m · la marca quedará en revisión" (alert icon, warning style) |
| Denied | chip | "Ubicación bloqueada · la marca quedará en revisión" (pin-off icon, warning style). The chip is followed, only in the dialog, by a short help line with the same site-settings hint style as the camera, collapsed into one sentence: "Puedes activarla en los permisos del sitio y volver a intentar." (`evidence.location.help`) |
| Unavailable / timeout | chip | "Ubicación no disponible · la marca quedará en revisión" (pin-off icon, warning style) |

- The chip text is exactly the plan strings. No distance ever.
- Warning styling is never the only signal: the icon differs per state and the text states the consequence.
- In the dialog, GPS state changes are announced through the dialog's polite live span only for the transitions searching to ready, and to a warning state.

#### 3.3.7 Evidence chips on the main screen

Chips are informational (`<ul>` of `<li>`), not buttons. A flat pill with a leading icon, `--color-info-bg` background, 1 px `--color-border` border, text 14 px, min-height 44 px so they stay comfortable if a later stage makes them interactive.

| Chip | States |
|---|---|
| Selfie | default "Tomar selfie" (camera icon). If Permissions API says camera `denied`: warning style, "Cámara bloqueada · no podrás marcar sin selfie" with a camera-off icon. |
| Location | the six states in the table above. |

The selfie chip keeps the mockup text "Tomar selfie" as a checklist item; the real action is the primary button. The section heading "EVIDENCIA" is a visible `h2` (small caps style, 14 px bold, letter-spacing .06em, text kept as "Evidencia" in the resource; use `text-transform: uppercase` in CSS so screen readers read it as a word, not letters).

### 3.6 Offline banner and states

```
┌ 📶✖ Sin señal, la marca se guarda y se ┐   warning box, above the day card
│     envía al reconectar.               │
└────────────────────────────────────────┘
```

- Shows when `navigator.onLine === false` or the last request failed at network level. Warning colors plus a wifi-off icon plus the full sentence. It is not `role="alert"` (it is a state, not an interruption); the Announcer announces the change once ("Sin señal, la marca se guarda y se envía al reconectar."). When connectivity returns the banner disappears and the Announcer says "Conexión restablecida. Enviando tus marcas…" (only if something is pending; otherwise "Conexión restablecida.").
- The static footer note on the main screen repeats the sentence as plain muted text at all times (mockup). When the banner is showing, the footer note is hidden (`hidden`) to avoid reading the sentence twice.
- The clock shows "(aprox.)" when approximate. Clocking stays fully enabled offline.
- The login and consent screens use the same banner component variant with their own texts (3.1, 3.2).

### 3.7 Re-auth (PIN) dialog (`ReauthDialog`)

Opens automatically when the flush gets 401, when the token is expired and the queue is not empty and the user is online, or on tap of the pending-counter button. The queue is never touched.

```
┌──────────────────────────────────┐
│ Ingresa tu PIN                   │   dialog title
│ Tu sesión venció. Ingresa tu PIN │
│ para continuar. Tus marcas       │
│ guardadas se conservan y se      │
│ enviarán al ingresar.            │
│                                  │
│ María Pérez                      │   read-only identity
│ Empresa DEMO · Empleado 1001     │   plain text, not inputs
│                                  │
│        ○ ○ ○ ○   ○ ○ ○ ○         │
│        0 de 8 dígitos            │
│  [1] [2] [3]                     │
│  [4] [5] [6]                     │
│  [7] [8] [9]                     │
│  [Borrar todo] [0] [⌫]           │
│ [        Ingresar        ]       │
│ [ Más tarde ]  [ ¿No eres tú? ]  │
└──────────────────────────────────┘
```

| State | Behavior |
|---|---|
| Default | Same keypad, dots and rules as login. The company and employee codes come from the stored identity and are shown as plain text with their labels (`<dl>`), not as inputs. |
| Submitting | keypad disabled, button spinner "Ingresando…". |
| Wrong PIN (401) | PIN cleared, error box with `error.INVALID_CREDENTIALS`, focus to the error. The dialog stays. The queue is kept. |
| 429 | same countdown pattern as login. |
| Network error | `error.network` in the dialog; queue kept. |
| Success | dialog closes, focus returns to the element that had focus before (or the status h2 if that is gone), the Announcer says "Sesión iniciada. Enviando tus marcas…" followed by each send result. |
| "Más tarde" / Esc | dialog closes, `needsReauth` remains. The PendingCounter becomes the "Ingresa tu PIN para enviar" button. Not re-opened automatically until the next trigger (reconnect, app open, or tap). |
| "¿No eres tú?" | closes this dialog and opens the logout confirmation (3.9), which warns about the pending items. |

Wrong-employee guard: a PIN that logs in as a different employee than the stored identity is treated as an error by the coder (not specified here); show `error.INVALID_CREDENTIALS`.

### 3.8 Update-available prompt (`UpdatePrompt`)

```
┌ ⟳ Hay una nueva versión.           ┐   info box, non-modal, top of main
│ [ Actualizar ]   [ Luego ]         │
└────────────────────────────────────┘
```

- Non-modal, in normal flow (after the offline banner), info colors (`--color-info-bg`, teal text and border, refresh icon).
- Not rendered while `SelfieCapture` or `ReauthDialog` is open or a flush is running; it appears afterwards. Never `role="alert"`.
- Announced politely once through the Announcer: "Hay una nueva versión disponible." (key `update.announce`).
- "Actualizar" calls `updateServiceWorker(true)`; the button shows a spinner and "Actualizando…" until the page reloads. "Luego" hides the banner for this page view; it returns on the next app open.
- Both buttons >= 44 px. Focus is not moved when it appears (it must not steal focus from a user mid-task).

### 3.9 Logout confirmation (`ConfirmDialog`)

The header "Cerrar sesión" button (44 px, secondary style, text visible at every width; at 320 px the header wraps to two rows, switcher first) opens a modal confirm. Always shown (see section 8, item 3).

No pending items:

```
┌──────────────────────────────────┐
│ ¿Cerrar sesión?                  │
│ Tendrás que ingresar tu PIN de   │
│ nuevo.                           │
│ [   Cerrar sesión   ]            │
│ [      Cancelar      ]           │
└──────────────────────────────────┘
```

With N pending items (shared-phone data-loss protection):

```
┌──────────────────────────────────┐
│ ¿Cerrar sesión?                  │
│ ┌ ⚠ Tienes 2 marcas sin enviar.  │   warning box
│ │ Si cierras sesión se borrarán  │
│ │ y no se registrarán.           │
│ └────────────────────────────────┘
│ [      Cancelar      ]  <- primary, initial focus
│ [ Cerrar sesión y borrar ]  <- destructive, error-colored outline + text
└──────────────────────────────────┘
```

- Initial focus: with no pending items "Cancelar"; with pending items "Cancelar" too (the safe action). Esc and backdrop click = Cancelar.
- Destructive action uses an outlined button with `--color-error` text and border (6.73:1 on bg, 7.54:1 on white), a trash icon and the words "borrar", so it does not rely on color.
- Confirming shows "Cerrando sesión…" with a spinner on the button (best-effort server call, 3 s timeout), then navigates to `/login`, focus on the login h1. Announcer text on arrival is not needed.
- The same `ConfirmDialog` component (props: title, body, confirm label, cancel label, tone) is reused for the "Not you?" path and for the "switch employee while another's items are pending" confirm on the login page.

---

## 4. Component inventory

All components are presentational; contexts supply data. Props are TypeScript shapes for the coder. Texts always come from `t(key)`. Every interactive component forwards a ref where focus is moved programmatically.

### 4.1 `PinKeypad`

```ts
type PinKeypadProps = {
  value: string                    // digits entered, 0-8 chars (never rendered)
  onChange: (next: string) => void
  maxLength?: 8
  disabled?: boolean               // submitting or counting down
  idPrefix: string
}
```
- Structure: `<div role="group" aria-label={t('login.keypad.group')}>` containing 12 `<button type="button">`. Rows: 1 2 3 / 4 5 6 / 7 8 9 / Borrar todo, 0, backspace. Grid `repeat(3, minmax(80px, 1fr))`, gap 12 px, key height 56 px, radius `--radius-md`, white surface, 1 px `--color-border`, text 1.5rem 600.
- Accessible names: digit keys named by the digit; backspace `aria-label` "Borrar último dígito" (icon-only, SVG `aria-hidden`); clear key shows and is named "Borrar todo" (label in name, WCAG 2.5.3).
- States: default, pressed (`--color-key-pressed` + `transform: scale(.97)`, color only under reduced motion), focus-visible ring, disabled (`aria-disabled="true"`, 60% opacity is **not** used; use `--color-disabled-bg` and `--color-disabled-text` so contrast holds). Backspace and clear are `aria-disabled` when `value` is empty. Digits are `aria-disabled` at 8.
- Keyboard: Tab enters the group at one stop (roving tabindex, initial key "5"). Arrow keys move in the 3 x 4 grid, Home/End go to row ends. Space/Enter activate. Number keys work from anywhere inside the group. Esc clears when value non-empty. Digits typed on a hardware keyboard are accepted only while focus is in the group.
- Focus management: after a key activation focus stays on that key. On a failed login (PIN cleared) focus goes to the error box, not the keypad. After the error is dismissed or the user starts typing, focus is where the user puts it.
- No key press sound. Haptic: none for keys (7.4).

### 4.2 `PinDots`

```ts
type PinDotsProps = { length: number; max?: 8 }   // length = digits entered
```
- Eight `<span>` dots in two groups of four (`gap` 8 px within a group, 20 px between). 20 px diameter, filled = solid `--color-primary`, empty = 2 px `--color-border` ring on `--color-surface`. Container `aria-hidden="true"` and `data-testid="pin-dots"`.
- Next to it a `<p class="pin-progress">` with `t('login.pinProgress', {n: length})`. It is visible (14 px muted) so low-vision users get the count in text, and it carries `aria-live="polite"`, content updated through a 400 ms debounce so fast typing yields one announcement ("5 de 8 dígitos"). It never contains or hints at digit values. When `length` is 8 the text stays "8 de 8 dígitos".
- States: 0-8 filled. During error clearing: all empty (no shake animation; reduced-motion safe by default).

### 4.3 `ClockButton`

```ts
type ClockButtonProps = {
  action: 'CLOCK_IN' | 'CLOCK_OUT' | 'DONE'
  disabled?: boolean               // renders aria-disabled
  disabledReasonId?: string        // aria-describedby target
  busy?: boolean                   // capture/enqueue in progress
  onPress: () => void
}
```
- A `<button type="button">`, full width, `min-height: 64px`, radius `--radius-lg`, `--fs-button`, bold, teal fill, white text, leading icon (log-in arrow for CLOCK_IN, log-out arrow for CLOCK_OUT, check for DONE). Label from `button.clockIn|clockOut|done`.
- States:
  - enabled: teal; hover not needed (touch first) but add `@media (hover: hover)` lighten-free: darker `--color-primary-pressed`.
  - pressed: `--color-primary-pressed`, `transform: scale(.98)`.
  - focus-visible: ring.
  - busy (`aria-busy="true"`, `aria-disabled="true"`): label "Guardando…" and a spinner icon; ignores presses (double-tap guard).
  - done/disabled: grey, check icon, `aria-disabled="true"`.
- Keyboard: Enter/Space activate. Disabled states stay in the tab order.
- Focus: returns here when `SelfieCapture` closes (the opener).

### 4.4 `ServerClock`

```ts
type ServerClockProps = { epochMs: number; timeZone: string; approximate: boolean }
```
- Markup: `<p class="clock-label">Hora del servidor</p><p class="clock"><time dateTime={iso}>07:58</time><span class="clock-approx">(aprox.)</span></p>`. The visible label is the accessible name; no `aria-label` on `<time>` (naming a generic/time element is unreliable, see section 8, item 1). Updates on minute boundaries. Not a live region. Font `--fs-clock`, tabular numerals, `--color-text`.
- States: exact, approximate (suffix shown), loading (`--:--` with `aria-busy`).

### 4.5 `StatusLine`

```ts
type StatusLineProps = {
  tone: 'neutral' | 'active' | 'done' | 'queued' | 'blocked'
  text: string
  secondary?: string           // worked duration or last-shift line
  headingRef: Ref<HTMLHeadingElement>
}
```
- `<h2 tabIndex={-1}>` with a leading icon (`aria-hidden`) chosen by `tone` (empty circle, dot-in-circle, check-circle, hourglass, info). Text is `--fs-h2`. The secondary line is a `<p>` below, 16 px.
- This h2 receives focus after a state change (success, queued, failure acknowledgement). It is not a live region (the Announcer does the announcing).

### 4.6 `EvidenceChips` (`SelfieChip`, `LocationChip`)

```ts
type SelfieChipProps = { camera: 'unknown' | 'blocked' }
type LocationChipProps = {
  status: 'idle' | 'searching' | 'ready' | 'lowAccuracy' | 'denied' | 'unavailable'
  accuracyMeters?: number      // rounded integer
}
```
- `<section aria-labelledby>` with the "Evidencia" h2 and a `<ul>` of two `<li class="chip">`. Each chip: icon (`aria-hidden`) + text. Tone classes: `chip--neutral`, `chip--ok` (success), `chip--warn` (warning). Min height 44 px, wraps to two lines at 320 px, `overflow-wrap: anywhere`.
- Not focusable, no role, no live region. Hidden entirely when the button is DONE/blocked.
- The location text is exactly the plan strings. `LocationChip` never receives a distance prop.

### 4.7 `EntryTimes`

```ts
type EntryTimesProps = {
  clockIn: EntryView | null
  clockOut: EntryView | null
  lateMinutes?: number | null
}
type EntryView = { timeText: string; result: EntryResultProps; reviewReasons: string[]; status: TimeEntryStatus }
```
- A `<dl>` of two `<div>` pairs (see 3.3.3), two columns with a 16 px gap that stack to one column below 20.5rem (328 px) viewport width only if a result line wraps awkwardly (use `grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr))`). `dt` 14 px muted, `dd` time `--fs-lead` 600 tabular. Empty time renders `--:--` as `aria-hidden` plus a visually hidden "Sin registrar".
- Contains `EntryResult`, `ReviewNotice` and the late line for each column.

### 4.8 `EntryResult`

```ts
type EntryResultProps =
  | { kind: 'pending' }
  | { kind: 'sending' }
  | { kind: 'inside'; distanceMeters: number }
  | { kind: 'outside'; distanceMeters: number }
  | { kind: 'noLocation' }
```
- `<p class="result result--{kind}">` icon + text; box style per 3.3.3. Pending/sending use plain muted text with no box; inside uses the success box; outside/noLocation use the warning box. Icons: hourglass, spinner, check-circle, alert-triangle, map-pin-off (distinct silhouettes).
- Not a live region. The Announcer announces the result once when it first arrives.

### 4.9 `ReviewNotice`

```ts
type ReviewNoticeProps = { status: 'PENDING_REVIEW' | 'REJECTED'; reasons: string[] }
```
- Warning box (REJECTED: error box), icon + "En revisión: Fuera del área, Enviada con retraso". `role` none, inside the entry column so it reads with its entry. For the missing clock-out notice use the same box styled component with its own text (`ReviewNotice` variant `kind="missingClockOut"`), placed before the status line.

### 4.10 `OfflineBanner` (+ `PendingCounter`)

```ts
type OfflineBannerProps = { offline: boolean }
type PendingCounterProps = { count: number; oldestAt?: Date; needsReauth: boolean; onReauth: () => void }
```
- Banner: warning box, wifi-off icon, `offline.banner` text; renders nothing when online. Not `role="alert"`. No focusable content.
- Counter: a chip (hourglass + `home.pendingCount`). With `needsReauth`, it is a `<button>` "Ingresa tu PIN para enviar" (44 px) that calls `onReauth`. The "pending since" second line is plain text.

### 4.11 `FailureCard`

```ts
type FailureCardProps = {
  type: 'CLOCK_IN' | 'CLOCK_OUT'
  timeText: string                 // deviceTime of the failed item
  messageKey: MessageKey           // error.<code>
  onAcknowledge: () => void
}
```
- See 3.3.4. `role="alert"`, error box, h3 title (inside `<section>`), button "Entendido" (secondary, 48 px). The card moves focus to nothing on mount (the alert announces it); focus is managed on acknowledge.

### 4.12 `SelfieCapture`

```ts
type SelfieCaptureProps = {
  open: boolean
  action: 'CLOCK_IN' | 'CLOCK_OUT'
  location: { status: LocationChipProps['status']; accuracyMeters?: number }
  onConfirm: (photo: { bytes: ArrayBuffer; type: 'image/jpeg' }, skipLocation: boolean) => void
  onCancel: () => void
}
```
- A native `<dialog aria-labelledby>` opened with `showModal()`. Steps as in 3.4. Includes a `CameraPreview` (the `<video>`), `LocationChip`, the shutter, review image, error panels (3.5) and the hidden file input with its labelled trigger button.
- ARIA: dialog title as h2, polite `aria-live` span for steps, `role="alert"` on error panels, `<video aria-label>`, review `<img alt>`.
- Keyboard: Tab cycles inside (native trap). Esc cancels and stops tracks. Enter/Space on the shutter. No gesture-only operation.
- Focus: see 3.4. Cancelling returns focus to the `ClockButton`.
- Lazy chunk; a short "Abriendo…" state covers the chunk load.

### 4.13 `ReauthDialog`

```ts
type ReauthDialogProps = {
  open: boolean
  identity: { name: string; companyCode: string; employeeCode: string }
  busy: boolean
  errorKey?: MessageKey
  retryAt?: number                 // epoch ms for the 429 countdown
  onSubmit: (pin: string) => void
  onLater: () => void
  onNotYou: () => void
}
```
- Native modal `<dialog>` composing `PinDots`, `PinKeypad`, the identity `<dl>` and the error box. Details in 3.7. Initial focus on the dialog title (`tabindex=-1`), so the first Tab lands on the first key, not a surprise key press. Esc = `onLater`.

### 4.14 `ConfirmDialog`

```ts
type ConfirmDialogProps = {
  open: boolean
  title: string
  body: ReactNode
  confirmLabel: string
  cancelLabel: string
  tone?: 'default' | 'destructive'
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}
```
- Native modal `<dialog>`: use `role="dialog"` for the default tone and `role="alertdialog"` with `aria-describedby` pointing to the body when destructive. Initial focus on the cancel button. Esc and backdrop click cancel. Focus returns to the opener.

### 4.15 `UpdatePrompt`

```ts
type UpdatePromptProps = { needRefresh: boolean; suppressed: boolean; onUpdate: () => void; onDismiss: () => void }
```
- Renders `null` unless `needRefresh && !suppressed`. See 3.8.

### 4.16 `Announcer`

```ts
type AnnouncerApi = { announce: (message: string, opts?: { dedupeKey?: string }) => void }
```
- One `<div role="status" aria-live="polite" aria-atomic="true" class="visually-hidden">` per employee page (login, consent, main). Provided through context; hooks call `announce(text)`.
- Rules: set the text on the next animation frame after clearing it (so an identical repeated message is re-announced); drop messages with the same `dedupeKey` within 1 s; keep a queue so two messages in the same tick are read one after another; clear the text after 8 s. The text passes through `toSpoken()`, which replaces " · " with ", " so screen readers do not read "punto medio". The `LanguageSwitcher` keeps its own `aria-live` span.
- Not mounted inside dialogs. Dialogs use their own local `aria-live` span so the single-`role="status"` rule per page is kept and modal inertness does not swallow the message.

### 4.17 `AppHeader` (+ logout)

```ts
type AppHeaderProps = { onLogout?: () => void }   // button shown when a session or identity exists
```
- `<header class="app-header">` flex row, `justify-content: space-between`, gap 8 px, wraps at 320 px. Left: `LanguageSwitcher` (unchanged). Right: `<button class="header-button">Cerrar sesión</button>` with a log-out icon (`aria-hidden`), 44 px high, 1 px teal border, transparent fill (same visual language as the language buttons). Not a menu: a single button avoids a menu widget, popup focus rules and extra taps (section 8, item 3).
- On `/login` the logout button is absent.
- The header is the first tab stop group: language ES, language EN, Cerrar sesión.

### 4.18 Shared icon set

Inline SVG (no icon font, no extra requests), 20 px, `currentColor`, `aria-hidden="true"`, `focusable="false"`, 2 px stroke, distinct silhouettes: check-circle, alert-triangle, x-circle, info, hourglass, map-pin, map-pin-off, camera, camera-off, wifi-off, refresh, log-in, log-out, trash, spinner. Create one `Icon.tsx` that maps a name to the SVG path. Stay within the bundle budget (about 1.5 KB gzip).

---

## 5. i18n

### 5.1 Conventions

- Keys are flat dotted strings in `resources.ts`; both `es` and `en` must contain every key.
- Placeholders `{name}`. Plurals use two keys, `.one` and `.other`; the caller picks `.one` when `n === 1`.
- Register: "tú" in Spanish throughout (matches the mockup). English is neutral second person.
- Texts that are intentionally identical in ES and EN (add to `SAME_IN_BOTH`): `login.pin`, `duration.hm`, `duration.m`, `home.noTime`.
- The server `detail` strings are never shown (they use "usted").

### 5.2 Key table

Login:

| Key | ES | EN |
|---|---|---|
| `login.title` | Iniciar sesión | Sign in |
| `login.companyCode` | Código de empresa | Company code |
| `login.employeeCode` | Código de empleado | Employee code |
| `login.pin` | PIN | PIN |
| `login.pinProgress` | {n} de 8 dígitos | {n} of 8 digits |
| `login.submit` | Ingresar | Sign in |
| `login.submitting` | Ingresando… | Signing in… |
| `login.offline` | Necesitas conexión a internet para iniciar sesión. | You need an internet connection to sign in. |
| `login.retryReady` | Ya puedes intentarlo de nuevo. | You can try again now. |
| `login.error.companyRequired` | Escribe tu código de empresa. | Enter your company code. |
| `login.error.employeeRequired` | Escribe tu código de empleado. | Enter your employee code. |
| `login.error.codeFormat` | Usa solo letras, números y guiones (máximo 20). | Use only letters, numbers and hyphens (maximum 20). |
| `login.keypad.group` | Teclado numérico del PIN | PIN keypad |
| `login.keypad.delete` | Borrar último dígito | Delete last digit |
| `login.keypad.clear` | Borrar todo | Clear all |

Consent:

| Key | ES | EN |
|---|---|---|
| `consent.title` | Tus datos y tu consentimiento | Your data and your consent |
| `consent.intro` | {company} usa esta aplicación para registrar tu asistencia. Antes de marcar, lee cómo se tratan tus datos (Ley 8968, Protección de la Persona frente al Tratamiento de sus Datos Personales). | {company} uses this app to record your attendance. Before you clock in, read how your data is handled (Law 8968, Protection of Individuals regarding the Processing of their Personal Data). |
| `consent.responsibleTitle` | Quién es el responsable | Who is responsible |
| `consent.responsibleBody` | {company} es la responsable del tratamiento de estos datos. | {company} is responsible for processing this data. |
| `consent.whatTitle` | Qué datos se guardan | What is collected |
| `consent.whatBody` | En cada entrada y salida se guardan una selfie, tu ubicación GPS (latitud, longitud y precisión) y la hora de tu teléfono. | On every clock-in and clock-out we keep a selfie, your GPS location (latitude, longitude and accuracy) and your phone's time. |
| `consent.whyTitle` | Para qué se usan | What it is used for |
| `consent.whyBody` | Para controlar la asistencia y revisar marcas irregulares. No se usan para ningún otro fin. | To control attendance and review irregular entries. They are not used for any other purpose. |
| `consent.whoTitle` | Quién puede verlos | Who can see it |
| `consent.whoBody` | Solo los administradores autorizados de {company}. | Only the authorized administrators of {company}. |
| `consent.retentionTitle` | Cuánto tiempo se conservan | How long it is kept |
| `consent.retentionBody` | Las selfies y las coordenadas exactas se conservan 12 meses y luego se eliminan. Los registros de hora se conservan el tiempo que exige la ley laboral. | Selfies and exact coordinates are kept for 12 months and then deleted. Time records are kept for as long as labour law requires. |
| `consent.rightsTitle` | Tus derechos | Your rights |
| `consent.rightsBody` | Puedes acceder a tus datos, rectificarlos, cancelarlos y oponerte a su tratamiento. Envía tu solicitud al administrador de tu empresa. | You can access, correct and delete your data, and object to its processing. Send your request to your company's administrator. |
| `consent.blockedNote` | Sin tu consentimiento no puedes marcar entrada ni salida. | Without your consent you cannot clock in or out. |
| `consent.version` | Versión del texto: {version} | Text version: {version} |
| `consent.accept` | Acepto | I accept |
| `consent.accepting` | Guardando… | Saving… |
| `consent.decline` | No acepto | I do not accept |
| `consent.declinedTitle` | No se puede continuar sin tu consentimiento | We can't continue without your consent |
| `consent.declinedBody` | Sin aceptar no puedes marcar entrada ni salida. Puedes cerrar sesión o volver y aceptar cuando quieras. Si tienes dudas, consulta a tu administrador. | Without accepting you cannot clock in or out. You can sign out, or go back and accept whenever you want. If you have questions, ask your administrator. |
| `consent.offline` | Necesitas conexión para aceptar. Vuelve a intentarlo cuando tengas señal. | You need a connection to accept. Try again when you have signal. |

Main screen:

| Key | ES | EN |
|---|---|---|
| `home.greeting` | Hola, {name} | Hello, {name} |
| `home.serverClock` | Hora del servidor | Server time |
| `home.approx` | (aprox.) | (approx.) |
| `home.loading` | Cargando… | Loading… |
| `home.status.notYet` | Aún no has marcado hoy | You haven't clocked in today |
| `home.status.onShift` | En turno desde {time} | On shift since {time} |
| `home.status.workedSoFar` | Llevas {duration} | {duration} so far |
| `home.status.dayComplete` | Jornada completa · {duration} | Day complete · {duration} |
| `home.status.queuedIn` | Entrada guardada · se enviará al reconectar | Clock-in saved · will be sent when you reconnect |
| `home.status.queuedOut` | Salida guardada · se enviará al reconectar | Clock-out saved · will be sent when you reconnect |
| `home.status.noBranch` | No tienes sucursal asignada. Consulta a tu administrador. | You have no branch assigned. Ask your administrator. |
| `home.status.needsConnection` | Conéctate para cargar tu estado | Connect to load your status |
| `home.lastShift` | Último turno: {date} · {in}–{out} · {duration} | Last shift: {date} · {in}–{out} · {duration} |
| `home.missingClockOut` | No marcaste la salida del {date}. Tu administrador lo revisará. | You didn't clock out on {date}. Your administrator will review it. |
| `home.late` | Llegada tarde: {minutes} min | Late arrival: {minutes} min |
| `home.review.badge` | En revisión: {reasons} | Under review: {reasons} |
| `home.review.rejected` | Rechazada. Contacta a tu administrador. | Rejected. Contact your administrator. |
| `home.entry` | Entrada | Clock-in |
| `home.exit` | Salida | Clock-out |
| `home.noTime` | --:-- | --:-- |
| `home.notRecorded` | Sin registrar | Not recorded |
| `home.todayEntries` | Marcas de hoy | Today's entries |
| `home.pendingCount.one` | 1 pendiente | 1 pending |
| `home.pendingCount.other` | {n} pendientes | {n} pending |
| `home.pendingSince` | Pendiente desde {date} | Pending since {date} |
The mockup footer note is not a separate key: the footer and the offline banner both use `offline.banner`.

Button:

| Key | ES | EN |
|---|---|---|
| `button.clockIn` | Marcar entrada | Clock in |
| `button.clockOut` | Marcar salida | Clock out |
| `button.done` | Listo por hoy | All done for today |
| `button.saving` | Guardando… | Saving… |

Evidence, result:

| Key | ES | EN |
|---|---|---|
| `evidence.title` | Evidencia | Evidence |
| `evidence.takeSelfie` | Tomar selfie | Take selfie |
| `evidence.selfie.blocked` | Cámara bloqueada · no podrás marcar sin selfie | Camera blocked · you can't clock without a selfie |
| `evidence.location.idle` | Ubicación: se pedirá al marcar | Location: will be requested when you clock |
| `evidence.location.searching` | Buscando ubicación… | Finding location… |
| `evidence.location.ready` | Ubicación lista · ±{accuracy} m | Location ready · ±{accuracy} m |
| `evidence.location.lowAccuracy` | Ubicación imprecisa · ±{accuracy} m · la marca quedará en revisión | Low location accuracy · ±{accuracy} m · the entry will be reviewed |
| `evidence.location.denied` | Ubicación bloqueada · la marca quedará en revisión | Location blocked · the entry will be reviewed |
| `evidence.location.unavailable` | Ubicación no disponible · la marca quedará en revisión | Location unavailable · the entry will be reviewed |
| `evidence.location.skip` | Marcar sin ubicación | Clock without location |
| `evidence.location.help` | Puedes activarla en los permisos del sitio y volver a intentar. | You can turn it on in the site permissions and try again. |
| `result.inside` | Dentro del área · a {meters} m del local | Inside the area · {meters} m from the branch |
| `result.outside` | Fuera del área · {meters} m · en revisión | Outside the area · {meters} m · under review |
| `result.noLocation` | Sin ubicación · en revisión | No location · under review |
| `result.pending` | Pendiente de envío | Pending send |
| `result.sending` | Enviando… | Sending… |

Camera:

| Key | ES | EN |
|---|---|---|
| `camera.title` | Tu selfie | Your selfie |
| `camera.forIn` | Para marcar tu entrada | To clock in |
| `camera.forOut` | Para marcar tu salida | To clock out |
| `camera.explain` | Para marcar necesitamos una selfie y tu ubicación. Tu teléfono te pedirá permiso para usar la cámara y la ubicación. | To clock in or out we need a selfie and your location. Your phone will ask for permission to use the camera and location. |
| `camera.opening` | Abriendo la cámara… | Opening the camera… |
| `camera.ready` | Cámara lista | Camera ready |
| `camera.preview` | Vista previa de la cámara | Camera preview |
| `camera.framing` | Centra tu cara en el encuadre. | Center your face in the frame. |
| `camera.capture` | Tomar foto | Take photo |
| `camera.reviewTitle` | Revisa tu selfie | Check your selfie |
| `camera.reviewAlt` | Vista previa de tu selfie | Preview of your selfie |
| `camera.captured` | Foto tomada. Revisa tu selfie. | Photo taken. Check your selfie. |
| `camera.notMirrored` | La foto se guarda sin efecto espejo. | The photo is saved without the mirror effect. |
| `camera.use` | Usar foto | Use photo |
| `camera.useHintIn` | Al usar la foto se registra tu entrada. | Using the photo records your clock-in. |
| `camera.useHintOut` | Al usar la foto se registra tu salida. | Using the photo records your clock-out. |
| `camera.retake` | Repetir | Retake |
| `camera.processing` | Procesando la foto… | Processing the photo… |
| `camera.denied` | No tenemos permiso para usar la cámara. | We don't have permission to use the camera. |
| `camera.deniedHelp` | Para marcar necesitas permitir la cámara: | To clock in or out you need to allow the camera: |
| `camera.deniedHelpAndroid` | Android (Chrome): toca el candado junto a la dirección, luego Permisos, Cámara y elige Permitir. | Android (Chrome): tap the lock next to the address, then Permissions, Camera, and choose Allow. |
| `camera.deniedHelpIos` | iPhone (Safari): toca aA en la barra de dirección, luego Configuración del sitio web, Cámara y elige Permitir. | iPhone (Safari): tap aA in the address bar, then Website Settings, Camera, and choose Allow. |
| `camera.contactAdmin` | Sin selfie no se puede marcar. Si no puedes habilitar la cámara, contacta a tu administrador. | You can't clock in or out without a selfie. If you can't enable the camera, contact your administrator. |
| `camera.notFound` | No se encontró una cámara en este teléfono. | No camera was found on this phone. |
| `camera.busy` | La cámara está en uso por otra aplicación. Ciérrala e inténtalo de nuevo. | Another app is using the camera. Close it and try again. |
| `camera.unsupported` | Este navegador no puede abrir la cámara. | This browser can't open the camera. |
| `camera.useNativeCamera` | Usar la cámara del teléfono | Use the phone's camera app |
| `camera.cannotClock` | No se puede marcar sin selfie. Contacta a tu administrador. | You can't clock in or out without a selfie. Contact your administrator. |
| `camera.photoTooLarge` | La foto es demasiado grande. Vuelve a tomarla. | The photo is too large. Take it again. |
| `camera.photoUnsupported` | No se pudo preparar la foto. Vuelve a tomarla. | The photo couldn't be prepared. Take it again. |

Offline, announcements, re-auth, logout, update, failure:

| Key | ES | EN |
|---|---|---|
| `offline.banner` | Sin señal, la marca se guarda y se envía al reconectar. | No signal: your entry is saved and will be sent when you reconnect. |
| `offline.restored` | Conexión restablecida. | Connection restored. |
| `offline.restoredSending` | Conexión restablecida. Enviando tus marcas… | Connection restored. Sending your entries… |
| `offline.queueFull` | Tienes demasiadas marcas sin enviar. Conéctate a internet para enviarlas. | You have too many unsent entries. Connect to the internet to send them. |
| `offline.storageFull` | No hay espacio para guardar la marca. Libera espacio en el teléfono o conéctate e inténtalo de nuevo. | There is no space to save the entry. Free up space on the phone, or connect and try again. |
| `announce.clockedIn` | Entrada registrada a las {time} | Clock-in recorded at {time} |
| `announce.clockedOut` | Salida registrada a las {time} | Clock-out recorded at {time} |
| `announce.queuedIn` | Entrada guardada; se enviará al reconectar | Clock-in saved; it will be sent when you reconnect |
| `announce.queuedOut` | Salida guardada; se enviará al reconectar | Clock-out saved; it will be sent when you reconnect |
| `announce.sentWithReview` | En revisión: {reasons} | Under review: {reasons} |
| `announce.sessionRestored` | Sesión iniciada. Enviando tus marcas… | Signed in. Sending your entries… |
| `announce.cameraBlocked` | Sin permiso de cámara. No puedes marcar sin selfie. | No camera permission. You can't clock without a selfie. |
| `reauth.title` | Ingresa tu PIN | Enter your PIN |
| `reauth.body` | Tu sesión venció. Ingresa tu PIN para continuar. Tus marcas guardadas se conservan y se enviarán al ingresar. | Your session expired. Enter your PIN to continue. Your saved entries are kept and will be sent when you sign in. |
| `reauth.enter` | Ingresa tu PIN para enviar | Enter your PIN to send |
| `reauth.later` | Más tarde | Later |
| `reauth.notYou` | ¿No eres tú? | Not you? |
| `logout.button` | Cerrar sesión | Sign out |
| `logout.confirmTitle` | ¿Cerrar sesión? | Sign out? |
| `logout.confirmBody` | Tendrás que ingresar tu PIN de nuevo. | You will need to enter your PIN again. |
| `logout.pendingWarning.one` | Tienes 1 marca sin enviar. Si cierras sesión se borrará y no se registrará. | You have 1 unsent entry. If you sign out it will be deleted and not recorded. |
| `logout.pendingWarning.other` | Tienes {n} marcas sin enviar. Si cierras sesión se borrarán y no se registrarán. | You have {n} unsent entries. If you sign out they will be deleted and not recorded. |
| `logout.confirm` | Cerrar sesión | Sign out |
| `logout.confirmDiscard` | Cerrar sesión y borrar | Sign out and delete |
| `logout.signingOut` | Cerrando sesión… | Signing out… |
| `update.available` | Hay una nueva versión. | A new version is available. |
| `update.announce` | Hay una nueva versión disponible. | A new version is available. |
| `update.reload` | Actualizar | Update |
| `update.updating` | Actualizando… | Updating… |
| `update.dismiss` | Luego | Later |
| `failure.title.CLOCK_IN` | No se pudo registrar tu entrada | We couldn't record your clock-in |
| `failure.title.CLOCK_OUT` | No se pudo registrar tu salida | We couldn't record your clock-out |
| `failure.markTime` | Marca de las {time} | Entry from {time} |
| `failure.contactAdmin` | Contacta a tu administrador. | Contact your administrator. |
| `failure.acknowledge` | Entendido | Got it |

Review reasons, statuses, durations, common:

| Key | ES | EN |
|---|---|---|
| `reviewReason.NO_GPS` | Sin ubicación | No location |
| `reviewReason.OUTSIDE_GEOFENCE` | Fuera del área | Outside the area |
| `reviewReason.LOW_GPS_ACCURACY` | Ubicación imprecisa | Low location accuracy |
| `reviewReason.SYNCED_LATE` | Enviada con retraso | Sent late |
| `reviewReason.DEVICE_CLOCK_AHEAD` | Hora del teléfono adelantada | Phone clock ahead |
| `reviewReason.SHIFT_TOO_LONG` | Turno demasiado largo | Shift too long |
| `status.VALID` | Válida | Valid |
| `status.PENDING_REVIEW` | En revisión | Under review |
| `status.REJECTED` | Rechazada | Rejected |
| `duration.hm` | {h} h {mm} min | {h} h {mm} min |
| `duration.m` | {m} min | {m} min |
| `common.close` | Cerrar | Close |
| `common.back` | Volver | Back |
| `common.cancel` | Cancelar | Cancel |
| `common.continue` | Continuar | Continue |
| `common.retry` | Reintentar | Try again |

Keys that the plan lists and this table covers: `login.*`, `consent.*`, `home.*`, `button.*`, `evidence.*`, `result.*`, `camera.*`, `offline.*`, `announce.*`, `reauth.*`, `logout.*`, `update.*`, `failure.*`, `reviewReason.*`, `status.*`, `error.*` (5.3), `duration.*`, `common.*`. Keys added beyond the plan are listed in section 8, item 10.

### 5.3 Error keys

| Key | ES | EN |
|---|---|---|
| `error.INVALID_CREDENTIALS` | Código o PIN incorrectos. Si fallaste varias veces, espera 15 minutos o contacta a tu administrador. | Incorrect code or PIN. If it failed several times, wait 15 minutes or contact your administrator. |
| `error.TOO_MANY_ATTEMPTS` | Demasiados intentos. Inténtalo de nuevo en unos minutos. | Too many attempts. Try again in a few minutes. |
| `error.tooManyAttemptsMinutes` | Demasiados intentos. Inténtalo de nuevo en {minutes} min. | Too many attempts. Try again in {minutes} min. |
| `error.tooManyAttemptsSeconds` | Demasiados intentos. Inténtalo de nuevo en {seconds} s. | Too many attempts. Try again in {seconds} s. |
| `error.INVALID_REQUEST` | Hay datos incorrectos. Revisa e inténtalo de nuevo. | Some data is incorrect. Check it and try again. |
| `error.METHOD_NOT_ALLOWED` | Ocurrió un error inesperado. Inténtalo de nuevo; si continúa, contacta a tu administrador. | Something went wrong. Try again; if it continues, contact your administrator. |
| `error.NOT_ACCEPTABLE` | (same text as `error.METHOD_NOT_ALLOWED`) | (same) |
| `error.UNSUPPORTED_MEDIA_TYPE` | (same text as `error.METHOD_NOT_ALLOWED`) | (same) |
| `error.NOT_FOUND` | No se encontró lo que buscabas. | We couldn't find what you were looking for. |
| `error.INTERNAL_ERROR` | (same text as `error.METHOD_NOT_ALLOWED`) | (same) |
| `error.INVALID_PIN` | El PIN debe tener 8 dígitos y no puede ser una secuencia, un patrón repetido ni una fecha. | The PIN must be 8 digits and cannot be a sequence, a repeated pattern or a date. |
| `error.CONSENT_REQUIRED` | Debes aceptar el tratamiento de tus datos para marcar. | You must accept the processing of your data to clock in or out. |
| `error.NO_BRANCH_ASSIGNED` | No tienes sucursal asignada. Contacta a tu administrador. | You have no branch assigned. Contact your administrator. |
| `error.ALREADY_CLOCKED_IN` | Ya tenías una entrada registrada, así que esta marca no se guardó. | You already had a clock-in recorded, so this entry was not saved. |
| `error.NOT_CLOCKED_IN` | No tienes una entrada abierta, así que no se pudo registrar la salida. | You have no open clock-in, so the clock-out couldn't be recorded. |
| `error.SHIFT_ALREADY_COMPLETED` | Tu jornada de hoy ya estaba completa, así que esta marca no se guardó. | Your day was already complete, so this entry was not saved. |
| `error.CLIENT_UUID_CONFLICT` | Esta marca no se pudo registrar por un conflicto interno. Vuelve a marcar. | This entry couldn't be recorded because of an internal conflict. Clock again. |
| `error.PHOTO_REQUIRED` | Falta la selfie. Vuelve a marcar con una foto. | The selfie is missing. Clock again with a photo. |
| `error.INVALID_PHOTO` | La foto no es válida. Vuelve a tomar la selfie. | The photo isn't valid. Take the selfie again. |
| `error.PAYLOAD_TOO_LARGE` | La foto es demasiado grande. Vuelve a tomar la selfie. | The photo is too large. Take the selfie again. |
| `error.SERVER_BUSY` | El servidor está ocupado. Lo intentaremos de nuevo automáticamente. | The server is busy. We'll try again automatically. |
| `error.network` | No se pudo conectar con el servidor. Revisa tu conexión e inténtalo de nuevo. | Couldn't connect to the server. Check your connection and try again. |
| `error.timeout` | El servidor tardó demasiado en responder. Inténtalo de nuevo. | The server took too long to respond. Try again. |
| `error.sessionExpired` | Tu sesión venció. Ingresa tu PIN. | Your session expired. Enter your PIN. |
| `error.forbidden` | No tienes permiso para hacer esto. | You don't have permission to do this. |
| `error.unknown` | Ocurrió un error inesperado. Inténtalo de nuevo. | Something went wrong. Try again. |

For the three "(same text)" keys the coder writes out the full string in each key (the table abbreviates it). Add them to `SAME_IN_BOTH` justification only if ES and EN texts are equal, which they are not.

### 5.4 Error code to behavior and display

The PWA shows its own text per code (never `detail`).

| Code | HTTP | Context | Behavior | Shown as |
|---|---|---|---|---|
| `INVALID_CREDENTIALS` | 401 | login, re-auth | clear PIN, keep codes | inline `role="alert"` box (login, dialog) |
| `TOO_MANY_ATTEMPTS` | 429 | login, re-auth | disable controls until `Retry-After` | alert box + visual countdown, `error.tooManyAttemptsMinutes/Seconds` |
| `INVALID_REQUEST` | 400 | login, queue | login: inline; queue: final failure | alert box or FailureCard |
| `INVALID_PIN` | 400 | not used by the PWA flows in Stage 3 | mapped for completeness | alert box |
| `CONSENT_REQUIRED` | 403 | queue, any | keep item, route to `/consent` | consent page (no card) |
| `NO_BRANCH_ASSIGNED` | 403 | queue | final failure | FailureCard |
| `ALREADY_CLOCKED_IN` | 409 | queue | final failure | FailureCard |
| `NOT_CLOCKED_IN` | 409 | queue | final failure | FailureCard |
| `SHIFT_ALREADY_COMPLETED` | 409 | queue | final failure | FailureCard |
| `CLIENT_UUID_CONFLICT` | 409 | queue | final failure | FailureCard |
| `PHOTO_REQUIRED` | 400 | queue | final failure | FailureCard |
| `INVALID_PHOTO` | 400 | queue | final failure | FailureCard |
| `PAYLOAD_TOO_LARGE` | 413 | queue | final failure | FailureCard |
| `SERVER_BUSY` | 503 | queue | retry after `Retry-After` | no card; the entry stays "Pendiente de envío" |
| `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_FOUND`, `INTERNAL_ERROR` | 405/406/415/404/500 | any | login/consent: inline alert; queue: 500 is a backoff retry, the others are final failures | alert box or FailureCard |
| client: network, timeout | (none) | any | login/consent/re-auth: inline alert; queue: backoff, offline banner | offline banner for the queue |
| client: sessionExpired (401, no code) | 401 | queue, today | open `ReauthDialog` | dialog with `reauth.*` (not `error.sessionExpired` as an alert) |
| client: forbidden (403, no code) | 403 | any | inline alert; queue: final failure | alert box or FailureCard |
| client: unknown | any | any | as above | `error.unknown` |

Every FailureCard also prints `failure.contactAdmin`.

### 5.5 Date, time and number formats

All formatting uses `Intl` with `timeZone = today.timeZone` (fallback: the identity's time zone), never the phone zone. Locale: `es-CR` for Spanish, `en-US` for English. `hourCycle: 'h23'`.

| Item | Options | ES result | EN result |
|---|---|---|---|
| Day header | `{weekday:'long', day:'numeric', month:'long'}`, first letter upper-cased with `toLocaleUpperCase(locale)` | Miércoles, 7 de octubre | Wednesday, October 7 |
| Clock and entry times | `{hour:'2-digit', minute:'2-digit', hourCycle:'h23'}` | 07:58 | 07:58 |
| Duration | `duration.hm` / `duration.m`, minutes 2-digit padded in `hm` | 9 h 05 min · 45 min | 9 h 05 min · 45 min |
| Short date (last shift, pending since, missing clock-out) | `{weekday:'short', day:'numeric', month:'short'}`; for `forgottenWorkDate` (a `LocalDate`) format at noon UTC with `timeZone:'UTC'` so the day never shifts | mar 6 oct | Tue, Oct 6 |
| Date with time (pending since) | short date + `, ` + time | 6 oct, 07:58 | Oct 6, 07:58 |
| Distance, accuracy | integer, `useGrouping:false` | 1112 m · ±12 m | 1112 m · ±12 m |
| Late minutes | integer | 8 min | 8 min |
| Greeting name | first whitespace token of `name` | María | María |

- Test fixture: `2026-10-08T05:30:00Z` in `America/Costa_Rica` must read "7 de octubre".
- Missing-clock-out text uses a longer date: `{weekday:'long', day:'numeric', month:'long'}` (lower case inside a sentence: "martes 6 de octubre"; do not capitalise).
- The server clock never shows seconds.

---

## 6. Accessibility notes

Target: WCAG 2.2 AA (2.4.11 focus not obscured, 2.5.8 target size, 3.3.7 redundant entry, 3.3.8 accessible authentication).

### 6.1 Heading order and landmarks

| Screen | Order |
|---|---|
| Login | `header` (banner) > `main` > h1 "Iniciar sesión". Fields are labelled, the PIN group is `role="group"` with a visible label. |
| Consent | h1 title > seven h2 sections > actions. When declined: h2 "No se puede continuar..." |
| Main | h1 "Hola, María" > (date is a `<p>` before it) > h2 status line > h2 "Evidencia" > h2 "Marcas de hoy" (visually hidden). FailureCard titles are h3 inside a `section` with `aria-labelledby`. |
| Dialogs | the dialog title is the first heading inside (h2). |

Each page has one `<main>`. `<header>` is the page banner. No skip link (two stops before the content); revisit if the header grows.

### 6.2 Focus order and focus management

| Screen | Tab order |
|---|---|
| Login | ES, EN, company code, employee code, keypad (one stop, roving), submit. Error box is programmatically focusable only. |
| Consent | ES, EN, Cerrar sesión, (text is not focusable), Acepto, No acepto. Declined: Cerrar sesión (panel), Volver. |
| Main | ES, EN, Cerrar sesión, FailureCard "Entendido" (if any), PendingCounter button (if reauth), UpdatePrompt buttons (if shown), primary button, admin link (J). |
| SelfieCapture | close (x), step-specific buttons in visual order. |
| ReauthDialog | keypad, Ingresar, Más tarde, ¿No eres tú? |
| Logout confirm | Cancelar, confirm. |

Focus on transitions:

| Transition | Focus goes to |
|---|---|
| Route change (login, consent, main) | the page h1 (`tabindex=-1`) |
| Login fails | the error box |
| Field validation fails | the first invalid field |
| 429 countdown ends | stays where it was |
| Capture dialog opens | per step (3.4) |
| Capture dialog closes (confirmed or cancelled) | the status h2 after a confirmed capture; the primary button after cancel |
| Final failure acknowledged | status h2, or the next card's button |
| Re-auth dialog closes | previously focused element, else the status h2 |
| Logout confirm closes by cancel | the header "Cerrar sesión" button |
| Consent accepted | main h1 |
| Queue-full / storage error | stays on the primary button; the alert box announces |

Native `<dialog>.showModal()` provides the trap, inert background and Esc. After `close()` the browser restores focus to the opener, but the coder must set it explicitly when the opener has been re-rendered.

### 6.3 Screen-reader announcements

All through the single `Announcer` (polite `role="status"`) unless stated.

| Event | Spoken text |
|---|---|
| Clock-in sent (201/200) | "Entrada registrada a las 07:58. Dentro del área, a 35 m del local." |
| Clock-in sent, outside | "Entrada registrada a las 07:58. Fuera del área, 1112 m, en revisión. En revisión: Fuera del área." |
| Clock-out sent | "Salida registrada a las 17:03. ..." (same pattern) |
| Clock-in/out queued | "Entrada guardada; se enviará al reconectar" |
| Queued item later sent | the sent text above, preceded by "Conexión restablecida. Enviando tus marcas…" once |
| Final failure | the FailureCard `role="alert"` reads title + message + "Contacta a tu administrador." (once). The Announcer is not used, to avoid a duplicate. |
| Offline | "Sin señal, la marca se guarda y se envía al reconectar." (on change only) |
| Online again | "Conexión restablecida." or the sending variant |
| Camera denied | dialog `role="alert"` with the denied message. |
| Session expired | dialog opens; its title and body are read by the dialog semantics. |
| Update available | "Hay una nueva versión disponible." |
| PIN progress | "3 de 8 dígitos" (debounced, never the digits) |
| 429 over | "Ya puedes intentarlo de nuevo." |

- Announced strings pass through `toSpoken()` (" · " becomes ", ").
- After a successful clock the status h2 is focused. Focus change and the Announcer message must not read the same thing twice: focus lands on the h2 and reads the new status ("En turno desde 07:58"), the Announcer reads the registration result. Keep those two texts different.
- Language change mid-flow: already announced messages stay in the old language (accepted).

### 6.4 Color is never the only signal

| Meaning | Signal 1 | Signal 2 | Signal 3 |
|---|---|---|---|
| Inside the area | green box | check-circle icon | words "Dentro del área" |
| Outside the area | amber box | alert-triangle icon | words "Fuera del área ... en revisión" |
| No location | amber box | map-pin-off icon | words "Sin ubicación ... en revisión" |
| Pending review | amber box | alert-triangle icon | words "En revisión: ..." |
| Rejected | red box | x-circle icon | words "Rechazada" |
| Pending send | none (muted) | hourglass icon | words "Pendiente de envío" |
| Error | red box | x-circle icon | words and `role="alert"` |
| Filled vs empty PIN dot | solid fill | ring vs solid shape | progress text |
| Language selected | fill | bold + underline | `aria-pressed` |
| Disabled button | grey | check/lock icon, no pressed effect | `aria-disabled` + reason text |
| Offline | amber box | wifi-off icon | full sentence |

In `forced-colors` mode all boxes keep a 1 px border and icons use `currentColor`.

### 6.5 PIN accessibility

- The PIN dots are `aria-hidden`. The only spoken feedback is the progress text "n de 8 dígitos". The digit values are never rendered into the DOM (no input value, no hidden text, no `title`, no `aria-label` change), never announced, never logged.
- Keypad keys have names (the digit) so a screen-reader user can find them. This is inherent: a TalkBack or VoiceOver user hears the digit of the key they touch. That is the same as any on-screen keypad. Recommend the coder offers, in a later stage, an alternative `<input type="password" inputmode="numeric" autocomplete="off">` mode toggle for users who prefer the system keyboard (not in Stage 3 scope; recorded in section 8, item 8).
- WCAG 3.3.8 (accessible authentication): no cognitive function test; paste is not blocked in code fields; the PIN cannot be pasted (digits only through keys), which is the accepted pattern for a PIN keypad. 3.3.7 (redundant entry): the re-auth dialog shows the stored codes so the user never retypes them.
- `autocomplete="off"` on the code fields so shared-phone browsers do not store employee codes in form history.

### 6.6 Error identification

- Field errors: `aria-invalid="true"`, `aria-describedby` to the message id under the field, message text names the problem and the fix, icon plus text.
- Form-level errors (401, 429, network): an error box above the submit button, `role="alert"`, `tabindex="-1"`, focus moved to it. One alert at a time (the previous is removed first).
- Never rely on the server `detail`; every message has a fix or next step.
- Login errors never state which field was wrong.

### 6.7 Other

- Language: `<html lang>` is already managed by `i18n/language.ts`. Mark the language switcher buttons with `lang` (already done).
- Orientation: no lock; works in landscape (3.4).
- Text resize 200% and reflow at 320 px are layout requirements (section 2).
- Reduced motion: 1.7 and section 7.
- Timing: no time limits on user actions. The 20 s GPS wait and the 5 s "Marcar sin ubicación" delay do not expire the dialog. The 429 countdown is informational.
- Touch gestures: single tap everywhere; no drag, no long press, no path-based gestures.
- Screen wake: the camera preview does not request a wake lock.
- Known a11y issues found while designing (also section 8):
  1. The plan's `aria-label` on `<time>` is unreliable.
  2. A PIN keypad inherently reads the digits of the keys to a screen-reader user.
  3. `·` as a separator can be read aloud; use `toSpoken()`.
  4. The preview is mirrored but the stored photo is not; explained by a caption.
  5. Chrome/Safari permission prompts are outside the app and cannot be styled or announced by it.
  6. An iOS standalone PWA prompts for camera access more often than Safari; the explainer helps but cannot prevent it.

---

## 7. Motion and feedback

### 7.1 Pressed state

- Buttons: on `:active` the background moves to the pressed color and the element scales to 0.98 over 80 ms (`transition: transform 80ms ease-out, background-color 80ms`). Keypad keys: `--color-key-pressed` and scale .97, 60 ms.
- Under `prefers-reduced-motion: reduce` the scale is removed; only the color changes (still visible: 12.96:1 for key text on the pressed key, white on `#0B4A43` 10.12:1).
- Apply `touch-action: manipulation` and `-webkit-tap-highlight-color: transparent` on buttons to remove the 300 ms delay and the grey flash (the pressed style replaces it).

### 7.2 Progress while sending

- Clocking is queue first, so the primary button is only busy during capture and saving (`button.saving`, spinner). This is short (under a second).
- Sending: the `EntryResult` line switches from "Pendiente de envío" to "Enviando…" with a spinner icon while the request runs (up to 45 s). On success it is replaced by the server result with a 150 ms opacity transition (none under reduced motion). On failure back to "Pendiente de envío".
- Spinner: a 20 px SVG arc, `animation: spin 0.9s linear infinite`. Under reduced motion no rotation: the same icon is shown static, and the adjacent text carries the meaning ("Enviando…", "Guardando…", "Ingresando…").
- Login and consent submit buttons show the same spinner plus changed label and `aria-busy`.
- No full-screen loaders and no skeleton shimmer (battery on low-end phones, and reduced-motion handling).

### 7.3 Other transitions

- Dialogs fade/slide in over 150 ms (`opacity` and `transform: translateY(8px)`). Reduced motion: no transition, appear instantly.
- Shutter feedback: a 120 ms white flash over the preview; omitted under reduced motion.
- No shake on error, no bouncing, no auto-advancing carousels.

### 7.4 Haptics

Use `navigator.vibrate` only if the function exists (Android Chrome; not iOS) and the page was activated by a user gesture. Skip it when `prefers-reduced-motion: reduce` is set (conservative: users who ask for less motion usually want less sensory feedback). Wrap in a `haptics.ts` helper (`tap`, `success`, `error`) that no-ops when unsupported.

| Event | Pattern |
|---|---|
| Shutter pressed | 15 ms |
| Clock registered or queued (confirmed) | 25 ms |
| Failure card / login failure | `[40, 60, 40]` |
| Keypad keys | none (too chatty, and it would leak typing rhythm) |

Haptics are never the only feedback; every event also has text and an icon.

---

## 8. Decisions that differ from or extend the plan (coder must read)

1. **`aria-label` on `<time>` (plan, Accessibility "Clock").** Naming a `<time>` element with `aria-label` is not reliably announced (it has no widely supported naming role). Use the visible label "Hora del servidor" as a sibling `<p>` before the `<time>`; no `aria-label`. The `<time dateTime>` stays.
2. **Evidence chips are informational, not buttons.** The mockup shows "Tomar selfie" as a chip. A second tappable path to the capture would duplicate the primary button. The text is kept as in the mockup (a checklist item).
3. **Logout confirm is always shown, and the logout is a plain header button, not a menu.** The plan says to confirm only when items are pending and calls the header control a "menu". A single button avoids a menu widget, and a one-tap confirm protects shared phones from accidental sign-out (it costs a PIN). If the product owner prefers no confirm without pending items, drop the no-pending variant of 3.9 only.
4. **GPS warm-up and the pre-send chip.** The plan starts `acquireLocation` when the user taps the button, but the chip must show "Ubicación lista · ±12 m" on the main screen before the tap. Design: on main-screen mount and on `visibilitychange`, if the Permissions API reports geolocation `granted` and the user can clock, run one `getCurrentPosition` (not a watch) to show readiness, and stop it. If permission is `prompt` or unknown, show `evidence.location.idle` and do not prompt out of context. The watch of the plan still starts when the capture dialog starts (after the explainer if there is one). If permission is `denied`, the chip shows the denied state at once. This costs one fix per visit and no continuous watch.
5. **Capture explainer step.** The plan lists a "permission pre-prompt explainer" in the hand-off; its place is step 0 of `SelfieCapture`, shown only when a permission is undecided. Starting the GPS watch after the explainer, not at dialog open, keeps the two browser prompts from stacking before the user reads why.
6. **Disabled controls use `aria-disabled`**, not the `disabled` attribute (primary button in DONE/blocked/loading, submit before 8 digits, keypad keys during a request), so they stay focusable and describable. Tests that look for `disabled` must check `aria-disabled="true"`.
7. **ReauthDialog can be dismissed ("Más tarde") and the PendingCounter becomes the way back in.** The plan did not define dismissal. Esc must not be a trap, and a user who is not the owner of the phone needs a way out via "¿No eres tú?".
8. **Out-of-scope recommendation:** an optional system-keyboard PIN entry for screen-reader users (see 6.5).
9. **Layout width:** the existing `main { max-width: 40rem }` stays for `/admin` and NotFound. Employee pages add `.screen { max-width: 30rem }` (plan: up to 480 px). The coder applies the class on `<main>` of Login, Consent and Main.
10. **Keys added beyond the plan's `resources.ts` list:**
    - login: `login.offline`, `login.retryReady`, `login.keypad.group`, `login.error.companyRequired`, `login.error.employeeRequired`, `login.error.codeFormat`
    - consent: `consent.responsibleTitle`, `consent.responsibleBody`, `consent.version`, `consent.accepting`, `consent.declinedTitle`
    - home: `home.loading`, `home.status.workedSoFar`, `home.status.needsConnection`, `home.review.badge`, `home.review.rejected`, `home.notRecorded`, `home.todayEntries`, and the plural split `home.pendingCount.one/.other` (plan had one key `home.pendingCount`)
    - button/evidence/result: `button.saving`, `evidence.selfie.blocked`, `evidence.location.idle`, `evidence.location.help`, `result.sending`
    - camera: `camera.forIn`, `camera.forOut`, `camera.explain`, `camera.opening`, `camera.ready`, `camera.framing`, `camera.reviewTitle`, `camera.reviewAlt`, `camera.captured`, `camera.notMirrored`, `camera.useHintIn`, `camera.useHintOut`, `camera.deniedHelpAndroid`, `camera.deniedHelpIos`, `camera.contactAdmin`, `camera.unsupported`
    - offline/announce: `offline.restored`, `offline.restoredSending`, `announce.sessionRestored`, `announce.cameraBlocked`
    - reauth/logout/update/failure: `reauth.enter`, `reauth.later`, `logout.pendingWarning.one/.other` (instead of one `pendingWarning`), `logout.confirmBody`, `logout.confirmDiscard`, `logout.signingOut`, `update.announce`, `update.updating`, `failure.title.CLOCK_IN/.CLOCK_OUT`, `failure.markTime`
    - errors: `error.tooManyAttemptsSeconds`
    - common: `common.cancel`, `common.continue`, `common.retry`
    - Existing key reused: `employeeHome.toAdmin` for the admin link (the key still lives under its old group; rename only if the `noHardcodedText`/resources tests allow).
11. **`PendingCounter` and `Icon`** are two small components not in the plan's list. Inventory the same way; neither adds a dependency.
12. **Date comma:** Intl gives "Miércoles, 7 de octubre" (comma) where the mockup shows no comma. The plan accepted it; unchanged.
13. **Header on the day card:** the date and the greeting sit in a teal band below `AppHeader`, not inside it, because the existing `LanguageSwitcher` is styled for a light background (teal border on `#F2F2F0`) and would be unreadable on teal. `LanguageSwitcher` is not modified.
14. **Mockup not verified visually.** The PDF could not be rendered. The main-screen order (date and greeting, clock, status, evidence, button, times, footer note) follows the brief. Compare once with the PDF and adjust spacing only.

Files created: `docs/design/stage-3-employee-pwa.md` (this file). Files changed: none. No component or CSS was implemented (tokens in 1.8 are ready to paste).
