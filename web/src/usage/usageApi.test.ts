import { describe, expect, it } from 'vitest'
import {
  creditAbundant,
  formatReset,
  prefixesFor,
  remainingPercent,
  shortLabel,
  tightestBucket,
  type UsageGroup,
} from './usageApi'

const bucket = (id: string, label: string, remaining: number) => ({
  id,
  label,
  value: `%${Math.round(remaining * 100)} kaldı`,
  description: '',
  remainingFraction: remaining,
  resetTime: '',
})

// Köprüden 11.08.2026'da alınan gerçek şeklin küçültülmüş hâli.
const GROUPS: UsageGroup[] = [
  {
    name: 'Claude Code',
    description: '',
    buckets: [
      bucket('claude-5h', 'Son 5 saat', 0.34),
      bucket('claude-week', 'Son 7 gün', 0.4),
    ],
  },
  {
    name: 'Claude Code (eski kart)',
    description: '',
    buckets: [
      bucket('eski-claude-5h', 'Son 5 saat', 0.9),
      bucket('eski-claude-week', 'Son 7 gün', 0.57),
    ],
  },
  {
    name: 'Codex',
    description: '',
    buckets: [bucket('codex-primary', 'Son 5 saat', 1)],
  },
]

describe('tightestBucket', () => {
  // Şeritte tek sayı var: kullanıcıyı İLK hangi pencerenin durduracağı.
  it('önekle sınırlanan grupta en az kalanı seçer', () => {
    expect(tightestBucket(GROUPS, ['eski'])?.id).toBe('eski-claude-week')
  })

  it('claude önekinde en az kalan pencereyi seçer', () => {
    const seçim = tightestBucket(GROUPS, ['claude'])
    expect(seçim?.id).toBe('claude-5h')
    expect(remainingPercent(seçim!)).toBe(34)
  })

  it('codex kendi limitine bakar', () => {
    expect(tightestBucket(GROUPS, ['codex'])?.id).toBe('codex-primary')
  })

  // Yanlış sayı göstermektense ilgisiz ama DOĞRU bir sayı göster; etiket
  // hangisi olduğunu zaten yazıyor.
  it('önek hiçbir şeye uymazsa tüm bucketlara düşer', () => {
    expect(tightestBucket(GROUPS, ['bilinmeyen'])?.id).toBe('claude-5h')
  })

  it('önek verilmezse tüm bucketlar', () => {
    expect(tightestBucket(GROUPS)?.id).toBe('claude-5h')
  })

  it('veri yoksa null', () => {
    expect(tightestBucket([])).toBeNull()
    expect(tightestBucket([{ name: 'x', description: '', buckets: [] }])).toBeNull()
  })
})

describe('creditAbundant', () => {
  it('10 doların üstü bol', () => {
    expect(creditAbundant({ creditUsd: 10.01 })).toBe(true)
    expect(creditAbundant({ creditUsd: 25 })).toBe(true)
  })

  it('10 dolar ve altı bol değil', () => {
    expect(creditAbundant({ creditUsd: 10 })).toBe(false)
    expect(creditAbundant({ creditUsd: 3.57 })).toBe(false)
    expect(creditAbundant({ creditUsd: 0 })).toBe(false)
  })

  it('kota kovasında alan yok, bol değil', () => {
    expect(creditAbundant({})).toBe(false)
  })
})

describe('prefixesFor', () => {
  it('codex backendinde codex öneki', () => {
    expect(prefixesFor('codex-app')).toEqual(['codex'])
  })

  it('diğer backendlerde claude öneki', () => {
    expect(prefixesFor('claude-app')).toEqual(['claude'])
  })
})

describe('shortLabel', () => {
  it('şeride sığacak hâle getirir', () => {
    expect(shortLabel('Son 5 saat')).toBe('5sa')
    expect(shortLabel('Son 7 gün')).toBe('7g')
  })

  it('tanımadığı etiketi bozmaz', () => {
    expect(shortLabel('Aylık kota')).toBe('Aylıkkota')
  })
})

describe('formatReset', () => {
  const now = new Date('2026-08-11T12:00:00')

  it('aynı günse yalnız saat', () => {
    expect(formatReset(new Date('2026-08-11T15:30:00').toISOString(), now)).toMatch(/^\d{2}:\d{2}$/)
  })

  it('başka günse gün + saat', () => {
    expect(formatReset(new Date('2026-08-14T08:00:00').toISOString(), now)).toMatch(/14/)
  })

  // "Invalid Date" basmaktansa hiç basma.
  it('boş ya da bozuk ISO boş döner', () => {
    expect(formatReset('')).toBe('')
    expect(formatReset('bu tarih değil')).toBe('')
  })
})
