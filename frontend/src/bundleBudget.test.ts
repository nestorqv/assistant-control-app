import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { resolve } from 'node:path'
import { gzipSync } from 'node:zlib'
import { describe, expect, test } from 'vitest'

const dist = resolve(__dirname, '../dist')
const assets = resolve(dist, 'assets')

const KB = 1024
// Budget for low-end Android on slow data (plan, "Bundle and performance budget").
const BUDGET = {
  jsGzip: 130 * KB,
  entryGzip: 110 * KB,
  cssGzip: 10 * KB,
  adminJsGzip: 80 * KB,
  adminCssGzip: 10 * KB,
  precacheRaw: 600 * KB,
}

const gz = (file: string) => gzipSync(readFileSync(file), { level: 9 }).length
const files = (ext: string) => (existsSync(assets) ? readdirSync(assets).filter((f) => f.endsWith(ext)) : [])
// The admin panel is one lazy chunk (admin-*.js, admin-*.css) that phones never download.
const isAdmin = (f: string) => f.startsWith('admin-')
const employeeFiles = (ext: string) => files(ext).filter((f) => !isAdmin(f))
const adminFiles = (ext: string) => files(ext).filter(isAdmin)

// Reads the output of `npm run build`. Locally it is skipped when dist/ does not exist; in CI (process.env.CI) a
// missing dist/ is a failure, so the budget can never pass by not running.
const built = existsSync(resolve(dist, 'sw.js'))
test.runIf(!built && !!process.env.CI)('CI: dist/ exists (run `npm run build` before the tests)', () => {
  expect(built).toBe(true)
})

describe.skipIf(!built)('bundle budget', () => {
  test('the employee JavaScript stays within 130 KB gzip', () => {
    const total = employeeFiles('.js').reduce((sum, f) => sum + gz(resolve(assets, f)), 0)
    expect(total).toBeLessThanOrEqual(BUDGET.jsGzip)
  })

  test('the entry chunk stays within 110 KB gzip', () => {
    const html = readFileSync(resolve(dist, 'index.html'), 'utf8')
    const entry = /src="\/assets\/(index-[^"]+\.js)"/.exec(html)?.[1]
    expect(entry).toBeDefined()
    expect(gz(resolve(assets, entry!))).toBeLessThanOrEqual(BUDGET.entryGzip)
  })

  test('the admin screens and the capture code are separate lazy chunks', () => {
    const names = files('.js')
    expect(names.some((n) => n.startsWith('admin-'))).toBe(true)
    expect(names.some((n) => n.startsWith('SelfieCapture-'))).toBe(true)
  })

  test('the employee CSS stays within 10 KB gzip', () => {
    const total = employeeFiles('.css').reduce((sum, f) => sum + gz(resolve(assets, f)), 0)
    expect(total).toBeLessThanOrEqual(BUDGET.cssGzip)
  })

  test('the admin chunk stays within its own budgets (80 KB JS, 10 KB CSS gzip)', () => {
    const js = adminFiles('.js').reduce((sum, f) => sum + gz(resolve(assets, f)), 0)
    const css = adminFiles('.css').reduce((sum, f) => sum + gz(resolve(assets, f)), 0)
    expect(js).toBeGreaterThan(0)
    expect(js).toBeLessThanOrEqual(BUDGET.adminJsGzip)
    expect(css).toBeLessThanOrEqual(BUDGET.adminCssGzip)
  })

  test('the precache stays within 600 KB raw and holds no admin file', () => {
    const sw = readFileSync(resolve(dist, 'sw.js'), 'utf8')
    const urls = [...sw.matchAll(/url:"([^"]+)"/g)].map((m) => m[1]).filter((u) => !u.startsWith('http'))
    expect(urls.length).toBeGreaterThan(5)
    expect(urls.filter((u) => /admin-/.test(u))).toEqual([])
    const total = urls.reduce((sum, u) => {
      const path = resolve(dist, u)
      return sum + (existsSync(path) ? statSync(path).size : 0)
    }, 0)
    expect(total).toBeLessThanOrEqual(BUDGET.precacheRaw)
  })
})
