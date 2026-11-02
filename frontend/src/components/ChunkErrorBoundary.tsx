import { Component, type ErrorInfo, type ReactNode } from 'react'
import { useI18n } from '../i18n/useI18n'
import { ErrorBox } from './ErrorBox'
import { LanguageSwitcher } from './LanguageSwitcher'

function ChunkFailure() {
  const { t } = useI18n()
  return (
    <main className="screen">
      <LanguageSwitcher />
      <ErrorBox>{t('adminChunk.error')}</ErrorBox>
      <button type="button" className="button button--primary" onClick={() => window.location.reload()}>
        {t('common.retry')}
      </button>
    </main>
  )
}

/**
 * The lazy admin chunk can fail to load (offline, or an old hashed file after a deploy). It lives in the
 * employee bundle on purpose: the boundary must exist when the chunk does not.
 */
export class ChunkErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Names only: component stacks and messages can hold paths, never personal data.
    console.warn('[admin] console failed to load', { error: error.name, where: info.componentStack ? 'render' : 'unknown' })
  }

  render() {
    return this.state.failed ? <ChunkFailure /> : this.props.children
  }
}
