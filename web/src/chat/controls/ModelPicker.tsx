// Model seçici. Seçenekler akış meta.availableModels'tan props ile gelir;
// boşsa GET /claude-app/models'e düşülür. Şu anki değer props'tan beslenir —
// seçici kendi başına tutmaz, istek uçarken devre dışı kalır.

import { useEffect, useState } from 'react'
import { fetchModels, isOmpOrRunpod, isRetiredModel, setModel } from './controlsApi'
import styles from './controls.module.css'

export interface ModelPickerProps {
  backend: string
  sessionId: string
  /** Akış meta.availableModels'tan gelen liste; boşsa uçtan yüklenir. */
  models?: { id: string; label?: string }[]
  value: string
  onChanged: (model: string) => void
}

export function ModelPicker({ backend, sessionId, models, value, onChanged }: ModelPickerProps) {
  const [fetched, setFetched] = useState<{ id: string; label?: string }[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const rawOptions = models && models.length > 0 ? models : fetched
  const options = rawOptions.filter(
    (m) => !isOmpOrRunpod(m.id) && !isOmpOrRunpod(m.label || '') && !isRetiredModel(m.id),
  )
  const hasExternalModels = (models?.length ?? 0) > 0

  useEffect(() => {
    if (hasExternalModels || isOmpOrRunpod(backend)) return
    let alive = true
    setLoadError(null)
    fetchModels(backend)
      .then((data) => {
        if (alive) setFetched(data.models ?? [])
      })
      .catch((err) => {
        if (alive) setLoadError(err instanceof Error ? err.message : String(err))
      })
    return () => {
      alive = false
    }
  }, [hasExternalModels, backend])

  const change = async (next: string) => {
    if (next === value) return
    setPending(true)
    setError(null)
    try {
      const applied = await setModel(backend, sessionId, next)
      onChanged(applied)
    } catch (err) {
      // Seçici props ile denetlendiği için eski değere dönüş otomatik.
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setPending(false)
    }
  }

  return (
    <label className={styles.picker}>
      <span className={styles.label}>Model</span>
      <select
        className={styles.select}
        value={value}
        disabled={pending || options.length === 0}
        title={options.find((m) => m.id === value)?.label || value}
        onChange={(event) => {
          void change(event.target.value)
        }}
      >
        {options.length === 0 ? (
          <option value="">yükleniyor…</option>
        ) : (
          options.map((model) => (
            <option key={model.id} value={model.id}>
              {model.label || model.id}
            </option>
          ))
        )}
      </select>
      {(error || loadError) && <span className={styles.error}>{error || loadError}</span>}
    </label>
  )
}
