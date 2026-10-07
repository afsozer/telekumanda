import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  approve,
  fetchEfforts,
  fetchModels,
  interrupt,
  setEffort,
  setModel,
  setPermissionMode,
  stop,
} from './controlsApi'

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } })
}

function stubFetch(handler: (url: string, init?: RequestInit) => Response) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

const bodyOf = (init?: RequestInit): Record<string, unknown> => JSON.parse(String(init?.body))

afterEach(() => vi.unstubAllGlobals())

describe('fetchModels', () => {
  it('GET /claude-app/models çağırır, listeyi ve varsayılanı döndürür', async () => {
    const fn = stubFetch(() =>
      jsonResponse({ models: [{ id: 'sonnet', label: 'Sonnet' }, { id: 'opus' }], defaultModel: 'sonnet' }),
    )
    const result = await fetchModels('claude-app')
    expect(fn).toHaveBeenCalledTimes(1)
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/models')
    expect(init?.body).toBeUndefined()
    expect(result.models).toEqual([
      { id: 'sonnet', label: 'Sonnet' },
      { id: 'opus' },
    ])
    expect(result.defaultModel).toBe('sonnet')
  })
})

describe('setModel', () => {
  it('POST /claude-app/model — gövde { sessionId, model }, dönüş model', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true, model: 'opus' }))
    const result = await setModel('claude-app', 's1', 'sonnet')
    expect(result).toBe('opus')
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/model')
    expect(init?.method).toBe('POST')
    expect(bodyOf(init)).toEqual({ sessionId: 's1', model: 'sonnet' })
  })

  it('ok:false yanıtı sessizce yutulmaz — hata fırlatır', async () => {
    stubFetch(() => jsonResponse({ ok: false, error: 'session not found' }))
    await expect(setModel('claude-app', 's1', 'sonnet')).rejects.toThrow('session not found')
  })
})

describe('fetchEfforts', () => {
  it('GET /claude-app/efforts çağırır ve listeyi döndürür', async () => {
    const fn = stubFetch(() => jsonResponse({ efforts: ['low', 'medium', 'high'] }))
    expect(await fetchEfforts('claude-app')).toEqual(['low', 'medium', 'high'])
    expect(fn.mock.calls[0][0]).toBe('/claude-app/efforts')
  })

  it('efforts alanı yoksa boş liste döner', async () => {
    stubFetch(() => jsonResponse({}))
    expect(await fetchEfforts('claude-app')).toEqual([])
  })
})

describe('setEffort', () => {
  it('POST /claude-app/effort — gövde { sessionId, effort }, dönüş effort', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true, effort: 'high' }))
    const result = await setEffort('claude-app', 's1', 'medium')
    expect(result).toBe('high')
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/effort')
    expect(init?.method).toBe('POST')
    expect(bodyOf(init)).toEqual({ sessionId: 's1', effort: 'medium' })
  })

  it('ok:false yanıtında hata fırlatır', async () => {
    stubFetch(() => jsonResponse({ ok: false, error: 'geçersiz effort' }))
    await expect(setEffort('claude-app', 's1', 'ultra')).rejects.toThrow('geçersiz effort')
  })
})

describe('setPermissionMode', () => {
  it('POST /claude-app/permission-mode — gövde { sessionId, mode }, dönüş permissionMode', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true, permissionMode: 'plan' }))
    const result = await setPermissionMode('claude-app', 's1', 'acceptEdits')
    expect(result).toBe('plan')
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/permission-mode')
    expect(init?.method).toBe('POST')
    expect(bodyOf(init)).toEqual({ sessionId: 's1', mode: 'acceptEdits' })
  })

  it('ok:false yanıtında hata fırlatır', async () => {
    stubFetch(() => jsonResponse({ ok: false, error: 'session not found' }))
    await expect(setPermissionMode('claude-app', 's1', 'plan')).rejects.toThrow('session not found')
  })
})

describe('approve', () => {
  it('onaylanan isteğin kimliği gövdeye girer', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    await approve('codex-app', 's1', true, undefined, 'req-7')
    expect(bodyOf(fn.mock.calls[0][1])).toEqual({ sessionId: 's1', allow: true, requestId: 'req-7' })
  })

  it('bayat onay (409) anlaşılır bir hata verir', async () => {
    stubFetch(() => jsonResponse({ ok: false, error: 'stale approval', stale: true }, 409))
    await expect(approve('claude-app', 's1', true, undefined, 'eski')).rejects.toThrow(/artık geçerli değil/)
  })

  it('POST /claude-app/approve — gövde { sessionId, allow }', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    await approve('claude-app', 's1', true)
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/approve')
    expect(init?.method).toBe('POST')
    expect(bodyOf(init)).toEqual({ sessionId: 's1', allow: true })
  })

  it('cevaplar { id, optionId, label } biçiminde gider', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    await approve('claude-app', 's1', true, [
      { id: 'q1', optionId: 'A', label: 'Evet' },
      { id: 'q1', optionId: 'C', label: 'Belki' },
    ])
    expect(bodyOf(fn.mock.calls[0][1])).toEqual({
      sessionId: 's1',
      allow: true,
      answers: [
        { id: 'q1', optionId: 'A', label: 'Evet' },
        { id: 'q1', optionId: 'C', label: 'Belki' },
      ],
    })
  })

  it('boş cevap listesi gövdeye answers olarak konmaz (Kotlin ile aynı)', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    await approve('claude-app', 's1', false, [])
    const body = bodyOf(fn.mock.calls[0][1])
    expect(body).toEqual({ sessionId: 's1', allow: false })
    expect('answers' in body).toBe(false)
  })

  it('ok:false yanıtında hata fırlatır', async () => {
    stubFetch(() => jsonResponse({ ok: false, error: 'tüm sorular cevaplanmalı' }))
    await expect(approve('claude-app', 's1', true)).rejects.toThrow('tüm sorular cevaplanmalı')
  })
})

describe('interrupt ve stop', () => {
  it('interrupt POST /claude-app/interrupt — gövde { sessionId }', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    await interrupt('claude-app', 's1')
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/interrupt')
    expect(init?.method).toBe('POST')
    expect(bodyOf(init)).toEqual({ sessionId: 's1' })
  })

  it('interrupt HTTP 200 + ok:false ile hata bildirir (HTTP katmanı yakalayamaz)', async () => {
    stubFetch(() => jsonResponse({ ok: false, error: 'session not found' }))
    await expect(interrupt('claude-app', 's1')).rejects.toThrow('session not found')
  })

  it('stop POST /claude-app/stop — gövde { sessionId }', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    await stop('claude-app', 's1')
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/stop')
    expect(init?.method).toBe('POST')
    expect(bodyOf(init)).toEqual({ sessionId: 's1' })
  })

  it('stop ok:false döndüğünde hata fırlatır', async () => {
    stubFetch(() => jsonResponse({ ok: false, error: 'not found' }))
    await expect(stop('claude-app', 's1')).rejects.toThrow('not found')
  })
})
