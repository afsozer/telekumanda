// Mesaj bazlı oturum aksiyonları: çatallama (fork) ve geri sarma (rewind).
//
// dropUserTurns sözleşmesi köprüyle aynı: sondan kaçıncı kullanıcı turuna
// kadar işlem yapılacağı. Seçilen kullanıcı mesajı DAHİL sonraki kullanıcı
// mesajlarının sayısı — çağıran (ChatScreen) satırlardan hesaplıyor.
//
//   fork:   seçilen mesajın ÖNCESİNE kadar kopya çocuk oturum açar;
//           orijinale dokunmaz. Yanıt {ok, sessionId} — yeni sekmede açılır.
//   rewind: seçilen mesaj DAHİL sonrasını atar. Köprü snapshot yayınlar,
//           akış kendini yeniden kurar; istemci ek tazeleme yapmaz.

import { ApiError, apiPost } from '../lib/api'

interface ForkResponse {
  ok?: boolean
  sessionId?: string
  error?: string
}

interface RewindResponse {
  ok?: boolean
  error?: string
  droppedUserTurns?: number
}

/** Seçilen mesajdan çatalla; yeni oturumun kimliğini döndürür. */
export async function forkFromMessage(
  backend: string,
  sessionId: string,
  dropUserTurns: number,
): Promise<string> {
  const data = await apiPost<ForkResponse>(`/${backend}/fork-from`, {
    sessionId,
    dropUserTurns,
  })
  if (data?.ok === false || !data?.sessionId) {
    throw new ApiError(data?.error || 'Çatallanamadı', 200, data)
  }
  return data.sessionId
}

/** Seçilen mesaja geri sar (mesaj dahil sonrası atılır). */
export async function rewindTo(
  backend: string,
  sessionId: string,
  dropUserTurns: number,
): Promise<void> {
  const data = await apiPost<RewindResponse>(`/${backend}/rewind`, {
    sessionId,
    dropUserTurns,
  })
  if (data?.ok === false) {
    throw new ApiError(data?.error || 'Geri sarılamadı', 200, data)
  }
}
