import { useId, useState } from 'react'
import type { ReviewReason } from '../../api/types'
import { ErrorBox } from '../../components/ErrorBox'
import { Icon } from '../../components/Icon'
import { ModalDialog } from '../../components/ModalDialog'
import { useAdminI18n, type AdminTranslate } from '../adminI18n'
import { fetchEntry } from '../api/adminApi'
import type { AdjustmentView, AdminEntryDetail } from '../api/adminTypes'
import { useAdminQuery } from '../hooks/useAdminQuery'
import { adminErrorText, problemStatus } from '../errors'
import { formatCoordinates, googleMapsUrl, localDateSentence, minutesApart, shortDateTime, timeOf } from '../format'
import { AdminIcon } from '../AdminIcon'
import { EntryStatusChip, SelfApprovedChip } from './Chips'
import { CreateEntryDialog, type CreateTarget } from './CreateEntryDialog'
import { EvidencePhoto } from './EvidencePhoto'
import { ReviewActionDialog, type ActionMode, type ReviewTarget } from './ReviewActionDialog'

type Props = {
  entryId: number
  /** name shown in the title while the detail loads */
  employeeName: string
  timeZone: string
  onClose: () => void
  /** something changed on the server: the caller reloads its list and the pending count */
  onChanged: () => void
}

/** Only link out when clicked: nothing is embedded and no third-party script, frame or tile is loaded. */
function ExternalMapLink({ latitude, longitude, label }: { latitude: number; longitude: number; label: string }) {
  const { t } = useAdminI18n()
  return (
    <a className="adm-link-button" href={googleMapsUrl(latitude, longitude)} target="_blank" rel="noopener noreferrer">
      <AdminIcon name="external-link" />
      <span>{label}</span>
      <span className="visually-hidden"> {t('admin.evidence.newTab')}</span>
    </a>
  )
}

/** null: there is no value (SQL NULL, an empty string or a legacy "null"); the caller shows a dash. */
function formatValue(adjustment: AdjustmentView, value: string | null, timeZone: string, lang: 'es' | 'en', t: AdminTranslate): string | null {
  if (value === null || value === '' || value === 'null') return null
  if (adjustment.field === 'status') return t(`status.${value as 'VALID' | 'PENDING_REVIEW' | 'REJECTED'}`)
  if (adjustment.field === 'late_minutes') return t('duration.m', { m: value })
  if (adjustment.field === 'effective_time') return shortDateTime(value, timeZone, lang)
  const entry = /^(CLOCK_IN|CLOCK_OUT) (.+)$/.exec(value)
  if (entry) {
    const type = entry[1] === 'CLOCK_IN' ? t('admin.table.clockIn') : t('admin.table.clockOut')
    return `${type} ${shortDateTime(entry[2], timeZone, lang)}`
  }
  return value
}

/** A dash for screen readers is noise: the dash is hidden from them and replaced by "no value". */
function Shown({ text }: { text: string | null }) {
  const { t } = useAdminI18n()
  if (text !== null) return <>{text}</>
  return (
    <>
      <span aria-hidden="true">—</span>
      <span className="visually-hidden">{t('admin.history.none')}</span>
    </>
  )
}

function AdjustmentHistory({ items, timeZone }: { items: AdjustmentView[]; timeZone: string }) {
  const { t, lang } = useAdminI18n()
  if (items.length === 0) return <p className="adm-hint">{t('admin.evidence.historyEmpty')}</p>
  return (
    <ol className="adm-history" reversed>
      {[...items].reverse().map((item) => (
        <li key={item.id}>
          <p>
            <strong>{t(`admin.adjustmentAction.${item.action}`)}</strong>{' '}
            <span>{t('admin.history.by', { name: item.adminName, date: shortDateTime(item.createdAt, timeZone, lang) })}</span>{' '}
            {item.selfApproved && <SelfApprovedChip />}
          </p>
          <p>
            {t(`admin.adjustmentField.${item.field}`)}: <Shown text={formatValue(item, item.oldValue, timeZone, lang, t)} /> →{' '}
            <Shown text={formatValue(item, item.newValue, timeZone, lang, t)} />
          </p>
          <p className="adm-history__reason">“{item.reason}”</p>
        </li>
      ))}
    </ol>
  )
}

function toTarget(d: AdminEntryDetail): ReviewTarget {
  return {
    id: d.id,
    employeeName: d.employee.name,
    employeeCode: d.employee.code,
    type: d.type,
    workDate: d.workDate,
    effectiveTime: d.effectiveTime,
    serverTime: d.serverTime,
    status: d.status,
    deviceTime: d.deviceTime,
    clockInTime: d.type === 'CLOCK_OUT' ? (d.pair?.effectiveTime ?? null) : null,
    syncedLateOrAhead: d.reviewReasons.includes('SYNCED_LATE') || d.reviewReasons.includes('DEVICE_CLOCK_AHEAD'),
  }
}

type Overlay = { kind: 'action'; mode: ActionMode } | { kind: 'create'; target: CreateTarget } | null

/** Photo, facts, exact coordinates (only here), map links, the other entry of the day and the history. */
export function EvidenceDialog({ entryId: initialId, employeeName, timeZone, onClose, onChanged }: Props) {
  const { t, lang } = useAdminI18n()
  const titleId = useId()
  const [entryId, setEntryId] = useState(initialId)
  const [overlay, setOverlay] = useState<Overlay>(null)
  const [note, setNote] = useState('')
  const query = useAdminQuery(() => fetchEntry(entryId), String(entryId))
  const detail = query.data && query.data.id === entryId ? query.data : null

  function done(message: string) {
    setOverlay(null)
    setNote(message)
    query.reload()
    onChanged()
  }

  const type = detail ? (detail.type === 'CLOCK_IN' ? t('admin.table.clockIn') : t('admin.table.clockOut')) : ''
  const notFound = problemStatus(query.error) === 404

  return (
    <>
      <ModalDialog labelledBy={titleId} onCancel={onClose} closeOnBackdrop={overlay === null} className="adm-dialog adm-dialog--wide">
        <div className="adm-dialog__head">
          <div>
            <h2 id={titleId} tabIndex={-1} data-autofocus>
              {t('admin.evidence.title', { name: detail?.employee.name ?? employeeName })}
            </h2>
            {detail && (
              <p className="adm-dialog__sub">
                {t(detail.type === 'CLOCK_IN' ? 'admin.evidence.subtitleIn' : 'admin.evidence.subtitleOut', {
                  date: localDateSentence(detail.workDate, lang),
                })}
              </p>
            )}
          </div>
          <button type="button" className="button button--secondary" onClick={onClose}>
            {t('admin.close')}
          </button>
        </div>

        {query.loading && !detail && (
          <p role="status" className="adm-hint">
            {t('admin.evidence.loading')}
          </p>
        )}
        {notFound && <ErrorBox>{t('admin.evidence.notFound')}</ErrorBox>}
        {query.error !== null && !notFound && (
          <>
            <ErrorBox>{adminErrorText(query.error, t)}</ErrorBox>
            <button type="button" className="button button--secondary" onClick={query.reload}>
              {t('common.retry')}
            </button>
          </>
        )}

        {detail && (
          <>
            <div className="adm-evidence">
              <EvidencePhoto
                entryId={detail.id}
                hasPhoto={detail.hasPhoto}
                manual={detail.source === 'ADMIN'}
                alt={t('admin.evidence.photoAlt', {
                  name: detail.employee.name,
                  type: type.toLocaleLowerCase(),
                  time: timeOf(detail.effectiveTime, timeZone, lang),
                })}
              />
              <div className="adm-facts">
                <div className="adm-facts__status">
                  <EntryStatusChip status={detail.status} />
                  {detail.syncedLate && !detail.reviewReasons.includes('SYNCED_LATE') && (
                    <span className="adm-reason-chip">{t('admin.evidence.syncedLate')}</span>
                  )}
                  {detail.reviewReasons.map((reason: ReviewReason) => (
                    <span key={reason} className="adm-reason-chip">
                      {t(`reviewReason.${reason}`)}
                    </span>
                  ))}
                </div>
                <h3>{t('admin.evidence.hoursTitle')}</h3>
                <dl>
                  <div>
                    <dt>{t('admin.evidence.effective')}</dt>
                    <dd>
                      {timeOf(detail.effectiveTime, timeZone, lang)}
                      {detail.source !== 'ADMIN' && detail.effectiveTime !== detail.serverTime && (
                        <span className="adm-edited">
                          <AdminIcon name="pencil" />
                          {t('admin.table.edited')}
                        </span>
                      )}
                    </dd>
                  </div>
                  <div>
                    <dt>{t('admin.evidence.received')}</dt>
                    <dd>
                      {timeOf(detail.serverTime, timeZone, lang)} <span className="adm-hint">{t('admin.evidence.receivedNote')}</span>
                    </dd>
                  </div>
                  {detail.deviceTime && (
                    <div>
                      <dt>{t('admin.evidence.deviceTime')}</dt>
                      <dd>
                        {t('admin.evidence.skew', {
                          time: timeOf(detail.deviceTime, timeZone, lang),
                          minutes: minutesApart(detail.deviceTime, detail.serverTime),
                        })}
                      </dd>
                    </div>
                  )}
                  <div>
                    <dt>{t('admin.evidence.source')}</dt>
                    <dd>{t(`admin.evidence.source.${detail.source}`)}</dd>
                  </div>
                </dl>

                <h3>{t('admin.evidence.locationTitle')}</h3>
                {detail.source === 'ADMIN' ? (
                  <p className="notice notice--info">
                    <Icon name="info" />
                    <span>{t('admin.evidence.manualNotice')}</span>
                  </p>
                ) : (
                  <dl>
                    <div>
                      <dt>{t('admin.evidence.distance')}</dt>
                      <dd>
                        {detail.distanceMeters === null
                          ? t('admin.evidence.noLocation')
                          : t('admin.evidence.distanceValue', {
                              meters: detail.distanceMeters,
                              branch: detail.branch.name,
                              radius: detail.branch.radiusMeters,
                            })}
                        {detail.insideGeofence !== null && (
                          <span className={`adm-geo adm-geo--${detail.insideGeofence ? 'inside' : 'outside'}`}>
                            <Icon name={detail.insideGeofence ? 'check-circle' : 'alert-triangle'} />
                            {t(detail.insideGeofence ? 'admin.evidence.inside' : 'admin.evidence.outside')}
                          </span>
                        )}
                      </dd>
                    </div>
                    <div>
                      <dt>{t('admin.evidence.accuracy')}</dt>
                      <dd>
                        {detail.gpsAccuracyMeters === null
                          ? t('admin.evidence.accuracyUnknown')
                          : t('admin.evidence.accuracyValue', { meters: detail.gpsAccuracyMeters })}
                      </dd>
                    </div>
                    <div>
                      <dt>{t('admin.evidence.coordinates')}</dt>
                      <dd>
                        {detail.location ? (
                          <span className="adm-coordinates" translate="no">
                            {formatCoordinates(detail.location.latitude, detail.location.longitude)}
                          </span>
                        ) : (
                          t('admin.evidence.noLocation')
                        )}
                      </dd>
                    </div>
                  </dl>
                )}
                {detail.source !== 'ADMIN' && (
                  <div className="adm-links">
                    {detail.location && (
                      <ExternalMapLink
                        latitude={detail.location.latitude}
                        longitude={detail.location.longitude}
                        label={t('admin.evidence.mapLink')}
                      />
                    )}
                    <ExternalMapLink
                      latitude={detail.branch.latitude}
                      longitude={detail.branch.longitude}
                      label={t('admin.evidence.branchMapLink')}
                    />
                    <p className="adm-hint">{t('admin.evidence.mapPrivacy')}</p>
                  </div>
                )}

                <h3>{t('admin.evidence.pairTitle')}</h3>
                {detail.pair ? (
                  <p>
                    {t('admin.evidence.pairValue', {
                      type: detail.pair.type === 'CLOCK_IN' ? t('admin.table.clockIn') : t('admin.table.clockOut'),
                      time: timeOf(detail.pair.effectiveTime, timeZone, lang),
                      status: t(`status.${detail.pair.status}`),
                    })}{' '}
                    <button
                      type="button"
                      className="adm-link-button"
                      onClick={() => {
                        if (detail.pair) setEntryId(detail.pair.id)
                      }}
                    >
                      {t('admin.evidence.pairView')}
                    </button>
                  </p>
                ) : (
                  <p>
                    {t('admin.evidence.pairNone')}
                    {detail.type === 'CLOCK_IN' && (
                      <>
                        {' '}
                        <button
                          type="button"
                          className="adm-link-button"
                          onClick={() =>
                            setOverlay({
                              kind: 'create',
                              target: {
                                employee: { id: detail.employee.id, name: detail.employee.name, code: detail.employee.code, active: true },
                                hasBranch: true,
                                type: 'CLOCK_OUT',
                                workDate: detail.workDate,
                                isToday: false,
                                existingIn: detail.effectiveTime,
                              },
                            })
                          }
                        >
                          {t('admin.review.create')}
                        </button>
                      </>
                    )}
                  </p>
                )}
              </div>
            </div>

            <h3>{t('admin.evidence.history')}</h3>
            <AdjustmentHistory items={detail.adjustments} timeZone={timeZone} />

            <div className="modal__actions adm-actions">
              {detail.status !== 'VALID' && (
                <button
                  type="button"
                  className="button button--primary"
                  onClick={() => setOverlay({ kind: 'action', mode: detail.status === 'REJECTED' ? 'undoReject' : 'approve' })}
                >
                  {t(detail.status === 'REJECTED' ? 'admin.review.undoReject' : 'admin.review.approve')}
                </button>
              )}
              {detail.status !== 'REJECTED' && (
                <button type="button" className="button button--destructive" onClick={() => setOverlay({ kind: 'action', mode: 'reject' })}>
                  {t('admin.review.reject')}
                </button>
              )}
              <button type="button" className="button button--secondary" onClick={() => setOverlay({ kind: 'action', mode: 'correct' })}>
                {t('admin.review.correct')}
              </button>
            </div>
          </>
        )}
        <span className="visually-hidden" aria-live="polite">
          {note}
        </span>
      </ModalDialog>

      {detail && overlay?.kind === 'action' && (
        <ReviewActionDialog
          mode={overlay.mode}
          target={toTarget(detail)}
          ownEntry={detail.ownEntry}
          timeZone={timeZone}
          onCancel={() => setOverlay(null)}
          onEntryChanged={() => {
            setOverlay(null)
            setNote(t('admin.evidence.changed'))
            query.reload()
            onChanged()
          }}
          onDone={(_updated, mode) =>
            done(
              t(
                mode === 'reject'
                  ? 'admin.review.rejected'
                  : mode === 'correct'
                    ? 'admin.review.corrected'
                    : mode === 'undoReject'
                      ? 'admin.review.undone'
                      : 'admin.review.approved',
              ),
            )
          }
        />
      )}
      {overlay?.kind === 'create' && (
        <CreateEntryDialog
          target={overlay.target}
          timeZone={timeZone}
          onCancel={() => setOverlay(null)}
          onRefresh={() => {
            setOverlay(null)
            query.reload()
            onChanged()
          }}
          onDone={() => done(t('admin.create.doneOut', { name: overlay.target.employee.name }))}
        />
      )}
    </>
  )
}
