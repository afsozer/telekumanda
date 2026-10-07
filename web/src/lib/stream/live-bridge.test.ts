// CANLI köprüye karşı entegrasyon testi. Varsayılan olarak ATLANIR.
//
// Çalıştırmak için köprünün adresini ve token'ını ortamdan ver:
//
//   BRIDGE_URL=http://127.0.0.1:8787 BRIDGE_TOKEN=... npx vitest run live-bridge
//
// Token repoda DURMAZ; bridge/config.json gitignore'da. Bu dosya yalnızca
// ortam değişkeni verildiğinde koşar, CI'da ve normal `npm test`te sessizce
// atlanır.
//
// Ölçtüğü şey birim testlerinin ölçemediği tek şey: köprünün GERÇEKTEN
// yolladığı çerçeve dizisinin indirgeyiciden geçince REST'ten okunan sohbetle
// aynı sonucu vermesi — ve `since` ile yeniden bağlanmanın satır ikilememesi.

import { describe, expect, it } from 'vitest'
import { emptyState, type SessionState } from './protocol'
import { reduce } from './reducer'

// Node ortam değişkenleri. `process` doğrudan kullanılmıyor: uygulama tsconfig'i
// tarayıcı hedefli, node tiplerini oraya sokmak yalnızca bu test dosyası için
// bütün uygulamayı kirletirdi.
const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}

const BRIDGE = env.BRIDGE_URL ?? 'http://127.0.0.1:8787'
const TOKEN = env.BRIDGE_TOKEN ?? ''
const BACKEND = env.BRIDGE_BACKEND ?? 'claude-app'

const run = TOKEN ? describe : describe.skip

interface SessionSummary {
  id: string
}

async function api<T>(path: string): Promise<T> {
  const res = await fetch(`${BRIDGE}${path}`, { headers: { authorization: `Bearer ${TOKEN}` } })
  if (!res.ok) throw new Error(`${path} -> ${res.status}`)
  return (await res.json()) as T
}

/**
 * Akışı `since`den açar, ilk sessiz aralığa kadar dinler ve biriken durumu
 * döndürür. Köprü bağlanır bağlanmaz ya tam snapshot ya da delta yolluyor.
 */
function drain(
  sessionId: string,
  since: number | null,
  initial: SessionState,
  quietMs = 1200,
): Promise<SessionState> {
  return new Promise((resolve, reject) => {
    const params = new URLSearchParams({ session: sessionId, delta: '1', token: TOKEN })
    if (since !== null) params.set('since', String(since))
    const ws = new WebSocket(`${BRIDGE.replace(/^http/, 'ws')}/${BACKEND}/stream?${params}`)

    let state = initial
    let quiet: ReturnType<typeof setTimeout> | null = null
    const finish = () => {
      if (quiet) clearTimeout(quiet)
      try { ws.close() } catch { /* zaten kapalı */ }
      resolve(state)
    }
    const bump = () => {
      if (quiet) clearTimeout(quiet)
      quiet = setTimeout(finish, quietMs)
    }

    ws.onopen = bump
    ws.onmessage = (event) => {
      state = reduce(state, JSON.parse(String(event.data))).state
      bump()
    }
    ws.onerror = () => reject(new Error('WebSocket hatası'))
    setTimeout(finish, 15_000) // kesin üst sınır
  })
}

run('canlı köprü akışı', () => {
  it('akıştan kurulan durum REST sohbetinin KUYRUĞUYLA birebir aynı', async () => {
    const { sessions } = await api<{ sessions: SessionSummary[] }>(`/${BACKEND}/sessions`)
    expect(sessions.length, 'canlı oturum yok — önce bir oturum başlat').toBeGreaterThan(0)
    const sessionId = sessions[0].id

    const streamed = await drain(sessionId, null, emptyState())
    const rest = await api<{ messages: { text?: string }[] }>(
      `/${BACKEND}/conversation?sessionId=${encodeURIComponent(sessionId)}`,
    )

    // Akış TAM geçmişi taşımaz: canlı oturum son MAX_ROWS_HARD (4000) satırla
    // sınırlı, taşanlar arşiv JSONL'ine düşüyor (session-utils.mjs capMessages).
    // /conversation ise arşivle birlikte hepsini veriyor. Bu yüzden karşılaştırma
    // kuyruk üzerinden — eşitlik beklemek istemciyi değil, sözleşmeyi yanlış
    // okumak olurdu.
    expect(streamed.rows.length).toBeLessThanOrEqual(rest.messages.length)
    expect(streamed.rows.length).toBeGreaterThan(0)

    const tail = rest.messages.slice(-streamed.rows.length).map((m) => m.text ?? '')
    expect(streamed.rows.map((r) => r.text)).toEqual(tail)
  }, 40_000)

  it('since ile yeniden bağlanmak satırları İKİLEMEZ', async () => {
    const { sessions } = await api<{ sessions: SessionSummary[] }>(`/${BACKEND}/sessions`)
    const sessionId = sessions[0].id

    // 1) Baştan kur.
    const first = await drain(sessionId, null, emptyState())
    expect(first.rows.length).toBeGreaterThan(0)

    // 2) Kopmuş gibi davran: aynı durumla ve since ile yeniden bağlan.
    //    Köprü ya kaçan deltaları oynatır ya da tam snapshot'a düşer;
    //    her iki yolda da sonuç aynı olmalı.
    const second = await drain(sessionId, first.seq, first)

    expect(second.rows.length).toBe(first.rows.length)
    expect(second.rows.map((r) => r.rowId)).toEqual(first.rows.map((r) => r.rowId))
    expect(new Set(second.rows.map((r) => r.rowId)).size).toBe(second.rows.length)
  }, 60_000)

  it('çok eski bir since sunucuyu tam snapshot’a düşürür, sonuç yine tutarlı', async () => {
    const { sessions } = await api<{ sessions: SessionSummary[] }>(`/${BACKEND}/sessions`)
    const sessionId = sessions[0].id
    const first = await drain(sessionId, null, emptyState())

    // seq=1: delta halkasının çok gerisinde. Sunucu kurtarma yoluna girer.
    const recovered = await drain(sessionId, 1, first)
    expect(recovered.rows.map((r) => r.rowId)).toEqual(first.rows.map((r) => r.rowId))
    expect(new Set(recovered.rows.map((r) => r.rowId)).size).toBe(recovered.rows.length)
  }, 60_000)
})
