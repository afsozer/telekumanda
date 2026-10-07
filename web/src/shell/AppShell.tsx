// Üst düzey gezinme: Sohbet / Ara / Projeler / Görsel / Notlar / Kullanım.
//
// Şerit DİKEY, yatay değil: ekran 1920px geniş ve yatay yer bol, dikey yer ise
// sohbetin okuma alanı — üst çubuğu alta indirmenin sebebi de oydu. 3,5rem'lik
// bir rayın maliyeti yok.

import { useEffect, useState } from 'react'
import { ChatScreen } from '../chat/ChatScreen'
import type { ChatNavigationTarget } from '../navigation'
import { NotesScreen } from '../notes'
import { ProjectsScreen } from '../projects'
import { GlobalSearchScreen } from '../search'
import { UsageScreen } from '../usage'
import styles from './AppShell.module.css'

const STORAGE_KEY = 'agentbridge.section'

export type Section = 'sohbet' | 'ara' | 'projeler' | 'notlar' | 'kullanim'

const SECTIONS: { id: Section; label: string; glyph: string }[] = [
  { id: 'sohbet', label: 'Sohbet', glyph: '💬' },
  { id: 'ara', label: 'Ara', glyph: '⌕' },
  { id: 'projeler', label: 'Projeler', glyph: '📁' },
  { id: 'notlar', label: 'Notlar', glyph: '📝' },
  { id: 'kullanim', label: 'Kullanım', glyph: '📊' },
]

function initialSection(): Section {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'notlar' || saved === 'sohbet' || saved === 'kullanim'
      || saved === 'ara' || saved === 'projeler')
      return saved
  } catch {
    /* depolama kapalıysa varsayılanla başla */
  }
  return 'sohbet'
}

export function AppShell() {
  const [section, setSection] = useState<Section>(initialSection)
  const [chatTarget, setChatTarget] = useState<ChatNavigationTarget | null>(null)
  const [projectTarget, setProjectTarget] = useState<string | null>(null)
  const [notesProject, setNotesProject] = useState<string | null>(null)

  const openChat = (target: ChatNavigationTarget) => {
    setChatTarget(target)
    setSection('sohbet')
  }

  const openProject = (projectId: string) => {
    setProjectTarget(projectId)
    setSection('projeler')
  }

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, section)
    } catch {
      /* seçim yalnız bu oturumda yaşar */
    }
  }, [section])

  return (
    <div className={styles.shell}>
      <nav className={styles.rail} aria-label="Bölümler">
        {SECTIONS.map((item) => (
          <button
            key={item.id}
            type="button"
            aria-current={section === item.id ? 'page' : undefined}
            className={section === item.id ? `${styles.tab} ${styles.tabOn}` : styles.tab}
            onClick={() => setSection(item.id)}
          >
            <span className={styles.glyph} aria-hidden="true">
              {item.glyph}
            </span>
            {item.label}
          </button>
        ))}
      </nav>

      {/*
        Ekranlar KOŞULLU çiziliyor, gizlenmiyor: sohbet ekranı bir WebSocket
        tutuyor ve arka planda açık bırakmak, bakılmayan bir oturum için akış
        beslemek demekti.
      */}
      <div className={styles.body}>
        {section === 'sohbet' && (
          <ChatScreen
            navigationTarget={chatTarget}
            onNavigationHandled={(requestKey) => {
              setChatTarget((current) => current?.requestKey === requestKey ? null : current)
            }}
          />
        )}
        {section === 'ara' && <GlobalSearchScreen onOpenProject={openProject} onOpenChat={openChat} />}
        {section === 'projeler' && (
          <ProjectsScreen
            initialProjectId={projectTarget}
            onInitialProjectHandled={(projectId) => {
              setProjectTarget((current) => current === projectId ? null : current)
            }}
            onOpenChat={openChat}
            onOpenNotes={(path) => {
              setNotesProject(path)
              setSection('notlar')
            }}
          />
        )}
        {section === 'notlar' && (
          <NotesScreen projectPath={notesProject} onClearProject={() => setNotesProject(null)} />
        )}
        {section === 'kullanim' && <UsageScreen />}
      </div>
    </div>
  )
}
