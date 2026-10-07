import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { SessionListItem } from './SessionList'
import styles from './SessionList.module.css'

export type SessionAction =
  | 'pin'
  | 'unpin'
  | 'archive'
  | 'unarchive'
  | 'rename'
  | 'delete'

export interface SessionActionSupport {
  pin: boolean
  archive: boolean
  rename: boolean
  delete: boolean
}

export interface SessionActionsMenuProps {
  session: SessionListItem
  support: SessionActionSupport
  anchor: { x: number; y: number }
  onOpenNewTab?: (session: SessionListItem) => void
  onAction: (session: SessionListItem, action: SessionAction, value?: string) => Promise<void>
  onNotice: (message: string) => void
  onClose: () => void
}

async function copyText(value: string): Promise<void> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
      return
    }
  } catch {
    // Düz HTTP/Tailscale adreslerinde Clipboard API reddedilebilir; eski ama
    // geniş destekli seçim+kopyalama yoluna düş.
  }
  const field = document.createElement('textarea')
  field.value = value
  field.setAttribute('readonly', '')
  field.style.position = 'fixed'
  field.style.opacity = '0'
  document.body.appendChild(field)
  field.select()
  const copied = document.execCommand?.('copy') ?? false
  field.remove()
  if (!copied) throw new Error('Panoya erişilemedi')
}

function displayTitle(session: SessionListItem): string {
  return session.title || session.cwd || session.id
}

export function SessionActionsMenu({
  session,
  support,
  anchor,
  onOpenNewTab,
  onAction,
  onNotice,
  onClose,
}: SessionActionsMenuProps) {
  const [mode, setMode] = useState<'menu' | 'rename' | 'delete'>('menu')
  const [renameValue, setRenameValue] = useState(session.title || '')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    const outside = (event: PointerEvent) => {
      if (mode === 'menu' && !menuRef.current?.contains(event.target as Node)) onClose()
    }
    document.addEventListener('keydown', key)
    document.addEventListener('pointerdown', outside)
    return () => {
      document.removeEventListener('keydown', key)
      document.removeEventListener('pointerdown', outside)
    }
  }, [mode, onClose])

  const invoke = async (action: SessionAction, value?: string) => {
    setPending(true)
    setError(null)
    try {
      await onAction(session, action, value)
      onClose()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setPending(false)
    }
  }

  const copyId = async () => {
    setPending(true)
    setError(null)
    try {
      await copyText(session.diskId || session.id)
      onNotice('Oturum kimliği kopyalandı')
      onClose()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setPending(false)
    }
  }

  const left = Math.max(8, Math.min(anchor.x, window.innerWidth - 228))
  const top = Math.max(8, Math.min(anchor.y, window.innerHeight - 310))

  if (mode === 'rename') {
    return createPortal(
      <div className={styles.dialogBackdrop} role="presentation" onPointerDown={onClose}>
        <div
          className={styles.dialog}
          role="dialog"
          aria-modal="true"
          aria-labelledby="session-rename-title"
          onPointerDown={(event) => event.stopPropagation()}
        >
          <h2 id="session-rename-title">Yeniden adlandır</h2>
          <input
            autoFocus
            className={styles.dialogInput}
            aria-label="Yeni oturum adı"
            value={renameValue}
            onChange={(event) => setRenameValue(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && renameValue.trim()) void invoke('rename', renameValue.trim())
            }}
          />
          {error && <p className={styles.menuError} role="alert">{error}</p>}
          <div className={styles.dialogActions}>
            <button type="button" className={styles.menuSecondary} onClick={onClose}>Vazgeç</button>
            <button
              type="button"
              className={styles.menuPrimary}
              disabled={pending || !renameValue.trim()}
              onClick={() => void invoke('rename', renameValue.trim())}
            >
              {pending ? 'Kaydediliyor…' : 'Kaydet'}
            </button>
          </div>
        </div>
      </div>,
      document.body,
    )
  }

  if (mode === 'delete') {
    return createPortal(
      <div className={styles.dialogBackdrop} role="presentation" onPointerDown={onClose}>
        <div
          className={styles.dialog}
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="session-delete-title"
          onPointerDown={(event) => event.stopPropagation()}
        >
          <h2 id="session-delete-title">Oturum silinsin mi?</h2>
          <p>“{displayTitle(session)}” kalıcı olarak silinir. Bu işlem geri alınamaz.</p>
          {error && <p className={styles.menuError} role="alert">{error}</p>}
          <div className={styles.dialogActions}>
            <button type="button" className={styles.menuSecondary} onClick={onClose}>Vazgeç</button>
            <button
              type="button"
              className={styles.menuDanger}
              disabled={pending}
              onClick={() => void invoke('delete')}
            >
              {pending ? 'Siliniyor…' : 'Sil'}
            </button>
          </div>
        </div>
      </div>,
      document.body,
    )
  }

  return createPortal(
    <div
      ref={menuRef}
      className={styles.contextMenu}
      style={{ left, top }}
      role="menu"
      aria-label="Oturum işlemleri"
      onContextMenu={(event) => event.preventDefault()}
    >
      <div className={styles.menuHeading} title={session.diskId || session.id}>
        <strong>{displayTitle(session)}</strong>
        <span>{session.diskId || session.id}</span>
      </div>
      {onOpenNewTab && (
        <button
          type="button"
          role="menuitem"
          disabled={pending}
          onClick={() => {
            onOpenNewTab(session)
            onClose()
          }}
        >
          Yeni sekmede aç
        </button>
      )}
      {support.pin && (
        <button type="button" role="menuitem" disabled={pending} onClick={() => void invoke(session.pinned ? 'unpin' : 'pin')}>
          {session.pinned ? 'Sabitlemeyi kaldır' : 'Sabitle'}
        </button>
      )}
      {support.archive && (
        <button type="button" role="menuitem" disabled={pending} onClick={() => void invoke(session.archived ? 'unarchive' : 'archive')}>
          {session.archived ? 'Arşivden çıkar' : 'Arşivle'}
        </button>
      )}
      {support.rename && (
        <button type="button" role="menuitem" disabled={pending} onClick={() => setMode('rename')}>
          Yeniden adlandır
        </button>
      )}
      <button type="button" role="menuitem" disabled={pending} onClick={() => void copyId()}>
        Kimliği kopyala
      </button>
      {support.delete && (
        <button type="button" role="menuitem" className={styles.menuDeleteItem} disabled={pending} onClick={() => setMode('delete')}>
          Sil
        </button>
      )}
      {error && <p className={styles.menuError} role="alert">{error}</p>}
    </div>,
    document.body,
  )
}
