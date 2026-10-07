import { useEffect, useMemo, useState } from 'react'
import { navigationKey, type ChatNavigationTarget } from '../navigation'
import { searchGlobal, type GlobalSearchHit, type SearchHitType } from './searchApi'
import styles from './Search.module.css'

export interface GlobalSearchScreenProps {
  onOpenProject: (projectId: string) => void
  onOpenChat: (target: ChatNavigationTarget) => void
}

const GROUP_LABELS: Record<SearchHitType, string> = {
  project: 'Projeler',
  session: 'Oturumlar',
  message: 'Mesajlar',
}

function formatDate(ms: number): string {
  if (!ms) return ''
  return new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium', timeStyle: 'short' }).format(ms)
}

function hitTarget(hit: GlobalSearchHit, query: string): ChatNavigationTarget {
  return {
    requestKey: navigationKey('search'),
    backend: hit.backend,
    backendLabel: hit.backendLabel,
    sessionId: hit.sessionId,
    cwd: hit.projectPath,
    title: hit.title,
    container: hit.container,
    rowId: hit.type === 'message' ? hit.rowId : undefined,
    matchOrdinal: hit.type === 'message' ? hit.matchOrdinal : undefined,
    query: hit.type === 'message' ? query : undefined,
  }
}

export function GlobalSearchScreen({ onOpenProject, onOpenChat }: GlobalSearchScreenProps) {
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<GlobalSearchHit[]>([])
  const [loading, setLoading] = useState(false)
  const [truncated, setTruncated] = useState(false)
  const [warnings, setWarnings] = useState<string[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    const trimmed = query.trim()
    if (trimmed.length < 2) {
      setHits([])
      setLoading(false)
      setError('')
      setWarnings([])
      setTruncated(false)
      return
    }
    const controller = new AbortController()
    setLoading(true)
    const timer = window.setTimeout(() => {
      void searchGlobal(trimmed, controller.signal)
        .then((result) => {
          setHits(result.hits)
          setTruncated(result.truncated)
          setWarnings(result.warnings ?? [])
          setError('')
        })
        .catch((err) => {
          if (!controller.signal.aborted) setError(err instanceof Error ? err.message : String(err))
        })
        .finally(() => {
          if (!controller.signal.aborted) setLoading(false)
        })
    }, 350)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [query])

  const groups = useMemo(() => {
    const result = new Map<SearchHitType, GlobalSearchHit[]>()
    for (const type of ['project', 'session', 'message'] as const) {
      const group = hits.filter((hit) => hit.type === type)
      if (group.length) result.set(type, group)
    }
    return result
  }, [hits])

  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <div>
          <h1>Global arama</h1>
          <p>Projeler, oturum başlıkları ve tam mesaj geçmişi</p>
        </div>
        <input
          autoFocus
          type="search"
          value={query}
          aria-label="Global ara"
          placeholder="En az iki karakter yaz…"
          onChange={(event) => setQuery(event.target.value)}
        />
      </header>

      <main className={styles.results}>
        {error && <p className={styles.error}>{error}</p>}
        {warnings.length > 0 && (
          <p className={styles.warning}>Bazı sağlayıcılar aranamadı: {warnings.join(' · ')}</p>
        )}
        {query.trim().length < 2 ? (
          <div className={styles.empty}>Aramak için en az iki karakter yaz.</div>
        ) : loading ? (
          <div className={styles.empty}>Bütün sağlayıcılarda aranıyor…</div>
        ) : hits.length === 0 && !error ? (
          <div className={styles.empty}>Eşleşme bulunamadı.</div>
        ) : (
          [...groups.entries()].map(([type, group]) => (
            <section key={type} className={styles.group}>
              <h2>{GROUP_LABELS[type]} <span>{group.length}</span></h2>
              <div className={styles.cards}>
                {group.map((hit) => (
                  <button
                    key={hit.id}
                    type="button"
                    className={styles.card}
                    onClick={() => {
                      if (hit.type === 'project') onOpenProject(hit.projectId)
                      else onOpenChat(hitTarget(hit, query.trim()))
                    }}
                  >
                    <span className={styles.cardTop}>
                      <strong>{hit.title || hit.projectName || hit.sessionId}</strong>
                      {hit.backendLabel && <span className={styles.backend}>{hit.backendLabel}</span>}
                      {hit.role && <span className={styles.role}>{hit.role === 'user' ? 'Sen' : 'Ajan'}</span>}
                    </span>
                    {hit.snippet && <span className={styles.snippet}>{hit.snippet}</span>}
                    <span className={styles.meta}>
                      {hit.projectName && <span>{hit.projectName}</span>}
                      {hit.projectPath && <span>{hit.projectPath}</span>}
                      {hit.mtime > 0 && <time>{formatDate(hit.mtime)}</time>}
                    </span>
                  </button>
                ))}
              </div>
            </section>
          ))
        )}
        {truncated && <p className={styles.warning}>İlk 100 sonuç gösteriliyor.</p>}
      </main>
    </div>
  )
}
