import { createContext } from 'react'
import type { Language } from './language'
import type { MessageKey } from './resources'

export type Translate = (key: MessageKey, vars?: Record<string, string | number>) => string

export type I18nValue = { lang: Language; setLang: (lang: Language) => void; t: Translate }

export const I18nContext = createContext<I18nValue | null>(null)
