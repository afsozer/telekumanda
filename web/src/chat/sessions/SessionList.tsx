// Canlı + disk oturum listesi: arama, kademeli çizim ve seçim.

import { useEffect, useRef, useState } from 'react'
import { filterSessions } from './filterSessions'
import {
  SessionActionsMenu,
  type SessionAction,
  type SessionActionSupport,
} from './SessionActionsMenu'
import styles from './SessionList.module.css'
import type { Session } from './sessionsApi'

const SESSION_PAGE_SIZE = 40

/**
 * Listede çizilen satır. `backend` yalnız birleşik ("Tümü") listede dolu.
 * `live: false` = oturum yalnız diskte; açılırken adopt edilmesi gerekir.
 */
export type SessionListItem = Session & {
  backend?: string
  backendLabel?: string
  live?: boolean
  /** Cowork satırlarında oturumun gerçek sahibi (adopt/akış bu uçtan gider). */
  provider?: string
}

export interface SessionListProps {
  sessions: SessionListItem[]
  /** Satırlarda ajan rozeti göster. Liste karışıkken ("Tümü") anlamlı. */
  showAgent?: boolean
  selectedId?: string | null
  /** Birleşik listede aynı session id başka backend'de de bulunabilir. */
  selectedBackend?: string | null
  /** Şu an adopt edilmekte olan oturum (satır beklemede görünsün). */
  busyId?: string | null
  /**
   * Satırın TAMAMI geçer: çağıran hem hangi uca bağlanacağını (`backend`) hem
   * de adopt için `cwd`yi buradan okur. Yalnız id geçmek, karışık listede tek
   * bir "aktif ajan" varsaymak demekti.
   */
  onSelect: (session: SessionListItem, options?: { newTab?: boolean }) => void
  /** Oturumu doğrudan yeni bir sekmede açmak için (orta tık veya sağ tık menüsü). */
  onSelectNewTab?: (session: SessionListItem) => void
  /** Backend kataloğundan türetilir; desteklenmeyen işlem menüde çizilmez. */
  actionSupport?: (session: SessionListItem) => SessionActionSupport
  onAction?: (session: SessionListItem, action: SessionAction, value?: string) => Promise<void>
}

function sessionTitle(session: SessionListItem): string {
  return session.title || session.cwd || session.id
}

export function sessionRepoName(cwd: string): string {
  const clean = cwd.trim().replace(/[\\/]+$/, '')
  if (!clean) return ''
  return clean.split(/[\\/]/).filter(Boolean).at(-1) ?? clean
}

export function SessionList({
  sessions,
  showAgent = false,
  selectedId = null,
  selectedBackend = null,
  busyId = null,
  onSelect,
  onSelectNewTab,
  actionSupport,
  onAction,
}: SessionListProps) {
  const [query, setQuery] = useState('')
  const [visibleLimit, setVisibleLimit] = useState(SESSION_PAGE_SIZE)
  const [menu, setMenu] = useState<{ session: SessionListItem; x: number; y: number } | null>(null)
  const [notice, setNotice] = useState('')
  const longPressTimer = useRef<number | null>(null)
  const loadMoreSentinel = useRef<HTMLDivElement | null>(null)
  const suppressSelect = useRef(false)
  const { visible } = filterSessions(sessions, { query })
  const displayed = visible.slice(0, visibleLimit)
  const hasMore = displayed.length < visible.length

  useEffect(() => () => {
    if (longPressTimer.current != null) window.clearTimeout(longPressTimer.current)
  }, [])

  // Yeni aramada listenin başından başla. Oturumların arka planda tazelenmesi
  // ise kullanıcının ulaştığı dilimi küçültmemeli.
  useEffect(() => setVisibleLimit(SESSION_PAGE_SIZE), [query])

  // Sidebar scroll'u sentinel'i yaklaştırdığında bir sonraki dilimi çiz.
  // IntersectionObserver olmayan test/eski tarayıcı ortamında alttaki düğme
  // aynı işi erişilebilir bir fallback olarak yapar.
  useEffect(() => {
    const node = loadMoreSentinel.current
    if (!node || !hasMore || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      if (!entries.some((entry) => entry.isIntersecting)) return
      setVisibleLimit((current) => Math.min(current + SESSION_PAGE_SIZE, visible.length))
    }, { rootMargin: '180px 0px' })
    observer.observe(node)
    return () => observer.disconnect()
  }, [hasMore, visible.length])

  const clearLongPress = () => {
    if (longPressTimer.current != null) window.clearTimeout(longPressTimer.current)
    longPressTimer.current = null
  }

  const openMenu = (session: SessionListItem, x: number, y: number) => {
    setNotice('')
    setMenu({ session, x, y })
  }

  const handleMiddleClick = (event: React.MouseEvent, session: SessionListItem) => {
    if (event.button === 1) {
      event.preventDefault()
      if (onSelectNewTab) {
        onSelectNewTab(session)
      } else {
        onSelect(session, { newTab: true })
      }
    }
  }

  return (
    <div className={styles.wrap}>
      {sessions.length > 6 && (
        <input
          type="search"
          className={styles.search}
          value={query}
          aria-label="Oturum ara"
          placeholder="Ara…"
          onChange={(event) => setQuery(event.target.value)}
        />
      )}

      {visible.length === 0 ? (
        <p className={styles.empty}>
          {sessions.length === 0
            ? 'Oturum yok.'
            : query
              ? 'Aramaya uyan oturum yok.'
              : 'Kullanılmış oturum yok.'}
        </p>
      ) : (
        <ul className={styles.list}>
          {displayed.map((session) => {
            const classes = [styles.row]
            const isSelected = session.id === selectedId
              && (!selectedBackend || session.backend === selectedBackend)
            if (isSelected) classes.push(styles.selected)
            if (session.awaitingApproval) classes.push(styles.approval)
            return (
              <li
                key={`${session.backend ?? ''}:${session.id}`}
                className={styles.rowShell}
                onContextMenu={(event) => {
                  event.preventDefault()
                  openMenu(session, event.clientX, event.clientY)
                }}
                onAuxClick={(event) => handleMiddleClick(event, session)}
                onMouseDown={(event) => {
                  if (event.button === 1) event.preventDefault()
                }}
                onPointerDown={(event) => {
                  if (event.pointerType !== 'touch' && event.pointerType !== 'pen') return
                  clearLongPress()
                  const { clientX, clientY } = event
                  longPressTimer.current = window.setTimeout(() => {
                    suppressSelect.current = true
                    openMenu(session, clientX, clientY)
                  }, 550)
                }}
                onPointerUp={clearLongPress}
                onPointerCancel={clearLongPress}
                onPointerLeave={clearLongPress}
              >
                <button
                  type="button"
                  className={classes.join(' ')}
                  disabled={busyId !== null && busyId !== session.id}
                  data-session-row="true"
                  onClick={() => {
                    if (suppressSelect.current) {
                      suppressSelect.current = false
                      return
                    }
                    onSelect(session)
                  }}
                  onAuxClick={(event) => handleMiddleClick(event, session)}
                  onMouseDown={(event) => {
                    if (event.button === 1) event.preventDefault()
                  }}
                  aria-current={isSelected ? 'true' : undefined}
                >
                  <span className={styles.titleRow}>
                    {showAgent && session.backendLabel && (
                      <span className={styles.agent} title={`Ajan: ${session.backendLabel}`}>
                        {session.backendLabel}
                      </span>
                    )}
                    {session.pinned && <span className={styles.pin} title="Sabitli" aria-label="Sabitli">📌</span>}
                    {sessionRepoName(session.cwd) && (
                      <span className={styles.repo} title={`Repo: ${session.cwd}`}>
                        {sessionRepoName(session.cwd)}
                      </span>
                    )}
                    <span className={styles.title}>{sessionTitle(session)}</span>
                    {session.status === 'running' && (
                      <span className={styles.running} title="Ajan şu anda çalışıyor">
                        ● çalışıyor
                      </span>
                    )}
                    {busyId === session.id && (
                      <span className={styles.running}>canlıya alınıyor…</span>
                    )}
                    {session.awaitingApproval && (
                      <span className={styles.badge} title="Kullanıcı onayı bekleniyor">
                        onay bekliyor
                      </span>
                    )}
                  </span>
                  {session.lastText && <span className={styles.lastText}>{session.lastText}</span>}
                  <span className={styles.meta}>
                    <span>{session.turns} tur</span>
                    {session.model && <span>{session.model}</span>}
                    {/* Köprüde kabuğu yok: tıklanınca önce canlıya alınacak. */}
                    {session.live === false && (
                      <span title="Köprüde canlı değil; açılırken canlıya alınır">diskte</span>
                    )}
                    {session.cwd && <span className={styles.cwd}>{session.cwd}</span>}
                  </span>
                </button>
                <button
                  type="button"
                  className={styles.menuTrigger}
                  aria-label={`Oturum işlemleri: ${session.diskId || session.id}`}
                  title={`${sessionTitle(session)} işlemleri`}
                  disabled={busyId !== null && busyId !== session.id}
                  onClick={(event) => {
                    const rect = event.currentTarget.getBoundingClientRect()
                    openMenu(session, rect.right - 210, rect.bottom + 4)
                  }}
                >
                  ⋯
                </button>
              </li>
            )
          })}
        </ul>
      )}

      {hasMore && (
        <div ref={loadMoreSentinel} className={styles.loadMoreSentinel}>
          <button
            type="button"
            className={styles.more}
            onClick={() => setVisibleLimit((current) => Math.min(current + SESSION_PAGE_SIZE, visible.length))}
          >
            {visible.length - displayed.length} oturum daha — devamını göster
          </button>
        </div>
      )}
      {notice && <p className={styles.notice} role="status">{notice}</p>}
      {menu && (
        <SessionActionsMenu
          session={menu.session}
          support={actionSupport?.(menu.session) ?? { pin: false, archive: false, rename: false, delete: false }}
          anchor={{ x: menu.x, y: menu.y }}
          onOpenNewTab={(session) => {
            if (onSelectNewTab) onSelectNewTab(session)
            else onSelect(session, { newTab: true })
          }}
          onAction={onAction ?? (async () => { throw new Error('Bu işlem desteklenmiyor') })}
          onNotice={setNotice}
          onClose={() => setMenu(null)}
        />
      )}
    </div>
  )
}
