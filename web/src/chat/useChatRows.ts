import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { SessionState, StreamRow } from '../lib/stream/protocol'
import { useSessionStream } from '../lib/stream/useSessionStream'
import { fetchOlderRows, prependRows } from './history'

export interface ChatRows {
  /** Sayfalanmış geçmiş + canlı akış, tek liste. */
  rows: StreamRow[]
  state: SessionState
  connected: boolean
  error: string | null
  /** Geçmişin başındayız; daha eskisi yok. */
  atStart: boolean
  loadingOlder: boolean
  loadOlder: () => void
  /** Global arama deep-link'i için geçmişin tamamını sıralı biçimde yükler. */
  loadAllOlder: () => Promise<StreamRow[]>
}

/**
 * Sohbetin satır kaynağı: canlı akış ile sayfalanan geçmişi birleştirir.
 *
 * Akış yalnız son 4000 satırı taşıyor; daha eskisi `loadOlder()` ile
 * `/conversation` ucundan geliyor (bkz. history.ts).
 */
export function useChatRows(backend: string, sessionId: string): ChatRows {
  const { state, connected, error } = useSessionStream(backend, sessionId)
  const [older, setOlder] = useState<StreamRow[]>([])
  const [atStart, setAtStart] = useState(false)
  const [loadingOlder, setLoadingOlder] = useState(false)
  // Eş zamanlı ikinci isteği engeller; state güncellemesi bir tur gecikiyor
  // ve kaydırma olayı arka arkaya iki kez tetikleyebiliyor.
  const inFlight = useRef(false)
  const allInFlight = useRef<Promise<StreamRow[]> | null>(null)
  const generation = useRef(0)
  const rowsRef = useRef<StreamRow[]>([])

  useEffect(() => {
    generation.current += 1
    setOlder([])
    setAtStart(false)
    setLoadingOlder(false)
    inFlight.current = false
    allInFlight.current = null
  }, [backend, sessionId])

  const rows = useMemo(() => prependRows(older, state.rows), [older, state.rows])
  useEffect(() => { rowsRef.current = rows }, [rows])

  const loadOlder = useCallback(() => {
    if (!sessionId || atStart || inFlight.current) return
    const first = rows[0]
    // Akış henüz hiç satır getirmediyse sayfalayacak bir sınır yok.
    if (!first) return

    inFlight.current = true
    setLoadingOlder(true)
    fetchOlderRows(sessionId, first.rowId, { backend })
      .then((page) => {
        if (page.length === 0) {
          setAtStart(true)
          return
        }
        setOlder((current) => prependRows(page, current))
      })
      .catch(() => {
        // Sayfalama hatası sohbeti bozmamalı; kullanıcı tekrar deneyebilir.
      })
      .finally(() => {
        inFlight.current = false
        setLoadingOlder(false)
      })
  }, [backend, sessionId, atStart, rows])

  const loadAllOlder = useCallback((): Promise<StreamRow[]> => {
    if (!sessionId) return Promise.resolve([])
    if (allInFlight.current) return allInFlight.current
    const startedAt = generation.current
    const task = (async () => {
      let merged = rowsRef.current
      let before = merged[0]?.rowId || ''
      // Köprü sayfayı en çok 500 satırla sınırlar. 100 sayfalık emniyet sınırı
      // sohbetin tamamını pratikte kapsar, bozuk bir cursor'da sonsuz döngüyü önler.
      for (let pageIndex = 0; pageIndex < 100; pageIndex += 1) {
        const page = await fetchOlderRows(sessionId, before, { backend, limit: 500 })
        if (generation.current !== startedAt) return []
        if (page.length === 0) {
          setAtStart(true)
          break
        }
        const next = prependRows(page, merged)
        const nextBefore = next[0]?.rowId || ''
        if (next.length === merged.length || nextBefore === before) break
        merged = next
        before = nextBefore
      }
      if (generation.current === startedAt) {
        setOlder((current) => prependRows(merged, current))
      }
      return merged
    })().finally(() => {
      if (generation.current === startedAt) allInFlight.current = null
    })
    allInFlight.current = task
    return task
  }, [backend, sessionId])

  return { rows, state, connected, error, atStart, loadingOlder, loadOlder, loadAllOlder }
}
