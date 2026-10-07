// İzin kipi seçici. Seçenekler akış meta.permissionModes'tan props ile gelir;
// boşsa varsayılan CLI mod listesine düşülür (BackendOptions.kt ile aynı).
// Değer akış meta.permissionMode'tan props ile beslenir.

import { useState } from 'react'
import { setPermissionMode } from './controlsApi'
import { DEFAULT_PERMISSION_MODES, permissionModeLabel } from './permissionModes'
import styles from './controls.module.css'

export interface PermissionModePickerProps {
  backend: string
  sessionId: string
  /** Akış meta.permissionModes'tan gelen liste; boşsa varsayılanlar kullanılır. */
  modes?: string[]
  value: string
  onChanged: (mode: string) => void
}

export function PermissionModePicker({ backend, sessionId, modes, value, onChanged }: PermissionModePickerProps) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const options = modes && modes.length > 0 ? modes : DEFAULT_PERMISSION_MODES

  const change = async (next: string) => {
    if (next === value) return
    setPending(true)
    setError(null)
    try {
      const applied = await setPermissionMode(backend, sessionId, next)
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
      <span className={styles.label}>İzin kipi</span>
      <select
        className={styles.select}
        value={value}
        disabled={pending}
        title={permissionModeLabel(value)}
        onChange={(event) => {
          void change(event.target.value)
        }}
      >
        {options.map((mode) => (
          <option key={mode} value={mode}>
            {permissionModeLabel(mode)}
          </option>
        ))}
      </select>
      {error && <span className={styles.error}>{error}</span>}
    </label>
  )
}
