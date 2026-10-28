/** FF D8 FF: the start of every JPEG the backend accepts. */
export function isJpeg(bytes: Uint8Array): boolean {
  return bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff
}

// SOF markers that are not baseline/extended-sequential: progressive (C2), lossless (C3, C7),
// differential (C5, C6), arithmetic (C9-CB, CD-CF). C4 (DHT), C8 (JPG) and CC (DAC) are not frames.
const NON_BASELINE_SOF = new Set([0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf])

/**
 * Walks the marker segments up to the first frame header: SOF0 or SOF1 means baseline.
 * The backend rejects progressive JPEGs, so a wrong encoder output is caught here instead of as a 400.
 */
export function isBaselineJpeg(bytes: Uint8Array): boolean {
  if (!isJpeg(bytes)) return false
  let i = 2
  while (i + 1 < bytes.length) {
    if (bytes[i] !== 0xff) return false
    // Any number of 0xFF fill bytes may precede a marker.
    while (i < bytes.length && bytes[i] === 0xff) i++
    const marker = bytes[i]
    i++
    if (marker === undefined) return false
    if (marker === 0xc0 || marker === 0xc1) return true
    if (NON_BASELINE_SOF.has(marker)) return false
    if (marker === 0xda || marker === 0xd9) return false // scan or end before any frame header
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) continue // standalone markers, no length
    if (i + 1 >= bytes.length) return false
    const length = (bytes[i] << 8) | bytes[i + 1]
    if (length < 2) return false
    i += length
  }
  return false
}
