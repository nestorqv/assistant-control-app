// First import on purpose: the Trusted Types default policy must exist before anything registers a worker.
import './security/trustedTypes.ts'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import './index.css'
import App from './App.tsx'
import { SessionProvider } from './auth/SessionProvider.tsx'
import { I18nProvider } from './i18n/I18nProvider.tsx'
import { initLanguage } from './i18n/language.ts'
import { OutboxProvider } from './sync/OutboxProvider.tsx'
import { ServerClockProvider } from './time/ServerClockProvider.tsx'

// Before the first render, so <html lang> and the title are right from the start.
initLanguage()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <I18nProvider>
      <SessionProvider>
        <ServerClockProvider>
          <OutboxProvider>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </OutboxProvider>
        </ServerClockProvider>
      </SessionProvider>
    </I18nProvider>
  </StrictMode>,
)
