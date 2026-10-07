import { useCallback, useEffect, useMemo, useState } from 'react'
import { listNotes, type Note } from '../notes/notesApi'
import { navigationKey, type ChatNavigationTarget } from '../navigation'
import { ProjectFiles } from './ProjectFiles'
import {
  downloadProjectFile,
  getProjectDetail,
  listProjects,
  markProjectOutputsSeen,
  type ProjectArtifact,
  type ProjectDetail,
  type ProjectSession,
  type ProjectSummary,
} from './projectsApi'
import styles from './Projects.module.css'

type ProjectFilter = 'all' | 'pinned' | 'active' | 'new'

export interface ProjectsScreenProps {
  initialProjectId?: string | null
  onInitialProjectHandled?: (projectId: string) => void
  onOpenChat: (target: ChatNavigationTarget) => void
  onOpenNotes: (projectPath: string) => void
}

function projectName(project: ProjectSummary): string {
  return project.displayName || project.name || project.path
}

function samePath(a: string, b: string): boolean {
  return a.replace(/\\/g, '/').replace(/\/+$/, '').toLocaleLowerCase('tr')
    === b.replace(/\\/g, '/').replace(/\/+$/, '').toLocaleLowerCase('tr')
}

function sessionTarget(session: ProjectSession, project: ProjectSummary): ChatNavigationTarget {
  return {
    requestKey: navigationKey('project'),
    backend: session.backend,
    backendLabel: session.backendLabel,
    sessionId: session.sessionId,
    cwd: project.path,
    title: session.title || session.summary,
    model: session.model,
    live: session.live,
    container: session.container,
  }
}

function artifactList(raw: Record<string, unknown>[] | undefined, kind: string): ProjectArtifact[] {
  return (raw ?? []).map((item) => ({
    backend: String(item.backend ?? ''),
    title: String(item.path ?? item.command ?? item.text ?? item.summary ?? ''),
    detail: String(item.status ?? item.summary ?? ''),
  })).filter((item) => item.title).map((item) => ({ ...item, detail: item.detail || kind }))
}

export function ProjectsScreen({
  initialProjectId,
  onInitialProjectHandled,
  onOpenChat,
  onOpenNotes,
}: ProjectsScreenProps) {
  const [projects, setProjects] = useState<ProjectSummary[]>([])
  const [selectedId, setSelectedId] = useState(initialProjectId || '')
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [notes, setNotes] = useState<Note[]>([])
  const [filter, setFilter] = useState<ProjectFilter>('all')
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [error, setError] = useState('')

  const refresh = useCallback((signal?: AbortSignal) => {
    setLoading(true)
    void listProjects(signal)
      .then((items) => {
        setProjects(items)
        setError('')
      })
      .catch((err) => {
        if (!signal?.aborted) setError(err instanceof Error ? err.message : String(err))
      })
      .finally(() => {
        if (!signal?.aborted) setLoading(false)
      })
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    refresh(controller.signal)
    return () => controller.abort()
  }, [refresh])

  useEffect(() => {
    if (initialProjectId) {
      setSelectedId(initialProjectId)
      onInitialProjectHandled?.(initialProjectId)
    }
  }, [initialProjectId, onInitialProjectHandled])

  useEffect(() => {
    if (!selectedId) {
      setDetail(null)
      setNotes([])
      return
    }
    const controller = new AbortController()
    setDetailLoading(true)
    void Promise.all([
      getProjectDetail(selectedId, controller.signal),
      listNotes('', controller.signal).catch(() => ({ notes: [] })),
    ])
      .then(([loaded, notePage]) => {
        setDetail(loaded)
        setNotes(notePage.notes.filter((note) => note.project && samePath(note.project.path, loaded.project.path)))
        setError('')
        void markProjectOutputsSeen(selectedId, controller.signal)
          .then(() => {
            if (!controller.signal.aborted) refresh(controller.signal)
          })
          .catch(() => {})
      })
      .catch((err) => {
        if (!controller.signal.aborted) setError(err instanceof Error ? err.message : String(err))
      })
      .finally(() => {
        if (!controller.signal.aborted) setDetailLoading(false)
      })
    return () => controller.abort()
  }, [selectedId, refresh])

  const visible = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase('tr')
    return projects
      .filter((project) => {
        if (filter === 'pinned' && !project.pinned) return false
        if (filter === 'active' && project.runningCount === 0) return false
        if (filter === 'new' && project.newOutputCount === 0) return false
        return !needle || `${projectName(project)} ${project.path}`.toLocaleLowerCase('tr').includes(needle)
      })
      .sort((a, b) => (
        Number(b.pinned) - Number(a.pinned)
        || Number(b.newOutputCount > 0) - Number(a.newOutputCount > 0)
        || Number(b.runningCount > 0) - Number(a.runningCount > 0)
        || (b.lastActivityAt || '').localeCompare(a.lastActivityAt || '')
      ))
  }, [projects, filter, query])

  const selected = detail?.project
  const artifacts = detail ? [
    ['Değişiklikler', artifactList(detail.artifacts?.changes, 'değişiklik')],
    ['Komutlar', artifactList(detail.artifacts?.commands, 'komut')],
    ['Plan', artifactList(detail.artifacts?.plan, 'plan')],
  ] as const : []

  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <header className={styles.sideHead}>
          <div><h1>Projeler</h1><span>{projects.length} proje</span></div>
          <button type="button" onClick={() => refresh()}>Yenile</button>
        </header>
        <input
          type="search"
          value={query}
          aria-label="Proje ara"
          placeholder="Proje veya yol ara…"
          onChange={(event) => setQuery(event.target.value)}
        />
        <div className={styles.filters}>
          {([['all', 'Tümü'], ['pinned', 'Sabit'], ['active', 'Aktif'], ['new', 'Yeni çıktı']] as const).map(([id, label]) => (
            <button key={id} type="button" aria-pressed={filter === id} onClick={() => setFilter(id)}>{label}</button>
          ))}
        </div>
        {loading && projects.length === 0 ? <p className={styles.muted}>Projeler taranıyor…</p> : (
          <ul className={styles.projectList}>
            {visible.map((project) => (
              <li key={project.id}>
                <button
                  type="button"
                  className={project.id === selectedId ? `${styles.projectRow} ${styles.projectOn}` : styles.projectRow}
                  onClick={() => setSelectedId(project.id)}
                >
                  <span className={styles.projectTitle}>
                    {project.pinned && <span>📌</span>}
                    <strong>{projectName(project)}</strong>
                    {project.runningCount > 0 && <span className={styles.running}>● {project.runningCount}</span>}
                    {project.newOutputCount > 0 && <span className={styles.newOutput}>{project.newOutputCount} yeni</span>}
                  </span>
                  <span className={styles.path}>{project.path}</span>
                  <span className={styles.counts}>{project.sessionCount} oturum · {project.outputCount} çıktı</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </aside>

      <main className={styles.main}>
        {error && <p className={styles.error}>{error}</p>}
        {!selectedId ? (
          <div className={styles.empty}>Soldan bir proje seç.</div>
        ) : detailLoading && !detail ? (
          <div className={styles.empty}>Proje ayrıntıları hazırlanıyor…</div>
        ) : selected && detail ? (
          <div className={styles.detail}>
            <header className={styles.detailHead}>
              <div>
                <h1>{projectName(selected)}</h1>
                <p>{selected.path}</p>
              </div>
              <div className={styles.summaryPills}>
                <span>{selected.sessionCount} oturum</span>
                <span>{selected.outputCount} çıktı</span>
                {selected.runningCount > 0 && <span className={styles.running}>{selected.runningCount} çalışıyor</span>}
              </div>
            </header>

            <section className={styles.section}>
              <h2>Oturumlar</h2>
              {detail.sessions.length === 0 ? <p className={styles.muted}>Oturum yok.</p> : (
                <div className={styles.sessionGrid}>
                  {detail.sessions.map((session) => (
                    <button
                      key={`${session.backend}:${session.sessionId}`}
                      type="button"
                      className={styles.sessionCard}
                      onClick={() => onOpenChat(sessionTarget(session, selected))}
                    >
                      <span><strong>{session.title || session.summary || session.sessionId}</strong><em>{session.backendLabel}</em></span>
                      <span>{session.model || 'model belirtilmedi'} · {session.status || (session.live ? 'canlı' : 'diskte')}</span>
                    </button>
                  ))}
                </div>
              )}
            </section>

            <section className={styles.section}>
              <h2>Teslimatlar</h2>
              {detail.outputs.length === 0 ? <p className={styles.muted}>Teslimat yok.</p> : (
                <ul className={styles.outputList}>
                  {detail.outputs.map((output) => (
                    <li key={output.path}>
                      <span><strong>{output.name}</strong>{output.isNew && <em>yeni</em>}</span>
                      <span>{Math.max(1, Math.round(output.size / 1024))} KB</span>
                      <button
                        type="button"
                        onClick={() => void downloadProjectFile(output.path, output.name)
                          .catch((err) => setError(err instanceof Error ? err.message : String(err)))}
                      >
                        İndir
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <section className={styles.section}>
              <div className={styles.sectionTitle}>
                <h2>Notlar</h2>
                <button type="button" onClick={() => onOpenNotes(selected.path)}>Notlarda aç</button>
              </div>
              {notes.length === 0 ? <p className={styles.muted}>Bu projeye bağlı not yok.</p> : (
                <div className={styles.noteChips}>{notes.map((note) => <span key={note.id}>{note.title}</span>)}</div>
              )}
            </section>

            <section className={styles.section}>
              <h2>Dosyalar</h2>
              <ProjectFiles root={selected.path} />
            </section>

            {artifacts.map(([title, items]) => items.length > 0 && (
              <section key={title} className={styles.section}>
                <h2>{title}</h2>
                <ul className={styles.artifacts}>
                  {items.map((item, index) => <li key={`${item.backend}:${item.title}:${index}`}><strong>{item.title}</strong><span>{item.backend}{item.detail ? ` · ${item.detail}` : ''}</span></li>)}
                </ul>
              </section>
            ))}

            <section className={styles.section}>
              <h2>Proje profili</h2>
              <div className={styles.profile}>
                <span>Güvenlik: <strong>{detail.security?.profile || 'standard'}</strong></span>
                <span>MCP: <strong>{detail.mcpProfile?.provider || 'profil yok'}</strong></span>
                {(detail.mcpProfile?.enabledNames?.length ?? 0) > 0 && <span>{detail.mcpProfile!.enabledNames!.join(', ')}</span>}
              </div>
            </section>
          </div>
        ) : null}
      </main>
    </div>
  )
}
