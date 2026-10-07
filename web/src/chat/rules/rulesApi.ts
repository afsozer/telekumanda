// Oturum kuralları uçları — talimatlar (v2 experimental instructions entries)
// ve kayıtlı izin kuralları. Sözleşme telefonla (BridgeClientBackend.kt) aynı:
// zarflar {ok, entries} ve {ok, rules}; telefon README'sindeki örnek kullanım:
// anahtar `uyap-bicimi`, kural "dilekçeleri UYAP biçiminde yaz."
//
// Bu uçlar YALNIZ opencode2-app'te kayıtlı (capability sessionInstructions /
// savedPermissions); çağıran buna göre düğmeyi çizer, burada backend denetimi yok.

import { ApiError, apiDelete, apiGet, apiPost } from '../../lib/api'

export interface InstructionEntry {
  key: string
  value: string
}

export interface SavedPermission {
  id: string
  projectID: string
  action: string
  resource: string
  created: number
}

interface InstructionsResponse {
  ok?: boolean
  entries?: { key?: string; value?: unknown }[]
  error?: string
}

interface RulesResponse {
  ok?: boolean
  rules?: {
    id?: string
    projectID?: string
    action?: string
    resource?: string
    created?: number
  }[]
  error?: string
}

interface MutationResponse {
  ok?: boolean
  error?: string
}

/** Talimat değeri sunucuda JSON olarak durur (db'de `"metin"` biçiminde). */
function asText(value: unknown): string {
  if (typeof value === 'string') return value
  if (value == null) return ''
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

export async function fetchInstructions(
  backend: string,
  sessionId: string,
  signal?: AbortSignal,
): Promise<InstructionEntry[]> {
  const data = await apiGet<InstructionsResponse>(
    `/${backend}/instructions?session=${encodeURIComponent(sessionId)}`,
    signal,
  )
  if (data?.ok === false) throw new ApiError(data.error || 'Talimatlar okunamadı', 200, data)
  return (data?.entries ?? [])
    .filter((entry) => (entry.key ?? '').length > 0)
    .map((entry) => ({ key: entry.key as string, value: asText(entry.value) }))
}

export async function putInstruction(
  backend: string,
  sessionId: string,
  key: string,
  value: string,
): Promise<void> {
  const data = await apiPost<MutationResponse>(`/${backend}/instructions`, {
    sessionId,
    key,
    value,
  })
  if (data?.ok === false) throw new ApiError(data.error || 'Talimat kaydedilemedi', 200, data)
}

/** Gövdeli DELETE: köprünün router.mjs'i DELETE'te de body() okuyor. */
export async function deleteInstruction(
  backend: string,
  sessionId: string,
  key: string,
): Promise<void> {
  const data = await apiDelete<MutationResponse>(`/${backend}/instructions`, { sessionId, key })
  if (data?.ok === false) throw new ApiError(data.error || 'Talimat silinemedi', 200, data)
}

export async function fetchSavedPermissions(
  backend: string,
  signal?: AbortSignal,
): Promise<SavedPermission[]> {
  const data = await apiGet<RulesResponse>(`/${backend}/permissions/saved`, signal)
  if (data?.ok === false) throw new ApiError(data.error || 'İzin kuralları okunamadı', 200, data)
  return (data?.rules ?? [])
    .filter((rule) => (rule.id ?? '').length > 0)
    .map((rule) => ({
      id: rule.id as string,
      projectID: rule.projectID ?? '',
      action: rule.action ?? '',
      resource: rule.resource ?? '',
      created: rule.created ?? 0,
    }))
}

export async function deleteSavedPermission(backend: string, id: string): Promise<void> {
  const data = await apiPost<MutationResponse>(`/${backend}/permissions/saved/delete`, { id })
  if (data?.ok === false) throw new ApiError(data.error || 'İzin kuralı silinemedi', 200, data)
}
