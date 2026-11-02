import { adminResources, type AdminMessageKey } from '../i18n/adminResources'
import type { Language } from '../i18n/language'
import { resources, type MessageKey } from '../i18n/resources'
import { useI18n } from '../i18n/useI18n'

export type AdminKey = AdminMessageKey | MessageKey
export type AdminTranslate = (key: AdminKey, vars?: Record<string, string | number>) => string

function format(template: string, vars?: Record<string, string | number>) {
  if (!vars) return template
  return template.replace(/\{(\w+)\}/g, (match, name: string) => (name in vars ? String(vars[name]) : match))
}

export function translateAdmin(lang: Language, key: AdminKey, vars?: Record<string, string | number>): string {
  const admin = adminResources[lang] as Record<string, string>
  const shared = resources[lang] as Record<string, string>
  return format(admin[key] ?? shared[key] ?? key, vars)
}

/**
 * Same language store as the rest of the app. Admin keys come from adminResources (loaded with this chunk);
 * the shared ones (common, status, reviewReason, duration, error) are looked up in resources.
 */
export function useAdminI18n(): { lang: Language; t: AdminTranslate } {
  const { lang } = useI18n()
  return { lang, t: (key, vars) => translateAdmin(lang, key, vars) }
}
