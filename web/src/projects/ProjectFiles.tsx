import { useCallback, useEffect, useState } from 'react'
import { listDirs, type DirEntry } from '../chat/sessions'
import { downloadProjectFile } from './projectsApi'
import styles from './Projects.module.css'

function pathKey(path: string): string {
  return path.replace(/\\/g, '/').replace(/\/+$/, '').toLocaleLowerCase('tr')
}

function parentOf(path: string): string {
  const clean = path.replace(/[\\/]+$/, '')
  const index = Math.max(clean.lastIndexOf('\\'), clean.lastIndexOf('/'))
  return index > 2 ? clean.slice(0, index) : clean
}

export function ProjectFiles({ root }: { root: string }) {
  const [current, setCurrent] = useState(root)
  const [entries, setEntries] = useState<DirEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [downloading, setDownloading] = useState('')

  useEffect(() => setCurrent(root), [root])

  const load = useCallback((path: string, signal?: AbortSignal) => {
    setLoading(true)
    void listDirs(path, true, signal)
      .then((page) => {
        setEntries(page.dirs ?? [])
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
    load(current, controller.signal)
    return () => controller.abort()
  }, [current, load])

  const atRoot = pathKey(current) === pathKey(root)
  return (
    <div className={styles.fileBrowser}>
      <div className={styles.fileBar}>
        <button
          type="button"
          disabled={atRoot}
          onClick={() => {
            const parent = parentOf(current)
            setCurrent(pathKey(parent).startsWith(pathKey(root)) ? parent : root)
          }}
        >
          ← Üst
        </button>
        <span title={current}>{current}</span>
        <button type="button" onClick={() => load(current)}>Yenile</button>
      </div>
      {error && <p className={styles.error}>{error}</p>}
      {loading ? (
        <p className={styles.muted}>Dosyalar yükleniyor…</p>
      ) : entries.length === 0 ? (
        <p className={styles.muted}>Bu klasör boş.</p>
      ) : (
        <ul className={styles.fileList}>
          {[...entries]
            .sort((a, b) => Number(b.type === 'dir') - Number(a.type === 'dir') || a.name.localeCompare(b.name, 'tr'))
            .map((entry) => (
              <li key={entry.path}>
                <button
                  type="button"
                  className={styles.fileRow}
                  disabled={downloading === entry.path}
                  onClick={() => {
                    if (entry.type === 'dir') {
                      setCurrent(entry.path)
                      return
                    }
                    setDownloading(entry.path)
                    void downloadProjectFile(entry.path, entry.name)
                      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
                      .finally(() => setDownloading(''))
                  }}
                >
                  <span>{entry.type === 'dir' ? '📁' : '📄'}</span>
                  <strong>{entry.name}</strong>
                  <span>{entry.type === 'dir' ? 'Klasör' : downloading === entry.path ? 'İndiriliyor…' : 'İndir'}</span>
                </button>
              </li>
            ))}
        </ul>
      )}
    </div>
  )
}
