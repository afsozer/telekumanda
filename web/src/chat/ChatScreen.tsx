import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { fetchBackends, findBackend, FALLBACK_BACKENDS, type BackendInfo } from '../lib/backends'
import type { ChatNavigationTarget } from '../navigation'
import { Composer } from './composer/Composer'
import {
  ApprovalPrompt,
  EffortPicker,
  ModelPicker,
  PermissionModePicker,
  RunControls,
  type ApprovalInfo,
} from './controls'
import { groupRows, MessageRow, ThoughtGroup } from './render'
import {
  NewSessionDialog,
  SessionList,
  SessionTabs,
  adoptSession,
  archiveSession,
  deleteSession,
  listAllSessions,
  listArchivedSessions,
  pinSession,
  renameSession,
  unarchiveSession,
  unpinSession,
  type SessionAction,
  type SessionActionSupport,
  type SessionListItem,
  type SessionTab,
  type TaggedSession,
} from './sessions'
import { UsageChip } from '../usage'
import { PlanPanel } from './PlanPanel'
import { RulesPanel } from './rules/RulesPanel'
import { ChangesPanel } from './changes/ChangesPanel'
import { forkFromMessage, rewindTo } from './forkApi'
import { useChatRows } from './useChatRows'
import { useStickToBottom } from './useStickToBottom'
import styles from './ChatScreen.module.css'

const STORAGE_KEY = 'agentbridge.backend'
const TABS_STORAGE_KEY = 'agentbridge.chat.tabs.v1'
const ACTIVE_TAB_STORAGE_KEY = 'agentbridge.chat.active-tab.v1'

/** Ajan filtresi "hepsi" değeri. Backend id'leriyle çakışmayacak bir sözcük. */
const ALL = '*'

function initialBackend(): string {
  try {
    const val = localStorage.getItem(STORAGE_KEY)
    if (val === 'omp' || val === 'runpod') {
      localStorage.removeItem(STORAGE_KEY)
      return ALL
    }
    return val || ALL
  } catch {
    return ALL
  }
}

type SessionView = 'all' | 'pinned' | 'archived'

interface StoredTabs {
  tabs: SessionTab[]
  activeKey: string
}

function sessionTabKey(backend: string, sessionId: string): string {
  return `${backend}:${sessionId}`
}

function initialTabs(): StoredTabs {
  try {
    const parsed = JSON.parse(localStorage.getItem(TABS_STORAGE_KEY) || '[]') as SessionTab[]
    const tabs = Array.isArray(parsed)
      ? parsed.filter(
          (tab) =>
            tab &&
            typeof tab.backend === 'string' &&
            typeof tab.sessionId === 'string' &&
            tab.backend !== 'omp' &&
            tab.backend !== 'runpod' &&
            tab.api !== 'omp' &&
            tab.api !== 'runpod',
        )
      : []
    const active = localStorage.getItem(ACTIVE_TAB_STORAGE_KEY) || ''
    const activeValid = active && !active.startsWith('omp:') && !active.startsWith('runpod:')
    return { tabs, activeKey: tabs.some((tab) => tab.key === active) && activeValid ? active : tabs[0]?.key || '' }
  } catch {
    return { tabs: [], activeKey: '' }
  }
}

export interface ChatScreenProps {
  navigationTarget?: ChatNavigationTarget | null
  onNavigationHandled?: (requestKey: string) => void
}

export function ChatScreen({ navigationTarget, onNavigationHandled }: ChatScreenProps = {}) {
  const [backends, setBackends] = useState<BackendInfo[]>([])
  // Ajan seçimi ZORUNLU değil: ALL iken bütün backend'lerin oturumları tek
  // listede birleşir, bir ajan seçilince liste ona filtrelenir (Android'deki
  // davranışın aynısı).
  const [filterId, setFilterId] = useState(initialBackend)
  const [sessionView, setSessionView] = useState<SessionView>('all')
  const [sessions, setSessions] = useState<TaggedSession[]>([])
  const [tabState, setTabState] = useState<StoredTabs>(initialTabs)
  const [creating, setCreating] = useState(false)
  // Diskteki bir oturum açılırken önce köprüye adopt edilir; o sırada liste
  // kilitli ve satır "canlıya alınıyor" gösteriyor.
  const [adopting, setAdopting] = useState<string | null>(null)
  // Oturum Kuralları paneli (yalnız opencode2-app, capability ile). Aç-çek:
  // açılınca bir kez yüklenir.
  const [rulesOpen, setRulesOpen] = useState(false)
  // Değişiklikler paneli (sessionDiff capability'si olan backend'lerde).
  const [changesOpen, setChangesOpen] = useState(false)
  const [listError, setListError] = useState<string | null>(null)
  const [searchTarget, setSearchTarget] = useState<ChatNavigationTarget | null>(null)
  const [highlightRowId, setHighlightRowId] = useState('')
  const [deepLinkNotice, setDeepLinkNotice] = useState('')
  const handledNavigation = useRef('')
  const handledDeepLink = useRef('')

  const activeTab = tabState.tabs.find((tab) => tab.key === tabState.activeKey) ?? null
  const selected = activeTab?.sessionId ?? ''
  const selectedBackend = activeTab?.backend ?? ''
  // Sekme değişince kurallar/değişiklikler paneli kapanır: açık kalsa yeni
  // oturumun verisini sessizce gösterirdi — başlıkta oturum adı olmadığı için
  // kullanıcı hangi oturuma baktığını sanabilirdi.
  useEffect(() => {
    setRulesOpen(false)
    setChangesOpen(false)
  }, [selected])

  // Katalog gelmeden önce gömülü tabloyla çalış: liste boş görünmesin.
  const sources: BackendInfo[] = useMemo(
    () =>
      (backends.length ? backends : FALLBACK_BACKENDS).filter(
        (b) => b.id !== 'omp' && b.id !== 'runpod',
      ),
    [backends],
  )
  // Geçmiş sekmesi ve "Tümü" listesi aynı kaynağı kullanır; ajan seçiliyse
  // yalnız o kaynak taranır.
  const scanned = useMemo(
    () => (filterId === ALL ? sources : sources.filter((source) => source.id === filterId)),
    [sources, filterId],
  )

  const activeBackendId = selectedBackend || (filterId === ALL ? '' : filterId)
  const backend = useMemo(
    () => findBackend(backends.length ? backends : FALLBACK_BACKENDS, activeBackendId),
    [backends, activeBackendId],
  )
  // `apiBackend` gerçek uç önekidir: cowork bir sunum katmanı ve claude-app
  // uçlarını kullanıyor. Katalog gelmeden önce seçili id ile devam edilir.
  // Sekmenin kendi `api` alanı varsa o kazanır — cowork oturumu codex-app
  // ya da opencode-app'e ait olabiliyor ve katalog eşlemesi onu bilmez.
  const api = activeTab?.api || backend?.apiBackend || activeBackendId
  const caps = backend?.capabilities

  const { rows, state, connected, error, atStart, loadingOlder, loadOlder, loadAllOlder } = useChatRows(
    api,
    selected,
  )
  const meta = state.meta
  // Son satırın uzunluğu da bağımlılıkta: akış sırasında satır sayısı sabit
  // kalıp yalnız metin uzuyor, o durumda da dipte kalmalıyız.
  const scroll = useStickToBottom<HTMLDivElement>(
    `${rows.length}:${rows[rows.length - 1]?.text.length ?? 0}`,
  )

  useEffect(() => {
    const controller = new AbortController()
    void fetchBackends(controller.signal).then(setBackends)
    return () => controller.abort()
  }, [])

  useEffect(() => {
    try {
      localStorage.setItem(TABS_STORAGE_KEY, JSON.stringify(tabState.tabs))
      localStorage.setItem(ACTIVE_TAB_STORAGE_KEY, tabState.activeKey)
    } catch {
      /* depolama kapalıysa sekmeler yalnız bu tarayıcı oturumunda yaşar */
    }
  }, [tabState])

  const refreshSessions = useCallback(async () => {
    const result = sessionView === 'archived'
      ? await listArchivedSessions(scanned)
      : await listAllSessions(scanned)
    const list = sessionView === 'pinned'
      ? result.sessions.filter((session) => session.pinned && !session.archived)
      : result.sessions
    setSessions(list)
    setTabState((current) => ({
      ...current,
      tabs: current.tabs.map((tab) => {
        const fresh = list.find((session) => (
          session.backend === tab.backend
          && (session.id === tab.sessionId || session.diskId === tab.diskId || session.id === tab.diskId)
        ))
        if (!fresh) return tab
        return {
          ...tab,
          title: fresh.title || tab.title,
          model: fresh.model || tab.model,
          status: fresh.status || tab.status,
          diskId: fresh.diskId || tab.diskId,
        }
      }),
    }))
    // Bir backend düştü diye listenin tamamı kaybolmasın; hata ayrı yazılır.
    setListError(result.errors.length ? result.errors.map((e) => `${e.label}: ${e.message}`).join(' · ') : null)
  }, [scanned, sessionView])

  useEffect(() => {
    void refreshSessions()
  }, [refreshSessions])

  // Akış bittiğinde (tur tamamlanınca) oturum listesini arka planda sessizce
  // tazele ki liste bayat kalmasın.
  useEffect(() => {
    if (meta.running === false) void refreshSessions()
  }, [meta.running, refreshSessions])

  const changeFilter = useCallback(
    (next: string) => {
      const sanitized = next === 'omp' || next === 'runpod' ? ALL : next
      setFilterId(sanitized)
      setListError(null)
      try {
        localStorage.setItem(STORAGE_KEY, sanitized)
      } catch {
        /* depolama kapalıysa seçim yalnız bu oturumda yaşar */
      }
    },
    [],
  )

  useEffect(() => {
    if (!tabState.activeKey || typeof meta.running !== 'boolean') return
    setTabState((current) => ({
      ...current,
      tabs: current.tabs.map((tab) => tab.key === current.activeKey
        ? { ...tab, status: meta.running ? 'running' : 'idle' }
        : tab),
    }))
  }, [meta.running, tabState.activeKey])

  const open = useCallback((row: SessionListItem, sessionId = row.id, inNewTab = false) => {
    const owner = row.backend ?? (filterId === ALL ? '' : filterId)
    if (!owner) return
    const source = sources.find((item) => item.id === owner)
    const key = sessionTabKey(owner, sessionId)
    const tab: SessionTab = {
      key,
      backend: owner,
      backendLabel: row.backendLabel || source?.label || owner,
      sessionId,
      diskId: row.diskId || (row.live === false ? row.id : undefined),
      cwd: row.cwd,
      title: row.title || row.cwd || sessionId,
      model: row.model,
      status: row.status,
      // Cowork satırı kendi sağlayıcısının ucunu taşır; katalogdaki
      // cowork→claude-app eşlemesi codex/opencode oturumları için yanlış.
      api: row.provider || undefined,
    }
    setTabState((current) => {
      // 1. Zaten açık bir sekme ise oraya geç:
      const existingIndex = current.tabs.findIndex((item) => item.key === key)
      if (existingIndex >= 0) {
        return {
          activeKey: key,
          tabs: current.tabs.map((item, idx) => (idx === existingIndex ? { ...item, ...tab } : item)),
        }
      }

      // 2. Yeni sekme istendiyse veya hiç aktif sekme yoksa yeni sekme ekle:
      if (inNewTab || !current.activeKey || current.tabs.length === 0) {
        return {
          activeKey: key,
          tabs: [...current.tabs, tab],
        }
      }

      // 3. Sol tık (aktif sekmede aç):
      // Aktif sekme şu anda çalışıyorsa (running) üzerine yazma, yeni sekme aç:
      const activeTabRunning = current.tabs.find((t) => t.key === current.activeKey)?.status === 'running'
      if (activeTabRunning) {
        return {
          activeKey: key,
          tabs: [...current.tabs, tab],
        }
      }

      // Aktif sekmeyi bu oturumla değiştir:
      return {
        activeKey: key,
        tabs: current.tabs.map((item) => (item.key === current.activeKey ? tab : item)),
      }
    })
  }, [filterId, sources])

  const closeTab = useCallback((key: string) => {
    setTabState((current) => {
      const index = current.tabs.findIndex((tab) => tab.key === key)
      const tabs = current.tabs.filter((tab) => tab.key !== key)
      const activeKey = current.activeKey === key
        ? tabs[Math.min(Math.max(index, 0), tabs.length - 1)]?.key || ''
        : current.activeKey
      return { tabs, activeKey }
    })
  }, [])

  // ── Mesaj aksiyonları: çatalla / geri sar ──────────────────────────────────
  // dropUserTurns sözleşmesi köprüyle aynı: seçilen kullanıcı mesajı DAHİL
  // sonraki kullanıcı mesajlarının sayısı. Satırlardan hesaplanıyor; history
  // sayfaları da rows'a birleştiği için sayım tüm görünen geçmişi kapsar.
  const [actionError, setActionError] = useState<{ rowId: string; message: string } | null>(null)

  const dropFor = useCallback(
    (rowId: string) => {
      const users = rows.filter((r) => r.role === 'user' && r.text.trim())
      const idx = users.findIndex((r) => r.rowId === rowId)
      return idx < 0 ? 0 : users.length - idx
    },
    [rows],
  )

  const doFork = useCallback(
    async (rowId: string) => {
      if (!selected || !api) return
      const drop = dropFor(rowId)
      if (drop < 1) return
      setActionError(null)
      try {
        const newId = await forkFromMessage(api, selected, drop)
        open(
          {
            id: newId,
            cwd: activeTab?.cwd ?? '',
            model: '',
            status: '',
            title: `${activeTab?.title || 'Oturum'} (çatal)`,
            lastUserAt: 0,
            turns: 0,
            lastText: '',
            awaitingApproval: false,
            restoredShell: false,
            backend: activeTab?.backend,
            live: true,
          },
          newId,
          true,
        )
        void refreshSessions()
      } catch (err) {
        setActionError({ rowId, message: err instanceof Error ? err.message : String(err) })
      }
    },
    [selected, api, dropFor, activeTab, open, refreshSessions],
  )

  const doRewind = useCallback(
    async (rowId: string) => {
      if (!selected || !api) return
      const drop = dropFor(rowId)
      if (drop < 1) return
      setActionError(null)
      try {
        // Köprü geri sarma sonrası snapshot yayınlar; akış satırları kendisi
        // yeniden kurar. Liste özetini de tazele (başlık/tur sayısı değişir).
        await rewindTo(api, selected, drop)
        void refreshSessions()
      } catch (err) {
        setActionError({ rowId, message: err instanceof Error ? err.message : String(err) })
      }
    },
    [selected, api, dropFor, refreshSessions],
  )

  /**
   * Listeden bir satır açar. Satır yalnız diskteyse önce köprüye adopt edilir —
   * canlı kabuğu olmayan bir oturuma akış kurulamaz. `cwd` göndermek şart:
   * köprü oturumu o dizinde yeniden kuruyor.
   */
  const openRow = useCallback(
    async (row: SessionListItem, inNewTab = false) => {
      const owner = row.backend
      if (row.live !== false) {
        open(row, row.id, inNewTab)
        return
      }
      // Cowork satırında sahip sağlayıcıdır; ayrıca adopt'a cowork bayrağı
      // geçilmeli ki köprü oturumu workspace kipinde kursun (outputs klasörü vs).
      const api = row.provider || sources.find((source) => source.id === owner)?.apiBackend || owner || ''
      setAdopting(row.id)
      setListError(null)
      try {
        const result = await adoptSession(
          api,
          row.id,
          row.cwd,
          undefined,
          owner === 'cowork' ? { cowork: true } : undefined,
        )
        open(row, result.sessionId || row.id, inNewTab)
        void refreshSessions()
      } catch (err) {
        setListError(err instanceof Error ? err.message : String(err))
      } finally {
        setAdopting(null)
      }
    },
    [open, sources, refreshSessions],
  )

  const sessionActionSupport = useCallback((row: SessionListItem): SessionActionSupport => {
    const capabilities = sources.find((source) => source.id === row.backend)?.capabilities
    return {
      pin: capabilities?.sessionPin === true,
      archive: capabilities?.sessionArchive === true,
      rename: capabilities?.sessionRename === true,
      delete: capabilities?.sessionDelete === true,
    }
  }, [sources])

  const runSessionAction = useCallback(async (
    row: SessionListItem,
    action: SessionAction,
    value?: string,
  ) => {
    const owner = sources.find((source) => source.id === row.backend)
    const endpoint = owner?.apiBackend ?? row.backend ?? ''
    if (!endpoint) throw new Error('Oturumun backend’i bulunamadı')
    // OpenCode canlı kabuk kimliğiyle disk kimliği ayrışabilir. Menü
    // Android gibi disk kaydını hedefler; varsa diskId bağlayıcı kimliktir.
    const id = row.diskId || row.id
    if (action === 'pin') await pinSession(endpoint, id)
    else if (action === 'unpin') await unpinSession(endpoint, id)
    else if (action === 'archive') await archiveSession(endpoint, id)
    else if (action === 'unarchive') await unarchiveSession(endpoint, id)
    else if (action === 'rename') await renameSession(endpoint, id, value?.trim() || '')
    else if (action === 'delete') await deleteSession(endpoint, id)

    if (action === 'delete') {
      setTabState((current) => {
        const keys = new Set(current.tabs.filter((tab) => (
          tab.backend === row.backend && (tab.sessionId === row.id || tab.diskId === id)
        )).map((tab) => tab.key))
        if (!keys.size) return current
        const tabs = current.tabs.filter((tab) => !keys.has(tab.key))
        return {
          tabs,
          activeKey: keys.has(current.activeKey) ? tabs[0]?.key || '' : current.activeKey,
        }
      })
    }
    await refreshSessions()
  }, [refreshSessions, sources])

  // Proje merkezi veya global aramadan gelen hedefi tek sıralı işlemle aç.
  // Önce ilgili backend'in kendi listesini tazeler; disk kaydıysa adopt eder,
  // sonra mesaj deep-link'ini devreye alır. Eski oturum üzerinde arama yapılmaz.
  useEffect(() => {
    if (!navigationTarget || handledNavigation.current === navigationTarget.requestKey) return
    handledNavigation.current = navigationTarget.requestKey
    let cancelled = false
    void (async () => {
      const source = sources.find((item) => item.id === navigationTarget.backend)
      const endpoint = source?.apiBackend ?? navigationTarget.backend
      setListError(null)
      setDeepLinkNotice('')
      try {
        const fresh = source ? await listAllSessions([source]) : { sessions: [], errors: [] }
        const found = fresh.sessions.find((session) => (
          session.id === navigationTarget.sessionId || session.diskId === navigationTarget.sessionId
        ))
        const row: SessionListItem = found ?? {
          id: navigationTarget.sessionId,
          cwd: navigationTarget.cwd,
          model: navigationTarget.model || '',
          status: '',
          title: navigationTarget.title || navigationTarget.sessionId,
          lastUserAt: 0,
          turns: 0,
          lastText: '',
          awaitingApproval: false,
          restoredShell: false,
          backend: navigationTarget.backend,
          backendLabel: navigationTarget.backendLabel || source?.label || navigationTarget.backend,
          live: navigationTarget.live,
        }
        let actualSessionId = row.id
        if (row.live === false || (!found && navigationTarget.live !== true)) {
          setAdopting(row.id)
          const adopted = await adoptSession(
            endpoint,
            navigationTarget.sessionId,
            navigationTarget.cwd,
            undefined,
            { cowork: navigationTarget.container === 'cowork' },
          )
          actualSessionId = adopted.sessionId || navigationTarget.sessionId
        }
        if (cancelled) return
        open(row, actualSessionId)
        if (navigationTarget.query && (navigationTarget.rowId || navigationTarget.matchOrdinal !== undefined)) {
          setSearchTarget({ ...navigationTarget, sessionId: actualSessionId })
          setDeepLinkNotice(`“${navigationTarget.query}” sonucu yükleniyor…`)
        } else {
          setSearchTarget(null)
          setHighlightRowId('')
        }
        void refreshSessions()
      } catch (err) {
        if (!cancelled) setListError(err instanceof Error ? err.message : String(err))
      } finally {
        if (!cancelled) {
          setAdopting(null)
          onNavigationHandled?.(navigationTarget.requestKey)
        }
      }
    })()
    return () => { cancelled = true }
  }, [navigationTarget, onNavigationHandled, open, refreshSessions, sources])

  useEffect(() => {
    if (!searchTarget || !searchTarget.query) return
    if (selectedBackend !== searchTarget.backend || selected !== searchTarget.sessionId) return
    if (handledDeepLink.current === searchTarget.requestKey) return
    handledDeepLink.current = searchTarget.requestKey
    let cancelled = false
    void loadAllOlder()
      .then((allRows) => {
        if (cancelled) return
        const matches = allRows.filter((row) => (
          (row.role === 'user' || row.role === 'agent')
          && row.text.toLocaleLowerCase('tr').includes(searchTarget.query!.toLocaleLowerCase('tr'))
        ))
        const target = searchTarget.rowId
          ? allRows.find((row) => row.rowId === searchTarget.rowId)
          : matches[searchTarget.matchOrdinal ?? 0]
        if (!target) {
          setDeepLinkNotice('Eşleşme bulundu ancak sohbet satırı yüklenemedi.')
          return
        }
        setHighlightRowId(target.rowId)
        setDeepLinkNotice(`Arama sonucu · ${Math.max(1, (searchTarget.matchOrdinal ?? 0) + 1)}. eşleşme`)
        window.setTimeout(() => {
          const node = [...document.querySelectorAll<HTMLElement>('[data-chat-row-id]')]
            .find((item) => item.dataset.chatRowId === target.rowId)
          node?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
        }, 0)
      })
      .catch((err) => {
        if (!cancelled) setDeepLinkNotice(`Arama geçmişi yüklenemedi: ${err instanceof Error ? err.message : String(err)}`)
      })
    return () => { cancelled = true }
  }, [loadAllOlder, searchTarget, selected, selectedBackend])

  const approval = meta.awaitingApproval ? (meta.approval as ApprovalInfo | undefined) : undefined
  const activeSession = sessions.find((session) => (
    session.backend === selectedBackend
    && (session.id === selected || session.diskId === activeTab?.diskId)
  ))
  // Modelin kaynağı oturum kaydı: META_KEYS'te `model` yok, akış onu taşımıyor.
  const activeModel = activeSession?.model ?? activeTab?.model ?? ''

  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <div className={styles.sidebarHead}>
          <h1 className={styles.brand}>Telekumanda</h1>
          <button type="button" className={styles.new} onClick={() => setCreating(true)}>
            Yeni
          </button>
        </div>
        <div className={styles.backendBar}>
          <label className={styles.backendLabel}>
            Ajan
            <select
              className={styles.backendSelect}
              value={filterId}
              aria-label="Ajan"
              onChange={(event) => changeFilter(event.target.value)}
            >
              <option value={ALL}>Tümü</option>
              {sources.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className={styles.sessionSegments} aria-label="Oturum görünümü">
          {([
            ['all', 'Tümü'],
            ['pinned', 'Sabitli'],
            ['archived', 'Arşiv'],
          ] as const).map(([id, label]) => (
            <button
              key={id}
              type="button"
              aria-pressed={sessionView === id}
              className={sessionView === id ? styles.segmentOn : styles.segment}
              onClick={() => setSessionView(id)}
            >
              {label}
            </button>
          ))}
        </div>
        {listError && <p className={styles.error}>{listError}</p>}
        {/*
          TEK liste: canlı kabuklar ve diskteki oturumlar birleşik
          (`unifySessions`). Canlı/Geçmiş sekmeleri kaldırıldı — ayrım
          kullanıcının umursadığı bir şey değil, köprünün iç durumuydu ve
          gerçek sohbetleri "Geçmiş"e saklıyordu. Android de tek liste tutuyor.
        */}
        <SessionList
          key={`${filterId}:${sessionView}`}
          sessions={sessions}
          showAgent={filterId === ALL}
          selectedId={selected}
          selectedBackend={selectedBackend}
          busyId={adopting}
          onSelect={(row, options) => void openRow(row, options?.newTab ?? false)}
          onSelectNewTab={(row) => void openRow(row, true)}
          actionSupport={sessionActionSupport}
          onAction={runSessionAction}
        />
      </aside>

      <main className={styles.main}>
        <SessionTabs
          tabs={tabState.tabs}
          activeKey={tabState.activeKey}
          onSelect={(key) => {
            setTabState((current) => ({ ...current, activeKey: key }))
            setHighlightRowId('')
            setSearchTarget(null)
            setDeepLinkNotice('')
          }}
          onClose={closeTab}
          onNew={() => setCreating(true)}
        />
        {!selected ? (
          <div className={styles.empty}>
            <p>Soldan bir oturum seç ya da yeni bir tane başlat.</p>
          </div>
        ) : (
          <>
            {error && <p className={styles.error}>{error}</p>}

            {deepLinkNotice && (
              <div className={styles.searchNotice} role="status">
                <span>{deepLinkNotice}</span>
                <button
                  type="button"
                  onClick={() => {
                    setDeepLinkNotice('')
                    setHighlightRowId('')
                    setSearchTarget(null)
                  }}
                >
                  Kapat
                </button>
              </div>
            )}

            {caps?.plan && <PlanPanel plan={meta.plan} draft={meta.planDraft} />}

            <div className={styles.scroll} ref={scroll.ref} onScroll={scroll.onScroll}>
              <div className={styles.column}>
                <div className={styles.older}>
                  {atStart ? (
                    <span className={styles.muted}>Sohbetin başı</span>
                  ) : (
                    <button
                      type="button"
                      className={styles.link}
                      onClick={loadOlder}
                      disabled={loadingOlder || rows.length === 0}
                    >
                      {loadingOlder ? 'Yükleniyor…' : 'Daha eskisini yükle'}
                    </button>
                  )}
                </div>
                {groupRows(rows).map((group) =>
                  group.kind === 'thoughts' ? (
                    <ThoughtGroup key={group.key} rows={group.rows} />
                  ) : (
                    <div
                      key={group.row.rowId}
                      data-chat-row-id={group.row.rowId}
                      className={group.row.rowId === highlightRowId ? styles.searchHit : undefined}
                    >
                      {/*
                        Aksiyonlar YETENEK güdümlü: çatalla yalnız v2'de (fork-from
                        ucu), geri sar kayıtlı backend'lerde — ve tur sürerken
                        çizilmez (köprü zaten reddeder; boşuna tıklanmasın).
                      */}
                      <MessageRow
                        row={group.row}
                        onFork={
                          caps?.sessionFork === true
                            ? () => {
                                void doFork(group.row.rowId)
                              }
                            : undefined
                        }
                        onRewind={
                          caps?.sessionRewind === true && meta.running !== true
                            ? () => {
                                void doRewind(group.row.rowId)
                              }
                            : undefined
                        }
                        actionError={
                          actionError?.rowId === group.row.rowId ? actionError.message : null
                        }
                      />
                    </div>
                  ),
                )}
              </div>
            </div>

            {!scroll.atBottom && (
              <button
                type="button"
                className={styles.toBottom}
                onClick={() => scroll.scrollToBottom('smooth')}
              >
                ↓ En alta
              </button>
            )}

            <div className={styles.dock}>
              <div className={styles.column}>
                {caps?.approvals && approval && (
                  <ApprovalPrompt
                    backend={api}
                    sessionId={selected}
                    approval={approval}
                    onDecision={() => {}}
                  />
                )}

                {rulesOpen && (
                  <RulesPanel
                    backend={api}
                    sessionId={selected}
                    onClose={() => setRulesOpen(false)}
                  />
                )}

                {changesOpen && (
                  <ChangesPanel
                    backend={api}
                    sessionId={selected}
                    onClose={() => setChangesOpen(false)}
                  />
                )}

                {/*
                  model / permissionMode BİLEREK geçirilmiyor: oturumun kendi
                  ayarları köprüde duruyor ve seçiciler onları kendi uçlarıyla
                  değiştiriyor. Her mesajda tekrar göndermek, kullanıcının
                  seçiciden yaptığı değişikliği sessizce geri alırdı.
                */}
                <Composer
                  sessionId={selected}
                  backend={api}
                  commands={meta.commands as { name: string; desc?: string }[] | undefined}
                  choices={meta.running === true ? undefined : meta.choices}
                  running={meta.running === true}
                  canSteer={caps?.userInputSteer === true}
                  onSent={() => scroll.scrollToBottom()}
                />
              </div>

              {/*
                Kontrol şeridi sütunun DIŞINDA, tam genişlikte: 62rem'e
                sıkıştırıldığında kes/durdur/bağlam ikinci satıra sarıyordu.
                Yazma kutusu metin sütunuyla hizalı kalır, şerit alt durum
                çubuğu gibi davranır.

                Kontroller YETENEK güdümlü: hangi ucun kayıtlı olduğu köprünün
                /backends kataloğundan geliyor (backend-contract.mjs). Burada
                backend adına göre koşul YAZMA — katalog tek kaynaktır.
              */}
              <div className={styles.dockBar}>
                <span
                  className={connected ? styles.live : styles.offline}
                  title={connected ? 'Akış bağlı' : 'Akış kopuk'}
                >
                  {connected ? '● bağlı' : '○ bağlı değil'}
                </span>
                <ModelPicker
                  backend={api}
                  sessionId={selected}
                  models={meta.availableModels}
                  value={activeModel}
                  onChanged={() => void refreshSessions()}
                />
                {caps?.permissionModes && (
                  <PermissionModePicker
                    backend={api}
                    sessionId={selected}
                    modes={meta.permissionModes as string[] | undefined}
                    value={meta.permissionMode ?? ''}
                    onChanged={() => {}}
                  />
                )}
                {caps?.efforts && (
                  <EffortPicker
                    backend={api}
                    sessionId={selected}
                    value={meta.effort ?? ''}
                    onChanged={() => {}}
                  />
                )}
                {/*
                  Kalan kullanım: şeritte tek sayı (en az kalan pencere),
                  tıklanınca hepsi. Eşleşme bucket id önekinden.
                */}
                <UsageChip backend={api} />
                {caps?.sessionInstructions && (
                  <button
                    type="button"
                    className={`${styles.rulesToggle} ${rulesOpen ? styles.rulesToggleActive : ''}`}
                    aria-pressed={rulesOpen}
                    title="Bu oturuma kalıcı talimatlar ve kayıtlı izin kuralları"
                    onClick={() => setRulesOpen((open) => !open)}
                  >
                    Kurallar
                  </button>
                )}
                {caps?.sessionDiff && (
                  <button
                    type="button"
                    className={`${styles.rulesToggle} ${changesOpen ? styles.rulesToggleActive : ''}`}
                    aria-pressed={changesOpen}
                    title="Bu oturumun dokunduğu dosyalar ve patch'ler"
                    onClick={() => setChangesOpen((open) => !open)}
                  >
                    Değişiklikler
                  </button>
                )}
                {typeof meta.cost === 'number' && meta.cost > 0 && (
                  <span className={styles.cost} title="Bu oturumun toplam maliyeti">
                    ${meta.cost.toFixed(2)}
                  </span>
                )}
                <RunControls
                  backend={api}
                  sessionId={selected}
                  running={meta.running === true}
                  canInterrupt={caps?.interrupt !== false}
                  interruptStuck={meta.interruptStuck}
                  contextTokens={caps?.context === false ? undefined : meta.contextTokens}
                  contextWindow={caps?.context === false ? undefined : meta.contextWindow}
                />
              </div>
            </div>
          </>
        )}
      </main>

      {creating && (
        <NewSessionDialog
          // Ajan filtresi ÖNCELIKLI: kullanıcı listeyi "OpenCode 2"ye süzüp
          // Yeni dediyse v2 oturumu bekler. Filtre "Tümü" iken (agents verilir,
          // seçici çizilir) aktif sekmenin ajanıyla devam edilir — sekme
          // değiştirmeden aynı ajanda oturum açmak yaygın akış.
          backend={filterId !== ALL ? filterId : activeBackendId || sources[0]?.id || 'claude-app'}
          agents={filterId === ALL ? sources : undefined}
          onCreated={(sessionId, owner) => {
            setCreating(false)
            const source = sources.find((item) => item.id === owner)
            open({
              id: sessionId,
              cwd: '',
              model: '',
              status: '',
              title: 'Yeni oturum',
              lastUserAt: 0,
              turns: 0,
              lastText: '',
              awaitingApproval: false,
              restoredShell: false,
              backend: owner,
              backendLabel: source?.label || owner,
              live: true,
            }, sessionId, true)
            void refreshSessions()
          }}
          onClose={() => setCreating(false)}
        />
      )}
    </div>
  )
}
