import { describe, expect, it } from 'vitest'
import { searchWithoutToken, tokenFromSearch } from './token'

describe('tokenFromSearch', () => {
  it('sorgudaki token değerini alır', () => {
    expect(tokenFromSearch('?token=abc123')).toBe('abc123')
  })

  it('başka parametrelerin arasından alır', () => {
    expect(tokenFromSearch('?a=1&token=abc123&b=2')).toBe('abc123')
  })

  it('token yoksa null döner', () => {
    expect(tokenFromSearch('?a=1')).toBeNull()
    expect(tokenFromSearch('')).toBeNull()
  })

  it('boş veya yalnız boşluktan ibaret token null sayılır', () => {
    expect(tokenFromSearch('?token=')).toBeNull()
    expect(tokenFromSearch('?token=%20%20')).toBeNull()
  })
})

describe('searchWithoutToken', () => {
  it('token parametresini çıkarır, ötekileri korur', () => {
    expect(searchWithoutToken('?a=1&token=abc&b=2')).toBe('?a=1&b=2')
  })

  it('tek parametre token ise sorgu tamamen boşalır', () => {
    expect(searchWithoutToken('?token=abc')).toBe('')
  })

  it('token yoksa null döner — adrese dokunulmayacak demek', () => {
    expect(searchWithoutToken('?a=1')).toBeNull()
    expect(searchWithoutToken('')).toBeNull()
  })
})
