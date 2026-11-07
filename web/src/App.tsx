import { useCallback, useEffect, useState } from 'react'
import { AppShell } from './shell/AppShell'
import { apiGet, setActiveToken, UnauthorizedError } from './lib/api'
import { bootstrapToken } from './lib/token'
import { TokenGate } from './TokenGate'

type Probe =
  | { state: 'bekliyor' }
  | { state: 'tamam' }
  | { state: 'yetkisiz' }
  | { state: 'hata'; message: string }

export function App() {
  const [ready, setReady] = useState(false)
  const [hasToken, setHasToken] = useState(false)
  const [probe, setProbe] = useState<Probe>({ state: 'bekliyor' })

  // Açılış: adresteki #token= (ya da eski ?token=) alınır, kaydedilir, adresten silinir.
  useEffect(() => {
    const token = bootstrapToken(window.location, window.history)
    setActiveToken(token)
    setHasToken(Boolean(token))
    setReady(true)
  }, [])

  const runProbe = useCallback(async (signal: AbortSignal) => {
    setProbe({ state: 'bekliyor' })
    try {
      // Kimlik doğrulaması isteyen ucuz bir uç: token gerçekten geçerli mi?
      // /health bunu ölçmez, auth istemiyor.
      await apiGet('/dirs/roots', signal)
      setProbe({ state: 'tamam' })
    } catch (err) {
      if (signal.aborted) return
      if (err instanceof UnauthorizedError) {
        setProbe({ state: 'yetkisiz' })
        return
      }
      setProbe({ state: 'hata', message: err instanceof Error ? err.message : String(err) })
    }
  }, [])

  useEffect(() => {
    if (!ready || !hasToken) return
    const controller = new AbortController()
    void runProbe(controller.signal)
    return () => controller.abort()
  }, [ready, hasToken, runProbe])

  if (!ready) return null

  if (!hasToken || probe.state === 'yetkisiz') {
    return (
      <TokenGate
        reason={probe.state === 'yetkisiz' ? 'Köprü bu token’ı kabul etmedi.' : undefined}
        onSubmit={(token) => {
          setActiveToken(token)
          setHasToken(true)
          setProbe({ state: 'bekliyor' })
        }}
      />
    )
  }

  if (probe.state === 'hata') {
    return (
      <main className="shell">
        <h1>Telekumanda</h1>
        <p className="error">Köprüye ulaşılamadı: {probe.message}</p>
        <p className="muted">Köprü çalışıyor mu, adres doğru mu?</p>
        <button
          type="button"
          className="link"
          onClick={() => {
            setActiveToken(null)
            setHasToken(false)
          }}
        >
          Token’ı unut
        </button>
      </main>
    )
  }

  if (probe.state === 'bekliyor') {
    return (
      <main className="shell">
        <h1>Telekumanda</h1>
        <p className="muted">Köprüye bağlanılıyor…</p>
      </main>
    )
  }

  return <AppShell />
}
