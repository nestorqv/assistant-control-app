import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { AnnouncerContext } from './announcerContext'
import { createAnnouncerEngine } from './announcerEngine'

/** The single polite role="status" region of an employee page. */
export function Announcer({ children }: { children: ReactNode }) {
  const [text, setText] = useState('')
  const [engine] = useState(() => createAnnouncerEngine(setText))

  useEffect(() => () => engine.dispose(), [engine])

  const value = useMemo(() => ({ announce: engine.announce }), [engine])
  return (
    <AnnouncerContext value={value}>
      {children}
      <div role="status" aria-live="polite" aria-atomic="true" className="visually-hidden">
        {text}
      </div>
    </AnnouncerContext>
  )
}
