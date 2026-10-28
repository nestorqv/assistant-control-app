import { useEffect, useState } from 'react'
import { useRegisterSW } from '../pwa/registerSW'
import { useI18n } from '../i18n/useI18n'
import { useAnnouncer } from './announcerContext'
import { UpdatePrompt } from './UpdatePrompt'

/** Connects the service worker hook to the prompt. `busy` = capture open, re-auth open or flushing. */
export function UpdateBanner({ busy }: { busy: boolean }) {
  const { t } = useI18n()
  const { announce } = useAnnouncer()
  const {
    needRefresh: [needRefresh],
    updateServiceWorker,
  } = useRegisterSW()
  const [dismissed, setDismissed] = useState(false)
  const visible = needRefresh && !dismissed && !busy

  useEffect(() => {
    if (visible) announce(t('update.announce'), { dedupeKey: 'update' })
  }, [visible, announce, t])

  return (
    <UpdatePrompt
      needRefresh={needRefresh && !dismissed}
      suppressed={busy}
      onUpdate={() => updateServiceWorker(true)}
      onDismiss={() => setDismissed(true)}
    />
  )
}
