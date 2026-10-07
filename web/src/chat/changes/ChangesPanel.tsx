// Değişiklikler paneli — oturumun toplam dosya diff'i. Telefondaki
// "Değişiklikler" görünümünün web karşılığı; v1/v2 şeması aynı.
// Aç-çek: açılınca bir kez yüklenir. Dosya satırına tıklanınca patch açılır.

import { useEffect, useState } from 'react'
import { fetchSessionDiff, type DiffEntry, type SessionDiff } from '../diffApi'
import styles from './ChangesPanel.module.css'

export interface ChangesPanelProps {
  backend: string
  sessionId: string
  onClose: () => void
}

/** Patch'i satır türe göre boyar; unified diff sözdizimi. */
function PatchBody({ patch }: { patch: string }) {
  const lines = patch.split('\n')
  return (
    <pre className={styles.patch}>
      {lines.map((line, i) => {
        if (line.startsWith('+') && !line.startsWith('+++'))
          return (
            <span key={i} className={styles.lineAdd}>
              {line || ' '}
            </span>
          )
        if (line.startsWith('-') && !line.startsWith('---'))
          return (
            <span key={i} className={styles.lineDel}>
              {line || ' '}
            </span>
          )
        if (line.startsWith('@@'))
          return (
            <span key={i} className={styles.lineHunk}>
              {line}
            </span>
          )
        return (
          <span key={i}>
            {line || ' '}
          </span>
        )
      })}
    </pre>
  )
}

function FileItem({ file }: { file: DiffEntry }) {
  const [open, setOpen] = useState(false)
  return (
    <li className={styles.item}>
      <button
        type="button"
        className={styles.fileHeader}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={styles.status}>{file.status || 'değişiklik'}</span>
        <span className={styles.filePath} title={file.path}>
          {file.path}
        </span>
        <span className={styles.adds}>+{file.additions}</span>
        <span className={styles.dels}>−{file.deletions}</span>
      </button>
      {open && (file.patch || file.truncated ? (
        <PatchBody patch={file.patch} />
      ) : (
        <span className={styles.truncated}>patch yok</span>
      ))}
      {open && file.truncated && (
        <span className={styles.truncated}>patch çok uzun — köprü kırptı</span>
      )}
    </li>
  )
}

export function ChangesPanel({ backend, sessionId, onClose }: ChangesPanelProps) {
  const [diff, setDiff] = useState<SessionDiff | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setDiff(null)
    setError(null)
    fetchSessionDiff(backend, sessionId, controller.signal)
      .then(setDiff)
      .catch((err) => {
        if (controller.signal.aborted) return
        setError(err instanceof Error ? err.message : String(err))
      })
    return () => controller.abort()
  }, [backend, sessionId])

  return (
    <section className={styles.panel} aria-label="Oturum değişiklikleri">
      <div className={styles.header}>
        <span className={styles.title}>
          Değişiklikler
          {diff ? (
            <span className={styles.hint} style={{ display: 'inline' }}>
              {' '}
              +{diff.additions} −{diff.deletions}
            </span>
          ) : null}
        </span>
        <button type="button" className={styles.close} onClick={onClose} aria-label="Kapat">
          ×
        </button>
      </div>
      <p className={styles.hint}>
        Bu oturumun dokunduğu dosyalar — satıra tıklayınca patch açılır.
        {diff?.cwd ? ` Kök: ${diff.cwd}` : ''}
      </p>

      {error ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : diff === null ? (
        <p className={styles.loading}>yükleniyor…</p>
      ) : diff.files.length === 0 ? (
        <p className={styles.empty}>Bu oturum henüz dosya değiştirmedi.</p>
      ) : (
        <ul className={styles.list}>
          {diff.files.map((file) => (
            <FileItem key={file.path} file={file} />
          ))}
        </ul>
      )}
      {diff?.truncated && (
        <p className={styles.hint}>Dosya sayısı köprü tarafından sınırlandı.</p>
      )}
    </section>
  )
}
