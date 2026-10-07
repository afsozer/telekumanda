import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { newRequestId, sendPrompt } from './promptApi'

interface Call {
  url: string
  body: Record<string, unknown>
}

let calls: Call[] = []

function respond(queue: (Response | Error)[]) {
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    calls.push({ url, body: JSON.parse(String(init?.body ?? '{}')) })
    const next = queue.shift()
    if (next instanceof Error) return Promise.reject(next)
    return Promise.resolve(next as Response)
  })
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const req = { sessionId: 'o1', text: 'selam' }
const fast = { waitMs: () => 0 }

beforeEach(() => {
  calls = []
})

afterEach(() => vi.unstubAllGlobals())

describe('newRequestId', () => {
  it('randomUUID varsa onu kullanır', () => {
    vi.stubGlobal('crypto', { randomUUID: () => 'uuid-1' })
    expect(newRequestId()).toBe('uuid-1')
  })

  it('randomUUID yokken getRandomValues ile UUID biçiminde üretir', () => {
    // Tailscale IP’si üzerinden düz http = güvenli olmayan bağlam;
    // randomUUID orada TANIMSIZ. getRandomValues çalışmaya devam ediyor.
    vi.stubGlobal('crypto', {
      getRandomValues: (arr: Uint8Array) => {
        arr.fill(0xab)
        return arr
      },
    })
    const id = newRequestId()
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })

  it('hiçbiri yoksa da bir kimlik üretir', () => {
    vi.stubGlobal('crypto', {})
    expect(newRequestId().length).toBeGreaterThan(8)
  })

  it('ardışık çağrılar farklı kimlik verir', () => {
    expect(newRequestId()).not.toBe(newRequestId())
  })
})

describe('sendPrompt', () => {
  it('doğru uca doğru gövdeyle gider', async () => {
    respond([json(200, { ok: true })])
    await sendPrompt({ ...req, model: 'opus', permissionMode: 'plan' }, fast)

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/claude-app/prompt')
    expect(calls[0].body.sessionId).toBe('o1')
    expect(calls[0].body.text).toBe('selam')
    expect(calls[0].body.model).toBe('opus')
    expect(calls[0].body.permissionMode).toBe('plan')
    expect(typeof calls[0].body.requestId).toBe('string')
  })

  it('verilmeyen model ve izin kipi gövdeye KONMAZ', async () => {
    respond([json(200, { ok: true })])
    await sendPrompt(req, fast)
    expect('model' in calls[0].body).toBe(false)
    expect('permissionMode' in calls[0].body).toBe(false)
  })

  it('backend değiştirilebilir', async () => {
    respond([json(200, { ok: true })])
    await sendPrompt(req, { ...fast, backend: 'codex-app' })
    expect(calls[0].url).toBe('/codex-app/prompt')
  })

  it('ağ hatasında AYNI requestId ile tekrar dener', async () => {
    // Yanıt hiç gelmedi: teslim edilip edilmediği bilinmiyor. Yeni id
    // üretmek mesajı ikilerdi; köprünün tekilleştirmesi aynı id ile çalışır.
    respond([new Error('network'), json(200, { ok: true })])
    await sendPrompt(req, fast)

    expect(calls).toHaveLength(2)
    expect(calls[1].body.requestId).toBe(calls[0].body.requestId)
  })

  it('409 alınca YENİ requestId ile gönderir', async () => {
    // Köprü "bu istek güvenle teslim edilmedi, yeni istek olarak gönder" diyor.
    respond([
      json(409, { ok: false, duplicate: true, error: 'prompt was not safely delivered' }),
      json(200, { ok: true }),
    ])
    await sendPrompt(req, fast)

    expect(calls).toHaveLength(2)
    expect(calls[1].body.requestId).not.toBe(calls[0].body.requestId)
  })

  it('200 + duplicate:true tekrar GÖNDERMEZ, başarı sayar', async () => {
    respond([json(200, { ok: true, duplicate: true })])
    const result = await sendPrompt(req, fast)

    expect(calls).toHaveLength(1)
    expect(result.duplicate).toBe(true)
  })

  it('kalıcı hatada (400) tekrar denemez', async () => {
    respond([json(400, { ok: false, error: 'session not found' })])
    await expect(sendPrompt(req, fast)).rejects.toThrow('session not found')
    expect(calls).toHaveLength(1)
  })

  it('200 ama gövdede ok:false ise hata fırlatır', async () => {
    respond([json(200, { ok: false, error: 'oturum meşgul' })])
    await expect(sendPrompt(req, fast)).rejects.toThrow('oturum meşgul')
  })

  it('deneme hakkı bitince son hatayı fırlatır', async () => {
    respond([new Error('network'), new Error('network'), new Error('network')])
    await expect(sendPrompt(req, fast)).rejects.toThrow('network')
    expect(calls).toHaveLength(3)
  })

  it('köprünün uyarısı taşınır', async () => {
    respond([json(200, { ok: true, warning: 'app-server geç yanıtladı' })])
    const result = await sendPrompt(req, fast)
    expect(result.warning).toBe('app-server geç yanıtladı')
  })
})
