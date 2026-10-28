import { toSpoken } from '../time/format'

export const HOLD_MS = 1500
export const CLEAR_AFTER_MS = 8000
export const DEDUPE_MS = 1000
const GAP_MS = 20

/**
 * The announcement queue behind the single polite status region. Messages are read one after another,
 * the text is cleared first so an identical repeat is announced again, and a message with the same
 * dedupe key within one second is dropped.
 */
export function createAnnouncerEngine(setText: (text: string) => void) {
  const queue: string[] = []
  const recent = new Map<string, number>()
  const timers = new Set<ReturnType<typeof setTimeout>>()
  let busy = false

  function later(fn: () => void, ms: number) {
    const id = setTimeout(() => {
      timers.delete(id)
      fn()
    }, ms)
    timers.add(id)
  }

  function next() {
    const message = queue.shift()
    if (message === undefined) {
      busy = false
      later(() => setText(''), CLEAR_AFTER_MS)
      return
    }
    busy = true
    setText('')
    later(() => {
      setText(message)
      later(next, HOLD_MS)
    }, GAP_MS)
  }

  return {
    announce(message: string, options?: { dedupeKey?: string }) {
      const key = options?.dedupeKey
      if (key) {
        const now = Date.now()
        const last = recent.get(key)
        if (last !== undefined && now - last < DEDUPE_MS) return
        recent.set(key, now)
      }
      queue.push(toSpoken(message))
      if (!busy) next()
    },
    dispose() {
      timers.forEach(clearTimeout)
      timers.clear()
    },
  }
}
