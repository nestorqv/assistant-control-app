import { ErrorBox } from '../../components/ErrorBox'
import { useAdminI18n } from '../adminI18n'
import { adminErrorText, problemCode } from '../errors'

type Props = {
  error: unknown
  /** repeated errors re-focus the box */
  attempt: number
  /** A stale record (409 RECORD_CHANGED): the button refetches and discards the local edits. */
  onReload?: () => void
  /** List pages have no edits to lose: they refetch on their own and show this line instead of the button. */
  listUpdated?: boolean
}

/**
 * The error box of a write. The text is always the fixed localized text of the code, never the submitted value
 * (names and codes are personal data). A stale record offers "Reload" when there are edits to discard.
 */
export function WriteError({ error, attempt, onReload, listUpdated = false }: Props) {
  const { t } = useAdminI18n()
  if (problemCode(error) === 'RECORD_CHANGED') {
    return (
      <ErrorBox attempt={attempt}>
        <span>{t('error.RECORD_CHANGED')} </span>
        {listUpdated ? (
          <span>{t('admin.recordChanged.listUpdated')}</span>
        ) : (
          <>
            <span>{t('admin.recordChanged.discards')} </span>
            {onReload && (
              <button type="button" className="button button--secondary" onClick={onReload}>
                {t('admin.recordChanged.reload')}
              </button>
            )}
          </>
        )}
      </ErrorBox>
    )
  }
  return <ErrorBox attempt={attempt}>{adminErrorText(error, t)}</ErrorBox>
}
