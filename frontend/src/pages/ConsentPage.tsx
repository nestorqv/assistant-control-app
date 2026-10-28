import { useEffect, useRef, useState } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { giveConsent } from '../api/me'
import { setConsentGiven } from '../auth/session'
import { useSession } from '../auth/useSession'
import { Announcer } from '../components/Announcer'
import { useAnnouncer } from '../components/announcerContext'
import { ErrorBox } from '../components/ErrorBox'
import { Icon } from '../components/Icon'
import { LogoutConfirm } from '../components/LogoutConfirm'
import { ReauthController } from '../components/ReauthController'
import { CONSENT_TEXT_VERSION } from '../consent'
import { describeError } from '../errors/errorMessages'
import { useI18n } from '../i18n/useI18n'
import type { MessageKey } from '../i18n/resources'

const SECTIONS: { title: MessageKey; body: MessageKey }[] = [
  { title: 'consent.responsibleTitle', body: 'consent.responsibleBody' },
  { title: 'consent.whatTitle', body: 'consent.whatBody' },
  { title: 'consent.whyTitle', body: 'consent.whyBody' },
  { title: 'consent.whoTitle', body: 'consent.whoBody' },
  { title: 'consent.retentionTitle', body: 'consent.retentionBody' },
  { title: 'consent.rightsTitle', body: 'consent.rightsBody' },
]

export function ConsentPage() {
  return (
    <Announcer>
      <ConsentContent />
    </Announcer>
  )
}

function ConsentContent() {
  const { t } = useI18n()
  const session = useSession()
  const navigate = useNavigate()
  const { announce } = useAnnouncer()
  const [online, setOnline] = useState(() => navigator.onLine)
  const [busy, setBusy] = useState(false)
  const [declined, setDeclined] = useState(false)
  const [errorText, setErrorText] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [leaving, setLeaving] = useState(false)
  const [reauthDismissed, setReauthDismissed] = useState(false)
  const h1 = useRef<HTMLHeadingElement>(null)
  const declinedHeading = useRef<HTMLHeadingElement>(null)
  const acceptButton = useRef<HTMLButtonElement>(null)

  // The page renders nothing until the stored identity is loaded, so wait for it.
  useEffect(() => {
    if (session.ready) h1.current?.focus()
  }, [session.ready])

  useEffect(() => {
    const on = () => {
      setOnline(true)
      announce(t('offline.restored'))
    }
    const off = () => {
      setOnline(false)
      announce(t('consent.offline'))
    }
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => {
      window.removeEventListener('online', on)
      window.removeEventListener('offline', off)
    }
  }, [announce, t])

  useEffect(() => {
    if (declined) declinedHeading.current?.focus()
  }, [declined])

  if (!session.ready) return null
  if (!session.identity) return <Navigate to="/login" replace />
  if (!session.consentPending) return <Navigate to="/" replace />
  const company = session.identity.companyName

  async function accept() {
    if (busy || !online) return
    setBusy(true)
    setErrorText(null)
    try {
      await giveConsent()
      setConsentGiven()
      navigate('/', { replace: true })
    } catch (error) {
      setErrorText(describeError(error, t))
      setAttempt((n) => n + 1)
      setBusy(false)
    }
  }

  return (
    <main className="screen">
      <h1 ref={h1} tabIndex={-1}>
        {t('consent.title')}
      </h1>
      <p>{t('consent.intro', { company })}</p>
      {SECTIONS.map(({ title, body }) => (
        <section key={title} className="consent-section">
          <h2>{t(title)}</h2>
          <p>{t(body, { company })}</p>
        </section>
      ))}
      <p className="notice notice--info">
        <Icon name="info" />
        <span>{t('consent.blockedNote')}</span>
      </p>
      <p className="hint">{t('consent.version', { version: CONSENT_TEXT_VERSION })}</p>

      {!declined ? (
        <>
          {!online && (
            <p className="notice notice--warn" id="consent-offline">
              <Icon name="wifi-off" />
              <span>{t('consent.offline')}</span>
            </p>
          )}
          {errorText && <ErrorBox attempt={attempt}>{errorText}</ErrorBox>}
          <div className="actions">
            <button
              ref={acceptButton}
              type="button"
              className="button button--primary"
              aria-disabled={busy || !online}
              aria-busy={busy || undefined}
              aria-describedby={!online ? 'consent-offline' : undefined}
              onClick={() => void accept()}
            >
              {busy && <Icon name="spinner" />}
              <span>{busy ? t('consent.accepting') : t('consent.accept')}</span>
            </button>
            <button
              type="button"
              className="button button--secondary"
              aria-disabled={busy}
              onClick={() => {
                if (!busy) setDeclined(true)
              }}
            >
              {t('consent.decline')}
            </button>
          </div>
        </>
      ) : (
        <section className="declined" role="region" aria-labelledby="declined-title">
          <h2 id="declined-title" ref={declinedHeading} tabIndex={-1}>
            {t('consent.declinedTitle')}
          </h2>
          <p>{t('consent.declinedBody')}</p>
          <div className="actions">
            <button type="button" className="button button--secondary" onClick={() => setLeaving(true)}>
              {t('logout.button')}
            </button>
            <button
              type="button"
              className="button button--primary"
              onClick={() => {
                setDeclined(false)
                queueMicrotask(() => acceptButton.current?.focus())
              }}
            >
              {t('common.back')}
            </button>
          </div>
        </section>
      )}

      <LogoutConfirm open={leaving} onClose={() => setLeaving(false)} />
      {session.needsReauth && !reauthDismissed && <ReauthController onClose={() => setReauthDismissed(true)} />}
    </main>
  )
}
