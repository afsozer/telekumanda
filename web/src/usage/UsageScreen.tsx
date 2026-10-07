// Kullanım ekranı — sağlayıcı limitleri ve yenilenme pencereleri.
// Android'deki HubUsageScreen'in karşılığı.

import { creditAbundant, formatReset, remainingPercent, type UsageBucket, type UsageGroup } from './usageApi'
import { useUsage } from './useUsage'
import styles from './Usage.module.css'

/**
 * Az kalanı göze sokar; eşikler Android'deki renk kademeleriyle aynı fikirde.
 * Bakiye kartında 10 $ üstü ayrı tonla çizilir: çubuk dolu ama "kota bitmek
 * üzere" yeşiliyle karışmasın.
 */
function tone(bucket: UsageBucket): string {
  if (creditAbundant(bucket)) return styles.plenty
  const fraction = bucket.remainingFraction
  if (fraction <= 0.1) return styles.critical
  if (fraction <= 0.25) return styles.low
  return styles.ok
}

export function BucketBar({ bucket }: { bucket: UsageBucket }) {
  const percent = remainingPercent(bucket)
  const reset = formatReset(bucket.resetTime)
  return (
    <div className={styles.bucket}>
      <div className={styles.bucketHead}>
        <span className={styles.bucketLabel}>{bucket.label}</span>
        {/* Köprünün hazır metni ("%34 kaldı") — biçimi burada uydurmuyoruz. */}
        <span className={styles.bucketValue}>{bucket.value || `%${percent} kaldı`}</span>
      </div>
      <div
        className={styles.track}
        role="progressbar"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={bucket.label}
      >
        <div className={`${styles.fill} ${tone(bucket)}`} style={{ width: `${percent}%` }} />
      </div>
      <div className={styles.bucketFoot}>
        {bucket.description && <span>{bucket.description}</span>}
        {reset && <span className={styles.reset}>yenilenme: {reset}</span>}
      </div>
    </div>
  )
}

function GroupCard({ group }: { group: UsageGroup }) {
  return (
    <section className={styles.card}>
      <header className={styles.cardHead}>
        <h2 className={styles.cardTitle}>{group.name}</h2>
        {group.stale && (
          <span className={styles.stale} title="Köprü bu grubu tazeleyemedi; sayı eski olabilir">
            eski
          </span>
        )}
      </header>
      {group.description && <p className={styles.cardDesc}>{group.description}</p>}
      {group.buckets?.length ? (
        group.buckets.map((bucket) => <BucketBar key={bucket.id} bucket={bucket} />)
      ) : (
        <p className={styles.muted}>Bu sağlayıcı limit bildirmiyor.</p>
      )}
    </section>
  )
}

export function UsageScreen() {
  const { groups, note, loading, error, reload } = useUsage()

  return (
    <div className={styles.shell}>
      <header className={styles.bar}>
        <h1 className={styles.brand}>Kullanım</h1>
        <span className={styles.note}>
          {note || 'Sağlayıcı limitleri ve yenilenme pencereleri'}
        </span>
        <span className={styles.spacer} />
        {/*
          force=true ŞART: köprüde hesap başına 5 dk'lık limit önbelleği var ve
          force'suz "Yenile" o süre boyunca aynı sayıları geri getiriyordu.
        */}
        <button type="button" disabled={loading} onClick={() => reload(true)}>
          {loading ? 'Yenileniyor…' : 'Yenile'}
        </button>
      </header>

      {error && <p className={styles.error}>Kullanım alınamadı: {error}</p>}

      <div className={styles.body}>
        {groups.length === 0 && !loading && !error ? (
          <p className={styles.muted}>Limit bildiren sağlayıcı yok.</p>
        ) : (
          <div className={styles.grid}>
            {groups.map((group) => (
              <GroupCard key={group.name} group={group} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
