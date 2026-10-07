// @vitest-environment happy-dom
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { NewSessionDialog } from './NewSessionDialog'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

const rootC = { name: 'C:', path: 'C:\\', type: 'dir', size: 0, mtime: 0 }
const proje1 = { name: 'proje1', path: 'C:\\proje1', type: 'dir', size: 0, mtime: 0 }
const proje2 = { name: 'proje2', path: 'C:\\proje2', type: 'dir', size: 0, mtime: 0 }

interface BridgeStub {
  mock: ReturnType<typeof vi.fn>
  newResponse: { status: number; body: unknown }
}

function stubBridge(): BridgeStub {
  const stub: BridgeStub = { mock: vi.fn(), newResponse: { status: 200, body: { ok: true, sessionId: 's-yeni' } } }
  stub.mock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = new URL(String(input), 'http://test')
    if (url.pathname === '/dirs/roots') {
      return jsonResponse(200, { ok: true, roots: [rootC] })
    }
    if (url.pathname === '/dirs') {
      const root = url.searchParams.get('root') ?? ''
      const base = root || 'C:\\Users\\dev'
      return jsonResponse(200, {
        ok: true,
        base,
        parent: root ? 'C:\\' : '',
        dirs: root === 'C:\\' ? [proje1, proje2] : [],
      })
    }
    if (url.pathname === '/dirs/search') {
      return jsonResponse(200, { ok: true, root: 'C:\\', query: 'proje', results: [proje1] })
    }
    if (url.pathname === '/claude-app/new') {
      return jsonResponse(stub.newResponse.status, stub.newResponse.body)
    }
    return jsonResponse(404, { ok: false, error: 'bilinmeyen uç: ' + url.pathname })
  })
  vi.stubGlobal('fetch', stub.mock)
  return stub
}

function lastDirsCall(mock: ReturnType<typeof vi.fn>): URL | null {
  const calls = mock.mock.calls.filter(([input]) => String(input).startsWith('/dirs?'))
  if (calls.length === 0) return null
  return new URL(String(calls.at(-1)![0]), 'http://test')
}

afterEach(() => vi.unstubAllGlobals())

describe('NewSessionDialog', () => {
  it('açılışta sürücü köklerini ve ev dizini listesini yükler', async () => {
    const stub = stubBridge()
    render(<NewSessionDialog backend="claude-app" onCreated={vi.fn()} />)

    // Kök çipi görünür.
    expect(await screen.findByRole('button', { name: 'C:' })).toBeTruthy()
    // Başlangıç listesi köprünün döndüğü ev dizinini gösterir.
    expect(await screen.findByText('C:\\Users\\dev')).toBeTruthy()
    expect(stub.mock.mock.calls.some(([input]) => String(input) === '/dirs/roots')).toBe(true)
  })

  it('kök tıklanınca /dirs o kökle çağrılır ve alt klasörler listelenir', async () => {
    const stub = stubBridge()
    render(<NewSessionDialog backend="claude-app" onCreated={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'C:' }))

    await waitFor(() => {
      const url = lastDirsCall(stub.mock)
      expect(url?.searchParams.get('root')).toBe('C:\\')
    })
    expect(await screen.findByRole('button', { name: 'proje1' })).toBeTruthy()
  })

  it('klasöre girince /dirs doğru root ile çağrılır', async () => {
    const stub = stubBridge()
    render(<NewSessionDialog backend="claude-app" onCreated={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'C:' }))
    fireEvent.click(await screen.findByRole('button', { name: 'proje1' }))

    await waitFor(() => {
      const url = lastDirsCall(stub.mock)
      expect(url?.searchParams.get('root')).toBe('C:\\proje1')
    })
  })

  it('seçilen klasör çalışma klasörü inputuna yazılır', async () => {
    stubBridge()
    render(<NewSessionDialog backend="claude-app" onCreated={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'C:' }))
    fireEvent.click(await screen.findByRole('button', { name: 'proje1' }))

    const input = await screen.findByLabelText('Çalışma klasörü yolu') as HTMLInputElement
    await waitFor(() => expect(input.value).toBe('C:\\proje1'))
  })

  it('aramada /dirs/search çağrılır ve sonuca tıklayınca yol seçilir', async () => {
    stubBridge()
    render(<NewSessionDialog backend="claude-app" onCreated={vi.fn()} />)

    // Arama kökü gezinilen klasördür — önce açılış yüklemesi bitsin.
    await screen.findByRole('button', { name: 'C:' })
    const search = screen.getByLabelText('Klasör adı ara')
    fireEvent.change(search, { target: { value: 'proje' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ara' }))

    const hit = await screen.findByRole('button', { name: 'C:\\proje1' })
    fireEvent.click(hit)

    const input = screen.getByLabelText('Çalışma klasörü yolu') as HTMLInputElement
    expect(input.value).toBe('C:\\proje1')
  })

  it('oluşturma /claude-app/new gövdesinde cwd ile gider ve onCreatedi çağırır', async () => {
    const stub = stubBridge()
    const onCreated = vi.fn()
    render(<NewSessionDialog backend="claude-app" onCreated={onCreated} model="opus" permissionMode="acceptEdits" effort="high" />)

    fireEvent.change(await screen.findByLabelText('Çalışma klasörü yolu'), {
      target: { value: 'C:\\proje1' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Oluştur' }))

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('s-yeni', 'claude-app'))
    const newCall = stub.mock.mock.calls.find(([input]) => String(input) === '/claude-app/new')!
    const body = JSON.parse(String((newCall[1] as RequestInit).body)) as Record<string, unknown>
    expect(body).toEqual({ cwd: 'C:\\proje1', model: 'opus', permissionMode: 'acceptEdits', effort: 'high' })
  })

  it('cwd boşken oluşturma isteği göndermez', async () => {
    const stub = stubBridge()
    const onCreated = vi.fn()
    render(<NewSessionDialog backend="claude-app" onCreated={onCreated} />)

    // Açılış yüklemeleri bitmeden buton busy nedeniyle disabled — önce bekle.
    await screen.findByRole('button', { name: 'C:' })
    fireEvent.click(screen.getByRole('button', { name: 'Oluştur' }))

    await waitFor(() =>
      expect(screen.getByText('Çalışma klasörü seçin veya yazın.')).toBeTruthy(),
    )
    expect(onCreated).not.toHaveBeenCalled()
    expect(stub.mock.mock.calls.some(([input]) => String(input) === '/claude-app/new')).toBe(false)
  })

  it('köprü hata döndüğünde error metnini gösterir, onCreatedi çağırmaz', async () => {
    const stub = stubBridge()
    stub.newResponse = { status: 400, body: { ok: false, error: 'yol bulunamadı' } }
    const onCreated = vi.fn()
    render(<NewSessionDialog backend="claude-app" onCreated={onCreated} />)

    fireEvent.change(await screen.findByLabelText('Çalışma klasörü yolu'), {
      target: { value: 'C:\\yok' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Oluştur' }))

    expect(await screen.findByText('yol bulunamadı')).toBeTruthy()
    expect(onCreated).not.toHaveBeenCalled()
  })

  it('onClose verilmişse İptal butonu gösterilir ve çağrılır', async () => {
    stubBridge()
    const onClose = vi.fn()
    render(<NewSessionDialog backend="claude-app" onCreated={vi.fn()} onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: 'İptal' }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
