import { useState } from 'react'
import { useNavigate } from 'react-router'
import { performLogout } from '../auth/logout'
import { useI18n } from '../i18n/useI18n'
import { useOutbox } from '../sync/useOutbox'
import { ConfirmDialog } from './ConfirmDialog'

/** Always asks. Unsent entries are never deleted by signing out: they stay on the phone for their owner. */
export function LogoutConfirm({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n()
  const { pending } = useOutbox()
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const count = pending.length

  async function confirm() {
    setBusy(true)
    await performLogout()
    setBusy(false)
    onClose()
    navigate('/login', { replace: true })
  }

  return (
    <ConfirmDialog
      open={open}
      title={t('logout.confirmTitle')}
      body={
        <>
          <p>{t('logout.confirmBody')}</p>
          {count > 0 && (
            <p className="notice notice--info">
              <span>{count === 1 ? t('logout.pendingKept.one') : t('logout.pendingKept.other', { n: count })}</span>
            </p>
          )}
        </>
      }
      confirmLabel={t('logout.confirm')}
      cancelLabel={t('common.cancel')}
      tone="default"
      busy={busy}
      busyLabel={t('logout.signingOut')}
      onConfirm={() => void confirm()}
      onCancel={onClose}
    />
  )
}
