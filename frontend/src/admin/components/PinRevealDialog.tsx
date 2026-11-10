import { useEffect, useId, useRef, useState } from 'react'
import { Icon } from '../../components/Icon'
import { ModalDialog } from '../../components/ModalDialog'
import { useAdminI18n } from '../adminI18n'

type Props = {
  mode: 'created' | 'reset'
  /** shown inside the dialog only (never in an announcement, title or URL) */
  name: string
  code: string
  /**
   * The one-time PIN. It lives in the owner's state and nowhere else: the owner sets it to '' when this
   * dialog closes. It is not written to storage, a URL, the title, a live region or the clipboard.
   */
  pin: string
  /** the admin reset their own PIN: closing signs them out */
  self: boolean
  onClose: () => void
}

/**
 * Shows a PIN once. There is deliberately no "copy" button (the clipboard and its history would keep the PIN),
 * and the dialog is not described by the PIN block, so it is not read aloud on open.
 */
export function PinRevealDialog({ mode, name, code, pin, self, onClose }: Props) {
  const { t } = useAdminI18n()
  const titleId = useId()
  const [shown, setShown] = useState(true)
  const [confirming, setConfirming] = useState(false)
  const backToPin = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (confirming) backToPin.current?.focus()
  }, [confirming])

  const first = pin.slice(0, 4)
  const second = pin.slice(4)
  const spoken = `${first.split('').join(' ')}, ${second.split('').join(' ')}`
  return (
    <ModalDialog labelledBy={titleId} onCancel={() => setConfirming(true)} className="adm-dialog adm-pin">
      <h2 id={titleId} tabIndex={-1} data-autofocus>
        {t(mode === 'created' ? 'admin.pin.titleCreated' : 'admin.pin.titleReset')}
      </h2>
      <p className="adm-dialog__sub">{t(mode === 'created' ? 'admin.pin.subtitle' : 'admin.pin.subtitleReset', { name, code })}</p>
      <div className="adm-pin__panel">
        <p className="adm-pin__digits" aria-hidden="true">
          <span>{shown ? first : '••••'}</span>
          <span>{shown ? second : '••••'}</span>
        </p>
        <span className="visually-hidden">{shown ? t('admin.pin.spoken', { digits: spoken }) : t('admin.pin.hiddenSpoken')}</span>
      </div>
      <button type="button" className="button button--secondary" onClick={() => setShown((value) => !value)}>
        <span>{t(shown ? 'admin.pin.hide' : 'admin.pin.show')}</span>
      </button>
      <p className="notice notice--warn">
        <Icon name="alert-triangle" />
        <span>{t('admin.pin.warning')}</span>
      </p>
      <p>{t('admin.pin.handover')}</p>
      {self && (
        <p className="notice notice--warn">
          <Icon name="alert-triangle" />
          <span>{t('admin.pin.selfReset')}</span>
        </p>
      )}
      {confirming ? (
        <div className="adm-pin__confirm" role="group" aria-label={t('admin.pin.confirmClose')}>
          <p>{t('admin.pin.confirmClose')}</p>
          <div className="modal__actions">
            <button ref={backToPin} type="button" className="button button--primary" onClick={() => setConfirming(false)}>
              {t('admin.pin.backToPin')}
            </button>
            <button type="button" className="button button--destructive" onClick={onClose}>
              {t('admin.pin.discard')}
            </button>
          </div>
        </div>
      ) : (
        <div className="modal__actions">
          <button type="button" className="button button--primary" onClick={onClose}>
            {t('admin.pin.done')}
          </button>
        </div>
      )}
    </ModalDialog>
  )
}
