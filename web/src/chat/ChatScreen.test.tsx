// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatScreen } from './ChatScreen'

// Sahte WebSocket: useSessionStream gerçeğini kuruyor, testte açılışı ve
// mesajları elle sürüyoruz.
class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null

  url: string

  // `constructor(readonly url)` YASAK: tsconfig'de erasableSyntaxOnly açık.
  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }
  close() {}
  emit(payload: unknown) {
    this.onmessage?.({ data: JSON.stringify(payload) })
  }
}

const socket = () => FakeWebSocket.instances[FakeWebSocket.instances.length - 1]

const json = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })

const SESSIONS = [
  {
    id: 'o1',
    cwd: 'C:/is/proje',
    model: 'opus',
    status: 'idle',
    title: 'Proje',
    lastUserAt: 200,
    turns: 3,
    lastText: 'son mesaj',
    awaitingApproval: false,
    restoredShell: false,
  },
]

const CATALOG = {
  contractVersion: 1,
  backends: [
    { id: 'claude-app', apiBackend: 'claude-app', label: 'Claude', adapterBacked: true,
      capabilities: { approvals: true, userInput: true, permissionModes: true, context: true,
        plan: false, outputs: false, efforts: true, interrupt: true } },
    { id: 'codex-app', apiBackend: 'codex-app', label: 'Codex', adapterBacked: true,
      capabilities: { approvals: true, userInput: true, permissionModes: true, context: true,
        plan: true, outputs: false, efforts: true, interrupt: false } },
    { id: 'opencode-app', apiBackend: 'opencode-app', label: 'OpenCode', adapterBacked: true,
      capabilities: { approvals: true, userInput: false, permissionModes: true, context: true,
        plan: false, outputs: false, efforts: false, interrupt: false } },
    { id: 'agy', apiBackend: 'agy', label: 'Agy', adapterBacked: true,
      capabilities: { approvals: false, userInput: false, permissionModes: false, context: false,
        plan: false, outputs: false, efforts: false, interrupt: false } },
  ],
}

const AGY_SESSIONS = [{ ...SESSIONS[0], id: 'agy1', title: 'Agy oturumu' }]
const OPENCODE_SESSIONS = [{ ...SESSIONS[0], id: 'open1', model: 'opencode/test', title: 'OpenCode oturumu' }]

// Yalnız DİSKTE duran oturum: köprüde canlı kabuğu yok, açılırken adopt edilir.
const DISK_SESSIONS = [
  { id: 'd1', cwd: 'C:/is/eski', title: 'Diskteki iş', lastText: 'yarım kalmış', turns: 4, mtime: 100 },
]
const ARCHIVED_SESSIONS = [
  { id: 'a1', cwd: 'C:/is/arsiv', title: 'Arşivlenmiş iş', lastText: 'tamamlandı', turns: 6, mtime: 50, archived: true },
]

let posts: { url: string; body: Record<string, unknown> }[] = []

beforeEach(() => {
  FakeWebSocket.instances = []
  posts = []
  // happy-dom'un localStorage'ında clear() yok; bellek içi basit bir taklit
  // hem temiz başlangıç veriyor hem de kalıcılığı gerçekten ölçüyor.
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, String(v)),
    removeItem: (k: string) => void store.delete(k),
    clear: () => store.clear(),
  })
  vi.stubGlobal('WebSocket', FakeWebSocket)
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    const parsed = new URL(String(url), 'http://test')
    const path = parsed.pathname
    if (init?.method === 'POST') {
      posts.push({ url: path, body: JSON.parse(String(init.body ?? '{}')) })
      return Promise.resolve(json({ ok: true }))
    }
    if (path === '/backends') return Promise.resolve(json(CATALOG))
    if (path === '/claude-app/sessions') return Promise.resolve(json({ sessions: SESSIONS }))
    if (path === '/agy/sessions') return Promise.resolve(json({ sessions: AGY_SESSIONS }))
    if (path === '/opencode-app/sessions') return Promise.resolve(json({ sessions: OPENCODE_SESSIONS }))
    if (path === '/claude-app/disk-sessions')
      return Promise.resolve(json({ ok: true, sessions: DISK_SESSIONS }))
    if (path === '/claude-app/disk-sessions-search' && parsed.searchParams.get('archived') === 'true')
      return Promise.resolve(json({ ok: true, sessions: ARCHIVED_SESSIONS }))
    if (path === '/codex-app/disk-sessions-search')
      return Promise.resolve(json({ ok: true, sessions: [] }))
    if (path.endsWith('/disk-sessions')) return Promise.resolve(json({ ok: true, sessions: [] }))
    if (path.endsWith('/sessions')) return Promise.resolve(json({ sessions: [] }))
    if (path.endsWith('/conversation') && parsed.searchParams.get('sessionId') === 'd1') {
      return Promise.resolve(json({ messages: parsed.searchParams.get('before') ? [] : [
        { rowId: 'd1:r1', role: 'user', text: 'delil ilk eşleşme', time: '' },
        { rowId: 'd1:r2', role: 'agent', text: 'delil ikinci eşleşme', time: '' },
      ] }))
    }
    if (path.endsWith('/conversation')) return Promise.resolve(json({ messages: [] }))
    if (path.endsWith('/models')) return Promise.resolve(json({ models: [], defaultModel: '' }))
    if (path.endsWith('/efforts')) return Promise.resolve(json({ efforts: ['low', 'high'] }))
    return Promise.resolve(json({ ok: true }))
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

async function selectSession() {
  render(<ChatScreen />)
  await waitFor(() => expect(screen.getByText('Proje')).toBeInTheDocument())
  fireEvent.click(screen.getByText('Proje'))
  await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0))
}

describe('ChatScreen', () => {
  it('oturum seçilmeden boş durum gösterir', async () => {
    render(<ChatScreen />)
    await waitFor(() => expect(screen.getByText('Proje')).toBeInTheDocument())
    expect(screen.getByText(/Soldan bir oturum seç/)).toBeInTheDocument()
    expect(FakeWebSocket.instances).toHaveLength(0)
  })

  it('oturum seçilince akış açılır ve mesajlar görünür', async () => {
    await selectSession()
    expect(new URL(socket().url).pathname).toBe('/claude-app/stream')

    socket().onopen?.()
    socket().emit({
      type: 'snapshot',
      seq: 1,
      messages: [
        { rowId: 'r1', role: 'user', text: 'merhaba' },
        { rowId: 'r2', role: 'agent', text: 'selam **dünya**' },
      ],
      running: false,
    })

    await waitFor(() => expect(screen.getByText('merhaba')).toBeInTheDocument())
    // Markdown gerçekten işleniyor: ** ** kalın oluyor.
    expect(screen.getByText('dünya').tagName).toBe('STRONG')
  })

  it('akış deltası ekrana yansır', async () => {
    await selectSession()
    socket().onopen?.()
    socket().emit({ type: 'snapshot', seq: 1, messages: [{ rowId: 'r1', role: 'agent', text: 'Mer' }] })
    await waitFor(() => expect(screen.getByText('Mer')).toBeInTheDocument())

    socket().emit({ type: 'delta', seq: 2, ops: [{ op: 'appendText', rowId: 'r1', chunk: 'haba' }] })
    await waitFor(() => expect(screen.getByText('Merhaba')).toBeInTheDocument())
  })

  it('yazma kutusundan gönderilen mesaj doğru uca gider', async () => {
    await selectSession()
    socket().onopen?.()
    socket().emit({ type: 'snapshot', seq: 1, messages: [], running: false })

    const area = await screen.findByLabelText('Mesaj')
    fireEvent.change(area, { target: { value: 'yeni görev' } })
    fireEvent.keyDown(area, { key: 'Enter' })

    await waitFor(() => expect(posts.some((p) => p.url === '/claude-app/prompt')).toBe(true))
    const prompt = posts.find((p) => p.url === '/claude-app/prompt')!
    expect(prompt.body.text).toBe('yeni görev')
    expect(prompt.body.sessionId).toBe('o1')
    // model/permissionMode BİLEREK gönderilmiyor: oturumun kendi ayarı köprüde.
    expect('model' in prompt.body).toBe(false)
    expect('permissionMode' in prompt.body).toBe(false)
  })

  it('onay beklerken onay kutusu çıkar', async () => {
    await selectSession()
    socket().onopen?.()
    socket().emit({
      type: 'snapshot',
      seq: 1,
      messages: [],
      awaitingApproval: true,
      approval: { requestId: 'a1', tool: 'Bash', summary: 'rm -rf çalıştır' },
    })

    await waitFor(() => expect(screen.getByText(/rm -rf çalıştır/)).toBeInTheDocument())
  })

  it('bağlantı durumu gösterilir', async () => {
    await selectSession()
    expect(screen.getByText(/bağlı değil/)).toBeInTheDocument()
    socket().onopen?.()
    await waitFor(() => expect(screen.getByText(/● bağlı/)).toBeInTheDocument())
  })

  it('geçmiş sayfası boş dönerse "sohbetin başı" yazar', async () => {
    await selectSession()
    socket().onopen?.()
    socket().emit({ type: 'snapshot', seq: 1, messages: [{ rowId: 'r1', role: 'user', text: 'x' }] })

    const button = await screen.findByRole('button', { name: /Daha eskisini yükle/ })
    fireEvent.click(button)
    await waitFor(() => expect(screen.getByText('Sohbetin başı')).toBeInTheDocument())
  })
})

describe('ChatScreen — çok backend (M3)', () => {
  it('ajan seçicisi köprü kataloğunu listeler', async () => {
    render(<ChatScreen />)
    const picker = await screen.findByLabelText('Ajan')
    await waitFor(() =>
      expect([...(picker as HTMLSelectElement).options].map((o) => o.textContent)).toEqual([
        'Tümü', 'Claude', 'Codex', 'OpenCode', 'Agy',
      ]),
    )
  })

  it('ajan filtresi değişince açık sekme korunur ve YENİ backend’in listesi gelir', async () => {
    render(<ChatScreen />)
    await waitFor(() => expect(screen.getByText('Proje')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Proje'))
    await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0))

    fireEvent.change(await screen.findByLabelText('Ajan'), { target: { value: 'agy' } })

    // Filtre yalnız kenar listesini daraltır; açık çalışma sekmesini kapatmaz.
    expect(screen.queryByText(/Soldan bir oturum seç/)).toBeNull()
    expect(screen.getByRole('tab', { name: /Proje/ })).toHaveAttribute('aria-selected', 'true')
    // Yeni backend’in oturumları geldi.
    await waitFor(() => expect(screen.getByText('Agy oturumu')).toBeInTheDocument())
  })

  it('akış YENİ backend’in ucuna bağlanır', async () => {
    render(<ChatScreen />)
    await screen.findByLabelText('Ajan')
    fireEvent.change(screen.getByLabelText('Ajan'), { target: { value: 'agy' } })
    await waitFor(() => expect(screen.getByText('Agy oturumu')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Agy oturumu'))

    await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0))
    expect(new URL(socket().url).pathname).toBe('/agy/stream')
  })

  it('desteklenmeyen kontroller ÇİZİLMEZ — agy’de izin kipi, çaba ve kesme yok', async () => {
    render(<ChatScreen />)
    await screen.findByLabelText('Ajan')
    fireEvent.change(screen.getByLabelText('Ajan'), { target: { value: 'agy' } })
    await waitFor(() => expect(screen.getByText('Agy oturumu')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Agy oturumu'))
    await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0))
    socket().onopen?.()
    socket().emit({ type: 'snapshot', seq: 1, messages: [], running: true })

    await waitFor(() => expect(screen.getByRole('button', { name: 'Durdur' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Kes' })).toBeNull()
    expect(screen.queryByLabelText(/İzin kipi/i)).toBeNull()
    expect(screen.queryByLabelText(/Çaba/i)).toBeNull()
  })

  it('claude-app’te kesme düğmesi VARDIR', async () => {
    await selectSession()
    socket().onopen?.()
    socket().emit({ type: 'snapshot', seq: 1, messages: [], running: true })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Kes' })).toBeInTheDocument())
  })

  it('oturum menüsü eylemini doğru backend ve disk kimliğine yollar', async () => {
    await selectSession()
    fireEvent.click(screen.getByRole('button', { name: 'Oturum işlemleri: o1' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sabitle' }))

    await waitFor(() => {
      const pin = posts.find((post) => post.url === '/claude-app/pin')
      expect(pin?.body).toEqual({ id: 'o1' })
    })
  })

  it('plan paneli yalnız plan yeteneği olan backend’de çıkar', async () => {
    // claude-app’in plan yeteneği yok: plan verisi gelse bile panel çizilmemeli.
    await selectSession()
    socket().onopen?.()
    socket().emit({
      type: 'snapshot',
      seq: 1,
      messages: [],
      plan: [{ text: 'ilk adım', status: 'pending' }],
    })
    await waitFor(() => expect(screen.getByText(/● bağlı/)).toBeInTheDocument())
    expect(screen.queryByText('ilk adım')).toBeNull()
  })

  it('seçilen ajan yeniden açılışta hatırlanır', async () => {
    const first = render(<ChatScreen />)
    await screen.findByLabelText('Ajan')
    fireEvent.change(screen.getByLabelText('Ajan'), { target: { value: 'codex-app' } })
    await waitFor(() => expect(localStorage.getItem('agentbridge.backend')).toBe('codex-app'))
    first.unmount()

    render(<ChatScreen />)
    const picker = await screen.findByLabelText('Ajan')
    expect((picker as HTMLSelectElement).value).toBe('codex-app')
  })
})

describe('ChatScreen — ajan seçimi zorunlu değil', () => {
  it('varsayılan "Tümü": bütün backendlerin oturumları tek listede', async () => {
    render(<ChatScreen />)
    // claude-app ve agy oturumları YAN YANA — ajan seçmeden.
    await waitFor(() => expect(screen.getByText('Proje')).toBeInTheDocument())
    expect(screen.getByText('Agy oturumu')).toBeInTheDocument()
    expect((screen.getByLabelText('Ajan') as HTMLSelectElement).value).toBe('*')
  })

  it('karışık listede satır kendi ucuna bağlanır', async () => {
    render(<ChatScreen />)
    await waitFor(() => expect(screen.getByText('Agy oturumu')).toBeInTheDocument())

    // Aktif "seçili ajan" YOK; uç oturumun kendi sahibinden okunmalı.
    fireEvent.click(screen.getByText('Agy oturumu'))
    await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0))
    expect(new URL(socket().url).pathname).toBe('/agy/stream')
  })

  it('karışık listede ajan rozeti çizilir, tek ajan seçilince kaybolur', async () => {
    render(<ChatScreen />)
    await waitFor(() => expect(screen.getByText('Agy oturumu')).toBeInTheDocument())
    expect(screen.getAllByTitle('Ajan: Agy').length).toBe(1)

    fireEvent.change(screen.getByLabelText('Ajan'), { target: { value: 'agy' } })
    await waitFor(() => expect(screen.queryByText('Proje')).toBeNull())
    expect(screen.queryByTitle('Ajan: Agy')).toBeNull()
  })

  it('ajan daraltmak AÇIK oturumu kapatmaz — oturum o ajana aitse', async () => {
    render(<ChatScreen />)
    await waitFor(() => expect(screen.getByText('Agy oturumu')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Agy oturumu'))
    await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0))

    fireEvent.change(screen.getByLabelText('Ajan'), { target: { value: 'agy' } })

    // Filtre daraldı ama oturum agy'nin: kapanmamalı.
    await waitFor(() => expect(screen.queryByText('Proje')).toBeNull())
    expect(screen.queryByText(/Soldan bir oturum seç/)).toBeNull()
  })
})


describe('ChatScreen — canlı ve disk tek listede', () => {
  it('yalnız diskte olan oturum listede çıkar ve "diskte" der', async () => {
    render(<ChatScreen />)
    await waitFor(() => expect(screen.getByText('Diskteki iş')).toBeInTheDocument())
    expect(screen.getByTitle(/Köprüde canlı değil/)).toBeInTheDocument()
  })

  it('diskteki oturum açılırken ÖNCE adopt edilir, sonra akış kurulur', async () => {
    render(<ChatScreen />)
    await waitFor(() => expect(screen.getByText('Diskteki iş')).toBeInTheDocument())

    fireEvent.click(screen.getByText('Diskteki iş'))

    // cwd göndermek ŞART: köprü oturumu o dizinde yeniden kuruyor.
    await waitFor(() => {
      const adopt = posts.find((p) => p.url === '/claude-app/adopt')
      expect(adopt?.body).toEqual({ id: 'd1', cwd: 'C:/is/eski' })
    })
    await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0))
    expect(new URL(socket().url).pathname).toBe('/claude-app/stream')
  })

  it('canlı oturum adopt EDİLMEZ — doğrudan açılır', async () => {
    render(<ChatScreen />)
    await waitFor(() => expect(screen.getByText('Proje')).toBeInTheDocument())

    fireEvent.click(screen.getByText('Proje'))
    await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0))
    expect(posts.find((p) => p.url.endsWith('/adopt'))).toBeUndefined()
  })

  it('Arşiv görünümü normal listeyi değil arşiv disk kayıtlarını gösterir', async () => {
    render(<ChatScreen />)
    await waitFor(() => expect(screen.getByText('Proje')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: 'Arşiv' }))

    await waitFor(() => expect(screen.getByText('Arşivlenmiş iş')).toBeInTheDocument())
    expect(screen.queryByText('Diskteki iş')).toBeNull()
  })

  it('global mesaj hedefinde oturumu açıp tam geçmişteki ordinal eşleşmeyi vurgular', async () => {
    render(<ChatScreen navigationTarget={{
      requestKey: 'arama-1', backend: 'claude-app', backendLabel: 'Claude',
      sessionId: 'd1', cwd: 'C:/is/eski', title: 'Diskteki iş', live: false,
      query: 'delil', matchOrdinal: 1,
    }} />)

    await waitFor(() => expect(screen.getByText('delil ikinci eşleşme')).toBeInTheDocument())
    expect(screen.getByText('Arama sonucu · 2. eşleşme')).toBeInTheDocument()
    expect(posts.find((post) => post.url === '/claude-app/adopt')?.body).toEqual({
      id: 'd1', cwd: 'C:/is/eski',
    })
  })
})
