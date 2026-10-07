// Tur kontrolleri: kesme (interrupt) ve durdurma (stop). Akış meta.running
// false iken ikisi de devre dışı; kesme isteği takıldıysa uyarı gösterilir ve
// stop öne çıkar. Bağlam doluluk yüzdesi küçük bir göstergede.

import { useRef, useState } from 'react'
import { interrupt, stop } from './controlsApi'
import styles from './controls.module.css'

export interface RunControlsProps {
  backend: string
  sessionId: string
  running: boolean
  /**
   * /<b>/interrupt bu backend'de kayıtlı mı (yetenek kataloğundan).
   * Yalnız claude-app'te var; /stop hepsinde. Desteklenmiyorsa düğme çizilmez —
   * tıklanınca 404 alan bir düğme göstermek kullanıcıyı yanıltır.
   */
  canInterrupt?: boolean
  /** Köprü kesme isteğinin takıldığını bildirdiyse true (meta.interruptStuck). */
  interruptStuck?: boolean
  contextTokens?: number
  contextWindow?: number
}

export function RunControls({
  backend,
  sessionId,
  running,
  canInterrupt = true,
  interruptStuck = false,
  contextTokens,
  contextWindow,
}: RunControlsProps) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const busy = useRef(false)

  const run = async (action: 'interrupt' | 'stop') => {
    if (busy.current) return
    busy.current = true
    setPending(true)
    setError(null)
    try {
      if (action === 'interrupt') await interrupt(backend, sessionId)
      else await stop(backend, sessionId)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      busy.current = false
      setPending(false)
    }
  }

  const fill =
    contextTokens != null && contextWindow && contextWindow > 0
      ? Math.min(100, Math.round((contextTokens / contextWindow) * 100))
      : null

  return (
    <div className={styles.runControls}>
      {canInterrupt && interruptStuck && (
        <p className={styles.warn}>Kesme isteği takıldı; yanıt beklemiyorsan Durdur'u kullan.</p>
      )}
      <div className={styles.actions}>
        {canInterrupt && (
          <button
            className={styles.ghostBtn}
            disabled={!running || pending}
            onClick={() => {
              void run('interrupt')
            }}
          >
            Kes
          </button>
        )}
        <button
          className={canInterrupt && interruptStuck ? styles.stopEmphasized : styles.primaryBtn}
          disabled={!running || pending}
          onClick={() => {
            void run('stop')
          }}
        >
          Durdur
        </button>
      </div>
      {error && <p className={styles.error}>{error}</p>}
      {fill != null && (
        <div className={styles.contextMeter} title={`${contextTokens} / ${contextWindow} token`}>
          <div className={styles.meterFill} style={{ width: `${fill}%` }} />
          <span className={styles.meterText}>Bağlam %{fill}</span>
        </div>
      )}
    </div>
  )
}
