// The client mirror of the server text rule (backend common/TextRules, audit finding L-1). Same character
// list and the same digit-run rule, checked before sending so the admin gets an inline message.

/** Control, format (bidi overrides, zero-width, BOM), surrogate and line/paragraph separator characters. */
export const UNSAFE_TEXT = /[\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}]/u

/** 4 or more digits, with spaces . - / allowed between them: a PIN, an id number, a phone. */
export const DIGIT_RUN = /\p{Nd}(?:[\s./-]*\p{Nd}){3,}/u

/** NFC and strip, like the server. */
export function cleanText(text: string): string {
  return text.normalize('NFC').trim()
}

export function hasUnsafeText(text: string): boolean {
  return UNSAFE_TEXT.test(text)
}

export function hasDigitRun(text: string): boolean {
  return DIGIT_RUN.test(text)
}

/** A name or title is valid when, after cleaning, its length is in range and it has no unsafe character. */
export function isTextInRange(text: string, min: number, max: number): boolean {
  // UTF-16 units, the way the server (@Size) counts.
  const length = cleanText(text).length
  return length >= min && length <= max
}
