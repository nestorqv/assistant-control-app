import { useState, type ClipboardEvent } from 'react'
import { useAnnouncer } from '../../components/announcerContext'
import { useAdminI18n } from '../adminI18n'
import { parsePastedPair } from '../coordinates'
import { Field } from './Field'

type Props = {
  readOnly?: boolean
  onApply: (latitude: string, longitude: string) => void
}

/**
 * Paste "9.9325, -84.0796" (as Google Maps copies it) and it splits into latitude and longitude. The field
 * clears itself and is never sent to the server. A decimal comma fills nothing and shows a hint.
 */
export function CoordinatesPasteField({ readOnly, onApply }: Props) {
  const { t } = useAdminI18n()
  const { announce } = useAnnouncer()
  const [value, setValue] = useState('')
  const [commaHint, setCommaHint] = useState(false)

  function handle(text: string): boolean {
    const result = parsePastedPair(text)
    if (result.kind === 'pair') {
      onApply(result.latitude, result.longitude)
      setValue('')
      setCommaHint(false)
      announce(t('admin.branches.pasteApplied'))
      return true
    }
    setCommaHint(result.kind === 'comma')
    return false
  }

  function onPaste(event: ClipboardEvent<HTMLInputElement>) {
    if (readOnly) return
    const text = event.clipboardData.getData('text')
    if (handle(text)) event.preventDefault()
  }

  return (
    <Field label={t('admin.branches.paste')} hint={commaHint ? t('admin.branches.pasteComma') : t('admin.branches.pasteHint')}>
      {(control) => (
        <input
          {...control}
          type="text"
          autoComplete="off"
          spellCheck={false}
          value={value}
          readOnly={readOnly}
          onPaste={onPaste}
          onChange={(event) => {
            const next = event.target.value
            // A value that arrives all at once (autofill, drop) is split like a paste; typing is left alone.
            if (Math.abs(next.length - value.length) > 1 && handle(next)) return
            setValue(next)
            if (next === '') setCommaHint(false)
          }}
        />
      )}
    </Field>
  )
}
