import { isBaselineJpeg, isJpeg } from './jpegInfo'

export const MAX_SIDE = 1024
export const FIRST_QUALITY = 0.7
export const RETRY_QUALITY = 0.5
const RETRY_ABOVE_BYTES = 1.5 * 1024 * 1024
const REJECT_ABOVE_BYTES = 2 * 1024 * 1024

export type CompressFailure = 'photoTooLarge' | 'photoUnsupported'

export class CompressError extends Error {
  readonly reason: CompressFailure
  constructor(reason: CompressFailure) {
    super(reason)
    this.name = 'CompressError'
    this.reason = reason
  }
}

type Drawable = ImageBitmap | HTMLVideoElement | HTMLImageElement
export type Source = ImageBitmap | HTMLVideoElement | Blob

/** The slice of HTMLCanvasElement that is used, so tests can fake it. */
export type CanvasLike = {
  width: number
  height: number
  getContext(kind: '2d'): { drawImage(source: Drawable, x: number, y: number, w: number, h: number): void } | null
  toBlob(callback: (blob: Blob | null) => void, type: string, quality: number): void
}

type Decoded = { drawable: Drawable; width: number; height: number; release: () => void }

export type CompressDeps = {
  createCanvas: () => CanvasLike
  decode: (blob: Blob) => Promise<Decoded>
}

export type Compressed = { bytes: ArrayBuffer; type: 'image/jpeg'; width: number; height: number }

export async function decodeBlob(blob: Blob): Promise<Decoded> {
  // createImageBitmap applies the EXIF orientation by default, so a gallery photo is not rotated twice.
  if (typeof createImageBitmap === 'function') {
    try {
      const bitmap = await createImageBitmap(blob)
      return { drawable: bitmap, width: bitmap.width, height: bitmap.height, release: () => bitmap.close() }
    } catch {
      // Fall through to the <img> path.
    }
  }
  const url = URL.createObjectURL(blob)
  try {
    const image = new Image()
    image.src = url
    await image.decode()
    return { drawable: image, width: image.naturalWidth, height: image.naturalHeight, release: () => undefined }
  } catch {
    throw new CompressError('photoUnsupported')
  } finally {
    URL.revokeObjectURL(url)
  }
}

const defaultDeps: CompressDeps = {
  createCanvas: () => document.createElement('canvas'),
  decode: decodeBlob,
}

export function blobToArrayBuffer(blob: Blob): Promise<ArrayBuffer> {
  if (typeof blob.arrayBuffer === 'function') return blob.arrayBuffer()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(blob)
  })
}

function encode(canvas: CanvasLike, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
}

async function toDecoded(source: Source, deps: CompressDeps): Promise<Decoded> {
  if (source instanceof Blob) return deps.decode(source)
  if (typeof HTMLVideoElement !== 'undefined' && source instanceof HTMLVideoElement) {
    return { drawable: source, width: source.videoWidth, height: source.videoHeight, release: () => undefined }
  }
  const bitmap = source as ImageBitmap
  return { drawable: bitmap, width: bitmap.width, height: bitmap.height, release: () => bitmap.close() }
}

/**
 * Long side at most 1024 px, baseline JPEG at quality 0.7 (0.5 if over 1.5 MB, rejected over 2 MB).
 * The source, the object URL and the canvas pixels are released whatever happens.
 */
export async function compressImage(source: Source, deps: CompressDeps = defaultDeps): Promise<Compressed> {
  const decoded = await toDecoded(source, deps)
  const canvas = deps.createCanvas()
  try {
    if (!decoded.width || !decoded.height) throw new CompressError('photoUnsupported')
    const scale = Math.min(1, MAX_SIDE / Math.max(decoded.width, decoded.height))
    const width = Math.max(1, Math.round(decoded.width * scale))
    const height = Math.max(1, Math.round(decoded.height * scale))
    canvas.width = width
    canvas.height = height
    const context = canvas.getContext('2d')
    if (!context) throw new CompressError('photoUnsupported')
    context.drawImage(decoded.drawable, 0, 0, width, height)

    let blob = await encode(canvas, FIRST_QUALITY)
    if (blob && blob.size > RETRY_ABOVE_BYTES) blob = await encode(canvas, RETRY_QUALITY)
    if (!blob) throw new CompressError('photoUnsupported')
    if (blob.size > REJECT_ABOVE_BYTES) throw new CompressError('photoTooLarge')

    const bytes = await blobToArrayBuffer(blob)
    const view = new Uint8Array(bytes)
    // A non-JPEG fallback (PNG) or a progressive encoder would only fail later with a server 400.
    if (!isJpeg(view) || !isBaselineJpeg(view)) throw new CompressError('photoUnsupported')
    return { bytes, type: 'image/jpeg', width, height }
  } finally {
    decoded.release()
    canvas.width = 0
    canvas.height = 0
  }
}
