import { describe, expect, test, vi } from 'vitest'
import { fakeCanvasDeps } from '../test/fakeCanvas'
import { baselineJpegBytes } from '../test/fixtures'
import { CompressError, compressImage, decodeBlob } from './compressImage'

const good = baselineJpegBytes()

/** A baseline JPEG header padded with filler up to `size` bytes. */
function jpegOfSize(size: number): Uint8Array {
  const out = new Uint8Array(size)
  out.set(good)
  return out
}

const progressive = new Uint8Array([0xff, 0xd8, 0xff, 0xc2, 0x00, 0x0b, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00, 0xff, 0xda, 0x00, 0x02])
const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])

async function run(outputs: Uint8Array[], size?: { width: number; height: number }) {
  const harness = fakeCanvasDeps(outputs, size)
  // The source only needs to be a Blob so the injected decoder is used.
  const result = await compressImage(new Blob(['x']), harness.deps)
  return { result, ...harness }
}

describe('scaling', () => {
  test('1920x1080 becomes 1024x576', async () => {
    const { result, canvases } = await run([good], { width: 1920, height: 1080 })
    expect(result.width).toBe(1024)
    expect(result.height).toBe(576)
    expect(canvases[0].drawCalls).toEqual([{ w: 1024, h: 576 }])
  })

  test('720x1280 (portrait) becomes 576x1024', async () => {
    const { result } = await run([good], { width: 720, height: 1280 })
    expect([result.width, result.height]).toEqual([576, 1024])
  })

  test('small images are not upscaled', async () => {
    const { result } = await run([good], { width: 640, height: 480 })
    expect([result.width, result.height]).toEqual([640, 480])
  })
})

describe('encoding', () => {
  test('encodes JPEG at quality 0.7 and returns the bytes untouched', async () => {
    const { result, canvases } = await run([good])
    expect(canvases[0].encodeCalls).toEqual([{ type: 'image/jpeg', quality: 0.7 }])
    expect(result.type).toBe('image/jpeg')
    expect(new Uint8Array(result.bytes)).toEqual(good)
  })

  test('over 1.5 MB is re-encoded at 0.5', async () => {
    const { result, canvases } = await run([jpegOfSize(1.6 * 1024 * 1024), jpegOfSize(900_000)])
    expect(canvases[0].encodeCalls.map((c) => c.quality)).toEqual([0.7, 0.5])
    expect(result.bytes.byteLength).toBe(900_000)
  })

  test('still over 2 MB after the retry is rejected as too large', async () => {
    const harness = fakeCanvasDeps([jpegOfSize(2.5 * 1024 * 1024), jpegOfSize(2.2 * 1024 * 1024)])
    await expect(compressImage(new Blob(['x']), harness.deps)).rejects.toMatchObject({ reason: 'photoTooLarge' })
  })

  test('a progressive JPEG is rejected as unsupported', async () => {
    const harness = fakeCanvasDeps([progressive])
    await expect(compressImage(new Blob(['x']), harness.deps)).rejects.toMatchObject({ reason: 'photoUnsupported' })
  })

  test('a PNG fallback from toBlob is rejected as unsupported', async () => {
    const harness = fakeCanvasDeps([png])
    await expect(compressImage(new Blob(['x']), harness.deps)).rejects.toBeInstanceOf(CompressError)
  })

  test('toBlob returning null is rejected as unsupported', async () => {
    const harness = fakeCanvasDeps([])
    await expect(compressImage(new Blob(['x']), harness.deps)).rejects.toMatchObject({ reason: 'photoUnsupported' })
  })
})

describe('release', () => {
  test('the bitmap is closed and the canvas zeroed after success', async () => {
    const { bitmap, canvases } = await run([good])
    expect(bitmap.closed).toBe(true)
    expect(canvases[0].width).toBe(0)
    expect(canvases[0].height).toBe(0)
  })

  test('and also after a failure', async () => {
    const harness = fakeCanvasDeps([progressive])
    await expect(compressImage(new Blob(['x']), harness.deps)).rejects.toBeInstanceOf(CompressError)
    expect(harness.bitmap.closed).toBe(true)
    expect(harness.canvases[0].width).toBe(0)
  })

  test('an ImageBitmap source is closed by the compressor', async () => {
    const harness = fakeCanvasDeps([good])
    const bitmap = { width: 800, height: 600, close: vi.fn() } as unknown as ImageBitmap
    await compressImage(bitmap, harness.deps)
    expect(bitmap.close).toHaveBeenCalledTimes(1)
  })

  test('the <img> fallback revokes its object URL', async () => {
    // jsdom has no createImageBitmap, so the Blob goes through URL.createObjectURL + <img>.
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test')
    // jsdom does not implement HTMLImageElement.decode.
    HTMLImageElement.prototype.decode = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(800)
    vi.spyOn(HTMLImageElement.prototype, 'naturalHeight', 'get').mockReturnValue(600)
    const harness = fakeCanvasDeps([good])
    await compressImage(new Blob(['x']), { createCanvas: harness.deps.createCanvas, decode: decodeBlob })
    expect(revoke).toHaveBeenCalledWith('blob:test')
  })
})
