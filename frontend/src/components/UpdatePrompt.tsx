import { useState } from 'react'
import { useI18n } from '../i18n/useI18n'
import { Icon } from './Icon'

type Props = { needRefresh: boolean; suppressed: boolean; onUpdate: () => unknown; onDismiss: () => void }

/**
 * Non-modal, in normal flow, never role="alert" and never steals focus. Hidden while a capture or a
 * re-auth is open or a flush is running. "Update" reloads; the outbox is in IndexedDB, so nothing is lost.
 */
export function UpdatePrompt({ needRefresh, suppressed, onUpdate, onDismiss }: Props) {
  const { t } = useI18n()
  const [updating, setUpdating] = useState(false)
  if (!needRefresh || suppressed) return null
  return (
    <div className="notice notice--info update-prompt">
      <p>
        <Icon name="refresh" />
        <span>{t('update.available')}</span>
      </p>
      <div className="button-row">
        <button
          type="button"
          className="button button--primary"
          aria-disabled={updating}
          onClick={() => {
            if (updating) return
            setUpdating(true)
            void onUpdate()
          }}
        >
          {updating && <Icon name="spinner" />}
          <span>{updating ? t('update.updating') : t('update.reload')}</span>
        </button>
        <button type="button" className="button button--secondary" onClick={onDismiss}>
          {t('update.dismiss')}
        </button>
      </div>
    </div>
  )
}
