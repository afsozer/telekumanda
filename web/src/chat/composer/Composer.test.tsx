// @vitest-environment happy-dom
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from './Composer'

interface Call {
  url: string
  body: Record<string, unknown>
}

let calls: Call[] = []
let queue: (Response | Error)[] = []

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

beforeEach(() => {
  calls = []
  queue = []
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    // Komut listesi açılışta çekiliyor; bu testlerin konusu değil, sayıma
    // girmesin (aksi hâlde her "tek istek atıldı" iddiası kırılır).
    if (url.startsWith('/slash')) return Promise.resolve(json(200, { commands: [] }))
    // Ek yükleme ham ArrayBuffer gönderiyor; onu JSON sanmak mock'u patlatır.
    const raw = init?.body
    const body =
      typeof raw === 'string' ? (JSON.parse(raw) as Record<string, unknown>) : { raw: true }
    calls.push({ url, body })
    const next = queue.shift() ?? json(200, { ok: true })
    if (next instanceof Error) return Promise.reject(next)
    return Promise.resolve(next)
  })
})

afterEach(() => vi.unstubAllGlobals())

const area = () => screen.getByLabelText('Mesaj') as HTMLTextAreaElement
const type = (value: string) => fireEvent.change(area(), { target: { value } })

describe('Composer', () => {
  it('Enter gönderir', async () => {
    render(<Composer sessionId="o1" />)
    type('selam')
    fireEvent.keyDown(area(), { key: 'Enter' })

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toBe('/claude-app/prompt')
    expect(calls[0].body.text).toBe('selam')
  })

  it('Shift+Enter GÖNDERMEZ — satır atlamak için', async () => {
    render(<Composer sessionId="o1" />)
    type('selam')
    fireEvent.keyDown(area(), { key: 'Enter', shiftKey: true })
    expect(calls).toHaveLength(0)
  })

  it('IME ile yazarken Enter göndermez', async () => {
    // Türkçe/çince düzenlerde Enter kelime onaylar; o sırada göndermek
    // yarım kelimeyi yollardı.
    render(<Composer sessionId="o1" />)
    type('sela')
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })
    Object.defineProperty(event, 'isComposing', { value: true })
    area().dispatchEvent(event)
    expect(calls).toHaveLength(0)
  })

  it('boş ve yalnız boşluktan ibaret mesaj gönderilmez', async () => {
    render(<Composer sessionId="o1" />)
    fireEvent.keyDown(area(), { key: 'Enter' })
    type('   ')
    fireEvent.keyDown(area(), { key: 'Enter' })
    expect(calls).toHaveLength(0)
  })

  it('gönderince kutu temizlenir', async () => {
    render(<Composer sessionId="o1" />)
    type('selam')
    fireEvent.keyDown(area(), { key: 'Enter' })
    await waitFor(() => expect(area().value).toBe(''))
  })

  it('hata olursa metin GERİ konur — kaybolmaz', async () => {
    queue = [json(400, { ok: false, error: 'oturum yok' })]
    render(<Composer sessionId="o1" />)
    type('kıymetli mesaj')
    fireEvent.keyDown(area(), { key: 'Enter' })

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('oturum yok'))
    expect(area().value).toBe('kıymetli mesaj')
  })

  it('model ve izin kipi gövdeye geçer', async () => {
    render(<Composer sessionId="o1" model="opus" permissionMode="plan" />)
    type('x')
    fireEvent.keyDown(area(), { key: 'Enter' })
    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].body.model).toBe('opus')
    expect(calls[0].body.permissionMode).toBe('plan')
  })

  it('gönderim sürerken ikinci Enter ikinci istek ATMAZ', async () => {
    let release: (value: Response) => void = () => {}
    queue = []
    vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
      if (url.startsWith('/slash')) return Promise.resolve(json(200, { commands: [] }))
      calls.push({ url, body: JSON.parse(String(init?.body ?? '{}')) })
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    })

    render(<Composer sessionId="o1" />)
    type('selam')
    fireEvent.keyDown(area(), { key: 'Enter' })
    await waitFor(() => expect(calls).toHaveLength(1))

    type('tekrar')
    fireEvent.keyDown(area(), { key: 'Enter' })
    expect(calls).toHaveLength(1)

    release(json(200, { ok: true }))
  })

  it('disabled iken gönderilmez', () => {
    render(<Composer sessionId="o1" disabled />)
    type('selam')
    fireEvent.keyDown(area(), { key: 'Enter' })
    expect(calls).toHaveLength(0)
  })

  it('oturum yokken gönderilmez', () => {
    render(<Composer sessionId="" />)
    type('selam')
    fireEvent.keyDown(area(), { key: 'Enter' })
    expect(calls).toHaveLength(0)
  })

  it('ajan çalışırken yer tutucu sıraya alınacağını söyler', () => {
    render(<Composer sessionId="o1" running />)
    expect(area().placeholder).toContain('sıraya')
  })

  it('gönderilince onSent çağrılır', async () => {
    const onSent = vi.fn()
    render(<Composer sessionId="o1" onSent={onSent} />)
    type('selam')
    fireEvent.keyDown(area(), { key: 'Enter' })
    await waitFor(() => expect(onSent).toHaveBeenCalledOnce())
  })

  it('yalnız ek ile de gönderilebilir — metin şart değil', async () => {
    queue = [
      json(200, { ok: true, name: 'a.png', path: 'C:/tmp/a.png' }),
      json(200, { ok: true }),
    ]
    render(<Composer sessionId="o1" />)

    const input = screen.getByLabelText('Dosya seç') as HTMLInputElement
    const file = new File([new Uint8Array(4)], 'a.png', { type: 'image/png' })
    fireEvent.change(input, { target: { files: [file] } })

    await waitFor(() => expect(screen.getByText(/a\.png/)).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Gönder' }))

    await waitFor(() => expect(calls.some((c) => c.url === '/claude-app/prompt')).toBe(true))
    const prompt = calls.find((c) => c.url === '/claude-app/prompt')!
    expect(prompt.body.text).toContain('Ek dosyalar:')
    expect(prompt.body.text).toContain('C:/tmp/a.png')
  })

  it('ek yüklenince prompt metnine yolu iliştirilir', async () => {
    queue = [
      json(200, { ok: true, name: 'not.txt', path: 'C:/tmp/not.txt' }),
      json(200, { ok: true }),
    ]
    render(<Composer sessionId="o1" />)
    fireEvent.change(screen.getByLabelText('Dosya seç'), {
      target: { files: [new File([new Uint8Array(3)], 'not.txt', { type: 'text/plain' })] },
    })
    await waitFor(() => expect(screen.getByText(/not\.txt/)).toBeInTheDocument())

    type('şuna bak')
    fireEvent.keyDown(area(), { key: 'Enter' })
    await waitFor(() => expect(calls.some((c) => c.url === '/claude-app/prompt')).toBe(true))
    expect(calls.find((c) => c.url === '/claude-app/prompt')!.body.text).toBe(
      ['şuna bak', '', 'Ek dosyalar:', '- not.txt: C:/tmp/not.txt'].join('\n'),
    )
  })

  it('ek kaldırılabilir', async () => {
    queue = [json(200, { ok: true, name: 'a.png', path: 'C:/tmp/a.png' })]
    render(<Composer sessionId="o1" />)
    fireEvent.change(screen.getByLabelText('Dosya seç'), {
      target: { files: [new File([new Uint8Array(2)], 'a.png', { type: 'image/png' })] },
    })
    await waitFor(() => expect(screen.getByText(/a\.png/)).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /ekini kaldır/ }))
    expect(screen.queryByText(/a\.png/)).toBeNull()
  })

  it('ek de metin de yokken gönderilmez', () => {
    render(<Composer sessionId="o1" />)
    fireEvent.keyDown(area(), { key: 'Enter' })
    expect(calls).toHaveLength(0)
  })

  it('/ yazınca komut menüsü açılır ve Enter GÖNDERMEZ, seçer', async () => {
    render(<Composer sessionId="o1" commands={[{ name: 'compact', desc: 'sıkıştır' }]} />)
    type('/comp')

    const option = await screen.findByRole('option')
    expect(option).toHaveTextContent('/compact')

    fireEvent.keyDown(area(), { key: 'Enter' })
    // Gönderilmedi; kutuya komut yazıldı.
    expect(calls.some((c) => c.url === '/claude-app/prompt')).toBe(false)
    expect(area().value).toBe('/compact ')
  })

  it('menüden tıklayarak seçilebilir', async () => {
    render(<Composer sessionId="o1" commands={[{ name: 'clear' }, { name: 'compact' }]} />)
    type('/c')
    const options = await screen.findAllByRole('option')
    fireEvent.click(options[1])
    expect(area().value).toBe('/compact ')
  })

  it('Escape menüyü kapatır, sonraki Enter gönderir', async () => {
    render(<Composer sessionId="o1" commands={[{ name: 'compact' }]} />)
    type('/comp')
    await screen.findByRole('option')

    fireEvent.keyDown(area(), { key: 'Escape' })
    expect(screen.queryByRole('option')).toBeNull()

    fireEvent.keyDown(area(), { key: 'Enter' })
    await waitFor(() => expect(calls.some((c) => c.url === '/claude-app/prompt')).toBe(true))
  })

  it('komut seçildikten sonra Enter gönderir', async () => {
    render(<Composer sessionId="o1" commands={[{ name: 'compact' }]} />)
    type('/comp')
    await screen.findByRole('option')
    fireEvent.keyDown(area(), { key: 'Enter' })

    fireEvent.keyDown(area(), { key: 'Enter' })
    await waitFor(() => expect(calls.some((c) => c.url === '/claude-app/prompt')).toBe(true))
    expect(calls.find((c) => c.url === '/claude-app/prompt')!.body.text).toBe('/compact')
  })

  it('yol yazarken menü açılmaz', () => {
    render(<Composer sessionId="o1" commands={[{ name: 'clear' }]} />)
    type('/c/Users/Dev/agtest')
    expect(screen.queryByRole('option')).toBeNull()
  })

  it('oturum komutları varken uca sorulmaz', () => {
    render(<Composer sessionId="o1" commands={[{ name: 'clear' }]} />)
    expect(calls.some((c) => c.url.startsWith('/slash'))).toBe(false)
  })

  it('şıklar tıklanınca kutuya yazılır, GÖNDERİLMEZ', async () => {
    // Şıkka bir şey eklemek isteyebilirsin ("2 ama şunu da yap") ve kazara
    // tıklama tur başlatmasın.
    render(<Composer sessionId="o1" choices={['1) Evet', '2) Hayır']} />)
    fireEvent.click(screen.getByRole('button', { name: '2) Hayır' }))

    expect(area().value).toBe('2) Hayır')
    expect(calls.some((c) => c.url === '/claude-app/prompt')).toBe(false)
  })

  it('komut menüsü açıkken şıklar gizlenir', async () => {
    render(<Composer sessionId="o1" choices={['1) Evet']} commands={[{ name: 'compact' }]} />)
    expect(screen.getByRole('button', { name: '1) Evet' })).toBeInTheDocument()

    type('/comp')
    await screen.findByRole('option')
    expect(screen.queryByRole('button', { name: '1) Evet' })).toBeNull()
  })

  it('şık yokken hiç çizilmez', () => {
    const { container } = render(<Composer sessionId="o1" choices={[]} />)
    expect(container.querySelectorAll('li').length).toBe(0)
  })
})

describe('Composer — tur-içi yönlendirme (steer)', () => {
  it('running + canSteer iken steer ucuna gider, düğme Yönlendir olur', async () => {
    render(<Composer sessionId="o1" backend="opencode2-app" running canSteer />)
    expect(screen.getByRole('button', { name: 'Yönlendir' })).toBeInTheDocument()
    type('ara ver, şunu da yap')
    fireEvent.keyDown(area(), { key: 'Enter' })

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toBe('/opencode2-app/steer')
    expect(calls[0].body).toMatchObject({ sessionId: 'o1', text: 'ara ver, şunu da yap' })
  })

  it('running ama canSteer yoksa normal prompt gider (sıraya)', async () => {
    render(<Composer sessionId="o1" backend="opencode2-app" running />)
    type('sıraya ekle')
    fireEvent.keyDown(area(), { key: 'Enter' })

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toBe('/opencode2-app/prompt')
  })

  it('steer hata verirse metin geri konur', async () => {
    queue = [json(400, { ok: false, error: 'tur bulunamadı' })]
    render(<Composer sessionId="o1" backend="opencode2-app" running canSteer />)
    type('yönlendirme denemesi')
    fireEvent.keyDown(area(), { key: 'Enter' })

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('tur bulunamadı'))
    expect(area().value).toBe('yönlendirme denemesi')
  })

  it('steer başarılıysa kutu temizlenir', async () => {
    render(<Composer sessionId="o1" backend="opencode2-app" running canSteer />)
    type('git')
    fireEvent.keyDown(area(), { key: 'Enter' })
    await waitFor(() => expect(area().value).toBe(''))
    expect(calls[0].url).toBe('/opencode2-app/steer')
  })
})
