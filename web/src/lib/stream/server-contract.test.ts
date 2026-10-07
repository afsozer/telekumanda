// Sözleşme testi: köprünün GERÇEK diff fonksiyonunu içeri alıp istemcinin
// indirgeyicisine karşı koşar. Elle yazılmış op'lar sunucunun ne ürettiğine
// dair bir VARSAYIMDIR; bu dosya varsayımı ortadan kaldırıyor.
//
// Sunucu tarafı değişirse bu testler kırılır — istenen tam olarak budur.

import { describe, expect, it } from 'vitest'
import { applyOps, emptyState, parseRows, reduce } from './reducer'
import type { StreamOp } from './protocol'

let diffSnapshots: ((prev: ServerSnapshot | null, next: ServerSnapshot) => StreamOp[]) | null = null
try {
  // @ts-expect-error dynamic import of bridge core if present
  const mod = await import('../../../../bridge/agent-session-core.mjs')
  diffSnapshots = mod.diffSnapshots
} catch {
  diffSnapshots = null
}
const bridgeCoreExists = Boolean(diffSnapshots)

interface ServerSnapshot {
  messages: { rowId?: string; role: string; text: string; time?: string }[]
  running?: boolean
  contextTokens?: number
  effort?: string
}

const diff = (prev: ServerSnapshot | null, next: ServerSnapshot): StreamOp[] =>
  (diffSnapshots ? diffSnapshots(prev, next) : []) as StreamOp[]

function snap(messages: ServerSnapshot['messages'], extra: Partial<ServerSnapshot> = {}): ServerSnapshot {
  return { messages, ...extra }
}

describe.skipIf(!bridgeCoreExists)('sunucu diff’i istemci indirgeyicisiyle uyumlu', () => {
  it('satır eklemesi aynen yansır', () => {
    const a = snap([{ rowId: 'r1', role: 'user', text: 'selam' }])
    const b = snap([
      { rowId: 'r1', role: 'user', text: 'selam' },
      { rowId: 'r2', role: 'agent', text: 'merhaba' },
    ])
    const result = applyOps(parseRows(a.messages), diff(a, b))
    expect(result.map((r) => r.rowId)).toEqual(['r1', 'r2'])
    expect(result[1].text).toBe('merhaba')
  })

  it('akış hâlindeki metin appendText olarak gelir ve doğru birikir', () => {
    let server = snap([{ rowId: 'r1', role: 'agent', text: '' }])
    let client = parseRows(server.messages)
    for (const parca of ['Bir', ' iki', ' üç']) {
      const next = snap([{ rowId: 'r1', role: 'agent', text: server.messages[0].text + parca }])
      const ops = diff(server, next)
      expect(ops.some((o) => o.op === 'appendText')).toBe(true)
      client = applyOps(client, ops)
      server = next
    }
    expect(client[0].text).toBe('Bir iki üç')
    expect(client[0].text).toBe(server.messages[0].text)
  })

  it('metin baştan yazıldığında patchRow gelir, sonuç yine eşleşir', () => {
    const a = snap([{ rowId: 'r1', role: 'agent', text: 'yanlış cevap' }])
    const b = snap([{ rowId: 'r1', role: 'agent', text: 'doğru cevap' }])
    const ops = diff(a, b)
    expect(ops.some((o) => o.op === 'patchRow')).toBe(true)
    expect(applyOps(parseRows(a.messages), ops)[0].text).toBe('doğru cevap')
  })

  it('baştaki satırlar düşünce (dropRows) istemcide aynı listeyi bırakır', () => {
    const a = snap([
      { rowId: 'r1', role: 'user', text: '1' },
      { rowId: 'r2', role: 'agent', text: '2' },
      { rowId: 'r3', role: 'user', text: '3' },
    ])
    const b = snap([
      { rowId: 'r2', role: 'agent', text: '2' },
      { rowId: 'r3', role: 'user', text: '3' },
    ])
    const result = applyOps(parseRows(a.messages), diff(a, b))
    expect(result.map((r) => r.rowId)).toEqual(['r2', 'r3'])
  })

  it('BOŞ TABANDAN kurulan delta satırları İKİLEMEZ', () => {
    // Köprünün gerçek kurtarma yolu: delta halkasından oynatamayınca
    // diffSnapshots(null, snapshot) çağırıyor (agent-session-core.mjs,
    // subscribe içinde). İstemcinin elinde AYNI satırlar zaten var.
    const server = snap([
      { rowId: 'r1', role: 'user', text: 'selam' },
      { rowId: 'r2', role: 'agent', text: 'merhaba' },
    ])
    const ops = diff(null, server)

    // Önce sunucunun bu yolda dropRows ÜRETMEDİĞİNİ sabitle — indirgeyicinin
    // upsert davranışının gerekçesi bu. Sunucu ileride dropRows üretmeye
    // başlarsa bu satır kırılır ve gerekçe yeniden değerlendirilir.
    expect(ops.some((o) => o.op === 'dropRows')).toBe(false)
    expect(ops.filter((o) => o.op === 'appendRow')).toHaveLength(2)

    const client = applyOps(parseRows(server.messages), ops)
    expect(client.map((r) => r.rowId)).toEqual(['r1', 'r2'])
    expect(client).toHaveLength(2)
  })

  it('meta değişimi setMeta ile gelir ve birleşir', () => {
    const a = snap([], { running: true, contextTokens: 10, effort: 'high' })
    const b = snap([], { running: false, contextTokens: 10, effort: 'high' })
    const ops = diff(a, b)
    const setMeta = ops.find((o) => o.op === 'setMeta')
    expect(setMeta).toBeDefined()
    // Yalnız DEĞİŞEN anahtar yollanır — birleştirme zorunluluğunun kanıtı.
    expect(Object.keys((setMeta as { meta: Record<string, unknown> }).meta)).toEqual(['running'])

    const before = { ...emptyState(), meta: { running: true, contextTokens: 10, effort: 'high' } }
    const { state } = reduce(before, { type: 'delta', seq: 2, ops })
    expect(state.meta.running).toBe(false)
    expect(state.meta.effort).toBe('high')
    expect(state.meta.contextTokens).toBe(10)
  })

  it('uzun bir tur boyunca istemci durumu sunucuyla birebir kalır', () => {
    const turns: ServerSnapshot[] = [
      snap([{ rowId: 'r1', role: 'user', text: 'soru' }], { running: true }),
      snap([
        { rowId: 'r1', role: 'user', text: 'soru' },
        { rowId: 'r2', role: 'agent', text: 'düşün' },
      ], { running: true }),
      snap([
        { rowId: 'r1', role: 'user', text: 'soru' },
        { rowId: 'r2', role: 'agent', text: 'düşünüyorum…' },
      ], { running: true, contextTokens: 42 }),
      snap([
        { rowId: 'r1', role: 'user', text: 'soru' },
        { rowId: 'r2', role: 'agent', text: 'düşünüyorum…' },
        { rowId: 'r3', role: 'agent', text: 'cevap' },
      ], { running: false, contextTokens: 42 }),
    ]

    let state = reduce(emptyState(), { type: 'snapshot', seq: 1, messages: turns[0].messages, running: true }).state
    for (let i = 1; i < turns.length; i++) {
      state = reduce(state, { type: 'delta', seq: i + 1, ops: diff(turns[i - 1], turns[i]) }).state
    }

    const son = turns[turns.length - 1]
    expect(state.rows.map((r) => ({ rowId: r.rowId, text: r.text }))).toEqual(
      son.messages.map((m) => ({ rowId: m.rowId!, text: m.text })),
    )
    expect(state.meta.running).toBe(false)
    expect(state.meta.contextTokens).toBe(42)
    expect(state.seq).toBe(4)
  })
})
