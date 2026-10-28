import type { Ref } from 'react'
import { Icon, type IconName } from './Icon'

export type StatusTone = 'neutral' | 'active' | 'done' | 'queued' | 'blocked' | 'warning'

const TONE_ICON: Record<StatusTone, IconName> = {
  neutral: 'circle',
  active: 'dot-circle',
  done: 'check-circle',
  queued: 'hourglass',
  blocked: 'info',
  warning: 'alert-triangle',
}

type Props = { tone: StatusTone; text: string; secondary?: string; id?: string; headingRef?: Ref<HTMLHeadingElement> }

/** The h2 that receives focus after a state change; the Announcer does the announcing. */
export function StatusLine({ tone, text, secondary, id, headingRef }: Props) {
  return (
    <div className="status-line">
      <h2 id={id} ref={headingRef} tabIndex={-1} className="status-line__text">
        <Icon name={TONE_ICON[tone]} />
        <span>{text}</span>
      </h2>
      {secondary && <p className="status-line__secondary">{secondary}</p>}
    </div>
  )
}
