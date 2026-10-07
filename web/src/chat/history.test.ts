import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchOlderRows, prependRows } from './history'
import type { StreamRow } from '../lib/stream/protocol'

function rows(...ids: string[]): StreamRow[] {
  return ids.map((rowId, i) => ({ rowId, role: 'agent', text: rowId, time: '', thoughtIndex: i }))
}

afterEach(() => vi.unstubAllGlobals())

describe('fetchOlderRows', () => {
  it('sessionId, before ve limit ile doğru uca gider', async () => {
    let seen = ''
    vi.stubGlobal('fetch', (url: string) => {
      seen = url
      return Promise.resolve(
        new Response(JSON.stringify({ messages: [] }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      )
    })

    await fetchOlderRows('o1', 'o1:row:50', { limit: 200 })
    const params = new URL(seen, 'http://x').searchParams
    expect(new URL(seen, 'http://x').pathname).toBe('/claude-app/conversation')
    expect(params.get('sessionId')).toBe('o1')
    expect(params.get('before')).toBe('o1:row:50')
    expect(params.get('limit')).toBe('200')
  })

  it('beforeRowId boşken before parametresi gönderilmez', async () => {
    let seen = ''
    vi.stubGlobal('fetch', (url: string) => {
      seen = url
      return Promise.resolve(
        new Response(JSON.stringify({ messages: [] }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      )
    })

    await fetchOlderRows('o1', '')
    expect(new URL(seen, 'http://x').searchParams.has('before')).toBe(false)
  })

  it('gelen mesajları StreamRow’a çevirir', async () => {
    vi.stubGlobal('fetch', () =>
      Promise.resolve(
        new Response(
          JSON.stringify({ messages: [{ rowId: 'r1', role: 'user', text: 'eski' }] }),
          { status: 200, headers: { 'content-type': 'application/json' } },
        ),
      ),
    )

    const result = await fetchOlderRows('o1', 'r5')
    expect(result).toEqual([{ rowId: 'r1', role: 'user', text: 'eski', time: '', thoughtIndex: -1 }])
  })
})

describe('prependRows', () => {
  it('eski sayfayı öne ekler', () => {
    expect(prependRows(rows('a', 'b'), rows('c', 'd')).map((r) => r.rowId)).toEqual([
      'a', 'b', 'c', 'd',
    ])
  })

  it('ÖRTÜŞEN satırları elemez — mevcut olan kazanır', () => {
    // Akış penceresi ile sayfanın kuyruğu aynı satırları taşıyabiliyor.
    // Elenmezse yukarı kaydırma sohbeti ikilerdi.
    const result = prependRows(rows('a', 'b', 'c'), rows('b', 'c', 'd'))
    expect(result.map((r) => r.rowId)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('tamamen örtüşen sayfa listeyi değiştirmez', () => {
    const current = rows('a', 'b')
    expect(prependRows(rows('a', 'b'), current)).toBe(current)
  })

  it('boş sayfa listeyi değiştirmez', () => {
    const current = rows('a')
    expect(prependRows([], current)).toBe(current)
  })

  it('mevcut liste boşken sayfayı olduğu gibi verir', () => {
    expect(prependRows(rows('a', 'b'), []).map((r) => r.rowId)).toEqual(['a', 'b'])
  })
})
