import { expect, test } from 'vitest'

// Every component, page, module and entry file, as raw source. Tests, test helpers and the
// resource tables themselves are excluded.
const files = import.meta.glob(
  ['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}', '!../test/**', '!../i18n/resources.ts', '!../vite-env.d.ts'],
  {
    query: '?raw',
    import: 'default',
    eager: true,
  },
) as Record<string, string>

const SPANISH_CHARS = /[áéíóúñ¿¡ÁÉÍÓÚÑ]/
const SPANISH_WORDS =
  /\b(Servidor|Panel|Volver|Inicio|Idioma|Página|Verificando|empleado|administrador|Asistencia|Marcar|entrada|salida|turno|Jornada|señal|Hola|Acepto|Ingresar|Cerrar|sesión|ubicación|Evidencia)\b/i

test('the glob found the UI sources', () => {
  const names = Object.keys(files)
  expect(names.some((n) => n.endsWith('/App.tsx'))).toBe(true)
  expect(names.some((n) => n.endsWith('/pages/NotFound.tsx'))).toBe(true)
  expect(names.some((n) => n.endsWith('/components/LanguageSwitcher.tsx'))).toBe(true)
  expect(names.some((n) => n.endsWith('/pages/LoginPage.tsx'))).toBe(true)
  expect(names.some((n) => n.endsWith('/components/PinKeypad.tsx'))).toBe(true)
  expect(names.some((n) => n.endsWith('/sync/flush.ts'))).toBe(true)
})

test.each(Object.entries(files))('%s has no hardcoded Spanish text', (_name, source) => {
  expect(source).not.toMatch(SPANISH_CHARS)
  expect(source).not.toMatch(SPANISH_WORDS)
})

// The JSX text pattern would misread generics and comparisons in plain .ts modules.
const jsxFiles = Object.entries(files).filter(([name]) => name.endsWith('.tsx'))

test.each(jsxFiles)('%s has no literal text inside JSX or text attributes', (_name, source) => {
  // Text between a closing ">" and the next "<" that is not an expression or whitespace.
  const textNodes = [...source.matchAll(/>([^<>{}=;()\n]*[A-Za-z][^<>{}=;()\n]*)</g)]
    .map((m) => m[1].trim())
    .filter((text) => text.length > 0)
  expect(textNodes).toEqual([])
  expect(source).not.toMatch(/\b(aria-label|title|alt|placeholder)="[^"]*[A-Za-z]/)
})
