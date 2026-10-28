import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, test } from 'vitest'
import { PROD_CSP, SECURITY_HEADERS } from '../securityHeaders'
import configFactory, { pwaOptions } from '../vite.config'

// The config is a function of the mode; call it for the plain dev server.
const config = (configFactory as unknown as (env: { mode: string; command: string }) => Record<string, any>)({
  mode: 'development',
  command: 'serve',
})

const dist = resolve(__dirname, '../dist')
const manifestPath = resolve(dist, 'manifest.webmanifest')

test('dev and preview proxy /api to the backend', () => {
  expect(config.server?.proxy).toHaveProperty('/api')
  expect(config.preview?.proxy).toHaveProperty('/api')
})

test('preview enforces the production headers and dev has no CSP', () => {
  expect(config.preview?.headers['Content-Security-Policy']).toBe(PROD_CSP)
  expect(config.preview?.headers).toEqual(SECURITY_HEADERS)
  expect(config.server?.headers).not.toHaveProperty('Content-Security-Policy')
  expect(config.server?.headers['Permissions-Policy']).toContain('camera=(self)')
})

test('phone mode adds HTTPS (basic-ssl) and plain dev does not', () => {
  const names = (c: Record<string, any>) => (c.plugins.flat() as { name?: string }[]).map((p) => p?.name)
  const phone = (configFactory as unknown as (env: { mode: string; command: string }) => Record<string, any>)({
    mode: 'phone',
    command: 'serve',
  })
  expect(names(phone).some((n) => n?.includes('basic-ssl'))).toBe(true)
  expect(names(config).some((n) => n?.includes('basic-ssl'))).toBe(false)
})

test('the service worker is updated on request (prompt) and never injected automatically', () => {
  expect(pwaOptions.registerType).toBe('prompt')
  expect(pwaOptions.injectRegister).toBe(false)
  expect(pwaOptions.workbox).not.toHaveProperty('runtimeCaching')
  expect(pwaOptions.workbox).toMatchObject({ navigateFallback: 'index.html', cleanupOutdatedCaches: true })
})

test('the workbox runtime is inlined so sw.js needs no importScripts under Trusted Types', () => {
  expect(pwaOptions.workbox).toMatchObject({ inlineWorkboxRuntime: true })
})

test('workbox-window ships in the app, so it is a production dependency (covered by npm audit --omit=dev)', () => {
  const pkg = JSON.parse(readFileSync(resolve(process.cwd(), 'package.json'), 'utf8'))
  expect(pkg.dependencies).toHaveProperty('workbox-window')
  expect(pkg.devDependencies).not.toHaveProperty('workbox-window')
})

test('data URIs are never inlined (the CSP has no data:)', () => {
  expect(config.build?.assetsInlineLimit).toBe(0)
})

// These checks inspect the output of `npm run build`; they are skipped when dist/ does not exist.
describe.skipIf(!existsSync(manifestPath))('built PWA output', () => {
  const manifest = () => JSON.parse(readFileSync(manifestPath, 'utf8'))
  const html = () => readFileSync(resolve(dist, 'index.html'), 'utf8')

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
    // The workbox runtime is inlined and minified (no class names): the fallback is recognised by its behaviour.
    expect(sw).toContain('"navigate"!==')
    expect(sw).toContain('createHandlerBoundToURL(')
    expect(sw).toContain(String.raw`denylist:[/^\/api\//]`)
  })

  test('service worker does not call importScripts (Trusted Types script URL sink)', () => {
    const sw = readFileSync(resolve(dist, 'sw.js'), 'utf8')
    expect(sw).not.toContain('importScripts')
  })

  test('service worker has no runtime route for /api or photos', () => {
    const sw = readFileSync(resolve(dist, 'sw.js'), 'utf8')
    // The only route is the navigation fallback (with its /api denylist); the precache is registered by workbox itself.
    expect(sw.match(/"navigate"!==/g)).toHaveLength(1)
    expect(sw.match(/\.createHandlerBoundToURL\(/g)).toHaveLength(1) // the single call site: the navigation fallback
    expect(sw).not.toMatch(/\/photo/)
    expect(sw).not.toContain('NetworkFirst')
    expect(sw).not.toContain('StaleWhileRevalidate')
    expect(sw).not.toContain('CacheFirst')
  })

  test('index.html links the manifest', () => {
    expect(html()).toContain('manifest.webmanifest')
  })

  test('index.html has no inline script, no inline style, no style attributes and no registerSW injection', () => {
    const page = html()
    const scripts = [...page.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
    for (const [, attributes, body] of scripts) {
      expect(attributes, 'every script has a src').toMatch(/\bsrc=/)
      expect(body.trim()).toBe('')
    }
    expect(page).not.toMatch(/<style\b/)
    expect(page).not.toMatch(/\sstyle=/)
    expect(page).not.toContain('registerSW.js')
  })
})
