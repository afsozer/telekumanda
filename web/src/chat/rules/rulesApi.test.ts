import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  deleteInstruction,
  deleteSavedPermission,
  fetchInstructions,
  fetchSavedPermissions,
  putInstruction,
} from './rulesApi'

let calls: { method: string; url: string; body: unknown }[] = []
let queue: (Response | Error)[] = []

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

beforeEach(() => {
  calls = []
  queue = []
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    calls.push({
      method: init?.method ?? 'GET',
      url,
      body: init?.body === undefined ? undefined : JSON.parse(String(init.body)),
    })
    const next = queue.shift()
    if (next instanceof Error) return Promise.reject(next)
    return Promise.resolve(next ?? json(200, { ok: true }))
  })
})

describe('fetchInstructions', () => {
  it('entries alanını listeye çevirir', async () => {
    queue.push(json(200, { ok: true, entries: [{ key: 'uyap-bicimi', value: 'UYAP biçimi' }] }))
    const list = await fetchInstructions('opencode2-app', 'ses_1')
    expect(list).toEqual([{ key: 'uyap-bicimi', value: 'UYAP biçimi' }])
    expect(calls[0].url).toBe('/opencode2-app/instructions?session=ses_1')
  })

  it('key alanı boş olanlar elenir', async () => {
    queue.push(json(200, { ok: true, entries: [{ key: '', value: 'x' }, { key: 'a', value: 'b' }] }))
    const list = await fetchInstructions('opencode2-app', 'ses_1')
    expect(list).toHaveLength(1)
  })

  it('ok:false gövdesi hata fırlatır (200 dönmüş olsa bile)', async () => {
    queue.push(json(200, { ok: false, error: 'oturum bulunamadı' }))
    await expect(fetchInstructions('opencode2-app', 'ses_1')).rejects.toThrow('oturum bulunamadı')
  })
})

describe('putInstruction / deleteInstruction', () => {
  it('PUT gövdesi {sessionId, key, value}', async () => {
    await putInstruction('opencode2-app', 'ses_1', 'kural', 'değer')
    expect(calls[0]).toMatchObject({
      method: 'POST',
      url: '/opencode2-app/instructions',
      body: { sessionId: 'ses_1', key: 'kural', value: 'değer' },
    })
  })

  it('DELETE gövdeli gider — POST değil', async () => {
    await deleteInstruction('opencode2-app', 'ses_1', 'kural')
    expect(calls[0]).toMatchObject({
      method: 'DELETE',
      url: '/opencode2-app/instructions',
      body: { sessionId: 'ses_1', key: 'kural' },
    })
  })
})

describe('fetchSavedPermissions / deleteSavedPermission', () => {
  it('rules alanını listeye çevirir', async () => {
    queue.push(
      json(200, {
        ok: true,
        rules: [{ id: 'perm_1', projectID: 'p', action: 'bash', resource: 'npm test', created: 5 }],
      }),
    )
    const list = await fetchSavedPermissions('opencode2-app')
    expect(list).toEqual([
      { id: 'perm_1', projectID: 'p', action: 'bash', resource: 'npm test', created: 5 },
    ])
    expect(calls[0].url).toBe('/opencode2-app/permissions/saved')
  })

  it('silme ucuna id gider', async () => {
    await deleteSavedPermission('opencode2-app', 'perm_1')
    expect(calls[0]).toMatchObject({
      method: 'POST',
      url: '/opencode2-app/permissions/saved/delete',
      body: { id: 'perm_1' },
    })
  })
})
