import { afterEach, expect, test, vi } from 'vitest'
import { detectInitialLanguage, getLanguage, initLanguage, setLanguage, STORAGE_KEY } from './language'

function stubNavigator(languages: readonly string[] | undefined, language = 'en-US') {
  vi.spyOn(navigator, 'languages', 'get').mockReturnValue(languages as string[])
  vi.spyOn(navigator, 'language', 'get').mockReturnValue(language)
}

afterEach(() => {
  vi.restoreAllMocks()
})

test.each([
  [['en-GB'], 'en'],
  [['es-CR'], 'es'],
  [['fr-FR'], 'es'],
  [['EN'], 'en'],
])('browser languages %j resolve to %s', (languages, expected) => {
  stubNavigator(languages)
  expect(detectInitialLanguage()).toBe(expected)
})

test('without navigator.languages it uses navigator.language, then Spanish', () => {
  stubNavigator(undefined, 'en-US')
  expect(detectInitialLanguage()).toBe('en')
  stubNavigator(undefined, 'de')
  expect(detectInitialLanguage()).toBe('es')
})

test('a stored choice wins over the browser language', () => {
  stubNavigator(['es-CR'])
  localStorage.setItem(STORAGE_KEY, 'en')
  expect(detectInitialLanguage()).toBe('en')
})

test('an invalid stored value is ignored', () => {
  stubNavigator(['en-GB'])
  localStorage.setItem(STORAGE_KEY, 'xx')
  expect(detectInitialLanguage()).toBe('en')
  localStorage.setItem(STORAGE_KEY, 'EN ')
  expect(detectInitialLanguage()).toBe('en')
})

test('setLanguage persists, updates <html lang> and the title', () => {
  setLanguage('en')
  expect(localStorage.getItem(STORAGE_KEY)).toBe('en')
  expect(document.documentElement.lang).toBe('en')
  expect(document.title).toBe('Attendance Control')
  setLanguage('es')
  expect(document.documentElement.lang).toBe('es')
  expect(document.title).toBe('Control de Asistencia')
})

test('setLanguage ignores unsupported languages', () => {
  setLanguage('es', false)
  setLanguage('fr' as never)
  expect(getLanguage()).toBe('es')
})

test('initLanguage applies the detected language without persisting it', () => {
  stubNavigator(['en-GB'])
  initLanguage()
  expect(getLanguage()).toBe('en')
  expect(document.documentElement.lang).toBe('en')
  expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
})

test('storage that throws does not break detection or switching', () => {
  stubNavigator(['en-GB'])
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('denied')
  })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('quota')
  })
  expect(detectInitialLanguage()).toBe('en')
  expect(() => setLanguage('en')).not.toThrow()
  expect(getLanguage()).toBe('en')
  expect(document.documentElement.lang).toBe('en')
})
