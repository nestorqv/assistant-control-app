import { useEffect, useRef, type ReactNode } from 'react'

type Props = {
  labelledBy: string
  describedBy?: string
  role?: 'dialog' | 'alertdialog'
  className?: string
  /** Esc, and a click on the backdrop when `closeOnBackdrop` is set */
  onCancel: () => void
  closeOnBackdrop?: boolean
  children: ReactNode
}

/**
 * A native modal <dialog>: focus trap, inert background and Esc come from the browser. It is mounted
 * only while open, so closing means unmounting; focus then goes back to the element that opened it.
 * Put data-autofocus on the element that should get the initial focus.
 */
export function ModalDialog({
  labelledBy,
  describedBy,
  role = 'dialog',
  className,
  onCancel,
  closeOnBackdrop = false,
  children,
}: Props) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    if (!dialog.open) dialog.showModal()
    dialog.querySelector<HTMLElement>('[data-autofocus]')?.focus()
    return () => {
      if (dialog.open) dialog.close()
      if (opener?.isConnected) opener.focus()
    }
  }, [])

  return (
    <dialog
      ref={ref}
      className={`modal${className ? ` ${className}` : ''}`}
      role={role}
      aria-labelledby={labelledBy}
      aria-describedby={describedBy}
      onCancel={(event) => {
        // Esc: let the parent decide (it unmounts the dialog), not the browser.
        event.preventDefault()
        onCancel()
      }}
      onClick={(event) => {
        if (closeOnBackdrop && event.target === ref.current) onCancel()
      }}
    >
      {children}
    </dialog>
  )
}
