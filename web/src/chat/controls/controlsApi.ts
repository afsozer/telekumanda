// Sohbet kontrolleri — köprü REST uçlarına ince bağlama. Taşıma apiGet/apiPost
// (web/src/lib/api.ts); burada yalnız gövde ve yanıt şekilleri bilinir.
// Uç listesi ve gövde şekilleri: android/shared/.../BridgeClientBackend.kt.

import { ApiError, apiGet, apiPost } from '../../lib/api'

export interface ModelsResponse {
  models: { id: string; label?: string }[]
  defaultModel: string
}

/** Onay cevabı. `id` = soru kimliği (Kotlin: ApprovalAnswer.questionId). */
export interface ApprovalAnswer {
  id: string
  optionId: string
  label: string
}

/**
 * Akış `meta.approval` nesnesi. Alan adları BridgeClient.kt içindeki
 * `parseApproval` / `parseApprovalOptions` / `parseApprovalQuestions` ile
 * birebir (karşılaştırılarak doğrulandı); köprü snapshot'ta aynı anahtarları
 * gönderiyor.
 */
export interface ApprovalOption {
  id: string
  label: string
  description?: string
  consequence?: string
}

export interface ApprovalQuestion {
  id: string
  header?: string
  question?: string
  options?: ApprovalOption[]
  multiple?: boolean
  custom?: boolean
}

export interface ApprovalInfo {
  requestId?: string
  kind?: string
  tool?: string
  summary?: string
  description?: string
  decisionReason?: string
  options?: ApprovalOption[]
  questions?: ApprovalQuestion[]
}

interface OkResponse {
  ok: boolean
  error?: string
}

/**
 * Köprü bu uçların çoğundan `{ ok: false, error }` döndürebiliyor (interrupt
 * her zaman 200 verir, hata gövdede gelir). `ok` false ise hata fırlat —
 * sessizce başarı sayma.
 */
function okOrThrow(data: OkResponse, fallback: string): void {
  if (data.ok === false) throw new Error(data.error || fallback)
}

export function isOmpOrRunpod(str: string): boolean {
  return (
    /\b(omp|runpod)\b/i.test(str) ||
    str.toLowerCase().startsWith('omp/') ||
    str.toLowerCase().startsWith('runpod/')
  )
}

export function isRetiredModel(id: string): boolean {
  return /(^|\/)gpt-5[.-]6(?:$|[-/])/i.test(id)
}

export async function fetchModels(backend: string): Promise<ModelsResponse> {
  if (isOmpOrRunpod(backend)) return { models: [], defaultModel: '' }
  const res = await apiGet<ModelsResponse>(`/${backend}/models`)
  const models = (res.models ?? []).filter(
    (m) => !isOmpOrRunpod(m.id) && !isOmpOrRunpod(m.label || '') && !isRetiredModel(m.id),
  )
  return { ...res, models }
}

export async function setModel(backend: string, sessionId: string, model: string): Promise<string> {
  const data = await apiPost<OkResponse & { model?: string }>(`/${backend}/model`, { sessionId, model })
  okOrThrow(data, 'Model değiştirilemedi')
  return data.model ?? model
}

export function fetchEfforts(backend: string): Promise<string[]> {
  return apiGet<{ efforts?: string[] }>(`/${backend}/efforts`).then((data) => data.efforts ?? [])
}

export async function setEffort(backend: string, sessionId: string, effort: string): Promise<string> {
  const data = await apiPost<OkResponse & { effort?: string }>(`/${backend}/effort`, { sessionId, effort })
  okOrThrow(data, 'Çaba değiştirilemedi')
  return data.effort ?? effort
}

export async function setPermissionMode(backend: string, sessionId: string, mode: string): Promise<string> {
  const data = await apiPost<OkResponse & { permissionMode?: string }>(`/${backend}/permission-mode`, {
    sessionId,
    mode,
  })
  okOrThrow(data, 'İzin kipi değiştirilemedi')
  return data.permissionMode ?? mode
}

/**
 * Onayı, onaylanan isteğin kimliğiyle gönderir. Köprü bekleyen istek başkaysa
 * (araya yeni bir istek girmişse) 409 döner ve onay uygulanmaz.
 */
export async function approve(
  backend: string,
  sessionId: string,
  allow: boolean,
  answers?: ApprovalAnswer[],
  requestId?: string,
): Promise<void> {
  const body: { sessionId: string; allow: boolean; answers?: ApprovalAnswer[]; requestId?: string } = { sessionId, allow }
  // Kotlin tarafı da boş listeyi gövdeye koymuyor (claudeAppApprove).
  if (answers && answers.length > 0) body.answers = answers
  if (requestId) body.requestId = requestId
  let data: OkResponse
  try {
    data = await apiPost<OkResponse>(`/${backend}/approve`, body)
  } catch (err) {
    if (err instanceof ApiError && err.status === 409) {
      throw new Error('Bu onay isteği artık geçerli değil; ekrandaki güncel isteğe bakın.')
    }
    throw err
  }
  okOrThrow(data, 'Onay iletilemedi')
}

export async function interrupt(backend: string, sessionId: string): Promise<void> {
  const data = await apiPost<OkResponse>(`/${backend}/interrupt`, { sessionId })
  okOrThrow(data, 'Kesme isteği iletilemedi')
}

export async function stop(backend: string, sessionId: string): Promise<void> {
  const data = await apiPost<OkResponse>(`/${backend}/stop`, { sessionId })
  okOrThrow(data, 'Durdurma isteği iletilemedi')
}
