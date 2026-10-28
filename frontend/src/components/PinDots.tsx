import { useEffect, useState } from 'react'
import { useI18n } from '../i18n/useI18n'

const PIN_LENGTH = 8
const DEBOUNCE_MS = 400

/**
 * Eight dots in two groups of four. The digits are never rendered. The progress text is debounced so
 * fast typing produces one screen-reader announcement, and it never hints at digit values.
 */
export function PinDots({ length }: { length: number }) {
  const { t } = useI18n()
  const [shown, setShown] = useState(length)

  useEffect(() => {
    const id = setTimeout(() => setShown(length), DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [length])

  return (
    <div className="pin-dots-block">
      <div className="pin-dots" aria-hidden="true" data-testid="pin-dots">
        {[0, 1].map((group) => (
          <span className="pin-dots__group" key={group}>
            {[0, 1, 2, 3].map((i) => (
              <span
                key={i}
                className={`pin-dot${group * 4 + i < length ? ' pin-dot--filled' : ''}`}
                data-filled={group * 4 + i < length}
              />
            ))}
          </span>
        ))}
      </div>
      <p className="pin-progress" aria-live="polite">
        {t('login.pinProgress', { n: Math.min(shown, PIN_LENGTH) })}
      </p>
    </div>
  )
}
