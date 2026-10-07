import { describe, expect, it } from 'vitest'
import { isNearBottom } from './useStickToBottom'

const at = (scrollTop: number) => ({ scrollTop, scrollHeight: 1000, clientHeight: 400 })

describe('isNearBottom', () => {
  it('tam dipte doğru', () => {
    expect(isNearBottom(at(600))).toBe(true)
  })

  it('eşik içinde doğru — kesirli piksel yuvarlaması yutulmalı', () => {
    expect(isNearBottom(at(560))).toBe(true) // 40px yukarıda
  })

  it('eşiğin dışında yanlış — kullanıcı okumaya çıkmış', () => {
    expect(isNearBottom(at(300))).toBe(false)
  })

  it('eşik sınırı dahil', () => {
    expect(isNearBottom(at(520))).toBe(true) // tam 80px
    expect(isNearBottom(at(519))).toBe(false) // 81px
  })

  it('eşik ayarlanabilir', () => {
    expect(isNearBottom(at(300), 300)).toBe(true)
    expect(isNearBottom(at(300), 10)).toBe(false)
  })

  it('içerik kutudan kısayken daima dipte sayılır', () => {
    expect(isNearBottom({ scrollTop: 0, scrollHeight: 200, clientHeight: 400 })).toBe(true)
  })
})
