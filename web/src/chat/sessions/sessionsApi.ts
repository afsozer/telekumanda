// Oturum listesi ve yeni oturum — köprü REST uçlarının tipli sarmalayıcıları.
// Taşıma (kimlik başlığı, JSON ayrıştırma) lib/api.ts'te; bu modül yalnız uç
// şekillerini bilir. Alan adları köprünün GERÇEK sözleşmesinden alındı:
// bridge/claude-app.mjs (listSessions), bridge/routes/backend.mjs ve
// general.mjs (/dirs*), BridgeClientBackend.kt (Android'in okuduğu alanlar).

import { apiGet, apiPost } from '../../lib/api'

/** Canlı oturum — köprünün listSessions() çıktısıyla birebir. */
export interface Session {
  id: string
  cwd: string
  model: string
  status: string
  title: string
  lastUserAt: number
  turns: number
  lastText: string
  awaitingApproval: boolean
  restoredShell: boolean
  /**
   * Canlı kabuğun diskteki kaydının kimliği.
   * Canlı kabuk ile disk kaydı kimliği ayrışırsa (varsa diskId bağlayıcıdır).
   * Yalnız `id` üzerinden birleştirmek aynı sohbeti listede iki satır yapıyordu.
   */
  diskId?: string
  pinned?: boolean
  archived?: boolean
}

/**
 * Disk oturumu (adopt edilebilir geçmiş). Alanlar Android'in okuduklarıyla
 * aynı: BridgeClientBackend.kt`teki ClaudeDiskSession. pinned/archived yalnız
 * köprü meta'sından gelir, ham listede bulunmayabilir.
 */
export interface DiskSession {
  id: string
  cwd: string
  title: string
  lastText: string
  turns: number
  /** Son değişiklik zamanı (ms). Sıralama bunun üzerinden. */
  mtime: number
  /** Bu oturumdan çatallanmış oturum sayısı. */
  forks?: number
  pinned?: boolean
  archived?: boolean
  /** Köprüde şu an canlı olarak da duruyor mu. */
  canli?: boolean
}

/** Klasör/dosya girdisi — /dirs* uçlarının ortak öğesi. */
export interface DirEntry {
  name: string
  path: string
  type: string
  size: number
  mtime: number
}

/** /dirs yanıtı: base = listelenen klasör, parent = üst klasör (yoksa boş). */
export interface WorkerDirs {
  ok: boolean
  error?: string
  base: string
  parent: string
  dirs: DirEntry[]
}

/** /dirs/search sonucu — yalnız ad ve tam yol taşır. */
export interface DirSearchResult {
  name: string
  path: string
}

export interface NewSessionInput {
  cwd: string
  model?: string
  permissionMode?: string
  effort?: string
}

export interface NewSessionResult {
  ok: boolean
  sessionId?: string
  error?: string
}

export interface OkResult {
  ok?: boolean
  error?: string
}

// Köprü `{ ok: false, error }` ile bazen HTTP 200 dönebiliyor (ör. /archive
// her zaman 200 yanıtlar). apiPost yalnız HTTP durumuna bakar; ok bayrağını
// burada ayrıca denetliyoruz ki hata asla yutulmasın.
function expectOk<T extends { ok?: boolean; error?: string }>(data: T): T {
  if (data && data.ok === false) throw new Error(data.error || 'köprü isteği reddetti')
  return data
}

export async function listSessions(backend: string, signal?: AbortSignal): Promise<Session[]> {
  const data = await apiGet<{ sessions: Session[] }>(`/${backend}/sessions`, signal)
  return data.sessions ?? []
}

// ── Çok backend'li listeleme ────────────────────────────────────────────────
// Ajan seçimi ZORUNLU değil (Android'deki gibi): seçilmezse bütün backend'lerin
// oturumları tek listede birleşir, seçilirse filtre olur. Birleşik listede her
// satırın hangi ajana ait olduğu taşınmak zorunda — yoksa oturuma tıklandığında
// hangi uca bağlanılacağı bilinemez.

/** Oturum çekilebilecek bir backend — lib/backends.ts'teki BackendInfo'nun alt kümesi. */
export interface SessionSource {
  id: string
  apiBackend: string
  label: string
  adapterBacked?: boolean
  capabilities?: {
    sessionArchive?: boolean
  }
}

/**
 * Cowork oturum kaydı — `/cowork/sessions?projectPath=` çıktısı.
 *
 * Cowork oturumları sağlayıcının NORMAL disk listesinde YOKTUR: ölçüldü
 * (13.08.2026, canlı köprü) `/claude-app/disk-sessions` 75 kayıt döndürüyor ve
 * hiçbirinin cwd'si CoworkSpaces altında değil. Yani bu uç sorgulanmazsa cowork
 * oturumları arayüzde hiç görünmez — çift listeleme riski de yoktur.
 */
export interface CoworkSessionRecord {
  /** Oturumun gerçek sahibi: claude-app | codex-app | opencode-app. */
  provider: string
  sessionId: string
  threadId?: string
  cwd: string
  model?: string
  title?: string
  lastText?: string
  createdAt?: string
  lastUsedAt?: string
}

interface CoworkProject {
  name: string
  path: string
}

/** Oturum + sahibi. `backend` katalog id'si (cowork değil, ucun gerçek sahibi). */
export interface TaggedSession extends Session {
  backend: string
  backendLabel: string
  /**
   * Cowork satırlarında oturumun GERÇEK sahibi. Cowork bir sunum katmanı ve
   * katalogda apiBackend'i claude-app; ama bir cowork oturumu codex-app ya da
   * opencode-app'e ait olabilir. Bu alan olmadan böyle bir oturum
   * yanlış uçtan adopt edilir.
   */
  provider?: string
  /**
   * Köprüde canlı bir kabuğu var mı. false ise oturum yalnız diskte duruyor ve
   * açmadan önce `adopt` edilmesi gerekir.
   */
  live: boolean
}

export interface MergedSessions<T> {
  sessions: T[]
  /** Ulaşılamayan backend'ler. Biri düştü diye liste tamamen boş kalmasın. */
  errors: { backend: string; label: string; message: string }[]
}

/**
 * Aynı uç önekini paylaşan backend'leri teke indirir.
 *
 * cowork bir SUNUM katmanıdır ve claude-app uçlarını kullanır: ikisini de
 * sorgulamak aynı oturumları iki kez listelerdi. Kanonik sahip, id'si
 * apiBackend'e eşit olandır.
 */
export function sessionSources<T extends SessionSource>(sources: T[]): T[] {
  const byApi = new Map<string, T>()
  for (const source of sources) {
    if (
      source.adapterBacked === false ||
      source.id === 'omp' ||
      source.apiBackend === 'omp' ||
      source.id === 'runpod' ||
      source.apiBackend === 'runpod'
    )
      continue
    const existing = byApi.get(source.apiBackend)
    if (!existing || source.id === source.apiBackend) byApi.set(source.apiBackend, source)
  }
  return [...byApi.values()]
}

async function mergeFrom<R, T>(
  sources: SessionSource[],
  fetchOne: (apiBackend: string) => Promise<R[]>,
  tag: (item: R, source: SessionSource) => T,
): Promise<MergedSessions<T>> {
  const picked = sessionSources(sources)
  const settled = await Promise.all(
    picked.map(async (source) => {
      try {
        return { source, items: await fetchOne(source.apiBackend), message: '' }
      } catch (err) {
        // Tek backend'in hatası ötekileri düşürmez; hata ayrı taşınır.
        return { source, items: [] as R[], message: err instanceof Error ? err.message : String(err) }
      }
    }),
  )

  const sessions: T[] = []
  const errors: MergedSessions<T>['errors'] = []
  for (const { source, items, message } of settled) {
    if (message) errors.push({ backend: source.id, label: source.label, message })
    for (const item of items) sessions.push(tag(item, source))
  }
  return { sessions, errors }
}

/**
 * Canlı kabukları ve disk oturumlarını TEK listede birleştirir.
 *
 * Neden gerekli: köprü, restart'ta geri yüklediği kabuğun TRANSKRİPTİNİ
 * OKUMUYOR — `/sessions` o oturum için `title: ''`, `turns: 0`,
 * `lastUserAt: 0` diyor. Ölçüldü (11.08.2026, canlı köprü): 98 "boş" canlı
 * oturumun 41'i aslında başlıklı, gerçek sohbetti. Diskteki kayıt bu alanların
 * doğrusunu taşıyor.
 *
 * Android listesini zaten canlı kabuklardan DEĞİL disk oturumlarından kuruyor
 * (`ChatRootScreen.kt`: `backendDiskSessions`); bu fonksiyon aynı modeli
 * getiriyor ve Canlı/Geçmiş ayrımını gereksiz kılıyor.
 *
 * Yön önemli: canlı değer DOLUYSA o kazanır (tur sürerken disk bayattır),
 * yalnız canlının boş bıraktığı alanlarda diske düşülür.
 */
export function unifySessions(
  live: Session[],
  disk: DiskSession[],
): (Session & { live: boolean })[] {
  const savedById = new Map(disk.map((entry) => [entry.id, entry]))
  const merged: (Session & { live: boolean })[] = live.map((session) => {
    // `diskId` varsa BAĞLAYICI odur: canlı kabuk ve disk kimliği ayrışabilir.
    const saved = savedById.get(session.diskId || session.id)
    if (!saved) return { ...session, live: true }
    return {
      ...session,
      live: true,
      title: session.title || saved.title || '',
      turns: Math.max(session.turns || 0, saved.turns || 0),
      lastText: session.lastText || saved.lastText || '',
      cwd: session.cwd || saved.cwd || '',
      // Sıralama bunun üzerinden: geri yüklenen kabuklar 0 kalırsa hepsi
      // listenin dibine yığılırdı.
      lastUserAt: Math.max(session.lastUserAt || 0, saved.mtime || 0),
      pinned: saved.pinned ?? session.pinned ?? false,
      archived: saved.archived ?? session.archived ?? false,
    }
  })

  // Yalnız diskte olanlar: köprüde kabuğu yok, açılırken adopt edilecek.
  // Küme HER İKİ kimliği de taşır, yoksa diskId ile eşleşen kayıt ikinci kez
  // eklenirdi.
  const liveIds = new Set(live.flatMap((session) => [session.id, session.diskId ?? '']))
  for (const saved of disk) {
    if (liveIds.has(saved.id)) continue
    merged.push({
      id: saved.id,
      cwd: saved.cwd || '',
      model: '',
      status: 'idle',
      title: saved.title || '',
      lastUserAt: saved.mtime || 0,
      turns: saved.turns || 0,
      lastText: saved.lastText || '',
      awaitingApproval: false,
      restoredShell: false,
      pinned: saved.pinned ?? false,
      archived: saved.archived ?? false,
      live: false,
    })
  }
  return merged
}

/** ISO zaman damgasını sıralamada kullanılan epoch ms'ye çevirir. */
export function coworkEpochMs(record: CoworkSessionRecord): number {
  const raw = record.lastUsedAt || record.createdAt || ''
  const parsed = Date.parse(raw)
  return Number.isFinite(parsed) ? parsed : 0
}

const COWORK_NEW_LABEL: Record<string, string> = {
  'claude-app': 'Yeni Claude oturumu',
  'codex-app': 'Yeni Codex oturumu',
  'opencode-app': 'Yeni OpenCode oturumu',
}

export function coworkSessionRow(record: CoworkSessionRecord, projectPath: string): TaggedSession {
  return {
    id: record.sessionId,
    // cwd boş dönen kayıt workspace yoluyla doldurulur, yoksa adopt oturumu
    // yanlış klasörde kurar (Android'de aynı düzeltme yapıldı).
    cwd: record.cwd || projectPath,
    model: record.model || '',
    status: 'idle',
    title: record.title || COWORK_NEW_LABEL[record.provider] || 'Yeni cowork oturumu',
    lastUserAt: coworkEpochMs(record),
    turns: 0,
    lastText: record.lastText || '',
    awaitingApproval: false,
    restoredShell: false,
    pinned: false,
    archived: false,
    backend: 'cowork',
    backendLabel: 'Cowork',
    provider: record.provider,
    live: false,
  }
}

/**
 * Bütün cowork workspace'lerinin oturumlarını tek listede toplar.
 *
 * Neden ayrı yol: cowork `adapterBacked: false` bildiriliyor ve `sessionSources`
 * onu eliyor — haklı olarak, çünkü cowork'ün `/cowork/...` dışında kendi
 * `/sessions` ucu yok. Ama eleme tek başına kaldığında cowork oturumları web'de
 * HİÇ görünmüyordu (Android bunları `loadAllCoworkSessions` ile ayrıca çekiyor).
 */
export async function listCoworkSessions(signal?: AbortSignal): Promise<TaggedSession[]> {
  const data = expectOk(
    await apiGet<{ ok?: boolean; error?: string; projects?: CoworkProject[] }>('/cowork/projects', signal),
  )
  const projects = data.projects ?? []
  const perProject = await Promise.all(
    projects.map(async (project) => {
      try {
        const body = expectOk(
          await apiGet<{ ok?: boolean; error?: string; sessions?: CoworkSessionRecord[] }>(
            `/cowork/sessions?projectPath=${encodeURIComponent(project.path)}`,
            signal,
          ),
        )
        return (body.sessions ?? [])
          .filter((record) => record.provider !== 'omp' && record.provider !== 'runpod')
          .map((record) => coworkSessionRow(record, project.path))
      } catch {
        // Tek workspace okunamazsa ötekiler listelenmeye devam etsin.
        return [] as TaggedSession[]
      }
    }),
  )
  return perProject.flat()
}

/**
 * Cowork satırlarını mevcut listeye kaynaştırır.
 *
 * Cowork oturumları sağlayıcının DİSK listesinde yok ama köprüde canlı kabuğu
 * varsa CANLI listesinde var — ölçüldü (13.08.2026, tarayıcıda): 8f74cd67 hem
 * "Claude" hem "Cowork" satırı olarak iki kez çıktı. Çakışan id'de cowork satırı
 * kazanır (etiket ve `provider` yönlendirmesi doğru olsun) ama canlı satırın
 * taşıdığı tur/durum/metin korunur — cowork kaydı bunları tutmuyor.
 */
export function mergeCoworkRows(
  sessions: TaggedSession[],
  coworkRows: TaggedSession[],
): TaggedSession[] {
  if (!coworkRows.length) return sessions
  const byId = new Map(coworkRows.map((row) => [row.id, row]))
  const used = new Set<string>()
  const merged = sessions.map((session) => {
    const cowork = byId.get(session.id)
    if (!cowork) return session
    used.add(session.id)
    return {
      ...cowork,
      title: cowork.title || session.title,
      lastText: cowork.lastText || session.lastText,
      model: cowork.model || session.model,
      status: session.status || cowork.status,
      turns: Math.max(session.turns || 0, cowork.turns || 0),
      lastUserAt: Math.max(session.lastUserAt || 0, cowork.lastUserAt || 0),
      awaitingApproval: session.awaitingApproval,
      live: session.live,
    }
  })
  for (const row of coworkRows) if (!used.has(row.id)) merged.push(row)
  return merged
}

export async function listAllSessions(
  sources: SessionSource[],
  signal?: AbortSignal,
): Promise<MergedSessions<TaggedSession>> {
  const merged = await mergeFrom(
    sources,
    async (api) => {
      const [live, disk] = await Promise.all([
        listSessions(api, signal),
        // Disk listesi alınamazsa canlı liste yine gelsin — başlıksız ama gelsin.
        listDiskSessions(api, signal).catch(() => [] as DiskSession[]),
      ])
      return unifySessions(live, disk)
    },
    (session, source) => ({ ...session, backend: source.id, backendLabel: source.label }),
  )
  const cowork = sources.find((source) => source.id === 'cowork')
  if (cowork) {
    try {
      merged.sessions = mergeCoworkRows(merged.sessions, await listCoworkSessions(signal))
    } catch (err) {
      merged.errors.push({
        backend: cowork.id,
        label: cowork.label,
        message: err instanceof Error ? err.message : String(err),
      })
    }
  }
  merged.sessions = merged.sessions.filter(
    (s) => s.backend !== 'omp' && (s as TaggedSession).provider !== 'omp' && s.backend !== 'runpod',
  )
  return merged
}

/** Arşiv görünümü yalnız disk kayıtlarından ve destekleyen backend'lerden kurulur. */
export function listArchivedSessions(
  sources: SessionSource[],
  signal?: AbortSignal,
): Promise<MergedSessions<TaggedSession>> {
  const capable = sources.filter(
    (source) =>
      source.capabilities?.sessionArchive === true &&
      source.id !== 'omp' &&
      source.id !== 'runpod',
  )
  return mergeFrom(
    capable,
    async (api) => unifySessions([], await listDiskSessions(api, signal, { archived: true })),
    (session, source) => ({ ...session, backend: source.id, backendLabel: source.label }),
  )
}


export async function listDiskSessions(
  backend: string,
  signal?: AbortSignal,
  filters?: { archived?: boolean; pinned?: boolean; query?: string },
): Promise<DiskSession[]> {
  const params = new URLSearchParams()
  if (filters?.archived !== undefined) params.set('archived', String(filters.archived))
  if (filters?.pinned !== undefined) params.set('pinned', String(filters.pinned))
  if (filters?.query) params.set('q', filters.query)
  const endpoint = filters
    ? `/${backend}/disk-sessions-search?${params.toString()}`
    : `/${backend}/disk-sessions`
  const data = expectOk(
    await apiGet<{ ok?: boolean; error?: string; sessions: DiskSession[] }>(
      endpoint,
      signal,
    ),
  )
  return data.sessions ?? []
}

export async function createSession(backend: string, input: NewSessionInput, signal?: AbortSignal): Promise<NewSessionResult> {
  // undefined alanlar JSON.stringify'da atlanır — verilmeyen model/permissionMode/
  // effort köprüye gitmez.
  return expectOk(await apiPost<NewSessionResult>(`/${backend}/new`, input, signal))
}

// DİKKAT: rename/pin/archive uçları gövdede `sessionId` DEĞİL `id` bekler
// (server.mjs: renameThread({ id: b.id }) ...). `sessionId` gönderilirse köprü
// 'id required' hatası verir; Android istemcisi de `id` gönderir
// (BridgeClientBackend.kt: claudeAppRename → put("id", id)).

export async function renameSession(backend: string, id: string, title: string, signal?: AbortSignal): Promise<OkResult> {
  return expectOk(await apiPost<OkResult>(`/${backend}/rename`, { id, title }, signal))
}

export async function pinSession(backend: string, id: string, signal?: AbortSignal): Promise<OkResult> {
  return expectOk(await apiPost<OkResult>(`/${backend}/pin`, { id }, signal))
}

export async function unpinSession(backend: string, id: string, signal?: AbortSignal): Promise<OkResult> {
  return expectOk(await apiPost<OkResult>(`/${backend}/unpin`, { id }, signal))
}

export async function archiveSession(backend: string, id: string, signal?: AbortSignal): Promise<OkResult> {
  return expectOk(await apiPost<OkResult>(`/${backend}/archive`, { id }, signal))
}

export async function unarchiveSession(backend: string, id: string, signal?: AbortSignal): Promise<OkResult> {
  return expectOk(await apiPost<OkResult>(`/${backend}/unarchive`, { id }, signal))
}

export async function deleteSession(backend: string, id: string, signal?: AbortSignal): Promise<OkResult> {
  return expectOk(await apiPost<OkResult>(`/${backend}/delete-session`, { id }, signal))
}

export async function listRoots(signal?: AbortSignal): Promise<DirEntry[]> {
  const data = expectOk(
    await apiGet<{ ok?: boolean; error?: string; roots: DirEntry[] }>('/dirs/roots', signal),
  )
  return data.roots ?? []
}

// Köprü parametre adı `root`'tur (general.mjs listDirs) — Android de öyle
// çağırır (BridgeClientGeneral.kt workerDirs). Boş root = ev dizini.
export async function listDirs(root: string, includeFiles = false, signal?: AbortSignal): Promise<WorkerDirs> {
  const params = new URLSearchParams()
  if (root) params.set('root', root)
  if (includeFiles) params.set('files', 'true')
  const query = params.toString()
  const data = await apiGet<WorkerDirs>(`/dirs${query ? `?${query}` : ''}`, signal)
  return expectOk(data)
}

// `root` zorunlu: general.mjs'te var olmayan kök 400 döndürür. Derinlik/sınır
// köprünün varsayılanıyla (6/50) aynı.
export async function searchDirs(
  root: string,
  query: string,
  maxDepth = 6,
  limit = 50,
  signal?: AbortSignal,
): Promise<DirSearchResult[]> {
  const params = new URLSearchParams({ root, q: query })
  if (maxDepth > 0) params.set('maxDepth', String(maxDepth))
  if (limit > 0) params.set('limit', String(limit))
  const data = expectOk(
    await apiGet<{ ok?: boolean; error?: string; results: DirSearchResult[] }>(
      `/dirs/search?${params.toString()}`,
      signal,
    ),
  )
  return data.results ?? []
}

export interface AdoptResult {
  ok?: boolean
  sessionId?: string
  cwd?: string
  model?: string
  error?: string
}

/**
 * Diskteki bir oturumu köprüye canlı olarak geri alır.
 *
 * `cwd` göndermek şart: köprü oturumu o dizinde yeniden kuruyor. Dönen
 * `sessionId` diskteki kimliğin aynısıdır (claude-app.adoptSession).
 */
export async function adoptSession(
  backend: string,
  id: string,
  cwd: string,
  signal?: AbortSignal,
  options?: { cowork?: boolean },
): Promise<AdoptResult> {
  return expectOk(await apiPost<AdoptResult>(
    `/${backend}/adopt`,
    { id, cwd, ...(options?.cowork === true ? { cowork: true } : {}) },
    signal,
  ))
}
