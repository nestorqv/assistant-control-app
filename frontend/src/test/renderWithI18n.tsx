import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router'
import { SessionProvider } from '../auth/SessionProvider'
import { I18nProvider } from '../i18n/I18nProvider'
import type { Language } from '../i18n/language'
import { OutboxProvider } from '../sync/OutboxProvider'
import { ServerClockProvider } from '../time/ServerClockProvider'

type Options = {
  lang?: Language
  route?: string
  /** also wrap in the session, server clock and outbox providers, as main.tsx does */
  providers?: boolean
  /** with `providers`: sync the server clock on mount (needs a fetch stub that answers /api/time) */
  autoSync?: boolean
}

/** Renders inside I18nProvider (Spanish by default). Pass `route` to also wrap in a MemoryRouter. */
export function renderWithI18n(
  ui: ReactElement,
  { lang = 'es', route, providers = false, autoSync = false }: Options = {},
) {
  let content = route === undefined ? ui : <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
  if (providers) {
    content = (
      <SessionProvider>
        <ServerClockProvider autoSync={autoSync}>
          <OutboxProvider>{content}</OutboxProvider>
        </ServerClockProvider>
      </SessionProvider>
    )
  }
  return render(<I18nProvider initialLanguage={lang}>{content}</I18nProvider>)
}
