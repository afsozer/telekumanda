import { afterEach, describe, expect, it, vi } from 'vitest'
import { setActiveToken } from '../lib/api'
import { searchGlobal } from './searchApi'

afterEach(() => {
  vi.unstubAllGlobals()
  setActiveToken(null)
})

describe('searchGlobal', () => {
  it('sorguyu ve limiti kodlayıp sonuçları döndürür', async () => {
    setActiveToken('test-token')
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      ok: true,
      query: 'iş hukuku',
      truncated: false,
      hits: [{ id: '1', type: 'message', title: 'Dava' }],
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await searchGlobal(' iş hukuku ', undefined, 25)

    expect(result.hits).toHaveLength(1)
    expect(fetchMock).toHaveBeenCalledWith(
      '/search/global?q=i%C5%9F+hukuku&limit=25',
      expect.objectContaining({ headers: { authorization: 'Bearer test-token' } }),
    )
  })
})
