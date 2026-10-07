import { useState } from 'react'
import type { StreamRow } from '../../lib/stream/protocol'
import { Markdown } from './Markdown'
import styles from './MessageRow.module.css'

export interface ThoughtGroupProps {
  rows: StreamRow[]
}

/**
 * Ardışık düşünce satırlarının tek katlanabilir kutusu. Varsayılan kapalı;
 * başlıkta kaç düşünce olduğu yazıyor ki açmadan da hacmi görülsün.
 */
export function ThoughtGroup({ rows }: ThoughtGroupProps) {
  const [open, setOpen] = useState(false)
  const visible = rows.filter((row) => row.text.trim())
  if (visible.length === 0) return null

  return (
    <div className={styles.thought}>
      <button
        type="button"
        className={styles.thoughtToggle}
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span className={styles.thoughtLabel}>
          {visible.length > 1 ? `${visible.length} düşünce` : 'düşünce'}
        </span>
        <span className={styles.thoughtHint}>{open ? 'gizle' : 'göster'}</span>
      </button>
      {open && (
        <div className={styles.thoughtBody}>
          {visible.map((row) => (
            <div key={row.rowId} className={styles.thoughtItem}>
              <Markdown text={row.text} />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
