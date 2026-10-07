import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchSlashCommands, matchCommands, slashPrefix, type SlashCommand } from './slashApi'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const cmds: SlashCommand[] = [
  { name: 'clear', desc: 'Bağlamı temizle' },
  { name: 'compact', desc: 'Bağlamı sıkıştır' },
  { name: 'devret', desc: 'Dış ajana ver' },
  { name: 'review', desc: 'Kod incelemesi' },
]

afterEach(() => vi.unstubAllGlobals())

describe('slashPrefix', () => {
  it('tek başına eğik çizgi boş önek verir — tüm liste açılır', () => {
    expect(slashPrefix('/')).toBe('')
  })

  it('komut adını çıkarır', () => {
    expect(slashPrefix('/comp')).toBe('comp')
  })

  it('eğik çizgiyle başlamayan metin menü açmaz', () => {
    expect(slashPrefix('selam')).toBeNull()
    expect(slashPrefix(' /clear')).toBeNull()
  })

  it('boşluktan sonra kapanır — komut seçilmiş, argüman yazılıyor', () => {
    expect(slashPrefix('/devret şu işi yap')).toBeNull()
    expect(slashPrefix('/clear ')).toBeNull()
  })

  it('yol benzeri metinde açılmaz', () => {
    // "/c/Users/..." ya da "/api/v2" yazarken menü fırlamasın.
    expect(slashPrefix('/c/Users/Dev')).toBeNull()
    expect(slashPrefix('/tmp\\dosya')).toBeNull()
  })

  it('satır ortasındaki eğik çizgi menü açmaz', () => {
    expect(slashPrefix('ya sonuç 3/4 olur')).toBeNull()
  })
})

describe('matchCommands', () => {
  it('boş önekte hepsini verir', () => {
    expect(matchCommands(cmds, '')).toHaveLength(4)
  })

  it('baştan eşleşenler önce gelir', () => {
    expect(matchCommands(cmds, 'c').map((c) => c.name)).toEqual(['clear', 'compact'])
  })

  it('içinde geçenler de bulunur, kendi sıralarını korur', () => {
    // Hiçbiri 'e' ile BAŞLAMIYOR; üçü de "içinde geçen" kovasında ve
    // listedeki asıl sıralarını koruyorlar.
    expect(matchCommands(cmds, 'e').map((c) => c.name)).toEqual(['clear', 'devret', 'review'])
  })

  it('baştan eşleşen, içinde geçenin ÖNÜNE geçer', () => {
    const liste = [{ name: 'review' }, { name: 'evet' }]
    expect(matchCommands(liste, 'ev').map((c) => c.name)).toEqual(['evet', 'review'])
  })

  it('büyük/küçük harf ayrımı yok', () => {
    expect(matchCommands(cmds, 'COMP').map((c) => c.name)).toEqual(['compact'])
  })

  it('eşleşme yoksa boş döner', () => {
    expect(matchCommands(cmds, 'zzz')).toEqual([])
  })
})

describe('fetchSlashCommands', () => {
  it('backend sorgusuyla /slash ucuna gider', async () => {
    let seen = ''
    vi.stubGlobal('fetch', (url: string) => {
      seen = url
      return Promise.resolve(json({ commands: cmds }))
    })
    const result = await fetchSlashCommands('codex-app')
    expect(new URL(seen, 'http://x').searchParams.get('backend')).toBe('codex-app')
    expect(result).toHaveLength(4)
  })

  it('uç hata verirse boş liste döner — yazmayı engellemez', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve(json({ error: 'yok' }, 500)))
    expect(await fetchSlashCommands('claude-app')).toEqual([])
  })
})
