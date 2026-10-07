import { useState } from 'react'
import type { PlanItem } from '../lib/stream/protocol'
import styles from './PlanPanel.module.css'

export interface PlanPanelProps {
  plan?: PlanItem[]
  /** Henüz maddelere ayrılmamış serbest metin plan (meta.planDraft). */
  draft?: string
}

/**
 * Tur planı. Yalnızca `plan` yeteneği olan backend'lerde çizilir (şu an
 * codex-app). Katlanabilir ve varsayılan AÇIK: plan kısa ve o an ne
 * yapıldığını söylüyor, kapalı olsa gözden kaçardı.
 */
const STATUS_LABEL: Record<string, string> = {
  pending: 'bekliyor',
  in_progress: 'sürüyor',
  completed: 'bitti',
  cancelled: 'iptal',
}

function statusClass(status: string): string {
  if (status === 'completed') return styles.done
  if (status === 'in_progress') return styles.active
  if (status === 'cancelled') return styles.cancelled
  return styles.pending
}

export function PlanPanel({ plan, draft }: PlanPanelProps) {
  const [open, setOpen] = useState(true)
  const items = plan ?? []
  if (items.length === 0 && !draft?.trim()) return null

  const done = items.filter((item) => item.status === 'completed').length

  return (
    <section className={styles.wrap}>
      <button
        type="button"
        className={styles.head}
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span className={styles.title}>Plan</span>
        {items.length > 0 && (
          <span className={styles.count}>
            {done}/{items.length}
          </span>
        )}
        <span className={styles.hint}>{open ? 'gizle' : 'göster'}</span>
      </button>

      {open && (
        <>
          {items.length > 0 && (
            <ol className={styles.list}>
              {items.map((item, index) => (
                <li key={item.itemId || `${index}:${item.text}`} className={statusClass(item.status)}>
                  <span className={styles.status}>{STATUS_LABEL[item.status] ?? item.status}</span>
                  <span className={styles.text}>{item.text}</span>
                </li>
              ))}
            </ol>
          )}
          {draft?.trim() && <p className={styles.draft}>{draft}</p>}
        </>
      )}
    </section>
  )
}
