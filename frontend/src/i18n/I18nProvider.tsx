import { useState, useSyncExternalStore, type ReactNode } from 'react'
import { I18nContext, type Translate } from './i18nContext'
import { getLanguage, setLanguage, subscribe, type Language } from './language'
import { resources } from './resources'

function format(template: string, vars?: Record<string, string | number>) {
  if (!vars) return template
  return template.replace(/\{(\w+)\}/g, (match, name: string) => (name in vars ? String(vars[name]) : match))
}

export function I18nProvider({ children, initialLanguage }: { children: ReactNode; initialLanguage?: Language }) {
  // Tests pass initialLanguage; the app calls initLanguage() before rendering instead.
  useState(() => {
    if (initialLanguage) setLanguage(initialLanguage, false)
  })
  const lang = useSyncExternalStore(subscribe, getLanguage, getLanguage)

  const t: Translate = (key, vars) => format(resources[lang][key] ?? resources.es[key] ?? key, vars)

  return <I18nContext value={{ lang, setLang: setLanguage, t }}>{children}</I18nContext>
}
