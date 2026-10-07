import { useState } from 'react'

interface Props {
  reason?: string
  onSubmit: (token: string) => void
}

/**
 * Token giriş ekranı. Normalde görünmez: köprü arayüzü `/ui/?token=...`
 * adresiyle açtırır ve token yerel depoya alınır. Bu ekran token'ın hiç
 * verilmediği ya da köprü tarafından reddedildiği durum içindir.
 */
export function TokenGate({ reason, onSubmit }: Props) {
  const [value, setValue] = useState('')

  return (
    <main className="shell">
      <h1>Telekumanda</h1>
      <p className="muted">
        Köprü token’ı gerekiyor. Normalde adres <code>/ui/?token=…</code> biçiminde açılır;
        token bir kez alınıp adresten temizlenir.
      </p>
      {reason && <p className="error">{reason}</p>}
      <form
        onSubmit={(event) => {
          event.preventDefault()
          const trimmed = value.trim()
          if (trimmed) onSubmit(trimmed)
        }}
      >
        <input
          type="password"
          autoComplete="off"
          spellCheck={false}
          aria-label="Köprü token’ı"
          placeholder="token"
          value={value}
          onChange={(event) => setValue(event.target.value)}
        />
        <button type="submit" disabled={!value.trim()}>
          Bağlan
        </button>
      </form>
    </main>
  )
}
