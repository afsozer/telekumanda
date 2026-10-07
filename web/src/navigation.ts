export interface ChatNavigationTarget {
  /** Her tıklamayı ayrı işlem yapan kararlı istek kimliği. */
  requestKey: string
  backend: string
  backendLabel?: string
  sessionId: string
  cwd: string
  title?: string
  model?: string
  live?: boolean
  container?: string
  rowId?: string
  matchOrdinal?: number
  query?: string
}

export function navigationKey(prefix = 'nav'): string {
  if (globalThis.crypto?.randomUUID) return `${prefix}:${globalThis.crypto.randomUUID()}`
  return `${prefix}:${Date.now()}:${Math.random().toString(36).slice(2)}`
}
