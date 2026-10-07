// Yeni oturum başlatma. cwd seçici kökler (/dirs/roots), gezinme (/dirs) ve
// arama (/dirs/search) üzerinden çalışır; kullanıcı yolu elle de yazabilir.
// model/permissionMode/effort burada SEÇİLMEZ — arayan üst bileşen props ile
// verir (başka bir ajanın seçicileriyle çakışmamak için) ve /claude-app/new
// gövdesine olduğu gibi geçirilir.

import { useEffect, useState } from 'react'
import styles from './NewSessionDialog.module.css'
import {
  createSession,
  listDirs,
  listRoots,
  searchDirs,
  type DirEntry,
  type DirSearchResult,
  type SessionSource,
} from './sessionsApi'

export interface NewSessionDialogProps {
  /** Varsayılan ajan (katalog id'si). `agents` verilirse seçicinin başlangıcı. */
  backend: string
  /**
   * Seçilebilir ajanlar. Ajan filtresi "Tümü" iken hangi ajanda oturum
   * açılacağı listeden okunamaz, kullanıcıya sorulur.
   */
  agents?: SessionSource[]
  model?: string
  permissionMode?: string
  effort?: string
  onCreated: (sessionId: string, backend: string) => void
  onClose?: () => void
}

export function NewSessionDialog({
  backend,
  agents,
  model,
  permissionMode,
  effort,
  onCreated,
  onClose,
}: NewSessionDialogProps) {
  const availableAgents = (agents ?? []).filter((a) => a.id !== 'omp' && a.id !== 'runpod')
  const initialAgent = backend === 'omp' || backend === 'runpod'
    ? availableAgents[0]?.id || 'claude-app'
    : backend
  const [agent, setAgent] = useState(initialAgent)
  const [cwd, setCwd] = useState('')
  // Gezinilen klasör; '' = köprü ev dizinini listeler.
  const [base, setBase] = useState('')
  const [parent, setParent] = useState('')
  const [entries, setEntries] = useState<DirEntry[]>([])
  const [roots, setRoots] = useState<DirEntry[]>([])
  const [query, setQuery] = useState('')
  const [searchResults, setSearchResults] = useState<DirSearchResult[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [searchBusy, setSearchBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)

  // Açılış: sürücü kökleri + ev dizini listesi birlikte yüklenir.
  useEffect(() => {
    const controller = new AbortController()
    let alive = true
    setBusy(true)
    setError(null)
    Promise.all([listRoots(controller.signal), listDirs('', false, controller.signal)])
      .then(([rootList, dirs]) => {
        if (!alive) return
        setRoots(rootList)
        setEntries(dirs.dirs)
        setParent(dirs.parent)
        setBase(dirs.base)
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return
        setError(err instanceof Error ? err.message : String(err))
      })
      .finally(() => {
        if (alive) setBusy(false)
      })
    return () => {
      alive = false
      controller.abort()
    }
  }, [])

  async function loadDir(root: string): Promise<void> {
    setBusy(true)
    setError(null)
    try {
      const dirs = await listDirs(root)
      setEntries(dirs.dirs)
      setParent(dirs.parent)
      setBase(dirs.base)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  // Klasör seçimi hem yolu seçer hem içine girer; "üst klasör" aynı şekilde.
  function openDir(entry: DirEntry | string): void {
    const target = typeof entry === 'string' ? entry : entry.path
    setCwd(target)
    void loadDir(target)
  }

  async function runSearch(): Promise<void> {
    const q = query.trim()
    if (q.length < 2) {
      setSearchResults([])
      return
    }
    // /dirs/search kök ister; gezinilen klasörü kullan, o da yoksa ilk kökü.
    const root = base || roots[0]?.path
    if (!root) {
      setError('Arama için önce bir klasör seçin')
      return
    }
    setSearchBusy(true)
    setError(null)
    try {
      setSearchResults(await searchDirs(root, q, 4, 30))
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSearchBusy(false)
    }
  }

  async function handleCreate(): Promise<void> {
    const target = cwd.trim()
    if (!target) {
      setCreateError('Çalışma klasörü seçin veya yazın.')
      return
    }
    setCreating(true)
    setCreateError(null)
    try {
      // Uç öneki apiBackend'tir (cowork -> claude-app); katalog yoksa id.
      const api = availableAgents.find((a) => a.id === agent)?.apiBackend ?? agent
      const result = await createSession(api, { cwd: target, model, permissionMode, effort })
      onCreated(result.sessionId ?? '', agent)
    } catch (err) {
      // Köprünün error metni — apiPost ve expectOk hataları Error olarak gelir.
      setCreateError(err instanceof Error ? err.message : String(err))
    } finally {
      setCreating(false)
    }
  }

  return (
    <div className={styles.backdrop}>
      <section className={styles.panel} role="dialog" aria-label="Yeni oturum">
        <h2 className={styles.heading}>Yeni oturum</h2>

        {availableAgents.length > 1 && (
          <label className={styles.field}>
            <span className={styles.label}>Ajan</span>
            <select value={agent} onChange={(event) => setAgent(event.target.value)}>
              {availableAgents.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
        )}

        <div className={styles.field}>
          <span className={styles.label}>Çalışma klasörü</span>
          <input
            value={cwd}
            onChange={(event) => setCwd(event.target.value)}
            placeholder="Yol yazın veya aşağıdan seçin"
            aria-label="Çalışma klasörü yolu"
          />
        </div>

        {roots.length > 0 && (
          <div className={styles.roots} role="group" aria-label="Sürücü kökleri">
            {roots.map((root) => (
              <button
                key={root.path}
                type="button"
                className={styles.rootChip}
                onClick={() => openDir(root)}
              >
                {root.name}
              </button>
            ))}
          </div>
        )}

        <div className={styles.browser}>
          <div className={styles.browserBar}>
            <span className={styles.base} title={base}>
              {base || '…'}
            </span>
            {parent && (
              <button type="button" className={styles.up} onClick={() => openDir(parent)}>
                Üst klasör
              </button>
            )}
          </div>
          {/* includeFiles kapalı — liste yalnız alt klasörleri içerir. */}
          <ul className={styles.entries} aria-label="Klasör içeriği">
            {entries.map((entry) => (
              <li key={entry.path}>
                <button type="button" className={styles.entry} onClick={() => openDir(entry)}>
                  {entry.name}
                </button>
              </li>
            ))}
            {entries.length === 0 && <li className={styles.entryEmpty}>Klasör boş</li>}
          </ul>
        </div>

        {/* label değil div: içerideki Ara butonu label metninden etkilenmesin
            (label, kapsadığı tüm metni kendi adına katar). */}
        <div className={styles.field}>
          <span className={styles.label}>Klasör ara</span>
          <div className={styles.searchRow}>
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void runSearch()
              }}
              placeholder="Alt klasör adı"
              aria-label="Klasör adı ara"
            />
            <button type="button" onClick={() => void runSearch()} disabled={searchBusy}>
              Ara
            </button>
          </div>
        </div>
        {searchResults !== null && (
          <ul className={styles.searchResults} aria-label="Arama sonuçları">
            {searchResults.map((hit) => (
              <li key={hit.path}>
                <button type="button" className={styles.entry} onClick={() => setCwd(hit.path)}>
                  {hit.path}
                </button>
              </li>
            ))}
            {searchResults.length === 0 && <li className={styles.entryEmpty}>Eşleşme yok</li>}
          </ul>
        )}

        {error && <p className={styles.error}>{error}</p>}
        {createError && <p className={styles.error}>{createError}</p>}

        <div className={styles.actions}>
          {onClose && (
            <button type="button" className={styles.secondary} onClick={onClose}>
              İptal
            </button>
          )}
          <button type="button" onClick={() => void handleCreate()} disabled={creating || busy}>
            {creating ? 'Oluşturuluyor…' : 'Oluştur'}
          </button>
        </div>
      </section>
    </div>
  )
}
