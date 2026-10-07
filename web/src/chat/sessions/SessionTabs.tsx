import { sessionRepoName } from './SessionList'
import styles from './SessionTabs.module.css'

export interface SessionTab {
  key: string
  backend: string
  backendLabel: string
  sessionId: string
  diskId?: string
  cwd: string
  title: string
  model?: string
  status?: string
  /**
   * Uç öneki için katalogdaki apiBackend'i EZER. Yalnız cowork'te gerekiyor:
   * katalog cowork'ü claude-app'e eşliyor ama bir cowork oturumu codex-app,
   * opencode-app'e ait olabilir; bu alan olmadan akış yanlış
   * backend'e kurulur.
   */
  api?: string
}

export interface SessionTabsProps {
  tabs: SessionTab[]
  activeKey: string
  onSelect: (key: string) => void
  onClose: (key: string) => void
  onNew: () => void
}

export function SessionTabs({ tabs, activeKey, onSelect, onClose, onNew }: SessionTabsProps) {
  const handleMiddleClick = (event: React.MouseEvent, key: string) => {
    if (event.button === 1) {
      event.preventDefault()
      event.stopPropagation()
      onClose(key)
    }
  }

  return (
    <div className={styles.bar} role="tablist" aria-label="Açık oturumlar">
      <div className={styles.scroller}>
        {tabs.map((tab) => {
          const repo = sessionRepoName(tab.cwd)
          return (
            <div
              key={tab.key}
              className={tab.key === activeKey ? `${styles.tabShell} ${styles.active}` : styles.tabShell}
              onAuxClick={(event) => handleMiddleClick(event, tab.key)}
              onMouseDown={(event) => {
                if (event.button === 1) event.preventDefault()
              }}
            >
              <button
                type="button"
                role="tab"
                aria-selected={tab.key === activeKey}
                className={styles.tab}
                title={`${tab.backendLabel} · ${tab.cwd || tab.sessionId}`}
                onClick={() => onSelect(tab.key)}
                onAuxClick={(event) => handleMiddleClick(event, tab.key)}
                onMouseDown={(event) => {
                  if (event.button === 1) event.preventDefault()
                }}
              >
                <span className={styles.provider}>{tab.backendLabel.slice(0, 1).toLocaleUpperCase('tr')}</span>
                {tab.status === 'running' && <span className={styles.running} aria-label="Çalışıyor" />}
                {repo && <span className={styles.repo}>{repo}</span>}
                <span className={styles.title}>{tab.title || tab.sessionId}</span>
              </button>
              <button
                type="button"
                className={styles.close}
                aria-label={`${tab.title || tab.sessionId} sekmesini kapat`}
                onClick={() => onClose(tab.key)}
              >
                ×
              </button>
            </div>
          )
        })}
      </div>
      <button type="button" className={styles.newTab} aria-label="Yeni oturum" onClick={onNew}>
        +
      </button>
    </div>
  )
}
