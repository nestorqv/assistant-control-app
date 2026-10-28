import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, test } from 'vitest'
import { renderWithI18n } from '../test/renderWithI18n'
import { PinDots } from './PinDots'
import { PinKeypad } from './PinKeypad'

function Harness({ disabled = false }: { disabled?: boolean }) {
  const [pin, setPin] = useState('')
  return (
    <>
      <PinDots length={pin.length} />
      <PinKeypad value={pin} onChange={setPin} disabled={disabled} />
      <output data-testid="length">{pin.length}</output>
    </>
  )
}

const length = () => screen.getByTestId('length').textContent
const filled = () => document.querySelectorAll('.pin-dot--filled').length

describe('PinKeypad', () => {
  test('digits append up to 8, then the digit keys stop', async () => {
    const user = userEvent.setup()
    renderWithI18n(<Harness />)
    for (const digit of '1234567890') await user.click(screen.getByRole('button', { name: digit }))
    expect(length()).toBe('8')
    expect(screen.getByRole('button', { name: '9' }).getAttribute('aria-disabled')).toBe('true')
  })

  test('backspace removes one, clear removes all', async () => {
    const user = userEvent.setup()
    renderWithI18n(<Harness />)
    for (const digit of '123') await user.click(screen.getByRole('button', { name: digit }))
    await user.click(screen.getByRole('button', { name: 'Borrar último dígito' }))
    expect(length()).toBe('2')
    await user.click(screen.getByRole('button', { name: 'Borrar todo' }))
    expect(length()).toBe('0')
    expect(screen.getByRole('button', { name: 'Borrar todo' }).getAttribute('aria-disabled')).toBe('true')
  })

  test('hardware digits, Backspace and Delete work while the keypad has focus', async () => {
    const user = userEvent.setup()
    renderWithI18n(<Harness />)
    screen.getByRole('button', { name: '5' }).focus()
    await user.keyboard('4821')
    expect(length()).toBe('4')
    await user.keyboard('{Backspace}')
    expect(length()).toBe('3')
    await user.keyboard('{Delete}')
    expect(length()).toBe('0')
  })

  test('hardware digits are ignored when the focus is outside the keypad', async () => {
    const user = userEvent.setup()
    renderWithI18n(
      <>
        <input aria-label="other" />
        <Harness />
      </>,
    )
    await user.click(screen.getByLabelText('other'))
    await user.keyboard('123')
    expect(length()).toBe('0')
  })

  test('keys are one tab stop and arrow keys move in the grid', async () => {
    const user = userEvent.setup()
    renderWithI18n(<Harness />)
    const keys = screen.getAllByRole('button')
    expect(keys.filter((k) => k.tabIndex === 0)).toHaveLength(1)
    expect(screen.getByRole('button', { name: '5' }).tabIndex).toBe(0)
    screen.getByRole('button', { name: '5' }).focus()
    await user.keyboard('{ArrowRight}')
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '6' }))
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '9' }))
    await user.keyboard('{Home}')
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '7' }))
  })

  test('a disabled keypad ignores presses but stays focusable (aria-disabled)', async () => {
    const user = userEvent.setup()
    renderWithI18n(<Harness disabled />)
    const one = screen.getByRole('button', { name: '1' })
    expect(one.getAttribute('aria-disabled')).toBe('true')
    expect((one as HTMLButtonElement).disabled).toBe(false)
    await user.click(one)
    expect(length()).toBe('0')
  })

  test('every key has an accessible name and a large target class', () => {
    renderWithI18n(<Harness />)
    const keys = screen.getAllByRole('button')
    expect(keys).toHaveLength(12)
    for (const key of keys) {
      expect(key.className).toContain('pin-key')
      expect(key.getAttribute('aria-label') ?? key.textContent).toBeTruthy()
    }
    expect(screen.getByRole('group', { name: 'Teclado numérico del PIN' })).toBeDefined()
  })

  test('digits are never rendered into the DOM or an input', async () => {
    const user = userEvent.setup()
    const { container } = renderWithI18n(<Harness />)
    for (const digit of '48207315') await user.click(screen.getByRole('button', { name: digit }))
    expect(container.querySelector('input')).toBeNull()
    expect(container.textContent).not.toContain('48207315')
  })
})

describe('PinDots', () => {
  test('eight dots in two groups of four, aria-hidden', () => {
    render(<div />)
    renderWithI18n(<PinDots length={3} />)
    const dots = screen.getByTestId('pin-dots')
    expect(dots.getAttribute('aria-hidden')).toBe('true')
    expect(dots.querySelectorAll('.pin-dots__group')).toHaveLength(2)
    expect(dots.querySelectorAll('.pin-dot')).toHaveLength(8)
    expect(filled()).toBe(3)
  })

  test('the progress text is polite, debounced, and never shows digits', async () => {
    const user = userEvent.setup()
    renderWithI18n(<Harness />)
    const progress = document.querySelector('.pin-progress')!
    expect(progress.getAttribute('aria-live')).toBe('polite')
    expect(progress.getAttribute('role')).toBeNull()
    expect(progress.textContent).toBe('0 de 8 dígitos')
    for (const digit of '123') await user.click(screen.getByRole('button', { name: digit }))
    expect(progress.textContent).toBe('0 de 8 dígitos') // still inside the debounce window
    await screen.findByText('3 de 8 dígitos')
    expect(progress.textContent).not.toMatch(/[1-3]{3}/)
  })
})
