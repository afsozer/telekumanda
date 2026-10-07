import type { StreamRow } from '../../lib/stream/protocol'
import { Markdown } from './Markdown'
import styles from './MessageRow.module.css'

interface MessageRowProps {
  row: StreamRow
  /**
   * Kullanıcı mesajından çatallama — verilmişse satır aksiyonu çizilir.
   * dropUserTurns hesabı çağırana ait (satırların tamamı onda).
   */
  onFork?: () => void
  /** Kullanıcı mesajına geri sarma (mesaj dahil sonrası atılır). */
  onRewind?: () => void
  /** Aksiyon isteği uçuştaki düğmeler kilitlenir; hata mesajı üstte gösterilir. */
  actionError?: string | null
}

/** Köprü rolü serbest metin gönderiyor; bilinen roller Türkçe etiketlenir. */
const ROLE_LABELS: Record<string, string> = {
  user: 'kullanıcı',
  agent: 'ajan',
  assistant: 'ajan',
  system: 'sistem',
  tool: 'araç',
  thought: 'düşünce',
}

/**
 * Tek sohbet satırı — baloncuk düzeni.
 *
 * Kullanıcı sağda ve dolu zeminde, ajan solda. Ajan baloncuğu genişçe: ajan
 * çıktısı kod bloğu ve tablo taşıyor, dar bir baloncuk onları okunmaz hâle
 * getiriyor.
 *
 * Düşünce satırları burada ÇİZİLMEZ: ardışık olanlar `ThoughtGroup` içinde
 * tek kutuda toplanıyor (bkz. groupRows.ts). Ayrıştırma orada yapılmasa
 * sohbet "DÜŞÜNCE göster" satırlarından ibaret hâle geliyordu.
 */
export function MessageRow({ row, onFork, onRewind, actionError }: MessageRowProps) {
  // Akış sırasında metni henüz dolmamış satırlar geliyor; boş satır çizilmez.
  if (!row.text.trim()) return null

  const isUser = row.role === 'user'
  const side = isUser ? styles.fromUser : styles.fromAgent
  const bubbleTone = isUser
    ? styles.bubbleUser
    : row.role === 'agent'
      ? styles.bubbleAgent
      : styles.bubbleOther

  // Aksiyonlar yalnız kullanıcı satırında: çatalla/geri sar bir AJAN
  // yanıtından dallanmaz — dallanma noktası hep kullanıcının sorusudur
  // (telefondaki "buradan çatalla" listesi de kullanıcı mesajlarından kurulur).
  const actions = isUser && (onFork || onRewind)

  return (
    <div className={`${styles.row} ${side}`}>
      <div className={`${styles.bubble} ${bubbleTone}`}>
        {/*
          Kullanıcı baloncuğunda rol rozeti YOK: sağda ve dolu zeminde olması
          zaten kimin yazdığını söylüyor, rozet gürültü. Ajan tarafında rol
          bilgisi rol adı bilinmediğinde işe yarıyor.
        */}
        {!isUser && (
          <span className={styles.role}>{ROLE_LABELS[row.role] ?? row.role}</span>
        )}
        <div className={styles.body}>
          <Markdown text={row.text} />
        </div>
      </div>
      {actions && (
        <div className={styles.actions}>
          {onFork && (
            <button type="button" className={styles.action} onClick={onFork}>
              Çatalla
            </button>
          )}
          {onRewind && (
            <button type="button" className={styles.action} onClick={onRewind}>
              Geri sar
            </button>
          )}
          {actionError && <span className={styles.actionError}>{actionError}</span>}
        </div>
      )}
      {row.time && <span className={styles.time}>{row.time}</span>}
    </div>
  )
}
