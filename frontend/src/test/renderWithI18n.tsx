import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router'
import { I18nProvider } from '../i18n/I18nProvider'
import type { Language } from '../i18n/language'

type Options = { lang?: Language; route?: string }

/** Renders inside I18nProvider (Spanish by default). Pass `route` to also wrap in a MemoryRouter. */
export function renderWithI18n(ui: ReactElement, { lang = 'es', route }: Options = {}) {
  const content = route === undefined ? ui : <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
  return render(<I18nProvider initialLanguage={lang}>{content}</I18nProvider>)
}
