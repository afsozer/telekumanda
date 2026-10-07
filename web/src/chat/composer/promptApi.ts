// Prompt gönderme. Köprünün tekilleştirme protokolü burada yaşıyor.
//
// `POST /<backend>/prompt` gövdesinde `requestId` var ve köprü
// (routes/backend.mjs -> prompt-requests.mjs) aynı id'yi ikinci kez görünce
// iki farklı şey yapıyor:
//
//   200 + duplicate:true  -> istek ZATEN güvenle teslim edilmiş. Tekrar
//                            gönderme; kullanıcının mesajı gitmiş durumda.
//   409 + duplicate:true  -> istek güvenle teslim EDİLEMEMİŞ. Aynı id ile
//                            ısrar etmek boşuna; YENİ id ile gönder.
//
// Ağ hatasında (yanıt hiç gelmediğinde) teslim edilip edilmediğini bilmiyoruz;
// doğru davranış AYNI id ile tekrar denemek — köprü teslim edildiyse 200
// duplicate döner, edilmediyse normal işler. Yeni id üretmek mesajı ikilerdi.

import { ApiError, apiPost } from '../../lib/api'

export interface PromptRequest {
  sessionId: string
  text: string
  model?: string
  permissionMode?: string
}

export interface PromptResult {
  /** Köprü mesajı zaten teslim etmişti; yeniden gönderilmedi. */
  duplicate: boolean
  /** Köprü kabul etti ama bir uyarı iletti (ör. app-server geç yanıtladı). */
  warning?: string
}

interface PromptResponse {
  ok?: boolean
  duplicate?: boolean
  warning?: string
  error?: string
}

/**
 * İstek kimliği üretir.
 *
 * `crypto.randomUUID` GÜVENLİ BAĞLAM ister; arayüz Tailscale IP'si üzerinden
 * düz http ile açıldığında tanımsız olur. `getRandomValues` insecure bağlamda
 * da çalışıyor, bu yüzden asıl yol o; sonuncusu yalnızca son çare.
 */
export function newRequestId(): string {
  const c = globalThis.crypto
  if (typeof c?.randomUUID === 'function') return c.randomUUID()
  if (typeof c?.getRandomValues === 'function') {
    const bytes = c.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6] & 0x0f) | 0x40
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  return `req-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function isNotSafelyDelivered(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409
}

export interface SendPromptOptions {
  backend?: string
  /** Ağ hatasında kaç kez daha denensin. Tekilleştirme sayesinde güvenli. */
  retries?: number
  /** Testlerde beklemeyi kısaltmak için. */
  waitMs?: (attempt: number) => number
}

const defaultWait = (attempt: number) => Math.min(500 * 2 ** attempt, 4000)

/**
 * Mesajı gönderir. Ağ hatasında aynı `requestId` ile tekrar dener; köprü
 * "güvenle teslim edilmedi" (409) derse YENİ `requestId` üretip yeniden
 * gönderir.
 */
export async function sendPrompt(
  request: PromptRequest,
  { backend = 'claude-app', retries = 2, waitMs = defaultWait }: SendPromptOptions = {},
): Promise<PromptResult> {
  let requestId = newRequestId()
  let lastError: unknown

  for (let attempt = 0; attempt <= retries; attempt++) {
    const body: Record<string, unknown> = {
      sessionId: request.sessionId,
      text: request.text,
      requestId,
    }
    if (request.model) body.model = request.model
    if (request.permissionMode) body.permissionMode = request.permissionMode

    try {
      const data = await apiPost<PromptResponse>(`/${backend}/prompt`, body)
      // Köprü 200 döndüğü hâlde gövdede ok:false gönderebiliyor. Bu bir
      // SUNUCU REDDİDİR, taşıma hatası değil — ApiError olarak fırlatılıyor ki
      // aşağıdaki yakalayıcı tekrar denemesin (ağ hatasıyla karışmasın).
      if (data?.ok === false) {
        throw new ApiError(data.error || 'Mesaj gönderilemedi', 200, data)
      }
      return { duplicate: data?.duplicate === true, warning: data?.warning }
    } catch (error) {
      lastError = error
      if (isNotSafelyDelivered(error)) {
        // Aynı id ile ısrar boşuna: köprü onu kalıcı olarak reddediyor.
        requestId = newRequestId()
        continue
      }
      // ApiError (4xx/5xx) kalıcı bir reddir, tekrar denemek anlamsız.
      if (error instanceof ApiError) throw error
      // Buraya düşen ağ hatasıdır: yanıt hiç gelmedi. AYNI id ile tekrar dene.
      if (attempt === retries) break
      const delay = waitMs(attempt)
      if (delay > 0) await new Promise((resolve) => setTimeout(resolve, delay))
    }
  }

  throw lastError instanceof Error ? lastError : new Error('Mesaj gönderilemedi')
}
