import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  DEFAULT_CAPABILITIES,
  FALLBACK_BACKENDS,
  fetchBackends,
  findBackend,
  normalizeCapabilities,
} from './backends'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

afterEach(() => vi.unstubAllGlobals())

describe('normalizeCapabilities', () => {
  it('eksik alanları varsayılana tamamlar', () => {
    const caps = normalizeCapabilities({ approvals: true }, 'bilinmeyen')
    expect(caps.approvals).toBe(true)
    expect(caps.plan).toBe(false)
    expect(caps.context).toBe(true)
  })

  it('değerleri boole’a çevirir', () => {
    const caps = normalizeCapabilities({ approvals: 1 as unknown as boolean }, 'x')
    expect(caps.approvals).toBe(true)
  })

  it('ESKİ köprü yeni yetenekleri bildirmezse gömülü tabloya düşer', () => {
    // Bu alanlar sonradan eklendi. Varsayılan false olsaydı, güncellenmemiş
    // bir köprüde claude-app’in çaba seçicisi sebepsiz kaybolurdu.
    const caps = normalizeCapabilities(
      { approvals: true, userInput: true, permissionModes: true, context: true },
      'claude-app',
    )
    expect(caps.efforts).toBe(true)
    expect(caps.interrupt).toBe(true)
    expect(normalizeCapabilities({}, 'codex-app').sessionArchive).toBe(true)
    expect(normalizeCapabilities({}, 'opencode-app').sessionPin).toBe(true)
    expect(normalizeCapabilities({}, 'agy').sessionDelete).toBe(true)
  })

  it('köprü açıkça false dediyse gömülü tabloyu EZMEZ', () => {
    const caps = normalizeCapabilities({ efforts: false, interrupt: false }, 'claude-app')
    expect(caps.efforts).toBe(false)
    expect(caps.interrupt).toBe(false)
  })

  it('bilinmeyen backend için efforts/interrupt kapalı kalır', () => {
    expect(normalizeCapabilities({}, 'yeni-sey').efforts).toBe(false)
  })

  it('şema alanlarının tamamını döndürür', () => {
    expect(Object.keys(normalizeCapabilities({}, 'x')).sort()).toEqual(
      Object.keys(DEFAULT_CAPABILITIES).sort(),
    )
  })
})

describe('fetchBackends', () => {
  it('köprü kataloğunu okur', async () => {
    vi.stubGlobal('fetch', () =>
      Promise.resolve(
        json({
          contractVersion: 1,
          backends: [
            { id: 'codex-app', apiBackend: 'codex-app', label: 'Codex', adapterBacked: true,
              capabilities: { approvals: true, plan: true, efforts: true } },
          ],
        }),
      ),
    )

    const list = await fetchBackends()
    expect(list).toHaveLength(1)
    expect(list[0].label).toBe('Codex')
    expect(list[0].capabilities.plan).toBe(true)
    expect(list[0].capabilities.interrupt).toBe(false)
  })

  it('cowork gibi apiBackend’i farklı olan girdiyi korur', async () => {
    vi.stubGlobal('fetch', () =>
      Promise.resolve(
        json({ backends: [{ id: 'cowork', apiBackend: 'claude-app', label: 'Cowork' }] }),
      ),
    )
    const list = await fetchBackends()
    expect(list[0].apiBackend).toBe('claude-app')
  })

  it('köprüye ulaşılamazsa gömülü tabloya düşer', async () => {
    vi.stubGlobal('fetch', () => Promise.reject(new Error('offline')))
    expect(await fetchBackends()).toBe(FALLBACK_BACKENDS)
  })

  it('katalog boş gelirse gömülü tabloya düşer', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve(json({ backends: [] })))
    expect(await fetchBackends()).toBe(FALLBACK_BACKENDS)
  })

  it('kimliksiz girdiler elenir', async () => {
    vi.stubGlobal('fetch', () =>
      Promise.resolve(json({ backends: [{ label: 'kimliksiz' }, { id: 'agy', label: 'Agy' }] })),
    )
    const list = await fetchBackends()
    expect(list.map((b) => b.id)).toEqual(['agy'])
  })

  it('köprüden gelse bile omp ve runpod girdileri elenir', async () => {
    vi.stubGlobal('fetch', () =>
      Promise.resolve(
        json({
          backends: [
            { id: 'claude-app', label: 'Claude' },
            { id: 'omp', label: 'OMP' },
            { id: 'runpod', label: 'RunPod' },
            { id: 'codex-app', label: 'Codex' },
          ],
        }),
      ),
    )
    const list = await fetchBackends()
    expect(list.map((b) => b.id)).toEqual(['claude-app', 'codex-app'])
  })
})

describe('gömülü yedek tablo', () => {
  it('köprünün bildirdiği gerçeklerle uyumlu', () => {
    const caps = (id: string) => findBackend(FALLBACK_BACKENDS, id)!.capabilities
    // /efforts yalnız claude-app ve codex-app’te kayıtlı (server.mjs extras).
    expect(caps('claude-app').efforts).toBe(true)
    expect(caps('codex-app').efforts).toBe(true)
    expect(caps('opencode-app').efforts).toBe(false)
    expect(caps('opencode-app').sessionRename).toBe(true)
    expect(caps('opencode-app').sessionArchive).toBe(false)
    // /interrupt yalnız claude-app’te.
    expect(caps('claude-app').interrupt).toBe(true)
    expect(caps('codex-app').interrupt).toBe(false)
    // agy oturum bağlamı taşımıyor.
    expect(caps('agy').context).toBe(false)
    expect(caps('agy').approvals).toBe(false)
  })
})
