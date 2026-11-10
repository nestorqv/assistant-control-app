// Parsing of the branch form numbers. Fields are plain text (type="text" inputmode) so a pasted value, a minus
// sign and a stray comma are visible to the admin; the server validates again.

const PAIR = /^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$/
const SINGLE_COMMA_DECIMAL = /^\s*-?\d+,\d+\s*$/
const DECIMAL = /^-?\d+(?:\.\d+)?$/

export type PastedPair = { kind: 'pair'; latitude: string; longitude: string } | { kind: 'comma' } | { kind: 'none' }

/**
 * "9.9325, -84.0796" (what Google Maps copies) splits into two values. A decimal comma ("9,9325") is
 * ambiguous with a pair, so a lone comma between digits without a space counts as a decimal comma and fills
 * nothing; the caller shows the hint.
 */
export function parsePastedPair(text: string): PastedPair {
  if (SINGLE_COMMA_DECIMAL.test(text)) return { kind: 'comma' }
  const match = PAIR.exec(text)
  if (match) return { kind: 'pair', latitude: match[1], longitude: match[2] }
  return /\d,\d/.test(text) ? { kind: 'comma' } : { kind: 'none' }
}

/** A finite decimal inside [min, max], or null. */
export function parseCoordinate(text: string, min: number, max: number): number | null {
  const trimmed = text.trim()
  if (!DECIMAL.test(trimmed)) return null
  const value = Number(trimmed)
  return Number.isFinite(value) && value >= min && value <= max ? value : null
}

export function parseLatitude(text: string): number | null {
  return parseCoordinate(text, -90, 90)
}

export function parseLongitude(text: string): number | null {
  return parseCoordinate(text, -180, 180)
}

/** A whole number of meters from 10 to 5000, or null. */
export function parseRadius(text: string): number | null {
  const trimmed = text.trim()
  if (!/^\d+$/.test(trimmed)) return null
  const value = Number(trimmed)
  return value >= 10 && value <= 5000 ? value : null
}
