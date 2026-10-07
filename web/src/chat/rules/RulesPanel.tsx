// Oturum Kuralları paneli — web karşılığı. Telefondaki Ui3Kurallar ile aynı
// sözleşme: oturuma kalıcı talimat parçaları + v2 deposunda birikmiş "her zaman"
// izin kuralları. Aç-çek: panel açılınca bir kez yüklenir, yoklamaya binmez.

import { useCallback, useEffect, useState } from 'react'
import {
  deleteInstruction,
  deleteSavedPermission,
  fetchInstructions,
  fetchSavedPermissions,
  putInstruction,
  type InstructionEntry,
  type SavedPermission,
} from './rulesApi'
import styles from './RulesPanel.module.css'

export interface RulesPanelProps {
  backend: string
  sessionId: string
  onClose: () => void
  /** Talimat değişti; isteyen yeniden yükler (telefondaki gibi zorunlu değil). */
  onChanged?: () => void
}

export function RulesPanel({ backend, sessionId, onClose, onChanged }: RulesPanelProps) {
  const [instructions, setInstructions] = useState<InstructionEntry[] | null>(null)
  const [permissions, setPermissions] = useState<SavedPermission[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [key, setKey] = useState('')
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(
    (signal?: AbortSignal) => {
      setInstructions(null)
      setPermissions(null)
      setLoadError(null)
      void fetchInstructions(backend, sessionId, signal)
        .then(setInstructions)
        .catch((err) => {
          if (signal?.aborted) return
          setLoadError(err instanceof Error ? err.message : String(err))
        })
      void fetchSavedPermissions(backend, signal)
        .then(setPermissions)
        .catch((err) => {
          if (signal?.aborted) return
          // Tek yüklenme hatası alanı var; iki istek yarışınca son gelen
          // yazar. Bölüm başına ayrı hata, bu panelde gereksiz karmaşa.
          setLoadError(err instanceof Error ? err.message : String(err))
          setPermissions([])
        })
    },
    [backend, sessionId],
  )

  useEffect(() => {
    const controller = new AbortController()
    reload(controller.signal)
    return () => controller.abort()
  }, [reload])

  const add = useCallback(async () => {
    const k = key.trim()
    const v = value.trim()
    if (!k || !v || busy) return
    setBusy(true)
    setError(null)
    try {
      await putInstruction(backend, sessionId, k, v)
      setKey('')
      setValue('')
      // Yerel listeye iyimser ekleme: v2'nin GET'i yeni talimatı bir sonraki
      // LLM adım sınırına kadar döndürmeyebilir (instruction_state ölçümü,
      // 26.09.2026). Yeniden çekmek boş liste gösterebilir — ekleme anında
      // kullanıcı eklediğini görmeli.
      setInstructions((current) =>
        current?.some((entry) => entry.key === k)
          ? current.map((entry) => (entry.key === k ? { key: k, value: v } : entry))
          : [...(current ?? []), { key: k, value: v }],
      )
      onChanged?.()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }, [backend, sessionId, key, value, busy, onChanged])

  const remove = useCallback(
    async (target: string) => {
      if (busy) return
      setBusy(true)
      setError(null)
      try {
        await deleteInstruction(backend, sessionId, target)
        setInstructions((current) => (current ?? []).filter((entry) => entry.key !== target))
        onChanged?.()
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err))
      } finally {
        setBusy(false)
      }
    },
    [backend, sessionId, busy, onChanged],
  )

  const removePermission = useCallback(
    async (id: string) => {
      if (busy) return
      setBusy(true)
      setError(null)
      try {
        await deleteSavedPermission(backend, id)
        setPermissions((current) => (current ?? []).filter((rule) => rule.id !== id))
        onChanged?.()
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err))
      } finally {
        setBusy(false)
      }
    },
    [backend, busy, onChanged],
  )

  const canAdd = key.trim().length > 0 && value.trim().length > 0 && !busy

  return (
    <section className={styles.panel} aria-label="Oturum kuralları">
      <div className={styles.header}>
        <span className={styles.title}>Oturum Kuralları</span>
        <button type="button" className={styles.close} onClick={onClose} aria-label="Kapat">
          ×
        </button>
      </div>
      <p className={styles.hint}>
        Bu oturuma kalıcı kural — ajan her turda görür. Anahtar a-z 0-9 . _ - ; izin
        kuralları silinince aynı izin bir daha sorulur.
      </p>

      <div className={styles.section}>
        <h4 className={styles.sectionTitle}>Talimatlar</h4>
        {loadError ? (
          <p className={styles.error} role="alert">
            {loadError}
          </p>
        ) : instructions === null ? (
          <p className={styles.loading}>yükleniyor…</p>
        ) : instructions.length === 0 ? (
          <p className={styles.empty}>Henüz talimat yok.</p>
        ) : (
          <ul className={styles.list}>
            {instructions.map((entry) => (
              <li key={entry.key} className={styles.item}>
                <span className={styles.itemKey}>{entry.key}</span>
                <span className={styles.itemValue}>{entry.value}</span>
                <button
                  type="button"
                  className={styles.remove}
                  disabled={busy}
                  aria-label={`${entry.key} talimatını sil`}
                  onClick={() => {
                    void remove(entry.key)
                  }}
                >
                  sil
                </button>
              </li>
            ))}
          </ul>
        )}
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault()
            void add()
          }}
        >
          <input
            className={`${styles.input} ${styles.keyInput}`}
            placeholder="anahtar (örn. uyap-bicimi)"
            value={key}
            onChange={(event) => setKey(event.target.value)}
            aria-label="Talimat anahtarı"
          />
          <input
            className={`${styles.input} ${styles.valueInput}`}
            placeholder="kural — ajan her turda görür"
            value={value}
            onChange={(event) => setValue(event.target.value)}
            aria-label="Talimat metni"
          />
          <button type="submit" className={styles.add} disabled={!canAdd}>
            Ekle
          </button>
        </form>
      </div>

      <div className={styles.section}>
        <h4 className={styles.sectionTitle}>Kayıtlı İzinler</h4>
        {permissions === null ? (
          <p className={styles.loading}>yükleniyor…</p>
        ) : permissions.length === 0 ? (
          <p className={styles.empty}>"Her zaman" verilen izin yok.</p>
        ) : (
          <ul className={styles.list}>
            {permissions.map((rule) => (
              <li key={rule.id} className={styles.item}>
                <span className={styles.itemValue}>
                  <strong>{rule.action || 'izin'}</strong> — {rule.resource || '(kaynak yok)'}
                </span>
                <button
                  type="button"
                  className={styles.remove}
                  disabled={busy}
                  aria-label="İzin kuralını kaldır"
                  onClick={() => {
                    void removePermission(rule.id)
                  }}
                >
                  kaldır
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
    </section>
  )
}
