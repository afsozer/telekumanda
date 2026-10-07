// Notlar ekranı — metin notu görüntüleme, ekleme ve düzenleme.

import { useCallback, useEffect, useState } from 'react'
import { Markdown } from '../chat/render'
import { joinFrontmatter, splitFrontmatter } from './frontmatter'
import {
  createNote,
  deleteNote,
  listNotes,
  readNoteFile,
  saveNoteFile,
  type Note,
} from './notesApi'
import styles from './Notes.module.css'

function when(ms: number): string {
  if (!ms) return ''
  const diff = Date.now() - ms
  const minute = 60_000
  if (diff < minute) return 'az önce'
  if (diff < 60 * minute) return `${Math.round(diff / minute)} dk önce`
  if (diff < 24 * 60 * minute) return `${Math.round(diff / (60 * minute))} sa önce`
  return `${Math.round(diff / (24 * 60 * minute))} gün önce`
}

interface Loaded {
  noteId: string
  path: string
  frontmatter: string
  body: string
  truncated: boolean
}

export interface NotesScreenProps {
  /** Proje merkezinden gelindiyse listeyi o çalışma alanına sınırlar. */
  projectPath?: string | null
  onClearProject?: () => void
}

function sameProjectPath(a: string, b: string): boolean {
  const normalize = (value: string) => value.replace(/\\/g, '/').replace(/\/+$/, '').toLocaleLowerCase('tr')
  return normalize(a) === normalize(b)
}

export function NotesScreen({ projectPath = null, onClearProject }: NotesScreenProps = {}) {
  const [notes, setNotes] = useState<Note[]>([])
  const [query, setQuery] = useState('')
  const [listing, setListing] = useState(true)
  const [selectedId, setSelectedId] = useState('')
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [draft, setDraft] = useState('')
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')

  const selected = notes.find((note) => note.id === selectedId) ?? null
  const dirty = loaded !== null && draft !== loaded.body

  const refresh = useCallback(async (q: string, signal?: AbortSignal) => {
    setListing(true)
    try {
      const page = await listNotes(q, signal)
      setNotes(projectPath
        ? page.notes.filter((note) => note.project && sameProjectPath(note.project.path, projectPath))
        : page.notes)
      setError(null)
    } catch (err) {
      if (signal?.aborted) return
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      if (!signal?.aborted) setListing(false)
    }
  }, [projectPath])

  // Arama köprüde yapılıyor (başlık + gövde); her tuşta değil, yazma durunca.
  useEffect(() => {
    const controller = new AbortController()
    const timer = setTimeout(() => void refresh(query, controller.signal), query ? 250 : 0)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [query, refresh])

  // Seçilen notun gövdesi ayrı uçtan gelir; frontmatter ayrılıp saklanır.
  useEffect(() => {
    if (!selected?.mdPath) {
      setLoaded(null)
      setDraft('')
      return
    }
    const controller = new AbortController()
    const path = selected.mdPath
    const noteId = selected.id
    void (async () => {
      try {
        const file = await readNoteFile(path, controller.signal)
        if (controller.signal.aborted) return
        const { frontmatter, body } = splitFrontmatter(file.content ?? '')
        setLoaded({ noteId, path, frontmatter, body, truncated: file.truncated })
        setDraft(body)
        setEditing(false)
        setError(null)
      } catch (err) {
        if (controller.signal.aborted) return
        setError(err instanceof Error ? err.message : String(err))
      }
    })()
    return () => controller.abort()
  }, [selected?.mdPath, selected?.id])

  const save = useCallback(async () => {
    if (!loaded) return
    // Köprü büyük dosyayı kırparak veriyor; kırpılmışı geri yazmak notun
    // kalanını SİLERDİ.
    if (loaded.truncated) {
      setError('Not çok büyük olduğu için kırpılmış geldi; üstüne yazılmıyor.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await saveNoteFile(loaded.path, joinFrontmatter(loaded.frontmatter, draft))
      setLoaded({ ...loaded, body: draft })
      setEditing(false)
      void refresh(query)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }, [loaded, draft, query, refresh])

  const create = useCallback(async () => {
    const name = newName.trim()
    if (!name) return
    setBusy(true)
    setError(null)
    try {
      const result = await createNote(name, projectPath)
      setCreating(false)
      setNewName('')
      setQuery('')
      await refresh('')
      if (result.id) {
        setSelectedId(result.id)
        setEditing(true)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }, [newName, refresh, projectPath])

  const remove = useCallback(async () => {
    if (!selected) return
    if (!window.confirm(`"${selected.title}" silinsin mi? Geri alınamaz.`)) return
    setBusy(true)
    setError(null)
    try {
      await deleteNote(selected.id)
      setSelectedId('')
      setLoaded(null)
      await refresh(query)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }, [selected, query, refresh])

  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <div className={styles.head}>
          <div>
            <h1 className={styles.brand}>Notlar</h1>
            {projectPath && <span className={styles.projectScope} title={projectPath}>Proje filtresi açık</span>}
          </div>
          <button type="button" className={styles.new} onClick={() => setCreating(true)}>
            Yeni
          </button>
        </div>

        {projectPath && onClearProject && (
          <button type="button" className={styles.clearScope} onClick={onClearProject}>
            Tüm notları göster
          </button>
        )}

        {creating && (
          <div className={styles.newRow}>
            <input
              autoFocus
              value={newName}
              aria-label="Not adı"
              placeholder="Not adı"
              onChange={(event) => setNewName(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void create()
                if (event.key === 'Escape') setCreating(false)
              }}
            />
            <button type="button" disabled={busy || !newName.trim()} onClick={() => void create()}>
              Oluştur
            </button>
          </div>
        )}

        <input
          type="search"
          className={styles.search}
          value={query}
          aria-label="Not ara"
          placeholder="Başlık ve içerikte ara…"
          onChange={(event) => setQuery(event.target.value)}
        />

        {listing && notes.length === 0 ? (
          <p className={styles.muted}>Yükleniyor…</p>
        ) : notes.length === 0 ? (
          <p className={styles.muted}>{query ? 'Eşleşen not yok.' : 'Henüz not yok.'}</p>
        ) : (
          <ul className={styles.list}>
            {notes.map((note) => (
              <li key={note.id}>
                <button
                  type="button"
                  className={note.id === selectedId ? `${styles.row} ${styles.rowActive}` : styles.row}
                  aria-current={note.id === selectedId ? 'true' : undefined}
                  onClick={() => setSelectedId(note.id)}
                >
                  <span className={styles.rowTitle}>
                    <span className={styles.noteTitle}>{note.title || note.id}</span>
                  </span>
                  {note.preview && <span className={styles.preview}>{note.preview}</span>}
                  <span className={styles.meta}>
                    <span>{when(note.updated_at_ms)}</span>
                    {note.project && <span>{note.project.name}</span>}
                    {note.reminderAt ? <span>hatırlatıcı</span> : null}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </aside>

      <main className={styles.main}>
        {error && <p className={styles.error}>{error}</p>}

        {!selected ? (
          <div className={styles.empty}>
            <p>Soldan bir not seç ya da yeni bir tane oluştur.</p>
          </div>
        ) : (
          <>
            <header className={styles.bar}>
              <h2 className={styles.title}>{selected.title || selected.id}</h2>
              <span className={styles.spacer} />
              <button type="button" onClick={() => setEditing((on) => !on)} disabled={!loaded}>
                {editing ? 'Önizle' : 'Düzenle'}
              </button>
              {editing && (
                <button
                  type="button"
                  className={styles.primary}
                  disabled={busy || !dirty}
                  onClick={() => void save()}
                >
                  {busy ? 'Kaydediliyor…' : 'Kaydet'}
                </button>
              )}
              <button type="button" className={styles.danger} disabled={busy} onClick={() => void remove()}>
                Sil
              </button>
            </header>

            {loaded?.truncated && (
              <p className={styles.warnLine}>
                Not çok büyük; gövde kırpılmış geldi ve kaydetme kapalı.
              </p>
            )}

            <div className={styles.body}>
              {!loaded ? (
                <p className={styles.muted}>Yükleniyor…</p>
              ) : editing ? (
                <textarea
                  className={styles.editor}
                  value={draft}
                  aria-label="Not içeriği"
                  onChange={(event) => setDraft(event.target.value)}
                />
              ) : (
                <div className={styles.preview_}>
                  {draft.trim() ? <Markdown text={draft} /> : <p className={styles.muted}>Boş not.</p>}
                </div>
              )}
            </div>
          </>
        )}
      </main>
    </div>
  )
}
