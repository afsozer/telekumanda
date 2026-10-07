import { useEffect, useRef, useState } from 'react'
import styles from './CodeBlock.module.css'

interface CodeBlockProps {
  code: string
  language?: string
}

/** Pano API'si yalnız güvenli bağlamda var; yoksa kopyala düğmesi çizilmez. */
function clipboardUsable(): boolean {
  return (
    typeof navigator !== 'undefined' &&
    typeof navigator.clipboard !== 'undefined' &&
    typeof navigator.clipboard.writeText === 'function'
  )
}

/** Çitli kod bloğu: dil etiketi, kopyala düğmesi ve yatay kaydırmalı gövde. */
export function CodeBlock({ code, language }: CodeBlockProps) {
  const [copied, setCopied] = useState(false)
  const [canCopy] = useState(clipboardUsable)
  const resetTimer = useRef<number | null>(null)

  useEffect(() => {
    return () => {
      if (resetTimer.current !== null) window.clearTimeout(resetTimer.current)
    }
  }, [])

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      if (resetTimer.current !== null) window.clearTimeout(resetTimer.current)
      resetTimer.current = window.setTimeout(() => setCopied(false), 2000)
    } catch {
      // Pano reddedilirse (izin, kilitli sekme) sessiz kal — sohbet bozulmaz.
    }
  }

  return (
    <div className={styles.block}>
      <div className={styles.header}>
        <span className={styles.language}>{language || 'kod'}</span>
        {canCopy && (
          <button
            type="button"
            className={copied ? `${styles.copy} ${styles.copied}` : styles.copy}
            onClick={() => void handleCopy()}
          >
            {copied ? 'Kopyalandı' : 'Kopyala'}
          </button>
        )}
      </div>
      {/* Uzun satırlar sarılmaz, yatay kaydırılır — sohbet düzeni taşmaz. */}
      <pre className={styles.code}>{code}</pre>
    </div>
  )
}
