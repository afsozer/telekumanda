// Sohbet ekleri.
//
// Köprüde ek için AYRI bir uç var: `POST /context/file?filename=<ad>`, gövde
// ham bayt. Dosya köprünün geçici klasörüne yazılıyor (24 saat TTL) ve PC
// yolu dönüyor. Model dosyayı O YOLDAN okuyor — bayt prompt gövdesine
// KONMUYOR. `/<backend>/prompt` gövdesindeki `images` alanı hiçbir backend
// tarafından okunmuyor (claude-app.prompt imzası: sessionId/text/model/
// permissionMode), oraya bir şey koymak sessizce kaybolur.
//
// Yollar prompt metnine "Ek dosyalar:" bloğu olarak ekleniyor — Android
// istemcisiyle birebir aynı biçim (ConversationDelegate.kt:95), böylece
// aynı oturuma iki istemciden bakınca sohbet tutarlı görünüyor.

import { ApiError, apiPost } from '../../lib/api'

export interface Attachment {
  name: string
  /** Köprünün geçici klasöründeki PC yolu. Model bunu okur. */
  path: string
  isImage: boolean
}

/** Android istemcisiyle aynı sınır: köprü tarafında bir tavan yok. */
export const MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024

interface UploadResponse {
  ok?: boolean
  name?: string
  path?: string
  error?: string
}

export async function uploadAttachment(file: File, signal?: AbortSignal): Promise<Attachment> {
  if (file.size === 0) throw new Error(`Dosya boş: ${file.name}`)
  if (file.size > MAX_ATTACHMENT_BYTES) {
    throw new Error(`Dosya çok büyük (sınır 25MB): ${file.name}`)
  }
  const buffer = await file.arrayBuffer()
  // Türkçe dosya adları başlıkta taşınamıyor (non-ASCII header reddediliyor),
  // köprü de bu yüzden adı sorgudan okuyor.
  const data = await apiPost<UploadResponse>(
    `/context/file?filename=${encodeURIComponent(file.name)}`,
    undefined,
    signal,
    { rawBody: buffer, contentType: 'application/octet-stream' },
  )
  if (data?.ok === false || !data?.path) {
    throw new ApiError(data?.error || 'Dosya eklenemedi', 200, data)
  }
  return {
    name: data.name || file.name,
    path: data.path,
    isImage: (file.type || '').startsWith('image/'),
  }
}

/**
 * Ekleri prompt metnine iliştirir. Biçim Android ile BİREBİR aynı
 * (ConversationDelegate.kt): aynı oturuma iki istemciden bakıldığında
 * geçmiş tutarlı görünsün.
 */
export function withAttachments(text: string, attachments: Attachment[]): string {
  if (attachments.length === 0) return text
  const list = attachments.map((a) => `- ${a.name}: ${a.path}`).join('\n')
  return `${text}\n\nEk dosyalar:\n${list}`
}
