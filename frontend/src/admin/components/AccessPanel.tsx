import { useId, type Ref } from 'react'
import { Icon } from '../../components/Icon'
import { AdminIcon } from '../AdminIcon'
import { useAdminI18n } from '../adminI18n'
import type { AdminEmployeeDetail } from '../api/adminTypes'
import { shortDateTime, timeOf } from '../format'
import { Chip } from './Chips'

export type AccessAction = 'unlock' | 'toggle' | null

type Props = {
  employee: AdminEmployeeDetail
  timeZone: string
  /** the admin looks at their own account */
  self: boolean
  /** the only active administrator */
  lastAdmin: boolean
  busy: AccessAction
  /** ids of the always-visible notes; the profile form points its blocked role radio at the same ones */
  selfNoteId: string
  lastNoteId: string
  resetRef: Ref<HTMLButtonElement>
  onUnlock: () => void
  onResetPin: () => void
  onToggle: () => void
}

/** State, lock, PIN reset and activation of one account, with the reasons a control is blocked always visible. */
export function AccessPanel({ employee, timeZone, self, lastAdmin, busy, selfNoteId, lastNoteId, resetRef, onUnlock, onResetPin, onToggle }: Props) {
  const { t, lang } = useAdminI18n()
  const titleId = useId()
  const adminNoteId = useId()
  const working = busy !== null
  // Own account and last administrator cannot be deactivated; the reason is always visible text.
  const blockDeactivate = employee.active && (self || lastAdmin)
  const deactivateNote = self ? selfNoteId : lastAdmin ? lastNoteId : undefined
  // Another active, unlocked administrator: the server refuses; saying why beats a surprise error.
  const adminTarget = employee.role === 'ADMIN' && employee.active && !employee.locked && !self
  const last = employee.lastPinReset
  return (
    <section className="adm-section" aria-labelledby={titleId}>
      <h2 id={titleId} className="adm-h2">
        {t('admin.employee.access')}
      </h2>
      <dl className="adm-facts">
        <div>
          <dt>{t('admin.employee.statusLabel')}</dt>
          <dd>
            {employee.active ? (
              <Chip family="teal" icon="check-circle">
                {t('admin.employee.stateActive')}
              </Chip>
            ) : (
              <Chip family="grey" icon="circle">
                {t('admin.employee.stateInactive')}
              </Chip>
            )}
          </dd>
        </div>
        <div>
          <dt>{t('admin.employee.consentLabel')}</dt>
          <dd>{t(employee.consentGiven ? 'admin.employees.yes' : 'admin.employees.no')}</dd>
        </div>
        <div>
          <dt>{t('admin.employee.lockLabel')}</dt>
          <dd>
            {employee.locked && employee.lockedUntil ? (
              <Chip family="redbrown" icon="lock">
                {t('admin.employee.locked', { time: timeOf(employee.lockedUntil, timeZone, lang) })}
              </Chip>
            ) : (
              t('admin.employees.no')
            )}
          </dd>
        </div>
      </dl>

      <div className="adm-access-actions">
        {employee.locked && (
          <button type="button" className="button button--secondary" aria-disabled={working} aria-busy={busy === 'unlock' || undefined} onClick={() => !working && onUnlock()}>
            {busy === 'unlock' && <Icon name="spinner" />}
            <span>{t('admin.employee.unlock')}</span>
          </button>
        )}
        <button
          ref={resetRef}
          type="button"
          className="button button--secondary"
          aria-disabled={working || adminTarget || undefined}
          aria-describedby={adminTarget ? adminNoteId : undefined}
          onClick={() => !working && !adminTarget && onResetPin()}
        >
          <span>{t('admin.employee.resetPin')}</span>
        </button>
        {adminTarget && (
          <p className="notice notice--info adm-note" id={adminNoteId}>
            <Icon name="info" />
            <span>{t('admin.pin.adminNotAllowed')}</span>
          </p>
        )}
        {last && (
          <div className="adm-last-reset">
            <p className="adm-hint">
              {last.reasonCode
                ? t('admin.pin.lastReset', {
                    date: shortDateTime(last.at, timeZone, lang),
                    name: last.byName ?? '',
                    reason: t(`admin.pin.reason.${last.reasonCode}`),
                  })
                : t('admin.pin.lastResetNoReason', { date: shortDateTime(last.at, timeZone, lang), name: last.byName ?? '' })}
            </p>
            {last.note && <p className="adm-hint">{t('admin.pin.lastResetNote', { note: last.note })}</p>}
          </div>
        )}
        {employee.active ? (
          <button
            type="button"
            className="button button--destructive"
            aria-disabled={working || blockDeactivate || undefined}
            aria-describedby={blockDeactivate ? deactivateNote : undefined}
            aria-busy={busy === 'toggle' || undefined}
            onClick={() => !working && !blockDeactivate && onToggle()}
          >
            {busy === 'toggle' && <Icon name="spinner" />}
            <span>{t('admin.employee.deactivate')}</span>
          </button>
        ) : (
          <button type="button" className="button button--secondary" aria-disabled={working} aria-busy={busy === 'toggle' || undefined} onClick={() => !working && onToggle()}>
            {busy === 'toggle' && <Icon name="spinner" />}
            <span>{t('admin.employee.activate')}</span>
          </button>
        )}
      </div>
      {self && (
        <p className="notice notice--info adm-note" id={selfNoteId}>
          <AdminIcon name="lock" />
          <span>{t('admin.employee.selfNote')}</span>
        </p>
      )}
      {lastAdmin && (
        <p className="notice notice--info adm-note" id={lastNoteId}>
          <AdminIcon name="lock" />
          <span>{t('admin.employee.lastAdminNote')}</span>
        </p>
      )}
    </section>
  )
}
