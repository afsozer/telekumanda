// Çaba (reasoning effort) seçici. Seçenekler GET /claude-app/efforts'tan gelir;
// değer akış meta.effort'tan props ile beslenir. Çaba değişikliği aktif turda
// değil, bir sonraki turda (respawn) geçerli olur — altındaki not bunu söyler.

import { useEffect, useState } from 'react'
import { fetchEfforts, setEffort } from './controlsApi'
import styles from './controls.module.css'

export interface EffortPickerProps {
  backend: string
  sessionId: string
  value: string
  onChanged: (effort: string) => void
}

export function EffortPicker({ backend, sessionId, value, onChanged }: EffortPickerProps) {
  const [efforts, setEfforts] = useState<string[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    setLoadError(null)
    fetchEfforts(backend)
      .then((list) => {
        if (alive) setEfforts(list)
      })
      .catch((err) => {
        if (alive) setLoadError(err instanceof Error ? err.message : String(err))
      })
    return () => {
      alive = false
    }
    // backend bağımlılıkta: her backend'in çaba seviyeleri farklı
    // (claude-app sabit liste, codex-app diskten türetiyor).
  }, [backend])

  const change = async (next: string) => {
    if (next === value) return
    setPending(true)
    setError(null)
    try {
      const applied = await setEffort(backend, sessionId, next)
      onChanged(applied)
    } catch (err) {
      // Seçici props ile denetlendiği için eski değere dönüş otomatik.
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setPending(false)
    }
  }

  return (
    <div className={styles.pickerColumn}>
      {/*
        Açıklama eskiden çubukta ayrı bir satırdı ve üst çubuğu şişiriyordu;
        artık seçicinin ipucunda (title) duruyor.
      */}
      <label
        className={styles.picker}
        title="Çaba değişikliği aktif turda değil, bir sonraki turda geçerli olur."
      >
        <span className={styles.label}>Çaba</span>
        <select
          className={styles.select}
          value={value}
          disabled={pending || efforts.length === 0}
          onChange={(event) => {
            void change(event.target.value)
          }}
        >
          {efforts.length === 0 ? (
            <option value="">yükleniyor…</option>
          ) : (
            <>
              {/*
                codex-app: boş değer "default" — turn/start'a effort gitmez,
                config.toml'daki model_reasoning_effort uygulanır. Yeni oturum
                03.09.2026'dan beri bununla açılıyor; seçilebilir de olmalı.
              */}
              {backend === 'codex-app' && <option value="">default</option>}
              {!efforts.includes(value) && !(backend === 'codex-app' && value === '') && (
                <option value={value}>{value || '—'}</option>
              )}
              {efforts.map((effort) => (
                <option key={effort} value={effort}>
                  {effort}
                </option>
              ))}
            </>
          )}
        </select>
        {(error || loadError) && <span className={styles.error}>{error || loadError}</span>}
      </label>
    </div>
  )
}
