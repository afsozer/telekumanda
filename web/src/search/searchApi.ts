import { apiGet } from '../lib/api'

export type SearchHitType = 'project' | 'session' | 'message'

export interface GlobalSearchHit {
  id: string
  type: SearchHitType
  projectId: string
  projectPath: string
  projectName: string
  backend: string
  backendLabel: string
  sessionId: string
  container: string
  title: string
  role: string
  snippet: string
  rowId: string
  matchOrdinal: number
  mtime: number
}

export interface GlobalSearchResult {
  ok: boolean
  error?: string
  query: string
  truncated: boolean
  hits: GlobalSearchHit[]
  warnings?: string[]
}

export async function searchGlobal(
  query: string,
  signal?: AbortSignal,
  limit = 100,
): Promise<GlobalSearchResult> {
  const params = new URLSearchParams({ q: query.trim(), limit: String(limit) })
  const result = await apiGet<GlobalSearchResult>(`/search/global?${params}`, signal)
  if (result.ok === false) throw new Error(result.error || 'Arama başarısız')
  return { ...result, hits: result.hits ?? [] }
}
