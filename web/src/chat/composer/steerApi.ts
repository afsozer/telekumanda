// Tur-içi yönlendirme (steer). v2'nin teslim kipi: süren tura anında enjekte
// edilir, sıraya girmez — gönderen taraf için fark, hata durumunda metnin
// GERİ KONMASI: steer teslim edilmediyse kullanıcı bir şey kaybetmemeli.
//
// Tekilleştirme (requestId) YOK: steer atılabilir bir komuttur; ağ hatasında
// aynı metni tekrar göndermek zararsız değildir ama köprü tarafı zaten
// çift-teslimi tolere ediyor (idempotent değilse tur içinde iki kez görünür —
// bu yüzden ağ hatasında otomatik tekrar YOK; hata kullanıcıya gösterilir).

import { ApiError, apiPost } from '../../lib/api'

interface SteerResponse {
  ok?: boolean
  error?: string
}

export async function steerPrompt(
  backend: string,
  sessionId: string,
  text: string,
): Promise<void> {
  const data = await apiPost<SteerResponse>(`/${backend}/steer`, { sessionId, text })
  if (data?.ok === false) {
    throw new ApiError(data.error || 'Yönlendirme gönderilemedi', 200, data)
  }
}
