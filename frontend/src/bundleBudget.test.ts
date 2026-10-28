import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { resolve } from 'node:path'
import { gzipSync } from 'node:zlib'
import { describe, expect, test } from 'vitest'

const dist = resolve(__dirname, '../dist')
const assets = resolve(dist, 'assets')

const KB = 1024
// Budget for low-end Android on slow data (plan, "Bundle and performance budget").
const BUDGET = { jsGzip: 130 * KB, entryGzip: 110 * KB, cssGzip: 10 * KB, precacheRaw: 600 * KB }

const gz = (file: string) => gzipSync(readFileSync(file), { level: 9 }).length
const files = (ext: string) => (existsSync(assets) ? readdirSync(assets).filter((f) => f.endsWith(ext)) : [])

// Reads the output of `npm run build`; skipped when dist/ does not exist.
describe.skipIf(!existsSync(resolve(dist, 'sw.js')))('bundle budget', () => {
  test('all JavaScript stays within 130 KB gzip', () => {
    const total = files('.js').reduce((sum, f) => sum + gz(resolve(assets, f)), 0)
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
    expect(names.some((n) => n.startsWith('AdminHome-'))).toBe(true)
    expect(names.some((n) => n.startsWith('SelfieCapture-'))).toBe(true)
  })

  test('CSS stays within 10 KB gzip', () => {
    const total = files('.css').reduce((sum, f) => sum + gz(resolve(assets, f)), 0)
    expect(total).toBeLessThanOrEqual(BUDGET.cssGzip)
  })

  test('the precache stays within 600 KB raw', () => {
    const sw = readFileSync(resolve(dist, 'sw.js'), 'utf8')
    const urls = [...sw.matchAll(/url:"([^"]+)"/g)].map((m) => m[1]).filter((u) => !u.startsWith('http'))
    expect(urls.length).toBeGreaterThan(5)
    const total = urls.reduce((sum, u) => {
      const path = resolve(dist, u)
      return sum + (existsSync(path) ? statSync(path).size : 0)
    }, 0)
    expect(total).toBeLessThanOrEqual(BUDGET.precacheRaw)
  })
})
