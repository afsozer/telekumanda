import { beforeEach, describe, expect, it, vi } from 'vitest'
import { forkFromMessage, rewindTo } from './forkApi'

let calls: { url: string; body: Record<string, unknown> }[] = []
let queue: (Response | Error)[] = []

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

beforeEach(() => {
  calls = []
  queue = []
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    calls.push({ url, body: JSON.parse(String(init?.body ?? '{}')) })
    const next = queue.shift()
    if (next instanceof Error) return Promise.reject(next)
    return Promise.resolve(next ?? json(200, { ok: true }))
  })
})

describe('forkFromMessage', () => {
  it('gövde {sessionId, dropUserTurns}, yanıt sessionId döner', async () => {
    queue.push(json(200, { ok: true, sessionId: 'yeni-1' }))
    const id = await forkFromMessage('opencode2-app', 'ses-1', 3)
    expect(id).toBe('yeni-1')
    expect(calls[0]).toMatchObject({
      url: '/opencode2-app/fork-from',
      body: { sessionId: 'ses-1', dropUserTurns: 3 },
    })
  })

  it('ok:false ya da sessionId yoksa hata fırlatır', async () => {
    queue.push(json(200, { ok: false, error: 'tur sürerken çatallanamaz' }))
    await expect(forkFromMessage('opencode2-app', 'ses-1', 1)).rejects.toThrow(
      'tur sürerken çatallanamaz',
    )
    queue.push(json(200, { ok: true }))
    await expect(forkFromMessage('opencode2-app', 'ses-1', 1)).rejects.toThrow('Çatallanamadı')
  })
})

describe('rewindTo', () => {
  it('gövde {sessionId, dropUserTurns}', async () => {
    await rewindTo('claude-app', 'ses-1', 2)
    expect(calls[0]).toMatchObject({
      url: '/claude-app/rewind',
      body: { sessionId: 'ses-1', dropUserTurns: 2 },
    })
  })

  it('ok:false gövdesi hata fırlatır', async () => {
    queue.push(json(200, { ok: false, error: 'geri dönülecek mesaj bulunamadı' }))
    await expect(rewindTo('claude-app', 'ses-1', 5)).rejects.toThrow(
      'geri dönülecek mesaj bulunamadı',
    )
  })
})
