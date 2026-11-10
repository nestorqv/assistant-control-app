import type { RefObject } from 'react'
import type { Role } from '../../api/types'
import { useAdminI18n, type AdminTranslate } from '../adminI18n'
import type { BranchView } from '../api/adminTypes'
import { cleanText, hasUnsafeText, isTextInRange } from '../text'
import { Field } from './Field'

export type EmployeeDraft = { code: string; name: string; jobTitle: string; role: Role; branchId: string }
export type DraftErrors = Partial<Record<'code' | 'name' | 'jobTitle' | 'branch', string>>
export const CODE_PATTERN = /^[A-Z0-9-]{1,20}$/

/** Uppercase and strip, like the server does. */
export function normalizeCode(code: string): string {
  return code.trim().toUpperCase()
}

/** The same rules as the server, with inline messages that never repeat the typed value. */
export function validateDraft(draft: EmployeeDraft, t: AdminTranslate, withCode: boolean): DraftErrors {
  const errors: DraftErrors = {}
  if (withCode && !CODE_PATTERN.test(normalizeCode(draft.code))) errors.code = t('admin.employee.error.code')
  if (hasUnsafeText(draft.name)) errors.name = t('admin.text.invisibleChars')
  else if (!isTextInRange(draft.name, 1, 120)) errors.name = t('admin.employee.error.name')
  if (hasUnsafeText(draft.jobTitle)) errors.jobTitle = t('admin.text.invisibleChars')
  else if (!isTextInRange(draft.jobTitle, 0, 80)) errors.jobTitle = t('admin.employee.error.jobTitle')
  if (draft.role === 'EMPLOYEE' && draft.branchId === '') errors.branch = t('admin.employee.error.branch')
  return errors
}

export function draftToInput(draft: EmployeeDraft) {
  const jobTitle = cleanText(draft.jobTitle)
  return {
    name: cleanText(draft.name),
    jobTitle: jobTitle === '' ? null : jobTitle,
    role: draft.role,
    branchId: draft.branchId === '' ? null : Number(draft.branchId),
  }
}

type Props = {
  draft: EmployeeDraft
  onChange: (draft: EmployeeDraft) => void
  errors: DraftErrors
  readOnly: boolean
  branches: BranchView[]
  /** create mode: an editable code; otherwise the code is shown as fixed text */
  codeEditable: boolean
  /** the current branch when it is inactive: shown as a disabled, selected option */
  retiredBranch?: { id: number; name: string } | null
  /** the role radio "Employee" is blocked (own account, last administrator) and points at this note */
  roleLockedNoteId?: string
  refs: Record<'code' | 'name' | 'jobTitle' | 'branch', RefObject<HTMLInputElement | HTMLSelectElement | null>>
}

/** The fields shared by the create dialog and the profile form of the detail page. */
export function EmployeeFields({ draft, onChange, errors, readOnly, branches, codeEditable, retiredBranch, roleLockedNoteId, refs }: Props) {
  const { t } = useAdminI18n()
  const set = (patch: Partial<EmployeeDraft>) => onChange({ ...draft, ...patch })
  const branchRequired = draft.role === 'EMPLOYEE'
  const activeBranches = branches.filter((b) => b.active)
  return (
    <>
      {codeEditable ? (
        <Field label={t('admin.employee.code')} required hint={t('admin.employee.codeHint')} error={errors.code}>
          {(control) => (
            <input
              {...control}
              ref={refs.code as RefObject<HTMLInputElement>}
              type="text"
              className="adm-uppercase"
              autoComplete="off"
              autoCapitalize="characters"
              spellCheck={false}
              value={draft.code}
              readOnly={readOnly}
              onChange={(event) => set({ code: event.target.value })}
              onBlur={() => set({ code: normalizeCode(draft.code) })}
            />
          )}
        </Field>
      ) : (
        <div className="field">
          <span className="field__label">{t('admin.employee.code')}</span>
          <p className="adm-fixed-value">
            {draft.code} <span className="adm-hint">({t('admin.employee.codeLocked')})</span>
          </p>
        </div>
      )}
      <Field label={t('admin.employee.name')} required error={errors.name}>
        {(control) => (
          <input
            {...control}
            ref={refs.name as RefObject<HTMLInputElement>}
            type="text"
            autoComplete="off"
            value={draft.name}
            readOnly={readOnly}
            onChange={(event) => set({ name: event.target.value })}
          />
        )}
      </Field>
      <Field label={t('admin.employee.jobTitle')} error={errors.jobTitle}>
        {(control) => (
          <input
            {...control}
            ref={refs.jobTitle as RefObject<HTMLInputElement>}
            type="text"
            autoComplete="off"
            value={draft.jobTitle}
            readOnly={readOnly}
            onChange={(event) => set({ jobTitle: event.target.value })}
          />
        )}
      </Field>
      <fieldset className="adm-reason-group">
        <legend>{t('admin.employee.role')}</legend>
        <label className="adm-check">
          <input
            type="radio"
            name="employee-role"
            checked={draft.role === 'EMPLOYEE'}
            aria-disabled={roleLockedNoteId ? true : undefined}
            aria-describedby={roleLockedNoteId}
            onChange={() => {
              if (!readOnly && !roleLockedNoteId) set({ role: 'EMPLOYEE' })
            }}
          />
          <span>{t('admin.employee.roleEmployee')}</span>
        </label>
        <p className="adm-hint">{t('admin.employee.roleEmployeeHelp')}</p>
        <label className="adm-check">
          <input
            type="radio"
            name="employee-role"
            checked={draft.role === 'ADMIN'}
            onChange={() => {
              if (!readOnly) set({ role: 'ADMIN' })
            }}
          />
          <span>{t('admin.employee.roleAdmin')}</span>
        </label>
        <p className="adm-hint">{t('admin.employee.roleAdminHelp')}</p>
      </fieldset>
      <Field
        label={t('admin.employee.branch')}
        required={branchRequired}
        hint={branchRequired ? null : t('admin.employee.branchAdminHelp')}
        error={errors.branch}
      >
        {(control) => (
          <select
            {...control}
            ref={refs.branch as RefObject<HTMLSelectElement>}
            value={draft.branchId}
            disabled={readOnly}
            onChange={(event) => set({ branchId: event.target.value })}
          >
            <option value="">{t('admin.employee.branchPlaceholder')}</option>
            {retiredBranch && (
              <option value={String(retiredBranch.id)}>
                {t('admin.employee.branchInactiveOption', { name: retiredBranch.name })}
              </option>
            )}
            {activeBranches.map((branch) => (
              <option key={branch.id} value={String(branch.id)}>
                {branch.name}
              </option>
            ))}
          </select>
        )}
      </Field>
    </>
  )
}
