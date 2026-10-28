import { useId, type ReactNode } from 'react'
import { Icon } from './Icon'
import { ModalDialog } from './ModalDialog'

type Props = {
  open: boolean
  title: string
  body: ReactNode
  confirmLabel: string
  cancelLabel: string
  /** destructive uses role="alertdialog" and an outlined, error-coloured confirm button */
  tone?: 'default' | 'destructive'
  busy?: boolean
  busyLabel?: string
  onConfirm: () => void
  onCancel: () => void
}

/** Initial focus is on the safe action (cancel). Esc and a backdrop click cancel. */
export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel,
  cancelLabel,
  tone = 'default',
  busy = false,
  busyLabel,
  onConfirm,
  onCancel,
}: Props) {
  const titleId = useId()
  const bodyId = useId()
  if (!open) return null
  const destructive = tone === 'destructive'
  const icon = busy ? 'spinner' : destructive ? 'trash' : null
  return (
    <ModalDialog
      labelledBy={titleId}
      describedBy={destructive ? bodyId : undefined}
      role={destructive ? 'alertdialog' : 'dialog'}
      onCancel={() => {
        if (!busy) onCancel()
      }}
      closeOnBackdrop
    >
      <h2 id={titleId}>{title}</h2>
      <div id={bodyId} className="modal__body">
        {body}
      </div>
      <div className="modal__actions">
        <button type="button" className="button button--primary" data-autofocus onClick={onCancel} aria-disabled={busy}>
          {cancelLabel}
        </button>
        <button
          type="button"
          className={`button ${destructive ? 'button--destructive' : 'button--secondary'}`}
          aria-disabled={busy}
          aria-busy={busy || undefined}
          onClick={() => {
            if (!busy) onConfirm()
          }}
        >
          {icon && <Icon name={icon} />}
          <span>{busy && busyLabel ? busyLabel : confirmLabel}</span>
        </button>
      </div>
    </ModalDialog>
  )
}
