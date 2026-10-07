// Kalan kullanım — `/usage` ucunun tipli sarmalayıcısı.
//
// Şekil KÖPRÜDEN doğrulandı (11.08.2026), Android'in veri sınıfından değil:
// `UsageBucket` orada `window`, `metered` ve grupta `source` alanlarını da
// okuyor ama canlı yanıtta bunlar YOK (Kotlin tarafı optString varsayılanına
// düşüyor). Burada yalnız gerçekten gelen alanlar tanımlı.
//
// Kaynak, `claude /usage` ve Codex app ile AYNI: köprü sayıları kendisi
// hesaplamıyor, sağlayıcıdan okuyor.

import { apiGet } from '../lib/api'

export interface UsageBucket {
  /** `claude-5h`, `claude-week`, `codex-primary` … */
  id: string
  label: string
  /** Köprünün hazır metni, ör. "%34 kaldı". Kendin biçimlendirme. */
  value: string
  description: string
  /** 0..1 arası KALAN oran. İlerleme çubuğu bunu kullanır. */
  remainingFraction: number
  /** ISO zaman; pencere ne zaman sıfırlanacak. */
  resetTime: string
  /**
   * Ön ödemeli bakiye kartlarında (Nano-GPT, DeepSeek, OpenRouter) USD tutar.
   * Köprü çubuğu 10 $ = dolu ölçeğiyle gönderir; kota kovalarında alan yok.
   */
  creditUsd?: number
}

/** Köprüyle aynı ölçek: 10 $ dolu çubuk. Üstü "bol" — ayrı renkle çizilir. */
export const CREDIT_BAR_FULL_USD = 10

export function creditAbundant(bucket: Pick<UsageBucket, 'creditUsd'>): boolean {
  return (bucket.creditUsd ?? 0) > CREDIT_BAR_FULL_USD
}

export interface UsageGroup {
  name: string
  description: string
  /** Köprü bu grubu tazeleyemedi, gösterilen sayı eski olabilir. */
  stale?: boolean
  buckets: UsageBucket[]
}

export interface UsageResult {
  groups: UsageGroup[]
  note: string
}

/**
 * `force` = kullanıcı "Yenile"ye bastı: köprüdeki grup önbelleği VE hesap
 * başına 5 dk'lık limit önbelleği atlanır. Otomatik yüklemeler force'suz
 * gitmeli, yoksa her açılışta sağlayıcıya gidilir.
 */
export function fetchUsage(force = false, signal?: AbortSignal): Promise<UsageResult> {
  return apiGet<UsageResult>(force ? '/usage?force=1' : '/usage', signal)
}

/** Tüm gruplardaki bucket'ları tek düzleme indirir. */
export function allBuckets(groups: UsageGroup[]): UsageBucket[] {
  return groups.flatMap((group) => group.buckets ?? [])
}

/**
 * Şeritte gösterilecek bucket'ı seçer: verilen öneklere uyanlar arasından EN AZ
 * kalanı. "En az kalan" doğru ölçü — kullanıcıyı ilk hangi pencerenin
 * durduracağını söyler; 5 saatlik %90 iken haftalık %12 ise sıkıntı haftalıkta.
 *
 * Önek eşleşmesi bucket id'si üzerinden: `claude` ve `codex` id'lerin başında
 * duruyor. Hiçbiri tutmazsa TÜM bucket'lara düşer —
 * yanlış bir sayı göstermektense ilgisiz ama doğru bir sayı göstermek yeğdir,
 * ve etiket zaten hangisi olduğunu yazıyor.
 */
export function tightestBucket(
  groups: UsageGroup[],
  prefixes: string[] = [],
): UsageBucket | null {
  const buckets = allBuckets(groups)
  if (buckets.length === 0) return null
  const scoped = prefixes.length
    ? buckets.filter((bucket) => prefixes.some((prefix) => bucket.id.startsWith(prefix)))
    : []
  const pool = scoped.length ? scoped : buckets
  return pool.reduce((low, bucket) =>
    bucket.remainingFraction < low.remainingFraction ? bucket : low,
  )
}

/** Sohbet şeridi için hangi öneklerin aranacağı. */
export function prefixesFor(backend: string): string[] {
  return backend.startsWith('codex') ? ['codex'] : ['claude']
}

/** "Son 5 saat" → "5sa", "Son 7 gün" → "7g" — şeritte yer dar. */
export function shortLabel(label: string): string {
  const compact = label
    .replace(/^Son\s+/i, '')
    .replace(/\s*saat/i, 'sa')
    .replace(/\s*gün/i, 'g')
    .replace(/\s+/g, '')
  return compact || label
}

/** Kalan yüzde — köprünün hazır metnini ayrıştırmadan orandan hesaplanır. */
export function remainingPercent(bucket: UsageBucket): number {
  return Math.round((bucket.remainingFraction ?? 0) * 100)
}

/**
 * Sıfırlanma zamanı, yerel saatle. Bugünse yalnız saat, değilse gün + saat.
 * Geçersiz/boş ISO'da boş döner — "Invalid Date" basmaktansa hiç basma.
 */
export function formatReset(iso: string, now = new Date()): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const sameDay =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate()
  const time = date.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' })
  if (sameDay) return time
  return `${date.toLocaleDateString('tr-TR', { day: 'numeric', month: 'short' })} ${time}`
}
