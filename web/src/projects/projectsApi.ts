import { apiGet, apiPost, apiObjectUrl } from '../lib/api'

export interface ProjectSummary {
  id: string
  path: string
  name: string
  displayName: string
  exists: boolean
  sessionCount: number
  runningCount: number
  outputCount: number
  newOutputCount: number
  providers: string[]
  pinned: boolean
  lastOpenedAt: string
  lastActivityAt: string
}

export interface ProjectSession {
  backend: string
  backendLabel: string
  sessionId: string
  model: string
  status: string
  summary: string
  title: string
  nativeSessionId: string
  mtime: number
  live: boolean
  pinned: boolean
  archived: boolean
  container: string
  threadId: string
}

export interface ProjectArtifact {
  backend: string
  title: string
  detail: string
}

export interface ProjectDelivery {
  name: string
  path: string
  size: number
  mtime: number
  isNew: boolean
}

export interface ProjectDetail {
  ok: boolean
  error?: string
  project: ProjectSummary
  sessions: ProjectSession[]
  outputs: ProjectDelivery[]
  artifacts: {
    changes?: Record<string, unknown>[]
    commands?: Record<string, unknown>[]
    plan?: Record<string, unknown>[]
  }
  security?: {
    profile?: string
    readRoots?: string[]
    writeRoots?: string[]
    customPolicy?: { permissions?: Record<string, string> }
  }
  audit?: Record<string, unknown>[]
  mcpProfile?: { provider?: string; enabledNames?: string[] }
}

interface ProjectsPage {
  ok: boolean
  error?: string
  projects: ProjectSummary[]
}

function expectOk<T extends { ok?: boolean; error?: string }>(value: T): T {
  if (value.ok === false) throw new Error(value.error || 'Köprü isteği reddetti')
  return value
}

export async function listProjects(signal?: AbortSignal): Promise<ProjectSummary[]> {
  const result = expectOk(await apiGet<ProjectsPage>('/projects', signal))
  return result.projects ?? []
}

export async function getProjectDetail(id: string, signal?: AbortSignal): Promise<ProjectDetail> {
  return expectOk(await apiGet<ProjectDetail>(`/projects/detail?id=${encodeURIComponent(id)}`, signal))
}

export async function markProjectOutputsSeen(id: string, signal?: AbortSignal): Promise<void> {
  expectOk(await apiPost<{ ok?: boolean; error?: string }>('/projects/outputs/seen', { id }, signal))
}

/** Kimlik doğrulamalı dosyayı tarayıcı indirmesine dönüştürür. */
export async function downloadProjectFile(path: string, name: string): Promise<void> {
  const url = await apiObjectUrl(`/download?path=${encodeURIComponent(path)}`)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1_000)
}
