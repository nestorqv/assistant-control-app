import { describe, expect, test } from 'vitest'
import { isBaselineJpeg, isJpeg } from './jpegInfo'

const SOI = [0xff, 0xd8]
const sof = (marker: number) => [0xff, marker, 0x00, 0x0b, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00]
const SOS = [0xff, 0xda, 0x00, 0x02]
const app = (marker: number, payload: number) => [
  0xff,
  marker,
  (payload + 2) >> 8,
  (payload + 2) & 0xff,
  ...new Array(payload).fill(0x41),
]
const bytes = (...parts: number[][]) => new Uint8Array(parts.flat())

describe('isJpeg', () => {
  test('needs FF D8 FF', () => {
    expect(isJpeg(bytes(SOI, [0xff, 0xe0, 0x00]))).toBe(true)
    expect(isJpeg(bytes([0x89, 0x50, 0x4e, 0x47]))).toBe(false)
    expect(isJpeg(new Uint8Array())).toBe(false)
  })
})

describe('isBaselineJpeg', () => {
  test('SOF0 is baseline', () => {
    expect(isBaselineJpeg(bytes(SOI, app(0xe0, 14), sof(0xc0), SOS))).toBe(true)
  })

  test('SOF1 (extended sequential) is accepted', () => {
    expect(isBaselineJpeg(bytes(SOI, app(0xe0, 14), sof(0xc1), SOS))).toBe(true)
  })

  test('SOF2 (progressive) is rejected', () => {
    expect(isBaselineJpeg(bytes(SOI, app(0xe0, 14), sof(0xc2), SOS))).toBe(false)
  })

  test('arithmetic and lossless frames (SOF3, SOF9, SOF10, SOF15) are rejected', () => {
    for (const marker of [0xc3, 0xc9, 0xca, 0xcf]) {
      expect(isBaselineJpeg(bytes(SOI, sof(marker), SOS)), marker.toString(16)).toBe(false)
    }
  })

  test('large APP1 (EXIF) and DQT/DHT segments before the frame are skipped by length', () => {
    const dht = [0xff, 0xc4, 0x00, 0x05, 0x01, 0x02, 0x03]
    expect(isBaselineJpeg(bytes(SOI, app(0xe1, 300), [0xff, 0xdb, 0x00, 0x03, 0x00], dht, sof(0xc0), SOS))).toBe(true)
  })

  test('an APP segment that contains bytes looking like a SOF2 marker does not fool the walk', () => {
    const tricky = [0xff, 0xe1, 0x00, 0x08, 0xff, 0xc2, 0x00, 0x00, 0x00, 0x00]
    expect(isBaselineJpeg(bytes(SOI, tricky, sof(0xc0), SOS))).toBe(true)
  })

  test('fill bytes before a marker are tolerated', () => {
    expect(isBaselineJpeg(bytes(SOI, [0xff, 0xff, 0xff], [0xc0, 0x00, 0x0b, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00], SOS))).toBe(true)
  })

  test('a scan or the end of the image before any frame header is rejected', () => {
    expect(isBaselineJpeg(bytes(SOI, SOS))).toBe(false)
    expect(isBaselineJpeg(bytes(SOI, [0xff, 0xd9]))).toBe(false)
  })

  test('a truncated stream is rejected', () => {
    expect(isBaselineJpeg(bytes(SOI, [0xff, 0xe1, 0x01, 0x00, 0x41]))).toBe(false)
    expect(isBaselineJpeg(bytes(SOI, [0xff]))).toBe(false)
    expect(isBaselineJpeg(bytes(SOI))).toBe(false)
  })

  test('non-JPEG data is rejected', () => {
    expect(isBaselineJpeg(bytes([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))).toBe(false)
  })

  test('a garbage byte where a marker is expected is rejected', () => {
    expect(isBaselineJpeg(bytes(SOI, [0xff, 0xe0, 0x00, 0x02], [0x12, 0x34]))).toBe(false)
  })
})
