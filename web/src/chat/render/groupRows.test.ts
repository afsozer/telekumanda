import { describe, expect, it } from 'vitest'
import { groupRows, isThought } from './groupRows'
import type { StreamRow } from '../../lib/stream/protocol'

function row(rowId: string, role: string, thoughtIndex = -1): StreamRow {
  return { rowId, role, text: rowId, time: '', thoughtIndex }
}

const kinds = (rows: StreamRow[]) =>
  groupRows(rows).map((g) => (g.kind === 'thoughts' ? `d${g.rows.length}` : g.row.rowId))

describe('isThought', () => {
  it('thoughtIndex >= 0 düşüncedir', () => {
    expect(isThought(row('a', 'agent', 0))).toBe(true)
    expect(isThought(row('a', 'agent', 5))).toBe(true)
  })

  it("rol 'thought' ise thoughtIndex -1 olsa da düşüncedir", () => {
    // Canlıda görüldü: köprü bazı satırları thoughtIndex vermeden `thought`
    // rolüyle yolluyor. Yalnız thoughtIndex'e bakmak onları normal baloncuk
    // yapıp "THOUGHT" etiketiyle çiziyordu.
    expect(isThought(row('a', 'thought'))).toBe(true)
  })

  it('normal satır düşünce değildir', () => {
    expect(isThought(row('a', 'agent'))).toBe(false)
    expect(isThought(row('a', 'user'))).toBe(false)
  })
})

describe('groupRows', () => {
  it('ardışık düşünceleri TEK grupta toplar', () => {
    expect(
      kinds([
        row('u1', 'user'),
        row('t1', 'agent', 0),
        row('t2', 'agent', 1),
        row('t3', 'agent', 2),
        row('a1', 'agent'),
      ]),
    ).toEqual(['u1', 'd3', 'a1'])
  })

  it('araya normal satır girince yeni grup başlar', () => {
    expect(
      kinds([row('t1', 'agent', 0), row('a1', 'agent'), row('t2', 'agent', 1)]),
    ).toEqual(['d1', 'a1', 'd1'])
  })

  it('iki işaretleme biçimi aynı grupta birleşir', () => {
    expect(kinds([row('t1', 'agent', 0), row('t2', 'thought')])).toEqual(['d2'])
  })

  it('sıra korunur', () => {
    const rows = [row('a', 'user'), row('b', 'agent'), row('c', 'user')]
    expect(kinds(rows)).toEqual(['a', 'b', 'c'])
  })

  it('boş liste boş sonuç verir', () => {
    expect(groupRows([])).toEqual([])
  })

  it('grup anahtarı ilk satırın kimliğidir — liste büyüyünce değişmez', () => {
    const g = groupRows([row('t1', 'agent', 0), row('t2', 'agent', 1)])
    const buyumus = groupRows([row('t1', 'agent', 0), row('t2', 'agent', 1), row('t3', 'agent', 2)])
    expect(g[0].kind === 'thoughts' && g[0].key).toBe('t1')
    expect(buyumus[0].kind === 'thoughts' && buyumus[0].key).toBe('t1')
  })

  it('yalnız düşüncelerden oluşan liste tek grup olur', () => {
    expect(kinds([row('t1', 'agent', 0), row('t2', 'agent', 1), row('t3', 'thought')])).toEqual([
      'd3',
    ])
  })
})
