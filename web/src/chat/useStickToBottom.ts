import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'

/**
 * Dibe yakın mıyız?
 *
 * Eşik sıfır olamaz: akış sırasında yükseklik sürekli değişiyor ve tarayıcının
 * kesirli piksel yuvarlamaları "tam dipte" durumunu birkaç piksel kaçırıyor.
 * Saf fonksiyon — kaydırma davranışının tek karar noktası burası.
 */
export function isNearBottom(
  metrics: { scrollTop: number; scrollHeight: number; clientHeight: number },
  threshold = 80,
): boolean {
  const { scrollTop, scrollHeight, clientHeight } = metrics
  return scrollHeight - scrollTop - clientHeight <= threshold
}

export interface StickToBottom<E extends HTMLElement> {
  ref: React.RefObject<E | null>
  /** Kullanıcı dipte; yeni satır gelince otomatik kaydırılacak. */
  atBottom: boolean
  /** Dibe in (yeni mesaj düğmesi). */
  scrollToBottom: (behavior?: ScrollBehavior) => void
  onScroll: () => void
}

/**
 * Akış sürerken dipte kalır, kullanıcı yukarı kaydırdıysa DOKUNMAZ.
 *
 * `dep` her değiştiğinde (satır sayısı, son satırın uzunluğu vb.) yalnız
 * kullanıcı dipteyken kaydırır. Yukarı çıkmış birini zorla aşağı çekmek
 * sohbeti okunamaz hâle getiriyor.
 */
export function useStickToBottom<E extends HTMLElement>(dep: unknown): StickToBottom<E> {
  const ref = useRef<E | null>(null)
  const [atBottom, setAtBottom] = useState(true)
  // Kaydırma kararı efekt içinde okunuyor; state bir tur geç geldiği için
  // ref'te de tutuluyor.
  const atBottomRef = useRef(true)

  const onScroll = useCallback(() => {
    const el = ref.current
    if (!el) return
    const near = isNearBottom(el)
    atBottomRef.current = near
    setAtBottom((current) => (current === near ? current : near))
  }, [])

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    const el = ref.current
    if (!el) return
    el.scrollTo({ top: el.scrollHeight, behavior })
    atBottomRef.current = true
    setAtBottom(true)
  }, [])

  // Boyama ÖNCESİ kaydır: useEffect ile bir kare zıplama görünüyordu.
  useLayoutEffect(() => {
    const el = ref.current
    if (!el || !atBottomRef.current) return
    el.scrollTop = el.scrollHeight
  }, [dep])

  useEffect(() => {
    const el = ref.current
    if (!el) return
    el.scrollTop = el.scrollHeight
    atBottomRef.current = true
  }, [])

  return { ref, atBottom, scrollToBottom, onScroll }
}
