import { useRef, useState, type KeyboardEvent } from 'react'
import { useI18n } from '../i18n/useI18n'
import { Icon } from './Icon'

const MAX = 8
const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'clear', '0', 'back'] as const
const COLUMNS = 3

type Props = {
  /** digits entered so far (0-8). Never rendered. */
  value: string
  onChange: (next: string) => void
  /** submitting or counting down */
  disabled?: boolean
}

/**
 * A custom keypad, not an input: nothing is written to a field, so the browser has nothing to save or
 * autofill and the digits never reach the DOM. One tab stop (roving tabindex, starts on "5"), arrow
 * keys move in the grid. Disabled keys use aria-disabled so they stay focusable and describable.
 */
export function PinKeypad({ value, onChange, disabled = false }: Props) {
  const { t } = useI18n()
  const [focusIndex, setFocusIndex] = useState(4)
  const buttons = useRef<(HTMLButtonElement | null)[]>([])

  const isDisabled = (key: (typeof KEYS)[number]) => {
    if (disabled) return true
    if (key === 'clear' || key === 'back') return value.length === 0
    return value.length >= MAX
  }

  function press(key: (typeof KEYS)[number]) {
    if (isDisabled(key)) return
    if (key === 'clear') onChange('')
    else if (key === 'back') onChange(value.slice(0, -1))
    else onChange(value + key)
  }

  function moveFocus(index: number) {
    const bounded = Math.max(0, Math.min(KEYS.length - 1, index))
    setFocusIndex(bounded)
    buttons.current[bounded]?.focus()
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const { key } = event
    if (/^[0-9]$/.test(key)) {
      event.preventDefault()
      press(key as (typeof KEYS)[number])
    } else if (key === 'Backspace') {
      event.preventDefault()
      press('back')
    } else if (key === 'Delete' || (key === 'Escape' && value.length > 0)) {
      event.preventDefault()
      // Escape with digits entered clears them instead of closing a dialog.
      event.stopPropagation()
      press('clear')
    } else if (key === 'ArrowRight') {
      event.preventDefault()
      moveFocus(focusIndex + 1)
    } else if (key === 'ArrowLeft') {
      event.preventDefault()
      moveFocus(focusIndex - 1)
    } else if (key === 'ArrowDown') {
      event.preventDefault()
      moveFocus(focusIndex + COLUMNS)
    } else if (key === 'ArrowUp') {
      event.preventDefault()
      moveFocus(focusIndex - COLUMNS)
    } else if (key === 'Home') {
      event.preventDefault()
      moveFocus(focusIndex - (focusIndex % COLUMNS))
    } else if (key === 'End') {
      event.preventDefault()
      moveFocus(focusIndex - (focusIndex % COLUMNS) + COLUMNS - 1)
    }
  }

  return (
    <div className="pin-keypad" role="group" aria-label={t('login.keypad.group')} onKeyDown={onKeyDown}>
      {KEYS.map((key, index) => {
        const off = isDisabled(key)
        return (
          <button
            key={key}
            ref={(el) => {
              buttons.current[index] = el
            }}
            type="button"
            className={`pin-key${key === 'clear' ? ' pin-key--text' : ''}${off ? ' is-disabled' : ''}`}
            tabIndex={index === focusIndex ? 0 : -1}
            aria-disabled={off}
            aria-label={key === 'back' ? t('login.keypad.delete') : undefined}
            onFocus={() => setFocusIndex(index)}
            onClick={() => press(key)}
          >
            {key === 'back' ? <Icon name="backspace" /> : key === 'clear' ? t('login.keypad.clear') : key}
          </button>
        )
      })}
    </div>
  )
}
