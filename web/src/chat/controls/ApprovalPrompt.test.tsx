// @vitest-environment happy-dom
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApprovalPrompt } from './ApprovalPrompt'
import type { ApprovalInfo } from './controlsApi'

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } })
}

function stubFetch(handler: (url: string, init?: RequestInit) => Response) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

const bodyOf = (init?: RequestInit): Record<string, unknown> => JSON.parse(String(init?.body))

afterEach(() => vi.unstubAllGlobals())

const toolApproval: ApprovalInfo = {
  requestId: 'r1',
  kind: 'tool',
  tool: 'Bash',
  summary: 'Bash çalıştır',
  description: 'ls -la',
}

const questionApproval: ApprovalInfo = {
  requestId: 'r2',
  questions: [
    {
      id: 'q1',
      question: 'Devam edilsin mi?',
      options: [
        { id: 'A', label: 'Evet' },
        { id: 'B', label: 'Hayır' },
      ],
    },
  ],
}

const multiApproval: ApprovalInfo = {
  requestId: 'r3',
  questions: [
    {
      id: 'm1',
      question: 'Hangileri?',
      multiple: true,
      options: [
        { id: 'A', label: 'A seçeneği' },
        { id: 'B', label: 'B seçeneği' },
      ],
    },
  ],
}

const customApproval: ApprovalInfo = {
  requestId: 'r4',
  questions: [{ id: 'c1', question: 'Açıklayın', custom: true }],
}

describe('ApprovalPrompt', () => {
  it('İzin ver allow:true, Reddet allow:false yollar (cevapsız)', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    render(<ApprovalPrompt backend="claude-app" sessionId="s1" approval={toolApproval} />)

    expect(screen.getByText('Bash çalıştır')).toBeTruthy()
    expect(screen.getByText('ls -la')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'İzin ver' }))
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/approve')
    expect(init?.method).toBe('POST')
    expect(bodyOf(init)).toEqual({ sessionId: 's1', allow: true, requestId: 'r1' })

    fireEvent.click(screen.getByRole('button', { name: 'Reddet' }))
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(2))
    const deny = bodyOf(fn.mock.calls[1][1])
    expect(deny).toEqual({ sessionId: 's1', allow: false, requestId: 'r1' })
    expect('answers' in deny).toBe(false)
  })

  it('sorular cevaplanmadan gönderim kapalı; seçilen cevap answers olarak gider', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    render(<ApprovalPrompt backend="claude-app" sessionId="s1" approval={questionApproval} />)

    const submit = screen.getByRole('button', { name: 'Cevapları gönder' }) as HTMLButtonElement
    expect(submit.disabled).toBe(true)

    fireEvent.click(screen.getByRole('radio', { name: 'Evet' }))
    await waitFor(() => expect(submit.disabled).toBe(false))

    fireEvent.click(submit)
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    const body = bodyOf(fn.mock.calls[0][1])
    expect(body).toEqual({
      sessionId: 's1',
      allow: true,
      answers: [{ id: 'q1', optionId: 'A', label: 'Evet' }],
      requestId: 'r2',
    })
  })

  it('tek seçimli soruda yeni seçim öncekinin yerine geçer', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    render(<ApprovalPrompt backend="claude-app" sessionId="s1" approval={questionApproval} />)

    fireEvent.click(screen.getByRole('radio', { name: 'Evet' }))
    fireEvent.click(screen.getByRole('radio', { name: 'Hayır' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cevapları gönder' }))
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    expect(bodyOf(fn.mock.calls[0][1]).answers).toEqual([{ id: 'q1', optionId: 'B', label: 'Hayır' }])
  })

  it('çok seçimli soruda her seçim ayrı cevap olarak gider', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    render(<ApprovalPrompt backend="claude-app" sessionId="s1" approval={multiApproval} />)

    fireEvent.click(screen.getByRole('checkbox', { name: 'A seçeneği' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'B seçeneği' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cevapları gönder' }))
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    expect(bodyOf(fn.mock.calls[0][1]).answers).toEqual([
      { id: 'm1', optionId: 'A', label: 'A seçeneği' },
      { id: 'm1', optionId: 'B', label: 'B seçeneği' },
    ])
  })

  it('custom soru serbest metin; cevap optionId=label=metin', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    render(<ApprovalPrompt backend="claude-app" sessionId="s1" approval={customApproval} />)

    const input = screen.getByPlaceholderText('Cevabınız')
    fireEvent.change(input, { target: { value: 'Evet, yap' } })
    fireEvent.click(screen.getByRole('button', { name: 'Cevapları gönder' }))
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    expect(bodyOf(fn.mock.calls[0][1]).answers).toEqual([{ id: 'c1', optionId: 'Evet, yap', label: 'Evet, yap' }])
  })

  it('istek uçarken ikinci tıklama ikinci istek atmaz', async () => {
    let resolveFetch!: (response: Response) => void
    const fn = vi.fn(() => new Promise<Response>((resolve) => (resolveFetch = resolve)))
    vi.stubGlobal('fetch', fn)
    render(<ApprovalPrompt backend="claude-app" sessionId="s1" approval={toolApproval} />)

    const allow = screen.getByRole('button', { name: 'İzin ver' })
    fireEvent.click(allow)
    fireEvent.click(allow)
    expect(fn).toHaveBeenCalledTimes(1)

    await act(async () => resolveFetch(jsonResponse({ ok: true })))
    await waitFor(() => expect((allow as HTMLButtonElement).disabled).toBe(false))
  })

  it('onay hatası görünür; düğmeler yeniden etkinleşir', async () => {
    stubFetch(() => jsonResponse({ ok: false, error: 'tüm sorular cevaplanmalı' }))
    render(<ApprovalPrompt backend="claude-app" sessionId="s1" approval={questionApproval} />)

    fireEvent.click(screen.getByRole('radio', { name: 'Evet' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cevapları gönder' }))
    await waitFor(() => expect(screen.getByText('tüm sorular cevaplanmalı')).toBeTruthy())

    const submit = screen.getByRole('button', { name: 'Cevapları gönder' }) as HTMLButtonElement
    expect(submit.disabled).toBe(false)
  })

  it('onDecision başarılı onay sonrası çağrılır', async () => {
    stubFetch(() => jsonResponse({ ok: true }))
    const onDecision = vi.fn()
    render(<ApprovalPrompt backend="claude-app" sessionId="s1" approval={toolApproval} onDecision={onDecision} />)
    fireEvent.click(screen.getByRole('button', { name: 'İzin ver' }))
    await waitFor(() => expect(onDecision).toHaveBeenCalledTimes(1))
  })
})
