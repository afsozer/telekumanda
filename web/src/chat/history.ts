// Eski mesajların sayfalanması.
//
// Akış TAM geçmişi taşımıyor: canlı oturum son 4000 satırla sınırlı
// (bridge/session-utils.mjs, MAX_ROWS_HARD) ve taşanlar arşiv JSONL'ine
// düşüyor. Yukarı kaydırınca eksik kalan geçmiş buradan çekilir.
//
// `GET /<backend>/conversation?sessionId=&before=&limit=`
//   before = bir rowId; SIKI ÖNCESİNDEKİ satırlar döner (o satır dahil değil)
//   limit  = köprüde 500 ile sınırlanıyor
//   boş dizi = geçmişin başındayız
//
// rowId'ler iki kanalda da aynı: köprü satırlara `<sessionId>:row:N` kalıcı
// kimliği veriyor (agent-session-core.mjs, ensureMessageRowIds) ve arşive de
// o kimlikle yazıyor. Bu yüzden birleştirme rowId üzerinden güvenli.

import { apiGet } from '../lib/api'
import type { StreamRow } from '../lib/stream/protocol'
import { parseRows } from '../lib/stream/reducer'

export const HISTORY_PAGE = 200

interface ConversationResponse {
  messages?: unknown
}

export async function fetchOlderRows(
  sessionId: string,
  beforeRowId: string,
  { backend = 'claude-app', limit = HISTORY_PAGE, signal }: {
    backend?: string
    limit?: number
    signal?: AbortSignal
  } = {},
): Promise<StreamRow[]> {
  const params = new URLSearchParams({ sessionId, limit: String(limit) })
  if (beforeRowId) params.set('before', beforeRowId)
  const data = await apiGet<ConversationResponse>(`/${backend}/conversation?${params}`, signal)
  return parseRows(data.messages)
}

/**
 * Eski sayfayı mevcut satırların önüne ekler.
 *
 * Örtüşme mümkün: akış penceresi ile sayfanın kuyruğu aynı satırları
 * taşıyabiliyor. Kimliği zaten görünen satırlar ELENİR — aksi hâlde yukarı
 * kaydırma sohbeti ikilerdi. Sıra korunur: eski sayfa önce, mevcutlar sonra.
 */
export function prependRows(older: StreamRow[], current: StreamRow[]): StreamRow[] {
  if (!older.length) return current
  const seen = new Set(current.map((row) => row.rowId))
  const fresh = older.filter((row) => !seen.has(row.rowId))
  return fresh.length ? [...fresh, ...current] : current
}
