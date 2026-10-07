// Akış indirgeyicisi — SAF. Soket yok, zamanlayıcı yok, yan etki yok.
// Bağlantı yönetimi sessionStream.ts'te; burası yalnızca "gelen mesaj + mevcut
// durum -> yeni durum".

import {
  emptyState,
  META_KEYS,
  type ServerMessage,
  type SessionState,
  type StreamMeta,
  type StreamOp,
  type StreamRow,
} from './protocol'

function str(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : value == null ? fallback : String(value)
}

/**
 * Satırı normalize eder. `rowId` yoksa sunucunun `rowKey`'iyle aynı mantıkta
 * bir yedek üretilir (role:thoughtIndex:index) — eski köprülerle uyum için.
 */
export function parseRow(raw: Record<string, unknown>, index: number): StreamRow {
  const thoughtIndex = typeof raw.thoughtIndex === 'number' ? raw.thoughtIndex : -1
  const role = str(raw.role)
  const rowId = str(raw.rowId) || str(raw.id) || `${role}:${thoughtIndex}:${index}`
  return { rowId, role, text: str(raw.text), time: str(raw.time), thoughtIndex }
}

export function parseRows(messages: unknown): StreamRow[] {
  if (!Array.isArray(messages)) return []
  return messages
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map(parseRow)
}

/** Mesaj kökünden yalnızca bilinen meta anahtarlarını toplar. */
export function metaFromRoot(raw: Record<string, unknown>): StreamMeta {
  const meta: Record<string, unknown> = {}
  for (const key of META_KEYS) {
    if (key in raw) meta[key] = raw[key]
  }
  return meta as StreamMeta
}

/**
 * Tek bir op'u uygular ve YENİ satır dizisi döndürür (girdi değişmez).
 *
 * `appendRow` KASITLI olarak upsert: aynı rowId zaten varsa yerine yazar.
 * Sebep ölçülmüş bir sunucu davranışı: köprü delta halkasından oynatamadığında
 * `diffSnapshots(null, snapshot)` çağırıyor ve bu yol `dropRows` ÜRETMİYOR
 * (agent-session-core.mjs: ilk dal `oldRows.length && newRows.length` boş taban
 * için çalışmaz). Yani istemci elinde satır varken baştan kurulan bir delta
 * alabiliyor. Düz `push` olsaydı bütün sohbet ikilenirdi.
 */
export function applyOp(rows: StreamRow[], op: StreamOp): StreamRow[] {
  switch (op.op) {
    case 'appendRow': {
      const row = parseRow(op.row ?? {}, rows.length)
      const index = rows.findIndex((r) => r.rowId === row.rowId)
      if (index < 0) return [...rows, row]
      const next = rows.slice()
      next[index] = row
      return next
    }
    case 'patchRow': {
      const index = rows.findIndex((r) => r.rowId === op.rowId)
      const row = parseRow({ rowId: op.rowId, ...(op.row ?? {}) }, index < 0 ? rows.length : index)
      if (index < 0) return [...rows, row]
      const next = rows.slice()
      next[index] = row
      return next
    }
    case 'appendText': {
      const index = rows.findIndex((r) => r.rowId === op.rowId)
      // Bilinmeyen satıra gelen ek yok sayılır: satırsız metin tutmanın yeri
      // yok ve uydurulmuş bir satır sıralamayı bozar.
      if (index < 0) return rows
      const next = rows.slice()
      next[index] = { ...next[index], text: next[index].text + str(op.chunk) }
      return next
    }
    case 'dropRows': {
      // `beforeRowId`ye KADAR olanları at; o satırın kendisi kalır.
      // Boş/bulunamayan id "hepsini at" demek (sunucu yeni liste boşken
      // beforeRowId'yi '' yolluyor).
      const index = op.beforeRowId ? rows.findIndex((r) => r.rowId === op.beforeRowId) : -1
      return index >= 0 ? rows.slice(index) : []
    }
    default:
      return rows
  }
}

export function applyOps(rows: StreamRow[], ops: StreamOp[]): StreamRow[] {
  return ops.reduce(applyOp, rows)
}

/**
 * Köprüden gelen bir mesajı duruma uygular.
 *
 * Dönen `state` daima TAM birleşmiş durumdur — tüketicinin ayrıca delta
 * birleştirmesi gerekmez. `event` tüketicinin tepki vermesi gereken şeyi
 * söyler ('end' / 'error' akış bitti demek).
 */
export function reduce(
  state: SessionState,
  message: ServerMessage,
): { state: SessionState; event: 'update' | 'end' | 'error' | 'ignored'; error?: string } {
  switch (message.type) {
    case 'snapshot':
    case 'conversation': {
      const raw = message as Record<string, unknown>
      const seq = typeof raw.seq === 'number' ? raw.seq : state.seq
      const sessionId = str(raw.sessionId) || null
      // Tam snapshot: satırlar da meta da sıfırdan kurulur, birikim atılır.
      return {
        state: { seq, sessionId, rows: parseRows(raw.messages), meta: metaFromRoot(raw) },
        event: 'update',
      }
    }
    case 'delta': {
      const ops = Array.isArray(message.ops) ? message.ops : []
      let rows = state.rows
      let meta = state.meta
      for (const op of ops) {
        if (op?.op === 'setMeta') {
          // Birleştirme: sunucu yalnız DEĞİŞEN anahtarları yolluyor.
          // Gelmeyen anahtara dokunulmaz — üç durumlu alanlar (goal gibi)
          // bu sayede "alan yok" ile "alan null" ayrımını koruyor.
          meta = { ...meta, ...(op.meta as StreamMeta) }
        } else if (op) {
          rows = applyOp(rows, op)
        }
      }
      return {
        state: {
          seq: typeof message.seq === 'number' ? message.seq : state.seq,
          sessionId: str(message.sessionId) || state.sessionId,
          rows,
          meta,
        },
        event: 'update',
      }
    }
    case 'end':
      return { state, event: 'end' }
    case 'error':
      return { state, event: 'error', error: str(message.error) || 'Akış hatası' }
    default:
      return { state, event: 'ignored' }
  }
}

export { emptyState }
