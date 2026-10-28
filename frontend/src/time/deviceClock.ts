/** A phone clock outside 2001-2099 (a reset battery) would make the server reject the entry with 400. */
export function isPlausibleDate(date: Date): boolean {
  const year = date.getUTCFullYear()
  return year >= 2001 && year <= 2099
}

/**
 * deviceTime / deviceSentAt always come from the phone clock (the server's skew maths needs both from
 * the same clock), except when that clock is absurd: then the server-clock estimate is used for both.
 */
export function deviceNow(serverEstimateMs: number, phone: Date = new Date()): Date {
  return isPlausibleDate(phone) ? phone : new Date(serverEstimateMs)
}
