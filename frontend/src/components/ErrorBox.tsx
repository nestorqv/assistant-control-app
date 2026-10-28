import { useEffect, useRef, type ReactNode } from 'react'
import { Icon } from './Icon'

/** One alert at a time. Focus moves to it when it appears or when `attempt` changes (a repeated error). */
export function ErrorBox({ children, attempt = 0 }: { children: ReactNode; attempt?: number }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    ref.current?.focus()
  }, [attempt])
  return (
    <div ref={ref} role="alert" tabIndex={-1} className="notice notice--error">
      <Icon name="x-circle" />
      <span>{children}</span>
    </div>
  )
}
