import { useCallback, useEffect, useRef, useState } from 'react'
import { uploadAttachment, withAttachments, type Attachment } from './attachments'
import { sendPrompt } from './promptApi'
import { steerPrompt } from './steerApi'
import { fetchSlashCommands, matchCommands, slashPrefix, type SlashCommand } from './slashApi'
import styles from './Composer.module.css'

export interface ComposerProps {
  sessionId: string
  backend?: string
  model?: string
  permissionMode?: string
  /** Ajan çalışıyor. Gönderme engellenmez — köprü sıraya alır — ama belirtilir. */
  running?: boolean
  /**
   * Süren tura enjeksiyon (POST /<b>/steer) bu backend'de var mı
   * (capability userInputSteer). Varsa ajan çalışırken gönder düğmesi
   * "Yönlendir" olur ve metin sıraya DEĞİL, anında o tura gider.
   */
  canSteer?: boolean
  /** Oturumun kendi komutları (akıştan, meta.commands). Doluysa uca sorulmaz. */
  commands?: SlashCommand[]
  /**
   * Ajanın son mesajından ayrıştırılan seçenekler (akıştan, meta.choices).
   * Köprü bunları metinden çıkarıyor (session-utils.extractChoices), yani
   * şıklar zaten mesajda yazılı — bu yalnızca yazma zahmetini kaldırıyor.
   */
  choices?: string[]
  disabled?: boolean
  onSent?: () => void
}

const MAX_ROWS = 12

export function Composer({
  sessionId,
  backend = 'claude-app',
  model,
  permissionMode,
  running = false,
  canSteer = false,
  disabled = false,
  commands,
  choices,
  onSent,
}: ComposerProps) {
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [attachments, setAttachments] = useState<Attachment[]>([])
  const [uploading, setUploading] = useState(0)
  const [dragging, setDragging] = useState(false)
  const [fetched, setFetched] = useState<SlashCommand[]>([])
  const [highlight, setHighlight] = useState(0)
  const [slashDismissed, setSlashDismissed] = useState(false)
  const areaRef = useRef<HTMLTextAreaElement | null>(null)
  const fileRef = useRef<HTMLInputElement | null>(null)

  const commandCount = commands?.length ?? 0
  useEffect(() => {
    if (commandCount > 0) return
    const controller = new AbortController()
    void fetchSlashCommands(backend, controller.signal).then(setFetched)
    return () => controller.abort()
  }, [backend, commandCount])

  const allCommands = commandCount > 0 ? commands! : fetched
  const prefix = slashPrefix(text)
  const matches = prefix === null || slashDismissed ? [] : matchCommands(allCommands, prefix)
  const menuOpen = matches.length > 0

  const complete = useCallback((command: SlashCommand) => {
    // Boşlukla tamamlanır: argümanı olan komutlarda ("/devret <iş>") kullanıcı
    // doğrudan yazmaya devam etsin.
    setText(`/${command.name} `)
    setSlashDismissed(true)
    areaRef.current?.focus()
  }, [])

  // Yüksekliği içeriğe uydur, MAX_ROWS'ta durdur.
  useEffect(() => {
    const el = areaRef.current
    if (!el) return
    el.style.height = 'auto'
    const lineHeight = parseFloat(getComputedStyle(el).lineHeight) || 20
    el.style.height = `${Math.min(el.scrollHeight, lineHeight * MAX_ROWS)}px`
  }, [text])

  const addFiles = useCallback(async (files: File[]) => {
    if (files.length === 0) return
    setError(null)
    setUploading((n) => n + files.length)
    for (const file of files) {
      try {
        const attachment = await uploadAttachment(file)
        setAttachments((current) => [...current, attachment])
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err))
      } finally {
        setUploading((n) => n - 1)
      }
    }
  }, [])

  const submit = useCallback(async () => {
    const value = text.trim()
    // Yalnız ek gönderilebilsin: "şu ekran görüntüsüne bak" derken metin
    // yazmadan yollamak yaygın.
    if ((!value && attachments.length === 0) || sending || disabled || !sessionId) return

    // STEER: ajan çalışırken, ek yoksa ve uç varsa metin sıraya değil süren
    // tura gider. Ek varsa steer edilemez — normal gönderim (köprü sıraya
    // alır) korunur; ekleri sessizce yutmak kullanıcıyı yanıltırdı.
    const steering = running && canSteer && attachments.length === 0

    const payload = withAttachments(value, attachments)
    const keptAttachments = attachments
    setSending(true)
    setError(null)
    // İyimser temizlik: kullanıcı beklemeden yazmaya devam edebilsin.
    setText('')
    setAttachments([])
    try {
      if (steering) {
        await steerPrompt(backend, sessionId, value)
      } else {
        await sendPrompt({ sessionId, text: payload, model, permissionMode }, { backend })
      }
      onSent?.()
    } catch (err) {
      setText((current) => (current ? current : value))
      setAttachments((current) => (current.length ? current : keptAttachments))
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSending(false)
    }
  }, [
    text,
    attachments,
    sending,
    disabled,
    sessionId,
    model,
    permissionMode,
    backend,
    onSent,
    running,
    canSteer,
  ])

  const busy = sending || uploading > 0
  const canSend = (text.trim().length > 0 || attachments.length > 0) && !busy && !disabled

  return (
    <div
      className={`${styles.wrap} ${dragging ? styles.dropping : ''}`}
      onDragOver={(event) => {
        if (disabled) return
        event.preventDefault()
        setDragging(true)
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(event) => {
        if (disabled) return
        event.preventDefault()
        setDragging(false)
        void addFiles([...event.dataTransfer.files])
      }}
    >
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}

      {menuOpen && (
        <ul className={styles.slashMenu} role="listbox" aria-label="Komutlar">
          {matches.map((command, index) => (
            <li key={command.name}>
              <button
                type="button"
                role="option"
                aria-selected={index === Math.min(highlight, matches.length - 1)}
                className={
                  index === Math.min(highlight, matches.length - 1)
                    ? `${styles.slashItem} ${styles.slashActive}`
                    : styles.slashItem
                }
                onMouseEnter={() => setHighlight(index)}
                onClick={() => complete(command)}
              >
                <span className={styles.slashName}>/{command.name}</span>
                {command.desc && <span className={styles.slashDesc}>{command.desc}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}

      {!menuOpen && choices && choices.length > 0 && (
        <ul className={styles.choices}>
          {choices.map((choice) => (
            <li key={choice}>
              <button
                type="button"
                className={styles.choice}
                disabled={disabled}
                // GÖNDERMEZ, kutuya yazar: şıkka bir şey eklemek isteyebilirsin
                // ("2 ama şunu da yap") ve kazara tıklama tur başlatmasın.
                onClick={() => {
                  setText(choice)
                  areaRef.current?.focus()
                }}
              >
                {choice}
              </button>
            </li>
          ))}
        </ul>
      )}

      {(attachments.length > 0 || uploading > 0) && (
        <ul className={styles.chips}>
          {attachments.map((attachment) => (
            <li key={attachment.path} className={styles.chip}>
              <span className={styles.chipName} title={attachment.path}>
                {attachment.isImage ? '🖼' : '📄'} {attachment.name}
              </span>
              <button
                type="button"
                className={styles.chipRemove}
                aria-label={`${attachment.name} ekini kaldır`}
                onClick={() =>
                  setAttachments((current) => current.filter((a) => a.path !== attachment.path))
                }
              >
                ×
              </button>
            </li>
          ))}
          {uploading > 0 && <li className={styles.chipPending}>{uploading} dosya yükleniyor…</li>}
        </ul>
      )}

      <form
        className={styles.form}
        onSubmit={(event) => {
          event.preventDefault()
          void submit()
        }}
      >
        <input
          ref={fileRef}
          type="file"
          multiple
          className={styles.hiddenFile}
          aria-label="Dosya seç"
          onChange={(event) => {
            void addFiles([...(event.target.files ?? [])])
            event.target.value = ''
          }}
        />
        <button
          type="button"
          className={styles.attach}
          disabled={disabled}
          title="Dosya ekle (sürükleyip bırakabilir ya da yapıştırabilirsin)"
          aria-label="Dosya ekle"
          onClick={() => fileRef.current?.click()}
        >
          +
        </button>
        <textarea
          ref={areaRef}
          className={styles.area}
          rows={1}
          value={text}
          disabled={disabled}
          aria-label="Mesaj"
          placeholder={
            running
              ? canSteer
                ? 'Ajanı yönlendir — mesaj süren tura anında gider…'
                : 'Ajan çalışıyor — sıraya eklenir'
              : 'Mesaj yaz…'
          }
          onChange={(event) => {
            setText(event.target.value)
            setHighlight(0)
            // Yeniden `/` yazılınca menü tekrar açılabilsin.
            if (slashPrefix(event.target.value) === null) setSlashDismissed(false)
          }}
          onPaste={(event) => {
            // Panodaki ekran görüntüsü doğrudan ek olur. Bu, ekran görüntüsü
            // akışının web'deki karşılığı — dosyaya kaydedip seçmeye gerek yok.
            const files = [...event.clipboardData.files]
            if (files.length) {
              event.preventDefault()
              void addFiles(files)
            }
          }}
          onKeyDown={(event) => {
            // Komut menüsü açıkken Enter GÖNDERMEZ, seçer. Aksi hâlde yarım
            // yazılmış bir komut ajana ham metin olarak giderdi.
            if (menuOpen && !event.nativeEvent.isComposing) {
              if (event.key === 'ArrowDown') {
                event.preventDefault()
                setHighlight((i) => (i + 1) % matches.length)
                return
              }
              if (event.key === 'ArrowUp') {
                event.preventDefault()
                setHighlight((i) => (i - 1 + matches.length) % matches.length)
                return
              }
              if (event.key === 'Enter' || event.key === 'Tab') {
                event.preventDefault()
                complete(matches[Math.min(highlight, matches.length - 1)])
                return
              }
              if (event.key === 'Escape') {
                event.preventDefault()
                setSlashDismissed(true)
                return
              }
            }
            // Enter gönderir, Shift+Enter satır atlar. IME ile yazarken
            // (Türkçe düzen dahil) Enter kelime onaylar — o sırada gönderme.
            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault()
              void submit()
            }
          }}
        />
        <button type="submit" className={styles.send} disabled={!canSend}>
          {sending ? 'Gönderiliyor…' : running && canSteer ? 'Yönlendir' : 'Gönder'}
        </button>
      </form>
    </div>
  )
}
