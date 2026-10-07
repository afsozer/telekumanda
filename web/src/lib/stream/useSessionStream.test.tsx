// @vitest-environment happy-dom
import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSessionStream } from './useSessionStream'
import type { SocketFactory, SocketHandlers } from './sessionStream'

const sockets: { url: string; handlers: SocketHandlers; closed: boolean }[] = []

const factory: SocketFactory = (url, handlers) => {
  const socket = { url, handlers, closed: false }
  sockets.push(socket)
  return { close: () => (socket.closed = true) }
}

const last = () => sockets[sockets.length - 1]

function Probe({ backend, sessionId }: { backend: string; sessionId: string }) {
  const { state, connected, error, ended } = useSessionStream(backend, sessionId, factory)
  return (
    <div>
      <span data-testid="rows">{state.rows.map((r) => r.text).join('|')}</span>
      <span data-testid="connected">{String(connected)}</span>
      <span data-testid="error">{error ?? ''}</span>
      <span data-testid="ended">{String(ended)}</span>
      <span data-testid="effort">{state.meta.effort ?? ''}</span>
    </div>
  )
}

const text = (id: string) => screen.getByTestId(id).textContent

beforeEach(() => {
  sockets.length = 0
  vi.stubGlobal('window', Object.assign(window, { location: { protocol: 'http:', host: 'pc:8787' } }))
})

afterEach(() => vi.unstubAllGlobals())

describe('useSessionStream', () => {
  it('oturum verilince akışı açar ve gelen durumu yansıtır', () => {
    render(<Probe backend="claude-app" sessionId="o1" />)
    expect(sockets).toHaveLength(1)

    act(() => {
      last().handlers.onOpen()
      last().handlers.onMessage(
        JSON.stringify({ type: 'snapshot', seq: 1, messages: [{ rowId: 'r1', role: 'user', text: 'selam' }] }),
      )
    })
    expect(text('rows')).toBe('selam')
    expect(text('connected')).toBe('true')
  })

  it('delta akışı satıra birikir', () => {
    render(<Probe backend="claude-app" sessionId="o1" />)
    act(() => {
      last().handlers.onOpen()
      last().handlers.onMessage(
        JSON.stringify({ type: 'snapshot', seq: 1, messages: [{ rowId: 'r1', role: 'agent', text: 'Mer' }] }),
      )
      last().handlers.onMessage(
        JSON.stringify({ type: 'delta', seq: 2, ops: [{ op: 'appendText', rowId: 'r1', chunk: 'haba' }] }),
      )
    })
    expect(text('rows')).toBe('Merhaba')
  })

  it('oturum boşken soket açmaz', () => {
    render(<Probe backend="claude-app" sessionId="" />)
    expect(sockets).toHaveLength(0)
  })

  it('oturum değişince durum sıfırlanır — eski sohbet görünmez', () => {
    const view = render(<Probe backend="claude-app" sessionId="o1" />)
    act(() => {
      last().handlers.onOpen()
      last().handlers.onMessage(
        JSON.stringify({ type: 'snapshot', seq: 1, messages: [{ rowId: 'r1', role: 'user', text: 'eski' }] }),
      )
    })
    expect(text('rows')).toBe('eski')

    view.rerender(<Probe backend="claude-app" sessionId="o2" />)
    expect(text('rows')).toBe('')
    expect(text('error')).toBe('')
  })

  it('oturuma geri dönünce duraktaki effort anında geri yüklenir', () => {
    const view = render(<Probe backend="codex-app" sessionId="o1" />)
    act(() => {
      last().handlers.onMessage(JSON.stringify({
        type: 'snapshot',
        seq: 4,
        messages: [{ rowId: 'r1', role: 'user', text: 'eski' }],
        effort: 'xhigh',
      }))
    })
    expect(text('effort')).toBe('xhigh')

    view.rerender(<Probe backend="codex-app" sessionId="o2" />)
    expect(text('effort')).toBe('')

    view.rerender(<Probe backend="codex-app" sessionId="o1" />)
    expect(text('effort')).toBe('xhigh')
  })

  it('hata ve bitiş ayrı ayrı yüzeye çıkar', () => {
    render(<Probe backend="claude-app" sessionId="o1" />)
    act(() => {
      last().handlers.onMessage(JSON.stringify({ type: 'error', error: 'session not found' }))
      last().handlers.onMessage(JSON.stringify({ type: 'end' }))
    })
    expect(text('error')).toBe('session not found')
    expect(text('ended')).toBe('true')
  })

  it('sekme öne gelince soketi tazeler', () => {
    render(<Probe backend="claude-app" sessionId="o1" />)
    act(() => last().handlers.onOpen())
    const once = sockets.length

    act(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(sockets.length).toBe(once + 1)
  })

  it('sekme arkaya gidince tazelemez', () => {
    render(<Probe backend="claude-app" sessionId="o1" />)
    act(() => last().handlers.onOpen())
    const once = sockets.length

    act(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(sockets.length).toBe(once)
  })

  it('bileşen sökülünce soket kapanır', () => {
    const view = render(<Probe backend="claude-app" sessionId="o1" />)
    const socket = last()
    view.unmount()
    expect(socket.closed).toBe(true)
  })
})
