// Köprü akış sözleşmesi. Kanonik kaynak:
//   bridge/agent-session-core.mjs        (sunucu: diffSnapshots, subscribe)
//   android/shared/.../SessionStreamManager.kt  (olgun istemci)
// Burayı çatallama; sözleşme değişirse orayı okuyup buraya yansıt.

/** Sohbetteki tek satır. `rowId` sunucu tarafında kararlı (bkz. rowKey). */
export interface StreamRow {
  rowId: string
  role: string
  text: string
  time: string
  thoughtIndex: number
}

/**
 * Snapshot'ın satır dışı alanları. Sunucu delta'da yalnız DEĞİŞEN anahtarları
 * `setMeta` ile yolluyor, bu yüzden istemci tarafında birikimli tutulur.
 * Liste `agent-session-core.mjs` içindeki META_KEYS ile birebir.
 */
export const META_KEYS = [
  'running', 'awaitingApproval', 'awaitingFirstOutput', 'awaitingUserInput',
  'approval', 'contextTokens', 'contextWindow', 'cost', 'usage',
  'permissionMode', 'effort', 'cowork', 'outputs', 'init',
  'interruptStuck', 'choices', 'plan', 'planDraft', 'availableModels',
  'permissionModes', 'commands',
] as const

export type MetaKey = (typeof META_KEYS)[number]

export interface PlanItem {
  text: string
  status: string
  itemId?: string
  turnId?: string
}

export interface BackendModel {
  id: string
  label?: string
}

/** Birikimli meta. Alanların çoğu isteğe bağlı — köprü yalnız değişeni yollar. */
export interface StreamMeta {
  running?: boolean
  awaitingApproval?: boolean
  awaitingFirstOutput?: boolean
  awaitingUserInput?: boolean
  approval?: unknown
  contextTokens?: number
  contextWindow?: number
  cost?: number
  usage?: unknown
  permissionMode?: string
  effort?: string
  cowork?: unknown
  outputs?: unknown[]
  init?: unknown
  interruptStuck?: boolean
  choices?: string[]
  plan?: PlanItem[]
  planDraft?: string
  availableModels?: BackendModel[]
  permissionModes?: unknown[]
  commands?: unknown[]
}

/** İstemcinin tuttuğu tam durum. Tüketiciler bunu olduğu gibi okur. */
export interface SessionState {
  /** Köprünün akış sırası. Yeniden bağlanırken `since` olarak geri gönderilir. */
  seq: number | null
  /**
   * Köprünün zarfa gömdüğü oturum kimliği. Akışın açıldığı id'den farklıysa
   * sunucu oturumu yeniden anahtarlamıştır (ör. başka bir istemci /clear
   * yaptı) — tüketici bunu fark edip yeni id'ye geçmeli.
   */
  sessionId: string | null
  rows: StreamRow[]
  meta: StreamMeta
}

export type StreamOp =
  | { op: 'appendRow'; row: Record<string, unknown> }
  | { op: 'patchRow'; rowId: string; row: Record<string, unknown> }
  | { op: 'appendText'; rowId: string; chunk: string }
  | { op: 'dropRows'; beforeRowId: string }
  | { op: 'setMeta'; meta: Record<string, unknown> }

export type ServerMessage =
  | ({ type: 'snapshot' | 'conversation' } & Record<string, unknown>)
  | { type: 'delta'; sessionId?: string; seq?: number; ops?: StreamOp[] }
  | { type: 'end' }
  | { type: 'error'; error?: string }

export function emptyState(): SessionState {
  return { seq: null, sessionId: null, rows: [], meta: {} }
}
