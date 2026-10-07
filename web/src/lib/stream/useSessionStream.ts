import { useCallback, useEffect, useRef, useState } from 'react'
import { emptyState, type SessionState } from './protocol'
import { SessionStream, type SocketFactory } from './sessionStream'

export interface SessionStreamResult {
  state: SessionState
  connected: boolean
  /** Akış hatası (ör. "session not found"). Yeni oturum açılınca temizlenir. */
  error: string | null
  /** Köprü akışı bitirdi (oturum kapandı). */
  ended: boolean
  /** Soketi anında tazele. Normalde gerekmez; sekme görünürlüğü zaten sürüyor. */
  nudge: () => void
}

/**
 * Bir oturumun canlı akışını React'e bağlar.
 *
 * `backend` ya da `sessionId` boşsa akış açılmaz (henüz oturum seçilmemiş hâli).
 * Sekme öne geldiğinde ve ağ geri geldiğinde soket koşulsuz tazelenir: arka
 * planda soket yarı-açık kalabiliyor — istemci "bağlı" sanıp veri akmıyor.
 */
export function useSessionStream(
  backend: string,
  sessionId: string,
  createSocket?: SocketFactory,
): SessionStreamResult {
  const [state, setState] = useState<SessionState>(emptyState)
  const [connected, setConnected] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [ended, setEnded] = useState(false)
  const streamRef = useRef<SessionStream | null>(null)

  useEffect(() => {
    const stream = new SessionStream(
      {
        onState: (_id, next) => setState(next),
        onEnd: () => setEnded(true),
        onError: (_id, message) => setError(message),
        onConnectionChange: setConnected,
      },
      createSocket,
    )
    streamRef.current = stream
    return () => {
      stream.close()
      streamRef.current = null
    }
  }, [createSocket])

  useEffect(() => {
    const stream = streamRef.current
    if (!stream) return
    setState(emptyState())
    setError(null)
    setEnded(false)
    if (!backend || !sessionId) {
      stream.close()
      return
    }
    stream.open(backend, sessionId)
    // Oturuma dönülüyorsa SessionStream satırları ve meta'yı LRU duraktan
    // anında geri yükler. Yeni bir sunucu deltası gelmesini beklemeden React'e
    // yansıt; effort/model kontrolleri aksi halde ilk seçeneğe düşmüş görünür.
    setState(stream.currentState)
  }, [backend, sessionId])

  const nudge = useCallback(() => streamRef.current?.nudge(), [])

  useEffect(() => {
    if (!backend || !sessionId) return
    const wake = () => {
      if (document.visibilityState === 'visible') streamRef.current?.nudge()
    }
    document.addEventListener('visibilitychange', wake)
    window.addEventListener('online', wake)
    window.addEventListener('focus', wake)
    return () => {
      document.removeEventListener('visibilitychange', wake)
      window.removeEventListener('online', wake)
      window.removeEventListener('focus', wake)
    }
  }, [backend, sessionId])

  return { state, connected, error, ended, nudge }
}
