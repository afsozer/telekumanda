// Onay kutusu: ajan bir araç için onay istediğinde (akış meta.approval) çıkar.
// Girdi tamamen props ile gelir — akışa kendisi bağlanmaz. Sorular cevaplanmadan
// "Cevapları gönder" açılmaz; istek uçarken düğmeler devre dışı kalır ve bir ref
// guard aynı anda ikinci isteği engeller (çift gönderim koruması).

import { useRef, useState } from 'react'
import { approve, type ApprovalAnswer, type ApprovalInfo, type ApprovalOption, type ApprovalQuestion } from './controlsApi'
import styles from './controls.module.css'

export interface ApprovalPromptProps {
  backend: string
  sessionId: string
  approval: ApprovalInfo
  /** Onay/red POST'u başarılı olunca çağrılır (isteğe bağlı). */
  onDecision?: () => void
}

/** Soru id -> cevap listesi. Tek seçimlide liste tek eleman; çok seçimlide çok. */
type Draft = Record<string, ApprovalAnswer[]>

export function ApprovalPrompt({ backend, sessionId, approval, onDecision }: ApprovalPromptProps) {
  const [draft, setDraft] = useState<Draft>({})
  const [customText, setCustomText] = useState<Record<string, string>>({})
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const busy = useRef(false)

  const questions = approval.questions ?? []
  const hasQuestions = questions.length > 0

  const answered = (question: ApprovalQuestion): boolean => {
    if (question.options && question.options.length > 0) return (draft[question.id]?.length ?? 0) > 0
    return (customText[question.id] ?? '').trim().length > 0
  }
  const complete = hasQuestions && questions.every(answered)

  // QuestionAnswerDraft.select ile aynı davranış: tek seçimlide yeni seçim
  // öncekinin yerine geçer; çok seçimlide aynı seçeneğe ikinci dokunuş çıkarır.
  const toggle = (question: ApprovalQuestion, option: ApprovalOption) => {
    setDraft((prev) => {
      const current = prev[question.id] ?? []
      if (question.multiple) {
        const exists = current.some((answer) => answer.optionId === option.id)
        const next = exists
          ? current.filter((answer) => answer.optionId !== option.id)
          : [...current, { id: question.id, optionId: option.id, label: option.label }]
        return next.length ? { ...prev, [question.id]: next } : { ...prev, [question.id]: [] }
      }
      return { ...prev, [question.id]: [{ id: question.id, optionId: option.id, label: option.label }] }
    })
  }

  const buildAnswers = (): ApprovalAnswer[] => {
    const out: ApprovalAnswer[] = []
    for (const question of questions) {
      if (question.options && question.options.length > 0) {
        out.push(...(draft[question.id] ?? []))
      } else {
        const text = (customText[question.id] ?? '').trim()
        if (text) out.push({ id: question.id, optionId: text, label: text })
      }
    }
    return out
  }

  const send = async (allow: boolean) => {
    if (busy.current) return
    busy.current = true
    setPending(true)
    setError(null)
    try {
      // Reddet cevapsız gider (Kotlin claudeAppApprove ile aynı).
      await approve(backend, sessionId, allow, allow ? buildAnswers() : undefined, approval.requestId)
      setDraft({})
      setCustomText({})
      onDecision?.()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      busy.current = false
      setPending(false)
    }
  }

  const title = approval.summary || approval.tool || 'İzin isteği'

  return (
    <section className={styles.approval}>
      <h3 className={styles.approvalTitle}>{title}</h3>
      {approval.description && <p className={styles.approvalDesc}>{approval.description}</p>}
      {approval.options && approval.options.length > 0 && (
        <ul className={styles.approvalOptions}>
          {approval.options.map((option) => (
            <li key={option.id}>{option.label}</li>
          ))}
        </ul>
      )}

      {hasQuestions && (
        <div className={styles.questions}>
          {questions.map((question) => (
            <fieldset key={question.id} className={styles.question}>
              <legend className={styles.questionTitle}>
                {question.question || question.header || question.id}
              </legend>
              {question.options && question.options.length > 0 ? (
                question.options.map((option) => {
                  const checked = (draft[question.id] ?? []).some((answer) => answer.optionId === option.id)
                  return (
                    <label key={option.id} className={styles.option}>
                      <input
                        type={question.multiple ? 'checkbox' : 'radio'}
                        name={question.id}
                        value={option.id}
                        checked={checked}
                        disabled={pending}
                        onChange={() => toggle(question, option)}
                      />
                      <span>{option.label}</span>
                      {option.description && <span className={styles.muted}> — {option.description}</span>}
                    </label>
                  )
                })
              ) : (
                <input
                  className={styles.customInput}
                  value={customText[question.id] ?? ''}
                  disabled={pending}
                  placeholder="Cevabınız"
                  onChange={(event) =>
                    setCustomText((prev) => ({ ...prev, [question.id]: event.target.value }))
                  }
                />
              )}
            </fieldset>
          ))}
        </div>
      )}

      {error && <p className={styles.error}>{error}</p>}

      <div className={styles.actions}>
        <button
          className={styles.primaryBtn}
          disabled={pending || (hasQuestions && !complete)}
          onClick={() => {
            void send(true)
          }}
        >
          {hasQuestions ? 'Cevapları gönder' : 'İzin ver'}
        </button>
        <button
          className={styles.ghostBtn}
          disabled={pending}
          onClick={() => {
            void send(false)
          }}
        >
          Reddet
        </button>
      </div>
    </section>
  )
}
