import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import { jsonResponse, problemResponse } from '../../test/fixtures'
import { stubFetch } from '../../test/appHarness'
import { renderWithI18n } from '../../test/renderWithI18n'
import { detailView } from '../../test/manageBackend'
import { PinResetDialog } from './PinResetDialog'

function setup(post: (body: unknown) => Response) {
  const bodies: unknown[] = []
  stubFetch((url, init) => {
    if (url === '/api/admin/employees/20/pin-reset') {
      const body = init?.body ? JSON.parse(String(init.body)) : null
      bodies.push(body)
      return post(body)
    }
    return undefined
  })
  const onDone = vi.fn()
  const onCancel = vi.fn()
  renderWithI18n(<PinResetDialog target={{ id: 20, name: 'Nombre Apellido' }} self={false} onCancel={onCancel} onDone={onDone} />)
  return { bodies, onDone, onCancel }
}

const ok = () => jsonResponse({ employee: detailView(), pin: '48207315' })
const confirm = () => screen.getByRole('button', { name: 'Restablecer PIN' })

test('"Sin motivo" is preselected and the note stays disabled with its reason visible', () => {
  setup(ok)
  expect((screen.getByRole('radio', { name: 'Sin motivo' }) as HTMLInputElement).checked).toBe(true)
  const note = screen.getByLabelText('Nota (opcional)') as HTMLTextAreaElement
  expect(note.disabled).toBe(true)
  expect(note.getAttribute('aria-describedby')).toBeTruthy()
  expect(screen.getByText('Elija un motivo para poder escribir una nota.')).toBeDefined()
})

test('no reason sends an empty body, a preset sends the code, a note is cleaned', async () => {
  const user = userEvent.setup()
  const first = setup(ok)
  await user.click(confirm())
  await waitFor(() => expect(first.onDone).toHaveBeenCalled())
  expect(first.bodies[0]).toEqual({})
})

test('a preset alone sends only reasonCode', async () => {
  const user = userEvent.setup()
  const view = setup(ok)
  await user.click(screen.getByRole('radio', { name: 'Olvidó el PIN' }))
  await user.click(confirm())
  await waitFor(() => expect(view.onDone).toHaveBeenCalled())
  expect(view.bodies[0]).toEqual({ reasonCode: 'FORGOT_PIN' })
})

test('a note is trimmed and NFC normalized before sending', async () => {
  const user = userEvent.setup()
  const view = setup(ok)
  await user.click(screen.getByRole('radio', { name: 'Otro' }))
  fireEvent.change(screen.getByLabelText('Nota (opcional)'), { target: { value: '  Solicitado por José  ' } })
  await user.click(confirm())
  await waitFor(() => expect(view.onDone).toHaveBeenCalled())
  expect(view.bodies[0]).toEqual({ reasonCode: 'OTHER', note: 'Solicitado por José' })
})

test.each([
  ['invisible characters', 'a​b', 'El texto contiene caracteres invisibles no permitidos. Escríbalo de nuevo sin copiar y pegar.'],
  ['a digit run', '1234 5678', 'La nota no puede contener números de 4 o más cifras.'],
  ['more than 200 characters', 'x'.repeat(201), 'La nota admite como máximo 200 caracteres.'],
])('%s in the note blocks the request and shows an inline message', async (_name, text, message) => {
  const user = userEvent.setup()
  const view = setup(ok)
  await user.click(screen.getByRole('radio', { name: 'Otro' }))
  fireEvent.change(screen.getByLabelText('Nota (opcional)'), { target: { value: text } })
  await user.click(confirm())
  expect(await screen.findByText(message)).toBeDefined()
  expect(view.bodies).toHaveLength(0)
  // The message never repeats what was typed.
  for (const alert of screen.getAllByRole('alert')) expect(alert.textContent).not.toContain(text)
})

test('going back to "Sin motivo" clears and disables the note', async () => {
  const user = userEvent.setup()
  setup(ok)
  await user.click(screen.getByRole('radio', { name: 'Otro' }))
  fireEvent.change(screen.getByLabelText('Nota (opcional)'), { target: { value: 'una nota' } })
  await user.click(screen.getByRole('radio', { name: 'Sin motivo' }))
  const note = screen.getByLabelText('Nota (opcional)') as HTMLTextAreaElement
  expect(note.value).toBe('')
  expect(note.disabled).toBe(true)
})

test('the hint is linked to the note when a reason is chosen', async () => {
  const user = userEvent.setup()
  setup(ok)
  await user.click(screen.getByRole('radio', { name: 'Otro' }))
  const note = screen.getByLabelText('Nota (opcional)')
  const hint = document.getElementById(note.getAttribute('aria-describedby')?.split(' ')[0] ?? '')
  expect(hint?.textContent).toContain('No escriba datos personales')
})

test('a refused reset of another administrator is shown inside the dialog, which stays open', async () => {
  const user = userEvent.setup()
  const view = setup(() => problemResponse(409, 'ADMIN_PIN_RESET_NOT_ALLOWED'))
  await user.click(confirm())
  expect(await screen.findByText(/No se puede restablecer el PIN de otro administrador activo/)).toBeDefined()
  expect(view.onDone).not.toHaveBeenCalled()
  expect(screen.getByRole('alertdialog')).toBeDefined()
})
