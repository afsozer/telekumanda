import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActiveToken } from '../api'
import { backoffMs, SessionStream, type SocketFactory, type SocketHandlers } from './sessionStream'
import type { SessionState } from './protocol'

/** Sahte soket: açılışı, mesajı ve kapanmayı test elle sürer. */
class FakeSocket {
  closed = false
  url: string
  handlers: SocketHandlers

  constructor(url: string, handlers: SocketHandlers) {
    this.url = url
    this.handlers = handlers
  }

  close() {
    this.closed = true
  }
}

function harness() {
  const sockets: FakeSocket[] = []
  const factory: SocketFactory = (url, handlers) => {
    const socket = new FakeSocket(url, handlers)
    sockets.push(socket)
    return socket
  }
  const states: { sessionId: string; state: SessionState }[] = []
  const ends: string[] = []
  const errors: { sessionId: string; message: string }[] = []
  const connections: boolean[] = []
  const stream = new SessionStream(
    {
      onState: (sessionId, state) => states.push({ sessionId, state }),
      onEnd: (sessionId) => ends.push(sessionId),
      onError: (sessionId, message) => errors.push({ sessionId, message }),
      onConnectionChange: (connected) => connections.push(connected),
    },
    factory,
  )
  const last = () => sockets[sockets.length - 1]
  const send = (payload: unknown) => last().handlers.onMessage(JSON.stringify(payload))
  return { stream, sockets, states, ends, errors, connections, last, send }
}

beforeEach(() => {
  vi.useFakeTimers()
  // streamUrl window.location'a bakıyor; jsdom yok, sahte veriyoruz.
  vi.stubGlobal('window', { location: { protocol: 'http:', host: 'pc:8787' } })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  setActiveToken(null)
})

describe('backoffMs', () => {
  it('1s’ten başlar, ikişer katlanır, 15s’te durur', () => {
    expect([0, 1, 2, 3, 4, 5, 99].map(backoffMs)).toEqual([
      1000, 2000, 4000, 8000, 15_000, 15_000, 15_000,
    ])
  })
})

describe('açılış', () => {
  it('doğru backend ve oturumla soket açar', () => {
    const h = harness()
    h.stream.open('claude-app', 'oturum-1')
    const url = new URL(h.last().url)
    expect(url.pathname).toBe('/claude-app/stream')
    expect(url.searchParams.get('session')).toBe('oturum-1')
    expect(url.searchParams.get('delta')).toBe('1')
    // İlk açılışta since YOK — elde satır yok, tam snapshot isteniyor.
    expect(url.searchParams.has('since')).toBe(false)
  })

  it('token sorguya girer — tarayıcı WS başlık gönderemiyor', () => {
    setActiveToken('gizli')
    const h = harness()
    h.stream.open('claude-app', 'o1')
    expect(new URL(h.last().url).searchParams.get('token')).toBe('gizli')
  })

  it('açılınca bağlantı durumu bildirilir', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    expect(h.stream.isConnected).toBe(false)
    h.last().handlers.onOpen()
    expect(h.stream.isConnected).toBe(true)
    expect(h.connections).toEqual([true])
  })
})

describe('mesaj işleme', () => {
  it('snapshot durumu yayar', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.last().handlers.onOpen()
    h.send({ type: 'snapshot', seq: 3, messages: [{ rowId: 'r1', role: 'user', text: 'selam' }], running: true })
    expect(h.states).toHaveLength(1)
    expect(h.states[0].sessionId).toBe('o1')
    expect(h.states[0].state.rows.map((r) => r.rowId)).toEqual(['r1'])
    expect(h.states[0].state.meta.running).toBe(true)
  })

  it('delta önceki duruma uygulanır', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.last().handlers.onOpen()
    h.send({ type: 'snapshot', seq: 3, messages: [{ rowId: 'r1', role: 'agent', text: 'Mer' }] })
    h.send({ type: 'delta', seq: 4, ops: [{ op: 'appendText', rowId: 'r1', chunk: 'haba' }] })
    expect(h.stream.currentState.rows[0].text).toBe('Merhaba')
    expect(h.stream.currentState.seq).toBe(4)
  })

  it('end ve error ayrı olaylara gider', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.send({ type: 'error', error: 'session not found' })
    h.send({ type: 'end' })
    expect(h.errors).toEqual([{ sessionId: 'o1', message: 'session not found' }])
    expect(h.ends).toEqual(['o1'])
  })

  it('bozuk JSON akışı öldürmez', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    expect(() => h.last().handlers.onMessage('{bozuk')).not.toThrow()
    h.send({ type: 'snapshot', seq: 1, messages: [] })
    expect(h.states).toHaveLength(1)
  })
})

describe('yeniden bağlanma', () => {
  it('kapanınca geri çekilmeli yeniden bağlanır', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.last().handlers.onOpen()
    h.last().handlers.onClose()

    expect(h.sockets).toHaveLength(1)
    vi.advanceTimersByTime(999)
    expect(h.sockets).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(h.sockets).toHaveLength(2)
  })

  it('yeniden bağlanırken since gönderir — kaçan deltalar istenir', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.last().handlers.onOpen()
    h.send({ type: 'snapshot', seq: 42, messages: [{ rowId: 'r1', role: 'user', text: 'x' }] })
    h.last().handlers.onClose()
    vi.advanceTimersByTime(1000)
    expect(new URL(h.last().url).searchParams.get('since')).toBe('42')
  })

  it('art arda başarısızlıkta bekleme büyür', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.last().handlers.onClose()
    vi.advanceTimersByTime(1000)
    expect(h.sockets).toHaveLength(2)

    h.last().handlers.onClose()
    vi.advanceTimersByTime(1999)
    expect(h.sockets).toHaveLength(2)
    vi.advanceTimersByTime(1)
    expect(h.sockets).toHaveLength(3)
  })

  it('başarılı bağlantı bekleme sayacını sıfırlar', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.last().handlers.onClose()
    vi.advanceTimersByTime(1000)
    h.last().handlers.onOpen() // bu sefer açıldı
    h.last().handlers.onClose()
    // Sayaç sıfırlandığı için yine 1 sn — 2 sn değil.
    vi.advanceTimersByTime(1000)
    expect(h.sockets).toHaveLength(3)
  })

  it('aynı anda tek bekleyen deneme olur', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.last().handlers.onClose()
    h.last().handlers.onClose()
    h.last().handlers.onClose()
    vi.advanceTimersByTime(1000)
    expect(h.sockets).toHaveLength(2)
  })
})

describe('epoch koruması', () => {
  it('kapatılmış oturumun soketi geri bağlanmaz', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    const eski = h.last()
    h.stream.close()
    eski.handlers.onClose() // ölmekte olan eski soket
    vi.advanceTimersByTime(60_000)
    expect(h.sockets).toHaveLength(1)
  })

  it('eski soketten gelen mesaj yeni oturumu kirletmez', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    const eski = h.last()
    h.stream.open('claude-app', 'o2')
    eski.handlers.onMessage(JSON.stringify({ type: 'snapshot', seq: 9, messages: [{ rowId: 'hayalet', role: 'agent', text: 'x' }] }))
    expect(h.states).toHaveLength(0)
    expect(h.stream.currentState.rows).toHaveLength(0)
  })

  it('bekleyen yeniden bağlanma, oturum değişince tetiklenmez', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.last().handlers.onClose()
    h.stream.open('claude-app', 'o2') // bekleme dolmadan geçiş
    const sonra = h.sockets.length
    vi.advanceTimersByTime(60_000)
    expect(h.sockets).toHaveLength(sonra)
  })
})

describe('durak (stash)', () => {
  it('oturuma dönüşte satırlar ve since geri gelir', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.last().handlers.onOpen()
    h.send({ type: 'snapshot', seq: 7, messages: [{ rowId: 'r1', role: 'user', text: 'selam' }] })

    h.stream.open('claude-app', 'o2') // başka oturuma geç
    expect(h.stream.currentState.rows).toHaveLength(0)

    h.stream.open('claude-app', 'o1') // geri dön
    expect(h.stream.currentState.rows.map((r) => r.rowId)).toEqual(['r1'])
    expect(new URL(h.last().url).searchParams.get('since')).toBe('7')
  })

  it('oturuma dönüşte effort dahil meta geri gelir', () => {
    const h = harness()
    h.stream.open('codex-app', 'o1')
    h.send({
      type: 'snapshot',
      seq: 7,
      messages: [{ rowId: 'r1', role: 'user', text: 'selam' }],
      effort: 'xhigh',
      permissionMode: 'ask',
    })

    h.stream.open('codex-app', 'o2')
    h.stream.open('codex-app', 'o1')

    expect(h.stream.currentState.meta.effort).toBe('xhigh')
    expect(h.stream.currentState.meta.permissionMode).toBe('ask')
  })

  it('aynı oturum kimliği farklı backend’lerde karışmaz', () => {
    const h = harness()
    h.stream.open('claude-app', 'ayni')
    h.last().handlers.onOpen()
    h.send({ type: 'snapshot', seq: 7, messages: [{ rowId: 'r1', role: 'user', text: 'x' }] })
    h.stream.open('codex-app', 'ayni')
    expect(h.stream.currentState.rows).toHaveLength(0)
    expect(new URL(h.last().url).searchParams.has('since')).toBe(false)
  })

  it('durak 8 oturumla sınırlı, en eskisi düşer', () => {
    const h = harness()
    for (let i = 0; i < 9; i++) {
      h.stream.open('claude-app', `o${i}`)
      h.last().handlers.onOpen()
      h.send({ type: 'snapshot', seq: i + 1, messages: [{ rowId: `r${i}`, role: 'user', text: 'x' }] })
    }
    h.stream.close()
    // o0 taşmayla düştü.
    h.stream.open('claude-app', 'o0')
    expect(h.stream.currentState.rows).toHaveLength(0)
    // o8 duruyor.
    h.stream.open('claude-app', 'o8')
    expect(h.stream.currentState.rows.map((r) => r.rowId)).toEqual(['r8'])
  })
})

describe('nudge', () => {
  it('bekleyen geri çekilmeyi iptal edip anında bağlanır', () => {
    const h = harness()
    h.stream.open('claude-app', 'o1')
    h.last().handlers.onOpen()
    h.send({ type: 'snapshot', seq: 5, messages: [{ rowId: 'r1', role: 'user', text: 'x' }] })
    h.last().handlers.onClose()

    h.stream.nudge()
    expect(h.sockets).toHaveLength(2) // beklemeden
    // since korunur: koşulsuz tazeleme ucuz olsun diye.
    expect(new URL(h.last().url).searchParams.get('since')).toBe('5')

    // İptal edilen zamanlayıcı sonradan üçüncü bir soket açmamalı.
    vi.advanceTimersByTime(60_000)
    expect(h.sockets).toHaveLength(2)
  })

  it('açık oturum yokken hiçbir şey yapmaz', () => {
    const h = harness()
    h.stream.nudge()
    expect(h.sockets).toHaveLength(0)
  })
})
