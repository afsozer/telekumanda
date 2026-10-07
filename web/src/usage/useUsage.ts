// Kullanım verisini çeken ortak kanca. Hem tam ekran (UsageScreen) hem sohbet
// şeridindeki düğme (UsageChip) bunu kullanır.
//
// İkisi ayrı ayrı çekiyor ve bu KASITLI: köprü zaten 5 dakikalık önbellek
// tutuyor, yani ikinci çağrı sağlayıcıya gitmiyor. Paylaşılan bir context
// kurmak, kazandırdığından fazla bağ yaratırdı.

import { useCallback, useEffect, useState } from 'react'
import { fetchUsage, type UsageGroup } from './usageApi'

/** Otomatik tazeleme aralığı; köprünün limit önbelleğiyle aynı. */
const REFRESH_MS = 5 * 60 * 1000

export interface UsageState {
  groups: UsageGroup[]
  note: string
  loading: boolean
  error: string | null
  /** `force` köprüdeki önbellekleri atlar — "Yenile" tuşu bunu true geçmeli. */
  reload: (force?: boolean) => void
}

export function useUsage(enabled = true): UsageState {
  const [groups, setGroups] = useState<UsageGroup[]>([])
  const [note, setNote] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)
  const [force, setForce] = useState(false)

  const reload = useCallback((next = false) => {
    setForce(next)
    setTick((n) => n + 1)
  }, [])

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    setLoading(true)
    void fetchUsage(force, controller.signal)
      .then((data) => {
        if (controller.signal.aborted) return
        setGroups(data.groups ?? [])
        setNote(data.note ?? '')
        setError(null)
      })
      .catch((err) => {
        if (controller.signal.aborted) return
        setError(err instanceof Error ? err.message : String(err))
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [enabled, tick, force])

  // Periyodik tazeleme force'SUZ: pencere açık dururken sağlayıcıyı 5 dakikada
  // bir dövmenin anlamı yok, köprünün önbelleği zaten o kadar yaşıyor.
  useEffect(() => {
    if (!enabled) return
    const timer = setInterval(() => {
      setForce(false)
      setTick((n) => n + 1)
    }, REFRESH_MS)
    return () => clearInterval(timer)
  }, [enabled])

  return { groups, note, loading, error, reload }
}
