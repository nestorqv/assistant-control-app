import 'fake-indexeddb/auto'
import { cleanup } from '@testing-library/react'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, vi } from 'vitest'
import { resetSessionForTests } from '../auth/session'
import { setLanguage } from '../i18n/language'
import { resetFlushForTests } from '../sync/flush'
import { resetDbForTests } from '../storage/db'
import { resetOutboxModuleForTests } from '../storage/outbox'
import { serverClock } from '../time/serverClock'

// jsdom has no modal <dialog>: model showModal/close with the `open` attribute.
if (typeof HTMLDialogElement !== 'undefined' && typeof HTMLDialogElement.prototype.showModal !== 'function') {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    if (!this.hasAttribute('open')) return
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

// Not implemented by jsdom: the camera preview and the review image use them.
HTMLMediaElement.prototype.play = () => Promise.resolve()
if (typeof URL.createObjectURL !== 'function') URL.createObjectURL = () => 'blob:mock'
if (typeof URL.revokeObjectURL !== 'function') URL.revokeObjectURL = () => undefined

afterEach(async () => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  await resetDbForTests()
  // A fresh in-memory database for every test.
  globalThis.indexedDB = new IDBFactory()
  localStorage.clear()
  sessionStorage.clear()
  resetSessionForTests()
  resetFlushForTests()
  resetOutboxModuleForTests()
  serverClock.reset()
  document.documentElement.lang = 'es'
  // jsdom reports navigator.language = 'en-US', so tests start from an explicit Spanish state.
  setLanguage('es', false)
})
