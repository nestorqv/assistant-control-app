import { screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import { renderWithI18n } from '../../test/renderWithI18n'
import { isReasonValid, ReasonField } from './ReasonField'

test.each([
  ['a bidi override', 'Verificado‮ con foto'],
  ['a zero-width space', 'Verificado​ con foto'],
  ['a left-to-right isolate', 'Verificado⁦ con foto'],
])('%s is not a valid reason', (_name, text) => {
  expect(isReasonValid(text)).toBe(false)
})

// Whitespace-like characters (line breaks, BOM, U+2028) are collapsed to single spaces before sending, like tabs.
test('accented Spanish and line breaks (collapsed to spaces) are valid', () => {
  expect(isReasonValid('Verificación con la fotografía, José Ñandú')).toBe(true)
  expect(isReasonValid('Primera línea\nsegunda línea')).toBe(true)
})

test('an invisible character shows the inline message at once', () => {
  renderWithI18n(<ReasonField value="" onChange={() => undefined} presets={[]} />)
  expect(screen.queryByText(/caracteres invisibles/)).toBeNull()
  renderWithI18n(<ReasonField value={'Verificado‮ con foto'} onChange={() => undefined} presets={[]} />)
  expect(screen.getByText(/caracteres invisibles/)).toBeDefined()
})
