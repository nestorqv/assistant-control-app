import { useId, useRef, useState, type FormEvent } from 'react'
import { useAnnouncer } from '../../components/announcerContext'
import { Icon } from '../../components/Icon'
import { ModalDialog } from '../../components/ModalDialog'
import { AdminIcon } from '../AdminIcon'
import { useAdminI18n } from '../adminI18n'
import { createBranch, fetchBranches, updateBranch } from '../api/adminApi'
import type { BranchView } from '../api/adminTypes'
import { parseLatitude, parseLongitude, parseRadius } from '../coordinates'
import { problemCode } from '../errors'
import { googleMapsUrl } from '../format'
import { cleanText, hasUnsafeText, isTextInRange } from '../text'
import { CoordinatesPasteField } from './CoordinatesPasteField'
import { Field } from './Field'
import { WriteError } from './WriteError'

type Props = {
  /** null: create */
  branch: BranchView | null
  onCancel: () => void
  onSaved: (branch: BranchView) => void
}

type Errors = Partial<Record<'name' | 'lat' | 'lng' | 'radius', string>>

/** Create / edit form. The coordinates are only ever put into the Google Maps check link, built from parsed numbers. */
export function BranchFormDialog({ branch, onCancel, onSaved }: Props) {
  const { t } = useAdminI18n()
  const { announce } = useAnnouncer()
  const titleId = useId()
  const [name, setName] = useState(branch?.name ?? '')
  const [latitude, setLatitude] = useState(branch ? String(branch.latitude) : '')
  const [longitude, setLongitude] = useState(branch ? String(branch.longitude) : '')
  const [radius, setRadius] = useState(branch ? String(branch.radiusMeters) : '100')
  const [version, setVersion] = useState(branch?.version ?? 0)
  const [errors, setErrors] = useState<Errors>({})
  const [writeError, setWriteError] = useState<unknown>(null)
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(false)
  const refs = {
    name: useRef<HTMLInputElement>(null),
    lat: useRef<HTMLInputElement>(null),
    lng: useRef<HTMLInputElement>(null),
    radius: useRef<HTMLInputElement>(null),
  }

  const lat = parseLatitude(latitude)
  const lng = parseLongitude(longitude)

  function validate(): Errors {
    const found: Errors = {}
    if (hasUnsafeText(name)) found.name = t('admin.text.invisibleChars')
    else if (!isTextInRange(name, 1, 120)) found.name = t('admin.branches.error.name')
    if (lat === null) found.lat = t('admin.branches.error.lat')
    if (lng === null) found.lng = t('admin.branches.error.lng')
    if (parseRadius(radius) === null) found.radius = t('admin.branches.error.radius')
    return found
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    const found = validate()
    setErrors(found)
    const first = (['name', 'lat', 'lng', 'radius'] as const).find((key) => found[key])
    if (first) {
      refs[first].current?.focus()
      return
    }
    setBusy(true)
    setWriteError(null)
    const body = { name: cleanText(name), latitude: lat as number, longitude: lng as number, radiusMeters: parseRadius(radius) as number }
    try {
      const saved = branch ? await updateBranch(branch.id, { ...body, expectedVersion: version }) : await createBranch(body)
      onSaved(saved)
    } catch (error) {
      setBusy(false)
      setAttempt((n) => n + 1)
      if (problemCode(error) === 'BRANCH_NAME_TAKEN') {
        setErrors({ name: t('error.BRANCH_NAME_TAKEN') })
        refs.name.current?.focus()
      } else {
        setWriteError(error)
      }
    }
  }

  // Stale record: refetch this branch, replace the form values and the version, discard local edits.
  async function reload() {
    if (!branch) return
    try {
      const fresh = (await fetchBranches()).find((b) => b.id === branch.id)
      if (!fresh) {
        setWriteError(null)
        onCancel()
        return
      }
      setName(fresh.name)
      setLatitude(String(fresh.latitude))
      setLongitude(String(fresh.longitude))
      setRadius(String(fresh.radiusMeters))
      setVersion(fresh.version)
      setErrors({})
      setWriteError(null)
      announce(t('admin.recordChanged.reloaded'))
      refs.name.current?.focus()
    } catch (error) {
      setAttempt((n) => n + 1)
      setWriteError(error)
    }
  }

  const mapsId = `${titleId}-maps`
  const dirty = name !== '' || latitude !== '' || longitude !== ''
  return (
    <ModalDialog labelledBy={titleId} onCancel={() => !busy && onCancel()} closeOnBackdrop={!dirty} className="adm-dialog">
      <h2 id={titleId} tabIndex={-1} data-autofocus>
        {t(branch ? 'admin.branches.editTitle' : 'admin.branches.new')}
      </h2>
      {branch && <p className="adm-dialog__sub">{t('admin.branches.activeNow', { n: branch.activeEmployees })}</p>}
      <form onSubmit={(event) => void submit(event)} noValidate aria-busy={busy || undefined} className="adm-form-grid">
        <Field label={t('admin.branches.name')} required error={errors.name}>
          {(control) => (
            <input
              {...control}
              ref={refs.name}
              type="text"
              autoComplete="off"
              value={name}
              readOnly={busy}
              onChange={(event) => setName(event.target.value)}
            />
          )}
        </Field>
        <CoordinatesPasteField
          readOnly={busy}
          onApply={(newLat, newLng) => {
            setLatitude(newLat)
            setLongitude(newLng)
            setErrors((prev) => ({ ...prev, lat: undefined, lng: undefined }))
          }}
        />
        <div className="adm-form-pair">
          <Field label={t('admin.branches.lat')} required error={errors.lat}>
            {(control) => (
              <input
                {...control}
                ref={refs.lat}
                type="text"
                inputMode="decimal"
                autoComplete="off"
                value={latitude}
                readOnly={busy}
                onChange={(event) => setLatitude(event.target.value)}
              />
            )}
          </Field>
          <Field label={t('admin.branches.lng')} required error={errors.lng}>
            {(control) => (
              <input
                {...control}
                ref={refs.lng}
                type="text"
                inputMode="decimal"
                autoComplete="off"
                value={longitude}
                readOnly={busy}
                onChange={(event) => setLongitude(event.target.value)}
              />
            )}
          </Field>
        </div>
        <Field label={t('admin.branches.radius')} required hint={t('admin.branches.radiusHint')} error={errors.radius}>
          {(control) => (
            <input
              {...control}
              ref={refs.radius}
              type="text"
              inputMode="numeric"
              autoComplete="off"
              value={radius}
              readOnly={busy}
              onChange={(event) => setRadius(event.target.value)}
            />
          )}
        </Field>
        <div className="adm-maps-check">
          {lat !== null && lng !== null ? (
            <a
              className="adm-link-button"
              href={googleMapsUrl(lat, lng)}
              target="_blank"
              rel="noopener noreferrer"
              aria-describedby={mapsId}
            >
              <AdminIcon name="external-link" />
              <span>{t('admin.branches.check')}</span>
              <span className="visually-hidden"> {t('admin.evidence.newTab')}</span>
            </a>
          ) : (
            <a className="adm-link-button" role="link" aria-disabled="true" tabIndex={0} aria-describedby={mapsId}>
              <AdminIcon name="external-link" />
              <span>{t('admin.branches.check')}</span>
            </a>
          )}
          <p className="adm-hint" id={mapsId}>
            {t('admin.evidence.mapPrivacy')}
          </p>
        </div>
        <p className="notice notice--info">
          <Icon name="info" />
          <span>{t('admin.branches.futureOnly')}</span>
        </p>
        {writeError !== null && <WriteError error={writeError} attempt={attempt} onReload={branch ? () => void reload() : undefined} />}
        <div className="modal__actions">
          <button type="submit" className="button button--primary" aria-disabled={busy || undefined} aria-busy={busy || undefined}>
            {busy && <Icon name="spinner" />}
            <span>{busy ? t('admin.action.saving') : t(branch ? 'admin.branches.save' : 'admin.branches.create')}</span>
          </button>
          <button type="button" className="button button--secondary" aria-disabled={busy || undefined} onClick={() => !busy && onCancel()}>
            {t('common.cancel')}
          </button>
        </div>
      </form>
    </ModalDialog>
  )
}
