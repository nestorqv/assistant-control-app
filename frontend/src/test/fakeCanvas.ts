import type { CanvasLike, CompressDeps } from '../capture/compressImage'

export type FakeCanvas = CanvasLike & {
  drawCalls: { w: number; h: number }[]
  encodeCalls: { type: string; quality: number }[]
}

/**
 * A canvas whose toBlob yields the next entry of `outputs` (bytes), one per call.
 * `bitmap` records close() so tests can check the source was released.
 */
export function fakeCanvasDeps(outputs: Uint8Array[], size = { width: 1920, height: 1080 }) {
  const canvases: FakeCanvas[] = []
  const bitmap = { width: size.width, height: size.height, closed: false, close() { this.closed = true } }
  let call = 0
  const deps: CompressDeps = {
    createCanvas: () => {
      const canvas: FakeCanvas = {
        width: 300,
        height: 150,
        drawCalls: [],
        encodeCalls: [],
        getContext: () => ({
          drawImage: (_source, _x, _y, w, h) => {
            canvas.drawCalls.push({ w, h })
          },
        }),
        toBlob: (callback, type, quality) => {
          canvas.encodeCalls.push({ type, quality })
          const bytes = outputs[Math.min(call++, outputs.length - 1)]
          callback(bytes ? new Blob([bytes as BlobPart], { type }) : null)
        },
      }
      canvases.push(canvas)
      return canvas
    },
    decode: async () => ({
      drawable: bitmap as unknown as ImageBitmap,
      width: bitmap.width,
      height: bitmap.height,
      release: () => bitmap.close(),
    }),
  }
  return { deps, canvases, bitmap }
}
