import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, test } from 'vitest'
import config from '../vite.config'

const dist = resolve(__dirname, '../dist')
const manifestPath = resolve(dist, 'manifest.webmanifest')

test('dev and preview proxy /api to the backend', () => {
  expect(config.server?.proxy).toHaveProperty('/api')
  expect(config.preview?.proxy).toHaveProperty('/api')
})

// These checks inspect the output of `npm run build`; they are skipped when dist/ does not exist.
describe.skipIf(!existsSync(manifestPath))('built PWA output', () => {
  const manifest = () => JSON.parse(readFileSync(manifestPath, 'utf8'))

  test('manifest matches the plan', () => {
    const m = manifest()
    expect(m.name).toBe('Control de Asistencia')
    expect(m.short_name).toBe('Asistencia')
    expect(m.lang).toBe('es-CR')
    expect(m.theme_color).toBe('#0F5F56')
    expect(m.background_color).toBe('#F2F2F0')
    expect(m.display).toBe('standalone')
    expect(m.start_url).toBe('/')
  })

  test('manifest icons cover 192, 512 any and 512 maskable, and exist in dist', () => {
    const icons = manifest().icons as { src: string; sizes: string; purpose?: string }[]
    expect(icons.some((i) => i.sizes === '192x192')).toBe(true)
    expect(icons.some((i) => i.sizes === '512x512' && i.purpose === 'any')).toBe(true)
    expect(icons.some((i) => i.sizes === '512x512' && i.purpose === 'maskable')).toBe(true)
    for (const i of icons) expect(existsSync(resolve(dist, i.src))).toBe(true)
  })

  test('service worker is emitted and denies /api navigation fallback', () => {
    const sw = readFileSync(resolve(dist, 'sw.js'), 'utf8')
    expect(sw).toContain('NavigationRoute')
    expect(sw).toContain(String.raw`denylist:[/^\/api\//]`)
  })

  test('index.html links the manifest', () => {
    expect(readFileSync(resolve(dist, 'index.html'), 'utf8')).toContain('manifest.webmanifest')
  })
})
