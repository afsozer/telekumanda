import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  archiveSession,
  createSession,
  deleteSession,
  unifySessions,
  listDirs,
  listDiskSessions,
  listRoots,
  listAllSessions,
  mergeCoworkRows,
  listArchivedSessions,
  listSessions,
  pinSession,
  renameSession,
  searchDirs,
  sessionSources,
  unarchiveSession,
  unpinSession,
} from './sessionsApi'
import type { TaggedSession } from './sessionsApi'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

type FetchHandler = (url: URL, init: RequestInit) => Response

function stubFetch(handler: FetchHandler): ReturnType<typeof vi.fn> {
  const mock = vi.fn(
    async (input: RequestInfo | URL, init?: RequestInit) =>
      handler(new URL(String(input), 'http://test'), init ?? {}),
  )
  vi.stubGlobal('fetch', mock)
  return mock
}

function lastCall(mock: ReturnType<typeof vi.fn>): { url: URL; init: RequestInit } {
  const call = mock.mock.calls.at(-1)!
  return { url: new URL(String(call[0]), 'http://test'), init: (call[1] ?? {}) as RequestInit }
}

function bodyOf(init: RequestInit): unknown {
  return JSON.parse(String(init.body))
}

afterEach(() => vi.unstubAllGlobals())

describe('listSessions', () => {
  it('GET ile /claude-app/sessions yoluna gider ve sessions dizisini döner', async () => {
    const session = {
      id: 's1', cwd: 'C:\\x', model: 'sonnet', status: 'idle', title: 'Başlık',
      lastUserAt: 123, turns: 4, lastText: 'metin', awaitingApproval: false, restoredShell: false,
    }
    const mock = stubFetch(() => jsonResponse(200, { sessions: [session] }))

    const result = await listSessions('claude-app')

    const { url, init } = lastCall(mock)
    expect(url.pathname).toBe('/claude-app/sessions')
    expect(init.method ?? 'GET').toBe('GET')
    expect(result).toEqual([session])
  })

  it('sessions alanı yoksa boş liste döner', async () => {
    stubFetch(() => jsonResponse(200, {}))
    await expect(listSessions('claude-app')).resolves.toEqual([])
  })
})

describe('listDiskSessions', () => {
  it('GET ile /claude-app/disk-sessions yoluna gider', async () => {
    const disk = {
      id: 'd1', cwd: 'C:\\x', title: 'Eski', lastText: 'son', turns: 2,
      mtime: 456, pinned: true, archived: false,
    }
    const mock = stubFetch(() => jsonResponse(200, { ok: true, sessions: [disk] }))

    const result = await listDiskSessions('claude-app')

    expect(lastCall(mock).url.pathname).toBe('/claude-app/disk-sessions')
    expect(result).toEqual([disk])
  })
})

describe('createSession', () => {
  it('POST /claude-app/new gövdesinde cwd ve verilen tüm seçenekler olur', async () => {
    const mock = stubFetch(() => jsonResponse(200, { ok: true, sessionId: 's-yeni' }))

    await createSession('claude-app', { cwd: 'C:\\proje', model: 'opus', permissionMode: 'acceptEdits', effort: 'high' })

    const { url, init } = lastCall(mock)
    expect(url.pathname).toBe('/claude-app/new')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ 'content-type': 'application/json' })
    expect(bodyOf(init)).toEqual({
      cwd: 'C:\\proje', model: 'opus', permissionMode: 'acceptEdits', effort: 'high',
    })
  })

  it('verilmeyen model/permissionMode/effort gövdeye girmez', async () => {
    const mock = stubFetch(() => jsonResponse(200, { ok: true, sessionId: 's' }))

    await createSession('claude-app', { cwd: 'C:\\proje' })

    const body = bodyOf(lastCall(mock).init) as Record<string, unknown>
    expect(body.cwd).toBe('C:\\proje')
    expect('model' in body).toBe(false)
    expect('permissionMode' in body).toBe(false)
    expect('effort' in body).toBe(false)
  })

  it("köprü { ok: false, error } döndüğünde hata fırlatır — yutulmaz", async () => {
    stubFetch(() => jsonResponse(200, { ok: false, error: 'cwd geçersiz' }))
    await expect(createSession('claude-app', { cwd: 'X' })).rejects.toThrow('cwd geçersiz')
  })

  it('HTTP 400 + { error } yanıtını da Error olarak iletir', async () => {
    stubFetch(() => jsonResponse(400, { ok: false, error: 'yol bulunamadı' }))
    await expect(createSession('claude-app', { cwd: 'X' })).rejects.toThrow('yol bulunamadı')
  })

  it('başarıda sessionId ile çözülür', async () => {
    stubFetch(() => jsonResponse(200, { ok: true, sessionId: 's-42' }))
    await expect(createSession('claude-app', { cwd: 'C:\\x' })).resolves.toMatchObject({ sessionId: 's-42' })
  })
})

describe('rename/pin/archive uçları', () => {
  // Gerçek sözleşme `sessionId` değil `id` bekler (server.mjs; Android de öyle
  // gönderir). Şartname tablosundaki `sessionId` burada uygulanmaz.
  it('rename: POST /claude-app/rename, gövde { id, title }', async () => {
    const mock = stubFetch(() => jsonResponse(200, { ok: true }))
    await renameSession('claude-app', 's1', 'Yeni ad')
    const { url, init } = lastCall(mock)
    expect(url.pathname).toBe('/claude-app/rename')
    expect(init.method).toBe('POST')
    expect(bodyOf(init)).toEqual({ id: 's1', title: 'Yeni ad' })
  })

  it.each([
    ['pin', pinSession, '/claude-app/pin'],
    ['unpin', unpinSession, '/claude-app/unpin'],
    ['archive', archiveSession, '/claude-app/archive'],
    ['unarchive', unarchiveSession, '/claude-app/unarchive'],
  ] as const)('%s: gövde { id } ile %s yoluna gider', async (_name, fn, path) => {
    const mock = stubFetch(() => jsonResponse(200, { ok: true }))
    await fn('claude-app', 's1')
    const { url, init } = lastCall(mock)
    expect(url.pathname).toBe(path)
    expect(bodyOf(init)).toEqual({ id: 's1' })
  })

  it('HTTP 200 ile gelen { ok: false } hatası da fırlatılır (ör. id eksik)', async () => {
    stubFetch(() => jsonResponse(200, { ok: false, error: 'id required' }))
    await expect(pinSession('claude-app', '')).rejects.toThrow('id required')
  })

  it('delete: POST /claude-app/delete-session, gövde { id }', async () => {
    const mock = stubFetch(() => jsonResponse(200, { ok: true }))
    await deleteSession('claude-app', 's1')
    const { url, init } = lastCall(mock)
    expect(url.pathname).toBe('/claude-app/delete-session')
    expect(bodyOf(init)).toEqual({ id: 's1' })
  })
})

describe('listRoots', () => {
  it('GET /dirs/roots yoluna gider ve roots dizisini döner', async () => {
    const roots = [
      { name: 'C:', path: 'C:\\', type: 'dir', size: 0, mtime: 0 },
      { name: 'D:', path: 'D:\\', type: 'dir', size: 0, mtime: 0 },
    ]
    const mock = stubFetch(() => jsonResponse(200, { ok: true, roots }))

    const result = await listRoots()

    expect(lastCall(mock).url.pathname).toBe('/dirs/roots')
    expect(result).toEqual(roots)
  })
})

describe('listDirs', () => {
  it('root sorgu parametresi olarak gider (şartnamedeki path değil — köprünün adı root)', async () => {
    const mock = stubFetch(() =>
      jsonResponse(200, { ok: true, base: 'C:\\proje', parent: 'C:\\', dirs: [] }),
    )

    await listDirs('C:\\proje')

    const { url } = lastCall(mock)
    expect(url.pathname).toBe('/dirs')
    expect(url.searchParams.get('root')).toBe('C:\\proje')
    expect(url.searchParams.has('files')).toBe(false)
  })

  it('includeFiles=true ise files=true eklenir', async () => {
    const mock = stubFetch(() =>
      jsonResponse(200, { ok: true, base: 'C:\\', parent: '', dirs: [] }),
    )

    await listDirs('C:\\', true)

    expect(lastCall(mock).url.searchParams.get('files')).toBe('true')
  })

  it('boş root köprüye root parametresi olmadan gider (köprü ev dizinini listeler)', async () => {
    const mock = stubFetch(() =>
      jsonResponse(200, { ok: true, base: 'C:\\Users\\dev', parent: 'C:\\Users', dirs: [] }),
    )

    const result = await listDirs('')

    expect(lastCall(mock).url.searchParams.has('root')).toBe(false)
    expect(result.base).toBe('C:\\Users\\dev')
    expect(result.parent).toBe('C:\\Users')
  })

  it('ok: false yanıtı hata olarak yutulmaz', async () => {
    stubFetch(() => jsonResponse(200, { ok: false, error: 'not a directory', base: '', parent: '', dirs: [] }))
    await expect(listDirs('C:\\yok')).rejects.toThrow('not a directory')
  })
})

describe('searchDirs', () => {
  it('root ve q sorgu parametreleriyle /dirs/search yoluna gider', async () => {
    const results = [{ name: 'proje', path: 'C:\\proje' }]
    const mock = stubFetch(() => jsonResponse(200, { ok: true, root: 'C:\\', query: 'proje', results }))

    const result = await searchDirs('C:\\', 'proje')

    const { url } = lastCall(mock)
    expect(url.pathname).toBe('/dirs/search')
    expect(url.searchParams.get('root')).toBe('C:\\')
    expect(url.searchParams.get('q')).toBe('proje')
    expect(result).toEqual(results)
  })

  it('maxDepth ve limit değerleri sorguya yazılır', async () => {
    const mock = stubFetch(() => jsonResponse(200, { ok: true, root: 'C:\\', query: 'x', results: [] }))

    await searchDirs('C:\\', 'x', 4, 30)

    const { url } = lastCall(mock)
    expect(url.searchParams.get('maxDepth')).toBe('4')
    expect(url.searchParams.get('limit')).toBe('30')
  })

  it('Türkçe karakterli sorgu encode edilir', async () => {
    const mock = stubFetch(() => jsonResponse(200, { ok: true, root: 'C:\\', query: 'dilekçe', results: [] }))

    await searchDirs('C:\\', 'dilekçe')

    expect(lastCall(mock).url.searchParams.get('q')).toBe('dilekçe')
  })

  it('ok: false yanıtı hata olarak yutulmaz', async () => {
    stubFetch(() => jsonResponse(400, { ok: false, error: 'root must be an existing directory' }))
    await expect(searchDirs('', 'x')).rejects.toThrow('root must be an existing directory')
  })
})

describe('çok backend birleştirme', () => {
  const SOURCES = [
    { id: 'claude-app', apiBackend: 'claude-app', label: 'Claude' },
    // cowork claude-app UÇLARINI kullanır: ayrı sorgulanırsa aynı oturumlar
    // listede iki kez çıkardı.
    { id: 'cowork', apiBackend: 'claude-app', label: 'Cowork' },
    { id: 'codex-app', apiBackend: 'codex-app', label: 'Codex' },
    { id: 'kapali', apiBackend: 'kapali', label: 'Kapalı', adapterBacked: false },
  ]

  it('aynı ucu paylaşan backendler teke iner, kanonik sahip kalır', () => {
    expect(sessionSources(SOURCES).map((s) => s.id)).toEqual(['claude-app', 'codex-app'])
  })

  it('kanonik sahip sırayla ilk gelmese de seçilir', () => {
    const flipped = [SOURCES[1], SOURCES[0]]
    expect(sessionSources(flipped).map((s) => s.id)).toEqual(['claude-app'])
  })

  it('oturumlar sahibiyle etiketlenir', async () => {
    stubFetch((url) =>
      jsonResponse(200, {
        sessions: [{ id: url.pathname.includes('codex') ? 'c1' : 'k1', title: 't' }],
      }),
    )

    const { sessions, errors } = await listAllSessions(SOURCES)

    expect(errors).toEqual([])
    expect(sessions.map((s) => [s.id, s.backend, s.backendLabel])).toEqual([
      ['k1', 'claude-app', 'Claude'],
      ['c1', 'codex-app', 'Codex'],
    ])
  })

  it('bir backend düşerse ötekiler yine listelenir', async () => {
    stubFetch((url) =>
      url.pathname.includes('codex')
        ? jsonResponse(500, { error: 'codex kapalı' })
        : jsonResponse(200, { sessions: [{ id: 'k1' }] }),
    )

    const { sessions, errors } = await listAllSessions(SOURCES)

    expect(sessions.map((s) => s.id)).toEqual(['k1'])
    expect(errors).toHaveLength(1)
    expect(errors[0].backend).toBe('codex-app')
  })

  it('arşiv görünümü yalnız destekleyen backendin filtreli disk ucunu kullanır', async () => {
    const calls: URL[] = []
    stubFetch((url) => {
      calls.push(url)
      return jsonResponse(200, {
        ok: true,
        sessions: [{ id: 'arsiv-1', cwd: 'C:/eski', title: 'Arşiv', archived: true }],
      })
    })

    const { sessions, errors } = await listArchivedSessions([
      { id: 'claude-app', apiBackend: 'claude-app', label: 'Claude', capabilities: { sessionArchive: true } },
      { id: 'agy', apiBackend: 'agy', label: 'Agy', capabilities: { sessionArchive: false } },
    ])

    expect(errors).toEqual([])
    expect(sessions.map((session) => [session.id, session.backend, session.live])).toEqual([
      ['arsiv-1', 'claude-app', false],
    ])
    expect(calls).toHaveLength(1)
    expect(calls[0].pathname).toBe('/claude-app/disk-sessions-search')
    expect(calls[0].searchParams.get('archived')).toBe('true')
  })
})

describe('canlı + disk birleştirme', () => {
  const shell = (over = {}) => ({
    id: 'x1', cwd: '', model: 'opus', status: 'idle', title: '',
    lastUserAt: 0, turns: 0, lastText: '', awaitingApproval: false,
    restoredShell: true, ...over,
  })
  const saved = (over = {}) => ({
    id: 'x1', cwd: 'C:/is', title: 'Gerçek başlık', lastText: 'son söz',
    turns: 7, mtime: 5000, ...over,
  })

  it('geri yüklenen boş kabuk diskteki başlığını ve turunu alır', () => {
    const [row] = unifySessions([shell()], [saved({ pinned: true, archived: false })])
    expect(row.title).toBe('Gerçek başlık')
    expect(row.turns).toBe(7)
    expect(row.lastUserAt).toBe(5000)
    expect(row.cwd).toBe('C:/is')
    expect(row.pinned).toBe(true)
    expect(row.archived).toBe(false)
  })

  it('canlı değer doluysa disk onu EZMEZ — tur sürerken disk bayattır', () => {
    const [row] = unifySessions(
      [shell({ title: 'Canlı başlık', turns: 9, lastText: 'yeni', lastUserAt: 9000 })],
      [saved()],
    )
    expect(row.title).toBe('Canlı başlık')
    expect(row.turns).toBe(9)
    expect(row.lastText).toBe('yeni')
    expect(row.lastUserAt).toBe(9000)
  })

  it('diskte karşılığı olmayan canlı oturum olduğu gibi kalır', () => {
    const [row] = unifySessions([shell({ id: 'yok' })], [])
    expect(row.title).toBe('')
    expect(row.turns).toBe(0)
    expect(row.live).toBe(true)
  })

  // Canlı/Geçmiş sekmeleri kalktı: yalnız diskte duran oturum da AYNI listede
  // çıkmalı, yoksa köprü restart'ından sonra ulaşılamaz olurdu.
  it('yalnız diskte olan oturum listeye live:false olarak girer', () => {
    const rows = unifySessions([], [saved({ id: 'sadece-disk' })])
    expect(rows).toHaveLength(1)
    expect(rows[0].id).toBe('sadece-disk')
    expect(rows[0].live).toBe(false)
    expect(rows[0].title).toBe('Gerçek başlık')
    expect(rows[0].lastUserAt).toBe(5000)
  })

  it('aynı oturum iki listede de varsa TEK satır olur', () => {
    const rows = unifySessions([shell()], [saved()])
    expect(rows).toHaveLength(1)
    expect(rows[0].live).toBe(true)
  })

  it('disk listesi alınamazsa canlı liste yine gelir', async () => {
    stubFetch((url) =>
      url.pathname.endsWith('/disk-sessions')
        ? jsonResponse(500, { ok: false, error: 'disk okunamadı' })
        : jsonResponse(200, { sessions: [shell({ title: 'Canlı' })] }),
    )

    const { sessions, errors } = await listAllSessions([
      { id: 'claude-app', apiBackend: 'claude-app', label: 'Claude' },
    ])

    expect(errors).toEqual([])
    expect(sessions.map((s) => s.title)).toEqual(['Canlı'])
  })
})

describe('diskId ile eşleştirme', () => {
  // Bazı sağlayıcılar canlı kabuğa her seferinde yeni UUID veriyor; disk kaydının kimliği
  // ayrı olabiliyor. Yalnız `id`ye bakmak aynı sohbeti listede iki satır yapıyordu.
  const canli = {
    id: 'canli-uuid', cwd: 'C:/is', model: 'deepseek', status: 'idle', title: '',
    lastUserAt: 0, turns: 0, lastText: '', awaitingApproval: false, restoredShell: false,
    diskId: 'disk-uuid',
  }
  const kayit = {
    id: 'disk-uuid', cwd: 'C:/is', title: 'merhaba dostum',
    lastText: 'Selam', turns: 1, mtime: 7000,
  }

  it('id"ler farklı olsa da TEK satır olur', () => {
    const rows = unifySessions([canli], [kayit])
    expect(rows).toHaveLength(1)
    expect(rows[0].id).toBe('canli-uuid')
    expect(rows[0].live).toBe(true)
  })

  it('disk kaydının başlığı ve turu canlı kabuğa geçer', () => {
    const [row] = unifySessions([canli], [kayit])
    expect(row.title).toBe('merhaba dostum')
    expect(row.turns).toBe(1)
    expect(row.lastUserAt).toBe(7000)
  })

  it('diskId başka bir kaydı gösteriyorsa o kayıt ayrıca eklenmez', () => {
    const rows = unifySessions([canli], [kayit, { ...kayit, id: 'baska', title: 'Başka' }])
    expect(rows.map((r) => r.title).sort()).toEqual(['Başka', 'merhaba dostum'])
  })

  it('diskId yoksa davranış değişmez (claude-app/codex-app)', () => {
    const { diskId, ...diskIdsiz } = canli
    void diskId
    const rows = unifySessions([{ ...diskIdsiz, id: 'disk-uuid' }], [kayit])
    expect(rows).toHaveLength(1)
    expect(rows[0].title).toBe('merhaba dostum')
  })
})

describe('cowork oturumları', () => {
  const SOURCES = [
    { id: 'claude-app', apiBackend: 'claude-app', label: 'Claude' },
    { id: 'cowork', apiBackend: 'claude-app', label: 'Cowork', adapterBacked: false },
  ]

  function coworkFetch(sessionsByPath: Record<string, unknown[]>, projects = ['ws-a', 'ws-b']) {
    return stubFetch((url) => {
      if (url.pathname === '/cowork/projects') {
        return jsonResponse(200, { ok: true, projects: projects.map((p) => ({ name: p, path: p })) })
      }
      if (url.pathname === '/cowork/sessions') {
        const path = url.searchParams.get('projectPath') || ''
        const rows = sessionsByPath[path]
        if (!rows) return jsonResponse(500, { ok: false, error: 'workspace okunamadı' })
        return jsonResponse(200, { ok: true, sessions: rows })
      }
      return jsonResponse(200, { sessions: [] })
    })
  }

  it('bütün workspacelerin oturumları listeye girer ve sahibi korunur', async () => {
    // Cowork sağlayıcının normal disk listesinde YOK; bu uç sorgulanmazsa
    // oturumlar arayüzde hiç görünmüyordu (13.08.2026 kullanıcı bildirimi).
    coworkFetch({
      'ws-a': [{ provider: 'claude-app', sessionId: 'a1', cwd: 'ws-a', title: 'A', lastUsedAt: '2026-08-13T09:30:02Z' }],
      'ws-b': [{ provider: 'codex-app', sessionId: 'b1', cwd: 'ws-b', title: 'B', lastUsedAt: '2026-08-12T14:12:27Z' }],
    })

    const { sessions, errors } = await listAllSessions(SOURCES)
    const cowork = sessions.filter((s) => s.backend === 'cowork')

    expect(errors).toEqual([])
    expect(cowork.map((s) => [s.id, s.provider, s.title])).toEqual([
      ['a1', 'claude-app', 'A'],
      ['b1', 'codex-app', 'B'],
    ])
    // live:false — açılmadan önce adopt edilmeli.
    expect(cowork.every((s) => s.live === false)).toBe(true)
    expect(cowork[0].lastUserAt).toBe(Date.parse('2026-08-13T09:30:02Z'))
  })

  it('cwd boş gelen kayıt workspace yoluyla doldurulur', async () => {
    // Boş cwd ile adopt oturumu YANLIŞ klasörde kurar; Android'de de aynısı yapılıyor.
    coworkFetch({ 'ws-a': [{ provider: 'codex-app', sessionId: 'a1', cwd: '' }], 'ws-b': [] })

    const { sessions } = await listAllSessions(SOURCES)

    expect(sessions.find((s) => s.id === 'a1')?.cwd).toBe('ws-a')
  })

  it('başlıksız kayıt sağlayıcısına göre adlandırılır', async () => {
    coworkFetch({
      'ws-a': [{ provider: 'codex-app', sessionId: 'a1', cwd: 'ws-a' }],
      'ws-b': [{ provider: 'opencode-app', sessionId: 'b1', cwd: 'ws-b' }],
    })

    const { sessions } = await listAllSessions(SOURCES)

    expect(sessions.find((s) => s.id === 'a1')?.title).toBe('Yeni Codex oturumu')
    expect(sessions.find((s) => s.id === 'b1')?.title).toBe('Yeni OpenCode oturumu')
  })

  it('bir workspace okunamazsa ötekiler yine listelenir', async () => {
    coworkFetch({ 'ws-b': [{ provider: 'claude-app', sessionId: 'b1', cwd: 'ws-b' }] })

    const { sessions, errors } = await listAllSessions(SOURCES)

    expect(errors).toEqual([])
    expect(sessions.filter((s) => s.backend === 'cowork').map((s) => s.id)).toEqual(['b1'])
  })

  it('cowork tümüyle düşerse hata ayrı taşınır, öteki backendler kalır', async () => {
    stubFetch((url) => {
      if (url.pathname === '/cowork/projects') {
        return jsonResponse(200, { ok: false, error: 'cowork kökü yok' })
      }
      return jsonResponse(200, { sessions: [{ id: 'k1', title: 't' }] })
    })

    const { sessions, errors } = await listAllSessions(SOURCES)

    expect(sessions.map((s) => s.id)).toEqual(['k1'])
    expect(errors).toEqual([{ backend: 'cowork', label: 'Cowork', message: 'cowork kökü yok' }])
  })

  it('katalogda cowork yoksa uç hiç çağrılmaz', async () => {
    const mock = stubFetch(() => jsonResponse(200, { sessions: [] }))

    await listAllSessions([SOURCES[0]])

    expect(mock.mock.calls.some((c) => String(c[0]).includes('/cowork/'))).toBe(false)
  })
})

describe('cowork tekilleştirme', () => {
  const SOURCES = [
    { id: 'claude-app', apiBackend: 'claude-app', label: 'Claude' },
    { id: 'cowork', apiBackend: 'claude-app', label: 'Cowork', adapterBacked: false },
  ]

  it('canlı sağlayıcı satırıyla aynı oturum iki kez listelenmez', async () => {
    // Cowork oturumu DİSK listesinde yok ama köprüde canlıysa CANLI listede var;
    // tarayıcıda ölçüldü (13.08.2026): aynı oturum "Claude" ve "Cowork" olarak
    // iki satır çıkıyordu.
    stubFetch((url) => {
      if (url.pathname === '/cowork/projects') return jsonResponse(200, { ok: true, projects: [{ name: 'ws', path: 'ws' }] })
      if (url.pathname === '/cowork/sessions') {
        return jsonResponse(200, { ok: true, sessions: [{ provider: 'claude-app', sessionId: 'dup', cwd: 'ws', title: 'Ortak' }] })
      }
      if (url.pathname === '/claude-app/sessions') {
        return jsonResponse(200, { sessions: [{ id: 'dup', title: 'Ortak', turns: 19, status: 'running' }] })
      }
      return jsonResponse(200, { sessions: [] })
    })

    const { sessions } = await listAllSessions(SOURCES)

    expect(sessions.filter((s) => s.id === 'dup')).toHaveLength(1)
    const row = sessions.find((s) => s.id === 'dup')!
    // Cowork kazanır (etiket + provider yönlendirmesi) ama canlı veri korunur.
    expect([row.backend, row.provider]).toEqual(['cowork', 'claude-app'])
    expect([row.turns, row.status, row.live]).toEqual([19, 'running', true])
  })

  it('yalnız diskte kalan cowork oturumu listeye eklenir', () => {
    const live = [{ id: 'x', backend: 'claude-app', backendLabel: 'Claude', live: true } as unknown as TaggedSession]
    const cowork = [{ id: 'y', backend: 'cowork', backendLabel: 'Cowork', live: false } as unknown as TaggedSession]
    expect(mergeCoworkRows(live, cowork).map((s) => s.id)).toEqual(['x', 'y'])
  })

  it('cowork satırı yoksa liste olduğu gibi kalır', () => {
    const live = [{ id: 'x' } as unknown as TaggedSession]
    expect(mergeCoworkRows(live, [])).toBe(live)
  })
})
