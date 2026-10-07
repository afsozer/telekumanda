import { afterEach, describe, expect, it, vi } from 'vitest'
import { MAX_ATTACHMENT_BYTES, uploadAttachment, withAttachments } from './attachments'
import type { Attachment } from './attachments'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

function fakeFile(name: string, bytes: number, type = 'text/plain'): File {
  const blob = new Blob([new Uint8Array(bytes)], { type })
  return new File([blob], name, { type })
}

afterEach(() => vi.unstubAllGlobals())

describe('uploadAttachment', () => {
  it('/context/file ucuna ham bayt gönderir, adı SORGUDA taşır', async () => {
    // Türkçe adlar başlıkta taşınamıyor (non-ASCII header reddediliyor);
    // köprü de adı sorgudan okuyor.
    let seen: { url: string; init: RequestInit } | null = null
    vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
      seen = { url, init: init ?? {} }
      return Promise.resolve(json({ ok: true, name: 'dilekçe.docx', path: 'C:/tmp/1_dilekçe.docx' }))
    })

    const result = await uploadAttachment(fakeFile('dilekçe.docx', 10))
    const url = new URL(seen!.url, 'http://x')
    expect(url.pathname).toBe('/context/file')
    expect(url.searchParams.get('filename')).toBe('dilekçe.docx')
    expect(seen!.init.method).toBe('POST')
    expect((seen!.init.headers as Record<string, string>)['content-type']).toBe(
      'application/octet-stream',
    )
    // Gövde JSON'a SARILMAMALI — sarılırsa dosya bozulur.
    expect(seen!.init.body).toBeInstanceOf(ArrayBuffer)
    expect(result.path).toBe('C:/tmp/1_dilekçe.docx')
  })

  it('görsel MIME türü işaretlenir', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve(json({ ok: true, name: 'a.png', path: 'C:/tmp/a.png' })))
    expect((await uploadAttachment(fakeFile('a.png', 5, 'image/png'))).isImage).toBe(true)
    expect((await uploadAttachment(fakeFile('a.txt', 5, 'text/plain'))).isImage).toBe(false)
  })

  it('boş dosya reddedilir — istek bile atılmaz', async () => {
    const fn = vi.fn()
    vi.stubGlobal('fetch', fn)
    await expect(uploadAttachment(fakeFile('bos.txt', 0))).rejects.toThrow(/boş/)
    expect(fn).not.toHaveBeenCalled()
  })

  it('25MB üstü reddedilir', async () => {
    const fn = vi.fn()
    vi.stubGlobal('fetch', fn)
    await expect(uploadAttachment(fakeFile('buyuk.bin', MAX_ATTACHMENT_BYTES + 1))).rejects.toThrow(
      /çok büyük/,
    )
    expect(fn).not.toHaveBeenCalled()
  })

  it('köprü yol vermezse hata fırlatır', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve(json({ ok: true, name: 'a.txt' })))
    await expect(uploadAttachment(fakeFile('a.txt', 5))).rejects.toThrow(/eklenemedi/)
  })

  it('köprü hata döndürürse metni taşınır', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve(json({ ok: false, error: 'disk dolu' })))
    await expect(uploadAttachment(fakeFile('a.txt', 5))).rejects.toThrow('disk dolu')
  })
})

describe('withAttachments', () => {
  const ek = (name: string, path: string): Attachment => ({ name, path, isImage: false })

  it('ek yoksa metne dokunmaz', () => {
    expect(withAttachments('selam', [])).toBe('selam')
  })

  it('Android ile AYNI biçimi üretir', () => {
    // ConversationDelegate.kt:95 ile birebir. Aynı oturuma iki istemciden
    // bakıldığında geçmiş tutarlı görünsün.
    expect(withAttachments('şuna bak', [ek('a.png', 'C:/tmp/a.png')])).toBe(
      'şuna bak\n\nEk dosyalar:\n- a.png: C:/tmp/a.png',
    )
  })

  it('birden çok ek satır satır listelenir', () => {
    expect(withAttachments('x', [ek('a', '/1'), ek('b', '/2')])).toBe(
      'x\n\nEk dosyalar:\n- a: /1\n- b: /2',
    )
  })

  it('metin boşken de ek listesi üretilir', () => {
    expect(withAttachments('', [ek('a.png', '/a')])).toBe('\n\nEk dosyalar:\n- a.png: /a')
  })
})
