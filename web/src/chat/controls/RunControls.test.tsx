// @vitest-environment happy-dom
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RunControls } from './RunControls'
import styles from './controls.module.css'

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } })
}

function stubFetch(handler: (url: string, init?: RequestInit) => Response) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

const kes = () => screen.getByRole('button', { name: 'Kes' }) as HTMLButtonElement
const durdur = () => screen.getByRole('button', { name: 'Durdur' }) as HTMLButtonElement

afterEach(() => vi.unstubAllGlobals())

describe('RunControls', () => {
  it('running false iken düğmeler devre dışı', () => {
    render(<RunControls backend="claude-app" sessionId="s1" running={false} />)
    expect(kes().disabled).toBe(true)
    expect(durdur().disabled).toBe(true)
  })

  it('running true iken Kes POST /claude-app/interrupt atar', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    render(<RunControls backend="claude-app" sessionId="s1" running={true} />)

    fireEvent.click(kes())
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/interrupt')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ sessionId: 's1' })
  })

  it('Durdur POST /claude-app/stop atar', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true }))
    render(<RunControls backend="claude-app" sessionId="s1" running={true} />)

    fireEvent.click(durdur())
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/stop')
    expect(JSON.parse(String(init?.body))).toEqual({ sessionId: 's1' })
  })

  it('interruptStuck uyarısı görünür ve Durdur öne çıkar', () => {
    render(<RunControls backend="claude-app" sessionId="s1" running={true} interruptStuck={true} />)
    expect(screen.getByText(/Kesme isteği takıldı/)).toBeTruthy()
    expect(durdur().classList.contains(styles.stopEmphasized)).toBe(true)
    expect(kes().classList.contains(styles.stopEmphasized)).toBe(false)
  })

  it('bağlam doluluk yüzdesini gösterir', () => {
    render(<RunControls backend="claude-app" sessionId="s1" running={true} contextTokens={500} contextWindow={1000} />)
    expect(screen.getByText('Bağlam %50')).toBeTruthy()
  })

  it('contextWindow yoksa gösterge çizilmez', () => {
    render(<RunControls backend="claude-app" sessionId="s1" running={true} contextTokens={500} />)
    expect(screen.queryByText(/Bağlam/)).toBeNull()
  })

  it('hata olunca görünür ve düğmeler yeniden etkinleşir', async () => {
    stubFetch(() => jsonResponse({ ok: false, error: 'session not found' }))
    render(<RunControls backend="claude-app" sessionId="s1" running={true} />)

    fireEvent.click(kes())
    await waitFor(() => expect(screen.getByText('session not found')).toBeTruthy())
    expect(kes().disabled).toBe(false)
  })
})
