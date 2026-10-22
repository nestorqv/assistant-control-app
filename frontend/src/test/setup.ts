import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { setLanguage } from '../i18n/language'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  localStorage.clear()
  document.documentElement.lang = 'es'
  // jsdom reports navigator.language = 'en-US', so tests start from an explicit Spanish state.
  setLanguage('es', false)
})
