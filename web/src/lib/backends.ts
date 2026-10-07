// Backend kataloğu. TEK KAYNAK köprüdür (`GET /backends`,
// bridge/backend-contract.mjs); buradaki gömülü tablo yalnızca köprüye
// ulaşılamadığında ya da eski bir köprü yeni alanları bildirmediğinde
// devreye giren yedektir.
//
// Yetenekler arayüzü AÇIKLAMAK içindir: desteklenmeyen kontrolü gizlemeye
// yarar, görev yönlendirme motoru değildir (docs/vision-roadmap.md).

import { apiGet } from './api'

export interface BackendCapabilities {
  approvals: boolean
  userInput: boolean
  permissionModes: boolean
  context: boolean
  plan: boolean
  outputs: boolean
  /** GET /<b>/efforts + POST /<b>/effort — yalnız claude-app ve codex-app. */
  efforts: boolean
  /** POST /<b>/interrupt — yalnız claude-app. /stop hepsinde var, o ayrı. */
  interrupt: boolean
  /** POST /<b>/steer — süren tura enjeksiyon. Telefondaki "Yönlendir". */
  userInputSteer: boolean
  /**
   * GET /<b>/instructions + POST/DELETE — oturum talimatları ("Kurallar"
   * paneli). Bugün yalnız opencode2-app; diğerlerinde düğme çizilmez.
   */
  sessionInstructions: boolean
  /** GET /<b>/permissions/saved — kayıtlı izin kuralları listesi. */
  savedPermissions: boolean
  /** GET /<b>/diff — oturumun dosya değişiklikleri ("Değişiklikler" paneli). */
  sessionDiff: boolean
  /** POST /<b>/fork-from — mesajdan çatallama. Bugün yalnız opencode2-app. */
  sessionFork: boolean
  /** POST /<b>/rewind — sondan sayımlı geri sarma. */
  sessionRewind: boolean
  /** Oturum listesi bağlam menüsü yetenekleri. */
  sessionPin: boolean
  sessionArchive: boolean
  sessionRename: boolean
  sessionDelete: boolean
}

export interface BackendInfo {
  id: string
  /** Ağ katmanının gerçekten kullanacağı önek. cowork -> claude-app. */
  apiBackend: string
  label: string
  adapterBacked: boolean
  capabilities: BackendCapabilities
}

export const DEFAULT_CAPABILITIES: BackendCapabilities = {
  approvals: false,
  userInput: false,
  permissionModes: false,
  context: true,
  plan: false,
  outputs: false,
  efforts: false,
  interrupt: false,
  userInputSteer: false,
  sessionInstructions: false,
  savedPermissions: false,
  sessionDiff: false,
  sessionFork: false,
  sessionRewind: false,
  sessionPin: false,
  sessionArchive: false,
  sessionRename: false,
  sessionDelete: false,
}

const APP_AGENT = { approvals: true, userInput: true, permissionModes: true, context: true }

/** Köprüye ulaşılamadığında kullanılan yedek. backend-contract.mjs ile aynı. */
export const FALLBACK_BACKENDS: BackendInfo[] = [
  { id: 'claude-app', apiBackend: 'claude-app', label: 'Claude', adapterBacked: true,
    capabilities: { ...DEFAULT_CAPABILITIES, ...APP_AGENT, efforts: true, interrupt: true,
      sessionPin: true, sessionArchive: true, sessionRename: true, sessionDelete: true } },
  { id: 'codex-app', apiBackend: 'codex-app', label: 'Codex', adapterBacked: true,
    capabilities: { ...DEFAULT_CAPABILITIES, ...APP_AGENT, plan: true, efforts: true,
      sessionPin: true, sessionArchive: true, sessionRename: true, sessionDelete: true } },
  { id: 'opencode-app', apiBackend: 'opencode-app', label: 'OpenCode', adapterBacked: true,
    capabilities: { ...DEFAULT_CAPABILITIES, ...APP_AGENT, userInput: false,
      sessionPin: true, sessionRename: true, sessionDelete: true } },
  { id: 'agy', apiBackend: 'agy', label: 'Agy', adapterBacked: true,
    capabilities: { ...DEFAULT_CAPABILITIES, context: false, sessionDelete: true } },
]

/**
 * Köprüden gelen kısmi yetenek nesnesini tamamlar.
 *
 * Eksik alan varsayılana düşer — ama sonradan eklenen yeteneklerde varsayılan
 * `false` yanlış olurdu: eski bir köprü bu alanları HİÇ bildirmiyor ve o
 * durumda claude-app'in çaba seçicisi sebepsiz kaybolurdu. Bu yüzden alan
 * yoksa gömülü tablodaki değer kullanılıyor.
 */
export function normalizeCapabilities(
  partial: Partial<BackendCapabilities> | undefined,
  backendId = '',
): BackendCapabilities {
  const known = FALLBACK_BACKENDS.find((b) => b.id === backendId)?.capabilities
  const out = { ...DEFAULT_CAPABILITIES }
  for (const key of Object.keys(DEFAULT_CAPABILITIES) as (keyof BackendCapabilities)[]) {
    if (partial && key in partial) out[key] = !!partial[key]
    else if (known && (
      key === 'efforts'
      || key === 'interrupt'
      || key === 'sessionPin'
      || key === 'sessionArchive'
      || key === 'sessionRename'
      || key === 'sessionDelete'
    )) out[key] = known[key]
  }
  return out
}

interface CatalogResponse {
  contractVersion?: number
  backends?: (Partial<BackendInfo> & { capabilities?: Partial<BackendCapabilities> })[]
}

export async function fetchBackends(signal?: AbortSignal): Promise<BackendInfo[]> {
  try {
    const data = await apiGet<CatalogResponse>('/backends', signal)
    const list = (data.backends ?? [])
      .filter(
        (entry) =>
          typeof entry.id === 'string' &&
          entry.id &&
          entry.id !== 'omp' &&
          entry.id !== 'runpod',
      )
      .map((entry) => ({
        id: entry.id!,
        apiBackend: entry.apiBackend || entry.id!,
        label: entry.label || entry.id!,
        adapterBacked: entry.adapterBacked !== false,
        capabilities: normalizeCapabilities(entry.capabilities, entry.id),
      }))
    return list.length ? list : FALLBACK_BACKENDS
  } catch {
    // Katalog isteğe bağlı bir zenginleştirme; alınamaması arayüzü durdurmamalı.
    return FALLBACK_BACKENDS
  }
}

export function findBackend(backends: BackendInfo[], id: string): BackendInfo | undefined {
  return backends.find((backend) => backend.id === id)
}
