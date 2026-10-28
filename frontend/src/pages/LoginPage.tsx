import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { login } from '../api/auth'
import type { LoginResponse } from '../api/types'
import { getLastCompanyCode, signIn } from '../auth/session'
import { useSession } from '../auth/useSession'
import { Announcer } from '../components/Announcer'
import { useAnnouncer } from '../components/announcerContext'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { ErrorBox } from '../components/ErrorBox'
import { Icon } from '../components/Icon'
import { PinDots } from '../components/PinDots'
import { PinKeypad } from '../components/PinKeypad'
import { formatCountdown, useCountdown } from '../components/useCountdown'
import { haptics } from '../capture/haptics'
import { describeError, retryAfterOf } from '../errors/errorMessages'
import { useI18n } from '../i18n/useI18n'
import { listAll } from '../storage/outbox'

const CODE_PATTERN = /^[A-Z0-9-]{1,20}$/

export function LoginPage() {
  return (
    <Announcer>
      <LoginForm />
    </Announcer>
  )
}

function useOnline(): boolean {
  const [online, setOnline] = useState(() => navigator.onLine)
  useEffect(() => {
    const on = () => setOnline(true)
    const off = () => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => {
      window.removeEventListener('online', on)
      window.removeEventListener('offline', off)
    }
  }, [])
  return online
}

function LoginForm() {
  const { t } = useI18n()
  const session = useSession()
  const navigate = useNavigate()
  const { announce } = useAnnouncer()
  const online = useOnline()

  const [companyCode, setCompanyCode] = useState(() => getLastCompanyCode() || session.identity?.companyCode || '')
  const [employeeCode, setEmployeeCode] = useState('')
  const [pin, setPin] = useState('')
  const [busy, setBusy] = useState(false)
  const [errorText, setErrorText] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [retryAt, setRetryAt] = useState<number | null>(null)
  const [fieldErrors, setFieldErrors] = useState<{ company?: string; employee?: string }>({})
  const [switching, setSwitching] = useState<{ response: LoginResponse; codes: { companyCode: string; employeeCode: string } } | null>(null)

  const h1 = useRef<HTMLHeadingElement>(null)
  const companyInput = useRef<HTMLInputElement>(null)
  const employeeInput = useRef<HTMLInputElement>(null)
  const seconds = useCountdown(retryAt)
  const locked = seconds > 0
  const wasLocked = useRef(false)

  useEffect(() => {
    h1.current?.focus()
  }, [])

  useEffect(() => {
    if (wasLocked.current && !locked) announce(t('login.retryReady'))
    wasLocked.current = locked
  }, [locked, announce, t])

  const wasOnline = useRef(online)
  useEffect(() => {
    if (online && !wasOnline.current) announce(t('offline.restored'))
    wasOnline.current = online
  }, [online, announce, t])

  // An expired token is caught by the first request (401 opens the PIN dialog), not here.
  if (session.ready && session.token && !session.needsReauth && session.identity) {
    return <Navigate to={session.consentPending ? '/consent' : '/'} replace />
  }

  const inactive = busy || locked || !online
  const complete = pin.length === 8

  function validate(): { company: string; employee: string } | null {
    const company = companyCode.trim().toUpperCase()
    const employee = employeeCode.trim().toUpperCase()
    const errors: { company?: string; employee?: string } = {}
    if (!company) errors.company = t('login.error.companyRequired')
    else if (!CODE_PATTERN.test(company)) errors.company = t('login.error.codeFormat')
    if (!employee) errors.employee = t('login.error.employeeRequired')
    else if (!CODE_PATTERN.test(employee)) errors.employee = t('login.error.codeFormat')
    setFieldErrors(errors)
    if (errors.company) companyInput.current?.focus()
    else if (errors.employee) employeeInput.current?.focus()
    return errors.company || errors.employee ? null : { company, employee }
  }

  async function finish(response: LoginResponse, codes: { companyCode: string; employeeCode: string }) {
    await signIn(response, codes)
    navigate(response.employee.consentPending ? '/consent' : '/', { replace: true })
  }

  async function submit(event?: FormEvent) {
    event?.preventDefault()
    if (inactive || !complete) return
    const codes = validate()
    if (!codes) return
    // Show what is being sent.
    setCompanyCode(codes.company)
    setEmployeeCode(codes.employee)
    setBusy(true)
    setErrorText(null)
    try {
      const response = await login(codes.company, codes.employee, pin)
      const other = session.identity && session.identity.employeeId !== response.employee.id
      const unsent = other ? (await listAll()).length : 0
      if (other && unsent > 0) {
        // Another person's unsent entries stay on this phone for that person; tell the user before switching.
        setSwitching({ response, codes: { companyCode: codes.company, employeeCode: codes.employee } })
        setPin('')
      } else {
        await finish(response, { companyCode: codes.company, employeeCode: codes.employee })
      }
    } catch (error) {
      haptics.error()
      setErrorText(describeError(error, t))
      setAttempt((n) => n + 1)
      const wait = retryAfterOf(error)
      setRetryAt(wait === undefined ? null : performance.now() + wait * 1000)
      setPin('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="screen">
      <h1 ref={h1} tabIndex={-1}>
        {t('login.title')}
      </h1>
      {!online && (
        <p className="notice notice--warn" id="login-offline">
          <Icon name="wifi-off" />
          <span>{t('login.offline')}</span>
        </p>
      )}
      <form onSubmit={(event) => void submit(event)} aria-busy={busy || undefined} noValidate>
        <div className="field">
          <label htmlFor="company-code">{t('login.companyCode')}</label>
          <input
            ref={companyInput}
            id="company-code"
            type="text"
            inputMode="text"
            enterKeyHint="next"
            autoCapitalize="characters"
            autoComplete="off"
            spellCheck={false}
            value={companyCode}
            disabled={busy}
            aria-invalid={fieldErrors.company ? true : undefined}
            aria-describedby={fieldErrors.company ? 'company-error' : undefined}
            onChange={(e) => setCompanyCode(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                employeeInput.current?.focus()
              }
            }}
          />
          {fieldErrors.company && (
            <p className="field__error" id="company-error" role="alert">
              {fieldErrors.company}
            </p>
          )}
        </div>
        <div className="field">
          <label htmlFor="employee-code">{t('login.employeeCode')}</label>
          <input
            ref={employeeInput}
            id="employee-code"
            type="text"
            inputMode="text"
            enterKeyHint="next"
            autoCapitalize="characters"
            autoComplete="off"
            spellCheck={false}
            value={employeeCode}
            disabled={busy}
            aria-invalid={fieldErrors.employee ? true : undefined}
            aria-describedby={fieldErrors.employee ? 'employee-error' : undefined}
            onChange={(e) => setEmployeeCode(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                if (complete) void submit()
              }
            }}
          />
          {fieldErrors.employee && (
            <p className="field__error" id="employee-error" role="alert">
              {fieldErrors.employee}
            </p>
          )}
        </div>

        <p className="field__label" id="pin-label">
          {t('login.pin')}
        </p>
        <PinDots length={pin.length} />
        <PinKeypad value={pin} onChange={setPin} disabled={inactive} />

        {errorText && <ErrorBox attempt={attempt}>{errorText}</ErrorBox>}
        {locked && (
          <p className="countdown" aria-hidden="true">
            {formatCountdown(seconds)}
          </p>
        )}

        <button
          type="submit"
          className="button button--primary"
          aria-disabled={inactive || !complete}
          aria-describedby={!online ? 'login-offline' : undefined}
        >
          {busy && <Icon name="spinner" />}
          <span>{busy ? t('login.submitting') : t('login.submit')}</span>
        </button>
      </form>

      <ConfirmDialog
        open={switching !== null}
        title={t('login.switchTitle')}
        body={<p>{t('login.switchBody')}</p>}
        confirmLabel={t('login.switchConfirm')}
        cancelLabel={t('common.cancel')}
        tone="default"
        onCancel={() => setSwitching(null)}
        onConfirm={() => {
          const pendingLogin = switching
          setSwitching(null)
          if (!pendingLogin) return
          void finish(pendingLogin.response, pendingLogin.codes)
        }}
      />
    </main>
  )
}
