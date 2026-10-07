// Oturum dosya değişiklikleri ("Değişiklikler" görünümü). Şema v1/v2'de aynı:
// {ok, files: [{path, patch, additions, deletions, status, truncated}], ...}
// YOKLAMAYA BİNMEZ — kullanıcı görünümü açınca çekilir (köprü notu aynı).

import { ApiError, apiGet } from '../lib/api'

export interface DiffEntry {
  path: string
  patch: string
  additions: number
  deletions: number
  status: string
  truncated: boolean
}

export interface SessionDiff {
  cwd: string
  files: DiffEntry[]
  additions: number
  deletions: number
  turns: number
  truncated: boolean
}

interface DiffResponse {
  ok?: boolean
  cwd?: string
  files?: {
    path?: string
    patch?: string
    additions?: number
    deletions?: number
    status?: string
    truncated?: boolean
  }[]
  additions?: number
  deletions?: number
  turns?: number
  truncated?: boolean
  error?: string
}

export async function fetchSessionDiff(
  backend: string,
  sessionId: string,
  signal?: AbortSignal,
): Promise<SessionDiff> {
  const data = await apiGet<DiffResponse>(
    `/${backend}/diff?session=${encodeURIComponent(sessionId)}`,
    signal,
  )
  if (data?.ok === false) throw new ApiError(data.error || 'Değişiklikler okunamadı', 200, data)
  return {
    cwd: data?.cwd ?? '',
    files: (data?.files ?? [])
      .filter((f) => (f.path ?? '').length > 0)
      .map((f) => ({
        path: f.path as string,
        patch: f.patch ?? '',
        additions: f.additions ?? 0,
        deletions: f.deletions ?? 0,
        status: f.status ?? '',
        truncated: f.truncated === true,
      })),
    additions: data?.additions ?? 0,
    deletions: data?.deletions ?? 0,
    turns: data?.turns ?? 0,
    truncated: data?.truncated === true,
  }
}
