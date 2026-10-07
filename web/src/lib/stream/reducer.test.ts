import { describe, expect, it } from 'vitest'
import { applyOp, applyOps, emptyState, parseRow, reduce } from './reducer'
import type { SessionState, StreamRow } from './protocol'

function rows(...ids: string[]): StreamRow[] {
  return ids.map((rowId, i) => ({ rowId, role: 'agent', text: rowId, time: '', thoughtIndex: i }))
}

function stateWith(rowIds: string[], meta: Record<string, unknown> = {}): SessionState {
  return { seq: 5, sessionId: 's1', rows: rows(...rowIds), meta }
}

describe('parseRow', () => {
  it('rowId yoksa sunucunun rowKey deseniyle yedek üretir', () => {
    expect(parseRow({ role: 'user', thoughtIndex: 2 }, 7).rowId).toBe('user:2:7')
  })

  it('thoughtIndex yoksa -1', () => {
    expect(parseRow({ role: 'agent' }, 0).thoughtIndex).toBe(-1)
  })

  it('eski köprünün `id` alanını rowId olarak kabul eder', () => {
    expect(parseRow({ id: 'r9', role: 'agent' }, 0).rowId).toBe('r9')
  })
})

describe('appendRow', () => {
  it('yeni satırı sona ekler', () => {
    const next = applyOp(rows('a'), { op: 'appendRow', row: { rowId: 'b', text: 'B' } })
    expect(next.map((r) => r.rowId)).toEqual(['a', 'b'])
  })

  it('AYNI rowId geldiğinde İKİLEMEZ, üstüne yazar', () => {
    // Bu davranış kritik: köprü delta halkasından oynatamayınca
    // diffSnapshots(null, snapshot) çağırıyor ve o yol dropRows ÜRETMİYOR.
    // Düz push olsaydı istemci elinde satır varken sohbetin tamamı ikilenirdi.
    const next = applyOp(rows('a', 'b'), { op: 'appendRow', row: { rowId: 'b', text: 'yeni' } })
    expect(next.map((r) => r.rowId)).toEqual(['a', 'b'])
    expect(next[1].text).toBe('yeni')
  })

  it('girdi dizisini değiştirmez', () => {
    const before = rows('a')
    applyOp(before, { op: 'appendRow', row: { rowId: 'b' } })
    expect(before.map((r) => r.rowId)).toEqual(['a'])
  })
})

describe('patchRow', () => {
  it('mevcut satırın yerine yazar, sırayı korur', () => {
    const next = applyOp(rows('a', 'b', 'c'), {
      op: 'patchRow',
      rowId: 'b',
      row: { text: 'yeni', role: 'agent' },
    })
    expect(next.map((r) => r.rowId)).toEqual(['a', 'b', 'c'])
    expect(next[1].text).toBe('yeni')
  })

  it('bilinmeyen rowId sona eklenir', () => {
    const next = applyOp(rows('a'), { op: 'patchRow', rowId: 'z', row: { text: 'Z' } })
    expect(next.map((r) => r.rowId)).toEqual(['a', 'z'])
  })
})

describe('appendText', () => {
  it('metni mevcut satıra ekler', () => {
    const base: StreamRow[] = [{ rowId: 'a', role: 'agent', text: 'Mer', time: '', thoughtIndex: -1 }]
    const next = applyOp(base, { op: 'appendText', rowId: 'a', chunk: 'haba' })
    expect(next[0].text).toBe('Merhaba')
  })

  it('bilinmeyen satıra gelen ek yok sayılır', () => {
    const before = rows('a')
    expect(applyOp(before, { op: 'appendText', rowId: 'z', chunk: 'x' })).toEqual(before)
  })

  it('ardışık parçalar birikir — akışın normal hâli', () => {
    const base: StreamRow[] = [{ rowId: 'a', role: 'agent', text: '', time: '', thoughtIndex: -1 }]
    const next = applyOps(base, [
      { op: 'appendText', rowId: 'a', chunk: 'bir ' },
      { op: 'appendText', rowId: 'a', chunk: 'iki ' },
      { op: 'appendText', rowId: 'a', chunk: 'üç' },
    ])
    expect(next[0].text).toBe('bir iki üç')
  })
})

describe('dropRows', () => {
  it('beforeRowId dahil olmak üzere sonrasını tutar', () => {
    const next = applyOp(rows('a', 'b', 'c', 'd'), { op: 'dropRows', beforeRowId: 'c' })
    expect(next.map((r) => r.rowId)).toEqual(['c', 'd'])
  })

  it('boş beforeRowId hepsini atar — sunucu yeni liste boşken böyle yolluyor', () => {
    expect(applyOp(rows('a', 'b'), { op: 'dropRows', beforeRowId: '' })).toEqual([])
  })

  it('bilinmeyen beforeRowId hepsini atar', () => {
    expect(applyOp(rows('a', 'b'), { op: 'dropRows', beforeRowId: 'yok' })).toEqual([])
  })
})

describe('reduce: tam snapshot', () => {
  it('satırları ve metayı sıfırdan kurar', () => {
    const { state, event } = reduce(emptyState(), {
      type: 'snapshot',
      seq: 12,
      sessionId: 'abc',
      messages: [{ rowId: 'r1', role: 'user', text: 'selam' }],
      running: true,
      contextTokens: 100,
    })
    expect(event).toBe('update')
    expect(state.seq).toBe(12)
    expect(state.sessionId).toBe('abc')
    expect(state.rows.map((r) => r.rowId)).toEqual(['r1'])
    expect(state.meta.running).toBe(true)
    expect(state.meta.contextTokens).toBe(100)
  })

  it('birikmiş metayı ATAR — tam snapshot yeni gerçektir', () => {
    const before = stateWith(['a'], { running: true, effort: 'high' })
    const { state } = reduce(before, { type: 'snapshot', seq: 20, messages: [], running: false })
    expect(state.meta.running).toBe(false)
    expect(state.meta.effort).toBeUndefined()
  })

  it('meta anahtarı olmayan kök alanları taşımaz', () => {
    const { state } = reduce(emptyState(), { type: 'snapshot', messages: [], text: 'transkript' })
    expect('text' in state.meta).toBe(false)
  })

  it('conversation ile snapshot aynı işlenir', () => {
    const { event } = reduce(emptyState(), { type: 'conversation', messages: [] })
    expect(event).toBe('update')
  })
})

describe('reduce: delta', () => {
  it('setMeta BİRLEŞTİRİR, değiştirmez', () => {
    const before = stateWith(['a'], { running: true, effort: 'high' })
    const { state } = reduce(before, {
      type: 'delta',
      seq: 6,
      ops: [{ op: 'setMeta', meta: { running: false } }],
    })
    expect(state.meta.running).toBe(false)
    // Gelmeyen anahtara dokunulmaz: "alan yok" ile "alan null" ayrımı korunur.
    expect(state.meta.effort).toBe('high')
  })

  it('seq gelmezse öncekini korur', () => {
    const { state } = reduce(stateWith(['a']), { type: 'delta', ops: [] })
    expect(state.seq).toBe(5)
  })

  it('sessionId gelmezse öncekini korur', () => {
    const { state } = reduce(stateWith(['a']), { type: 'delta', seq: 6, ops: [] })
    expect(state.sessionId).toBe('s1')
  })

  it('farklı sessionId görünür kalır — yeniden anahtarlama tespiti', () => {
    const { state } = reduce(stateWith(['a']), { type: 'delta', sessionId: 'baska', ops: [] })
    expect(state.sessionId).toBe('baska')
  })

  it('ops yoksa çökmez', () => {
    const { state, event } = reduce(stateWith(['a']), { type: 'delta', seq: 9 })
    expect(event).toBe('update')
    expect(state.rows).toHaveLength(1)
  })
})

describe('reduce: akış sonu', () => {
  it('end olayı durumu bozmaz', () => {
    const before = stateWith(['a'])
    const { state, event } = reduce(before, { type: 'end' })
    expect(event).toBe('end')
    expect(state).toEqual(before)
  })

  it('error mesajı taşınır', () => {
    const { event, error } = reduce(emptyState(), { type: 'error', error: 'session not found' })
    expect(event).toBe('error')
    expect(error).toBe('session not found')
  })

  it('bilinmeyen tip yok sayılır', () => {
    const before = stateWith(['a'])
    const { state, event } = reduce(before, { type: 'bilinmeyen' } as never)
    expect(event).toBe('ignored')
    expect(state).toBe(before)
  })
})
