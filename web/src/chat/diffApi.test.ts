import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchSessionDiff } from './diffApi'

let calls: { url: string }[] = []
let queue: (Response | Error)[] = []

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

beforeEach(() => {
  calls = []
  queue = []
  vi.stubGlobal('fetch', (url: string) => {
    calls.push({ url })
    const next = queue.shift()
    if (next instanceof Error) return Promise.reject(next)
    return Promise.resolve(next ?? json(200, { ok: true }))
  })
})

describe('fetchSessionDiff', () => {
  it('files alanını tipli listeye çevirir', async () => {
    queue.push(
      json(200, {
        ok: true,
        cwd: 'C:\\x',
        files: [
          { path: 'a/b.kt', patch: '@@ -1 +1 @@', additions: 3, deletions: 1, status: 'modified' },
        ],
        additions: 3,
        deletions: 1,
        turns: 2,
      }),
    )
    const diff = await fetchSessionDiff('opencode2-app', 'ses-1')
    expect(diff.files).toEqual([
      {
        path: 'a/b.kt',
        patch: '@@ -1 +1 @@',
        additions: 3,
        deletions: 1,
        status: 'modified',
        truncated: false,
      },
    ])
    expect(calls[0].url).toBe('/opencode2-app/diff?session=ses-1')
  })

  it('yolsuz kayıt elenir, eksik alanlar savunmayla dolar', async () => {
    queue.push(json(200, { ok: true, files: [{ patch: 'p' }, { path: 'x.ts' }] }))
    const diff = await fetchSessionDiff('opencode-app', 'ses-1')
    expect(diff.files).toHaveLength(1)
    expect(diff.files[0]).toMatchObject({ path: 'x.ts', additions: 0, deletions: 0 })
  })

  it('ok:false gövdesi hata fırlatır', async () => {
    queue.push(json(200, { ok: false, error: 'session not found' }))
    await expect(fetchSessionDiff('opencode2-app', 'ses-1')).rejects.toThrow('session not found')
  })

  it('sessionId URL kodlanır', async () => {
    await fetchSessionDiff('opencode2-app', 'a b/c')
    expect(calls[0].url).toBe('/opencode2-app/diff?session=a%20b%2Fc')
  })
})
