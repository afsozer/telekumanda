// Notlar — köprü uçlarının tipli sarmalayıcıları.
//
// Notlar cowork'ün bir parçası olarak doğdu ama sohbet backend'ine bağlı DEĞİL:
// uçlar `/cowork/note*` önekinde, depolama PC'de dosya. Not gövdesi ayrı
// uçlardan okunup yazılıyor (`/file`, `/cowork/savefile`) — not yaşam döngüsü
// (oluştur/adlandır/sil) ile içerik ayrı katmanlar, köprüde de öyle.
//
// Not yalnız metin notudur (`.md`); kalem notu desteği kaldırıldı.

import { apiGet, apiPost } from '../lib/api'

/** `/cowork/notes` listesindeki bir not. */
export interface Note {
  /** `<klasör>/<slug>` — bütün not uçlarının anahtarı. */
  id: string
  title: string
  /** Projeye bağlıysa proje bilgisi; bağımsız notta null. */
  project: { name: string; path: string } | null
  /** Markdown dosyasının PC yolu. */
  mdPath: string | null
  /** Listede gösterilen kısa özet; aramada eşleşen cümleyle değişir. */
  preview: string
  reminderAt: number | null
  updated_at_ms: number
}

export interface NotesPage {
  ok: boolean
  error?: string
  root: string
  notes: Note[]
}

function expectOk<T extends { ok?: boolean; error?: string }>(data: T): T {
  if (data && data.ok === false) throw new Error(data.error || 'köprü isteği reddetti')
  return data
}

/**
 * Not listesi. `query` verilirse köprü BAŞLIK ve GÖVDE'yi birlikte tarar ve
 * eşleşen notun `preview` alanını eşleşmenin çevresiyle değiştirir — ayrı bir
 * arama ucu yok, cevap şekli birebir aynı.
 */
export async function listNotes(query = '', signal?: AbortSignal): Promise<NotesPage> {
  const q = query.trim()
  const path = q ? `/cowork/notes?q=${encodeURIComponent(q)}` : '/cowork/notes'
  return expectOk(await apiGet<NotesPage>(path, signal))
}

export interface CreatedNote {
  ok: boolean
  error?: string
  id?: string
  mdPath?: string
}

/** Boş metin notu oluşturur. */
export async function createNote(
  name: string,
  projectPath: string | null = null,
  signal?: AbortSignal,
): Promise<CreatedNote> {
  return expectOk(await apiPost<CreatedNote>('/cowork/note', { name, projectPath }, signal))
}

export async function renameNote(noteId: string, name: string, signal?: AbortSignal): Promise<void> {
  expectOk(await apiPost<{ ok?: boolean; error?: string }>('/cowork/note/rename', { noteId, name }, signal))
}

export async function deleteNote(noteId: string, signal?: AbortSignal): Promise<void> {
  expectOk(await apiPost<{ ok?: boolean; error?: string }>('/cowork/note/delete', { noteId }, signal))
}

export interface FileRead {
  ok: boolean
  error?: string
  name: string
  path: string
  size: number
  mtime: number
  hash: string
  /** Köprü büyük dosyayı kırpar; kırpılmış gövdeyi ÜSTÜNE YAZMA. */
  truncated: boolean
  content: string
}

/**
 * Not gövdesini okur. Mutlak PC yolu geçerlidir (server.mjs resolveWorkspacePath
 * "fast path 2"); notun `mdPath`i zaten mutlak.
 */
export async function readNoteFile(mdPath: string, signal?: AbortSignal): Promise<FileRead> {
  return expectOk(await apiGet<FileRead>(`/file?path=${encodeURIComponent(mdPath)}`, signal))
}

/**
 * Not gövdesini yazar. Köprü gövdeyi HAM BAYT okuyor — JSON'a sarmak dosyayı
 * bozar, o yüzden `rawBody`.
 */
export async function saveNoteFile(
  mdPath: string,
  text: string,
  signal?: AbortSignal,
): Promise<void> {
  expectOk(
    await apiPost<{ ok?: boolean; error?: string }>(
      `/cowork/savefile?path=${encodeURIComponent(mdPath)}`,
      undefined,
      signal,
      { rawBody: new Blob([text], { type: 'text/markdown' }), contentType: 'text/markdown' },
    ),
  )
}
