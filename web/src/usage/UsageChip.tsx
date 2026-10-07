// Sohbetin alt şeridindeki kalan-kullanım düğmesi.
//
// Şeritte tek bir sayı var çünkü yer dar: aktif sağlayıcının (Claude ya da
// Codex) pencerelerinden EN AZ kalanı. Kullanıcıyı ilk hangi pencerenin durduracağını
// söyler — 5 saatlik %90 iken haftalık %12 ise sıkıntı haftalıktadır.
// Tıklanınca bütün gruplar açılır.

import { useEffect, useRef, useState } from 'react'
import { BucketBar } from './UsageScreen'
import { prefixesFor, remainingPercent, shortLabel, tightestBucket } from './usageApi'
import { useUsage } from './useUsage'
import styles from './Usage.module.css'

export interface UsageChipProps {
  /** Aktif backend; Codex'in kendi limiti var, bucket öneki ona göre seçilir. */
  backend: string
}

export function UsageChip({ backend }: UsageChipProps) {
  const [open, setOpen] = useState(false)
  const { groups, loading, error, reload } = useUsage()
  const wrapRef = useRef<HTMLDivElement | null>(null)

  // Dışarı tıklayınca ve Escape ile kapan: açılır kutu şeridin üstünde duruyor
  // ve kapatma yolu olmadan sohbetin bir kısmını örterdi.
  useEffect(() => {
    if (!open) return
    const onDown = (event: MouseEvent) => {
      if (!wrapRef.current?.contains(event.target as Node)) setOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const bucket = tightestBucket(groups, prefixesFor(backend))
  const percent = bucket ? remainingPercent(bucket) : null

  return (
    <div className={styles.chipWrap} ref={wrapRef}>
      <button
        type="button"
        className={styles.chip}
        aria-expanded={open}
        title={bucket ? `${bucket.label}: ${bucket.value}` : 'Kalan kullanım'}
        onClick={() => setOpen((on) => !on)}
      >
        {percent === null
          ? loading
            ? 'kullanım…'
            : 'kullanım'
          : `%${percent} · ${shortLabel(bucket!.label)}`}
      </button>

      {open && (
        <div className={styles.popover} role="dialog" aria-label="Kalan kullanım">
          <div className={styles.popHead}>
            <strong>Kalan kullanım</strong>
            <button type="button" className={styles.link} disabled={loading} onClick={() => reload(true)}>
              {loading ? 'Yenileniyor…' : 'Yenile'}
            </button>
          </div>
          {error && <p className={styles.error}>{error}</p>}
          {groups.length === 0 && !loading && !error && (
            <p className={styles.muted}>Limit bildiren sağlayıcı yok.</p>
          )}
          {groups.map((group) => (
            <div key={group.name} className={styles.popGroup}>
              <div className={styles.popGroupName}>
                {group.name}
                {group.stale && <span className={styles.stale}>eski</span>}
              </div>
              {group.buckets?.map((item) => (
                <BucketBar key={item.id} bucket={item} />
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
