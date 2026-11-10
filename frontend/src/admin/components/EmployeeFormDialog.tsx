import { useId, useRef, useState, type FormEvent } from 'react'
import { NetworkError, TimeoutError } from '../../api/problem'
import { ErrorBox } from '../../components/ErrorBox'
import { Icon } from '../../components/Icon'
import { ModalDialog } from '../../components/ModalDialog'
import { useAdminI18n } from '../adminI18n'
import { createEmployee } from '../api/adminApi'
import type { BranchView, CreatedEmployee } from '../api/adminTypes'
import { problemCode } from '../errors'
import {
  draftToInput,
  EmployeeFields,
  normalizeCode,
  validateDraft,
  type DraftErrors,
  type EmployeeDraft,
} from './EmployeeFields'
import { WriteError } from './WriteError'

type Props = {
  branches: BranchView[]
  /** The result carries the PIN: the parent shows it once and must not keep it anywhere else. */
  onCreated: (result: CreatedEmployee) => void
  /** refresh: the create may or may not have happened (network error), so the list should be reloaded */
  onCancel: (refresh: boolean) => void
}

const EMPTY: EmployeeDraft = { code: '', name: '', jobTitle: '', role: 'EMPLOYEE', branchId: '' }

/** Create dialog. There is no PIN field: the server generates it and returns it once. */
export function EmployeeFormDialog({ branches, onCreated, onCancel }: Props) {
  const { t } = useAdminI18n()
  const titleId = useId()
  const [draft, setDraft] = useState<EmployeeDraft>(EMPTY)
  const [errors, setErrors] = useState<DraftErrors>({})
  const [writeError, setWriteError] = useState<unknown>(null)
  const [uncertain, setUncertain] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(false)
  const refs = {
    code: useRef<HTMLInputElement | HTMLSelectElement>(null),
    name: useRef<HTMLInputElement | HTMLSelectElement>(null),
    jobTitle: useRef<HTMLInputElement | HTMLSelectElement>(null),
    branch: useRef<HTMLInputElement | HTMLSelectElement>(null),
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy || uncertain) return
    const found = validateDraft(draft, t, true)
    setErrors(found)
    const first = (['code', 'name', 'jobTitle', 'branch'] as const).find((key) => found[key])
    if (first) {
      refs[first].current?.focus()
      return
    }
    setBusy(true)
    setWriteError(null)
    try {
      onCreated(await createEmployee({ code: normalizeCode(draft.code), ...draftToInput(draft) }))
    } catch (error) {
      setBusy(false)
      setAttempt((n) => n + 1)
      const code = problemCode(error)
      if (error instanceof NetworkError || error instanceof TimeoutError) {
        // The server may have created the employee: never retried automatically (not idempotent).
        setUncertain(true)
      } else if (code === 'EMPLOYEE_CODE_TAKEN') {
        setErrors({ code: t('error.EMPLOYEE_CODE_TAKEN') })
        refs.code.current?.focus()
      } else if (code === 'BRANCH_REQUIRED' || code === 'BRANCH_INACTIVE') {
        setErrors({ branch: t(code === 'BRANCH_REQUIRED' ? 'error.BRANCH_REQUIRED' : 'error.BRANCH_INACTIVE') })
        refs.branch.current?.focus()
      } else {
        setWriteError(error)
      }
    }
  }

  return (
    <ModalDialog labelledBy={titleId} onCancel={() => !busy && onCancel(uncertain)} className="adm-dialog">
      <h2 id={titleId} tabIndex={-1} data-autofocus>
        {t('admin.employees.new')}
      </h2>
      <form onSubmit={(event) => void submit(event)} noValidate aria-busy={busy || undefined} className="adm-form-grid">
        <EmployeeFields
          draft={draft}
          onChange={setDraft}
          errors={errors}
          readOnly={busy}
          branches={branches}
          codeEditable
          refs={refs}
        />
        {uncertain && <ErrorBox attempt={attempt}>{t('admin.employees.createUncertain')}</ErrorBox>}
        {writeError !== null && <WriteError error={writeError} attempt={attempt} />}
        <div className="modal__actions">
          <button type="submit" className="button button--primary" aria-disabled={busy || uncertain || undefined} aria-busy={busy || undefined}>
            {busy && <Icon name="spinner" />}
            <span>{busy ? t('admin.employee.creating') : t('admin.employee.create')}</span>
          </button>
          <button type="button" className="button button--secondary" aria-disabled={busy || undefined} onClick={() => !busy && onCancel(uncertain)}>
            {t('common.cancel')}
          </button>
        </div>
      </form>
    </ModalDialog>
  )
}
