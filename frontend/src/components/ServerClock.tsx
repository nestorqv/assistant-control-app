import { useI18n } from '../i18n/useI18n'
import { formatTime } from '../time/format'

type Props = { epochMs: number; timeZone: string; approximate: boolean; loading?: boolean }

/** The visible label names the time (aria-label on <time> is not reliably announced). Not a live region. */
export function ServerClock({ epochMs, timeZone, approximate, loading = false }: Props) {
  const { lang, t } = useI18n()
  return (
    <div className="server-clock">
      <p className="clock-label">{t('home.serverClock')}</p>
      <p className="clock" aria-busy={loading || undefined}>
        <time dateTime={new Date(epochMs).toISOString()}>
          {loading ? t('home.noTime') : formatTime(epochMs, timeZone, lang)}
        </time>
        {approximate && !loading && <span className="clock-approx">{t('home.approx')}</span>}
      </p>
    </div>
  )
}
