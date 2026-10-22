import { useState } from 'react'
import { useI18n } from '../i18n/useI18n'
import { SUPPORTED, type Language } from '../i18n/language'
import { resources } from '../i18n/resources'

export function LanguageSwitcher() {
  const { lang, setLang, t } = useI18n()
  // Empty on mount so nothing is announced until the user actually changes the language.
  const [announcement, setAnnouncement] = useState('')

  function choose(code: Language) {
    if (code === lang) return
    setLang(code)
    setAnnouncement(resources[code]['language.changed'])
  }

  return (
    <div className="language-switcher" role="group" aria-label={t('language.label')}>
      {SUPPORTED.map((code) => (
        <button key={code} type="button" lang={code} aria-pressed={lang === code} onClick={() => choose(code)}>
          {t(`language.name.${code}`)}
        </button>
      ))}
      {/* Not role="status": HealthStatus owns the single status region of each page. */}
      <span className="visually-hidden" aria-live="polite">
        {announcement}
      </span>
    </div>
  )
}
