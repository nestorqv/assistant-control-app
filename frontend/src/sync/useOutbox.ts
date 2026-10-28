import { useContext } from 'react'
import { OutboxContext, type OutboxValue } from './outboxContext'

export function useOutbox(): OutboxValue {
  return useContext(OutboxContext)
}
