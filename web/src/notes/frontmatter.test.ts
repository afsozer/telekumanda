import { describe, expect, it } from 'vitest'
import { frontmatterTitle, joinFrontmatter, splitFrontmatter } from './frontmatter'

const NOTE = `---
title: Duruşma notu
reminder_at: 2026-08-12T14:00:00+03:00
reminder_state: pending
---
İlk satır

İkinci paragraf
`

describe('splitFrontmatter', () => {
  it('bloğu gövdeden ayırır', () => {
    const { frontmatter, body } = splitFrontmatter(NOTE)
    expect(frontmatter).toContain('reminder_state: pending')
    expect(body).toBe('İlk satır\n\nİkinci paragraf\n')
  })

  // Kritik: `reminder_*` kaybolursa hatırlatıcı sessizce düşer. Editör gövdeyi
  // değiştirse bile blok BİREBİR geri dönmeli.
  it('gövde değişse de blok birebir korunur', () => {
    const { frontmatter, body } = splitFrontmatter(NOTE)
    const rebuilt = joinFrontmatter(frontmatter, body.replace('İlk satır', 'Değişti'))
    expect(rebuilt.startsWith(frontmatter)).toBe(true)
    expect(rebuilt).toContain('Değişti')
    expect(rebuilt).toContain('reminder_state: pending')
  })

  it('gidiş-dönüş dosyayı büyütmez', () => {
    const { frontmatter, body } = splitFrontmatter(NOTE)
    expect(joinFrontmatter(frontmatter, body)).toBe(NOTE)
  })

  it('frontmatter yoksa her şey gövdedir', () => {
    expect(splitFrontmatter('düz metin')).toEqual({ frontmatter: '', body: 'düz metin' })
  })

  // `---` ile başlayıp KAPANMAYAN dosya frontmatter DEĞİLDİR: yatay çizgiyle
  // başlayan bir notu kesip atmak, gövdenin yarısını yutmak demekti.
  it('kapanışı olmayan blok gövde sayılır', () => {
    const text = '---\nbaşlık gibi ama kapanmıyor\n\nmetin'
    expect(splitFrontmatter(text)).toEqual({ frontmatter: '', body: text })
  })

  it('CRLF satır sonlarında da çalışır', () => {
    const { frontmatter, body } = splitFrontmatter('---\r\ntitle: x\r\n---\r\ngövde')
    expect(frontmatter).toContain('title: x')
    expect(body).toBe('gövde')
  })

  it('boş gövdeli not bozulmaz', () => {
    const { frontmatter, body } = splitFrontmatter('---\ntitle: x\n---\n')
    expect(body).toBe('')
    expect(joinFrontmatter(frontmatter, body)).toBe('---\ntitle: x\n---\n')
  })
})

describe('frontmatterTitle', () => {
  it('başlığı okur', () => {
    expect(frontmatterTitle(splitFrontmatter(NOTE).frontmatter)).toBe('Duruşma notu')
  })

  it('tırnakları soyar', () => {
    expect(frontmatterTitle('---\ntitle: "Tırnaklı"\n---\n')).toBe('Tırnaklı')
  })

  it('başlık yoksa boş döner', () => {
    expect(frontmatterTitle('---\nreminder_state: pending\n---\n')).toBe('')
  })
})
