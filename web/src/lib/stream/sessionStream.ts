// Akış bağlantı yöneticisi. Karşılığı: SessionStreamManager.kt.
//
// İndirgeme mantığı burada DEĞİL (reducer.ts). Burada olan: soket ömrü, epoch
// koruması, geri çekilmeli yeniden bağlanma, oturum değiştirince durak (stash).

import { streamUrl } from '../api'
import { emptyState, type ServerMessage, type SessionState, type StreamMeta, type StreamRow } from './protocol'
import { reduce } from './reducer'

/** Soket soyutlaması — testte sahtesi verilebilsin diye. */
export interface SocketHandlers {
  onOpen(): void
  onMessage(data: string): void
  onClose(): void
}

export interface StreamSocket {
  close(): void
}

export type SocketFactory = (url: string, handlers: SocketHandlers) => StreamSocket

export interface SessionStreamCallbacks {
  onState(sessionId: string, state: SessionState): void
  onEnd(sessionId: string): void
  onError(sessionId: string, message: string): void
  onConnectionChange?(connected: boolean): void
}

/**
 * Geri çekilme süresi. Kotlin istemcisiyle aynı eğri: 1s, 2s, 4s, 8s, sonra
 * 15s tavan. Tavan bilinçli — akış donmuş görünürken 30 sn beklemek
 * kullanıcının fark ettiği tek şey oluyor.
 */
export function backoffMs(attempt: number): number {
  return Math.min(1000 * 2 ** Math.min(Math.max(attempt, 0), 4), 15_000)
}

interface StashEntry {
  seq: number | null
  rows: StreamRow[]
  meta: StreamMeta
}

/**
 * Ayrılınan oturumların akış hafızası. Dönüşte `since` ile yalnız kaçan
 * deltalar istenir; sıfırdan tam snapshot indirilmez. LRU, 8 oturum —
 * Kotlin tarafındaki StreamSessionStash ile aynı.
 */
class Stash {
  private entries = new Map<string, StashEntry>()
  private capacity: number

  // Not: kurucu parametresi alan bildirimi (`constructor(private x)`) KULLANMA —
  // tsconfig'de `erasableSyntaxOnly` açık, derleme kırılır.
  constructor(capacity = 8) {
    this.capacity = capacity
  }

  take(key: string): StashEntry | undefined {
    const value = this.entries.get(key)
    if (value) this.entries.delete(key)
    return value
  }

  put(key: string, value: StashEntry): void {
    if (!key.trim()) return
    this.entries.delete(key)
    this.entries.set(key, value)
    while (this.entries.size > this.capacity) {
      const oldest = this.entries.keys().next().value
      if (oldest === undefined) break
      this.entries.delete(oldest)
    }
  }
}

const defaultFactory: SocketFactory = (url, handlers) => {
  const ws = new WebSocket(url)
  ws.onopen = () => handlers.onOpen()
  ws.onmessage = (event) => handlers.onMessage(String(event.data))
  ws.onclose = () => handlers.onClose()
  // Tarayıcıda error'ı daima close izler; ikisini de dinlemek çift yeniden
  // bağlanma denemesi üretirdi.
  ws.onerror = () => {}
  return { close: () => ws.close() }
}

export class SessionStream {
  private socket: StreamSocket | null = null
  private backend = ''
  private sessionId = ''
  private state: SessionState = emptyState()
  private stash = new Stash()
  /**
   * open()/close()/nudge() ile artar. Epoch'u eskimiş bir dinleyici ya da
   * bekleyen yeniden bağlanma hiçbir şey yapmaz — böylece ölmekte olan eski
   * bir soket, kullanıcının çoktan ayrıldığı oturumu geri bağlayamaz.
   */
  private epoch = 0
  private attempt = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private connected = false

  private callbacks: SessionStreamCallbacks
  private createSocket: SocketFactory

  constructor(callbacks: SessionStreamCallbacks, createSocket: SocketFactory = defaultFactory) {
    this.callbacks = callbacks
    this.createSocket = createSocket
  }

  get isConnected(): boolean {
    return this.connected
  }

  get currentSessionId(): string {
    return this.sessionId
  }

  get currentState(): SessionState {
    return this.state
  }

  open(backend: string, sessionId: string): void {
    this.close()
    this.epoch++
    this.backend = backend
    this.sessionId = sessionId
    const restored = this.stash.take(`${backend}:${sessionId}`)
    this.state = restored
      ? { ...emptyState(), seq: restored.seq, rows: restored.rows, meta: restored.meta }
      : emptyState()
    this.attempt = 0
    this.connect(this.epoch)
  }

  /**
   * Bekleyen geri çekilmeyi iptal edip soketi ANINDA tazeler. Sekme öne
   * gelince çağrılır: arka planda soket yarı-açık kalabiliyor (istemci "bağlı"
   * sanır ama veri akmaz) ve bunu anlamak geri çekilme süresini beklemeye
   * kalıyor. `since` korunduğu için koşulsuz tazelemek ucuz.
   */
  nudge(): void {
    if (!this.backend || !this.sessionId) return
    this.epoch++
    this.clearTimer()
    this.socket?.close()
    this.socket = null
    this.setConnected(false)
    this.attempt = 0
    this.connect(this.epoch)
  }

  close(): void {
    // Kapanan oturumun hafızası atılmaz, kenara konur.
    if (this.sessionId && (
      this.state.seq !== null || this.state.rows.length || Object.keys(this.state.meta).length
    )) {
      this.stash.put(`${this.backend}:${this.sessionId}`, {
        seq: this.state.seq,
        rows: this.state.rows,
        meta: this.state.meta,
      })
    }
    this.epoch++
    this.clearTimer()
    this.socket?.close()
    this.socket = null
    this.backend = ''
    this.sessionId = ''
    this.state = emptyState()
    this.attempt = 0
    this.setConnected(false)
  }

  private clearTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  private setConnected(value: boolean): void {
    if (this.connected === value) return
    this.connected = value
    this.callbacks.onConnectionChange?.(value)
  }

  private connect(myEpoch: number): void {
    const { backend, sessionId } = this
    // `since` verildiğinde köprü yalnız kaçan deltaları yollar; halka yetmezse
    // kendiliğinden tam snapshot'a düşer, o durumu indirgeyici karşılıyor.
    const url = streamUrl(`/${backend}/stream`, {
      session: sessionId,
      since: this.state.seq ?? undefined,
      delta: '1',
    })

    this.socket = this.createSocket(url, {
      onOpen: () => {
        if (myEpoch !== this.epoch) return
        this.attempt = 0
        this.setConnected(true)
      },
      onMessage: (data) => {
        if (myEpoch !== this.epoch) return
        this.handleMessage(sessionId, data)
      },
      onClose: () => {
        if (myEpoch !== this.epoch) return
        this.setConnected(false)
        this.scheduleReconnect(myEpoch)
      },
    })
  }

  private scheduleReconnect(myEpoch: number): void {
    if (myEpoch !== this.epoch || this.reconnectTimer !== null) return
    const delay = backoffMs(this.attempt)
    this.attempt++
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      if (myEpoch !== this.epoch || this.connected) return
      this.socket?.close()
      this.connect(myEpoch)
    }, delay)
  }

  private handleMessage(sessionId: string, data: string): void {
    let parsed: ServerMessage
    try {
      parsed = JSON.parse(data) as ServerMessage
    } catch {
      // Bozuk çerçeve akışı öldürmemeli; köprü bir sonrakinde toparlar.
      return
    }
    const { state, event, error } = reduce(this.state, parsed)
    this.state = state
    if (event === 'update') this.callbacks.onState(sessionId, state)
    else if (event === 'end') this.callbacks.onEnd(sessionId)
    else if (event === 'error') this.callbacks.onError(sessionId, error ?? 'Akış hatası')
  }
}
