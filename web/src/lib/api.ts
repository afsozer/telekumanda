// Köprü istemcisi — ince katman. Kotlin tarafındaki BridgeClient'ın karşılığı
// değil; yalnızca taşıma. Uç noktaya özel çağrılar kendi modüllerinde durur.

import { clearToken, saveToken } from './token'

/** Köprü 401 döndüğünde fırlatılır. Uygulama bunu yakalayıp token ekranına döner. */
export class UnauthorizedError extends Error {
  constructor() {
    super('unauthorized')
    this.name = 'UnauthorizedError'
  }
}

/**
 * Köprü hata döndürdüğünde fırlatılır. Durum kodu ve gövde taşınır: bazı uçlar
 * duruma göre farklı davranış istiyor — ör. /prompt 409 ile "bu istek güvenle
 * teslim edilmedi, YENİ istek olarak gönder" diyor ve bunu 400'den ayırmak şart.
 */
export class ApiError extends Error {
  status: number
  data: unknown

  constructor(message: string, status: number, data: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.data = data
  }
}

let activeToken: string | null = null

export function setActiveToken(token: string | null): void {
  activeToken = token
  if (token) saveToken(token)
  else clearToken()
}

export function getActiveToken(): string | null {
  return activeToken
}

function authHeaders(): HeadersInit {
  return activeToken ? { authorization: `Bearer ${activeToken}` } : {}
}

async function parse<T>(res: Response): Promise<T> {
  if (res.status === 401) throw new UnauthorizedError()
  const text = await res.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      throw new Error(`köprü JSON olmayan yanıt verdi (${res.status}): ${text.slice(0, 200)}`)
    }
  }
  if (!res.ok) {
    const message =
      (data as { error?: string } | null)?.error ?? `köprü ${res.status} döndü`
    throw new ApiError(message, res.status, data)
  }
  return data as T
}

export async function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(path, { headers: authHeaders(), signal })
  return parse<T>(res)
}

export interface PostOptions {
  /**
   * JSON yerine ham gövde. Köprünün dosya uçları (`/context/file`, `/upload`)
   * gövdeyi olduğu gibi bayt olarak okuyor; JSON'a sarmak dosyayı bozar.
   */
  rawBody?: BodyInit
  contentType?: string
}

export async function apiPost<T>(
  path: string,
  body?: unknown,
  signal?: AbortSignal,
  options?: PostOptions,
): Promise<T> {
  const raw = options?.rawBody !== undefined
  const res = await fetch(path, {
    method: 'POST',
    headers: {
      ...authHeaders(),
      'content-type': options?.contentType ?? 'application/json',
    },
    body: raw ? options.rawBody : body === undefined ? undefined : JSON.stringify(body),
    signal,
  })
  return parse<T>(res)
}

/**
 * Kimlik doğrulamalı DELETE. Gövde taşınabilir — köprünün router.mjs'i
 * DELETE'te de body() okuyor (opencode2 talimat silme: {sessionId, key}).
 * fetch'in delete() yöntemi gövdeyi destekliyor; tarayıcılar gönderiyor,
 * sunucu tarafında da okunuyor (ölçüldü 26.09.2026).
 */
export async function apiDelete<T>(
  path: string,
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const res = await fetch(path, {
    method: 'DELETE',
    headers: {
      ...authHeaders(),
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  })
  return parse<T>(res)
}

/**
 * Kimlik doğrulamalı bir uçtan blob çeker ve object URL döner.
 *
 * `<img src="/download?path=…">` YAZILAMAZ: tarayıcı alt kaynak isteklerine
 * `Authorization` başlığı eklemiyor. Token'ı adrese gömmek de istenmez —
 * DOM'a ve olası ekran görüntülerine sızar. Bu yüzden bayt fetch'le alınıp
 * object URL'e çevriliyor.
 *
 * Çağıran, URL'i bıraktığında `URL.revokeObjectURL` çağırmalı; yoksa bayt
 * sayfa ömrü boyunca bellekte kalır.
 */
export async function apiObjectUrl(path: string, signal?: AbortSignal): Promise<string> {
  const res = await fetch(path, { headers: authHeaders(), signal })
  if (res.status === 401) throw new UnauthorizedError()
  if (!res.ok) throw new ApiError(`köprü ${res.status} döndü`, res.status, null)
  return URL.createObjectURL(await res.blob())
}

/**
 * Akış adresi üretir. Token adrese GİRMEZ; bağlanmadan hemen önce `wsTicket`
 * ile alınan tek kullanımlık bilet `withTicket` ile eklenir (bkz. token.ts).
 */
export function streamUrl(
  path: string,
  params: Record<string, string | number | undefined>,
  location: Pick<Location, 'protocol' | 'host'> = window.location,
): string {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value))
  }
  return `${scheme}//${location.host}${path}?${search.toString()}`
}

/** Akış için tek kullanımlık, kısa ömürlü bilet alır (başlıkla kimlik doğrulanır). */
export async function wsTicket(signal?: AbortSignal): Promise<string> {
  const r = await apiPost<{ ticket: string }>('/ws-ticket', undefined, signal)
  return r.ticket
}

/** Akış adresine bileti ekler. Saf fonksiyon. */
export function withTicket(url: string, ticket: string): string {
  const u = new URL(url)
  u.searchParams.set('ticket', ticket)
  return u.toString()
}

export interface Health {
  ok: boolean
  protocolVersion: number
  protocolMinClient: number
}

/** /health kimlik doğrulaması İSTEMEZ — köprünün ayakta olduğunu ölçmek için. */
export function fetchHealth(signal?: AbortSignal): Promise<Health> {
  return apiGet<Health>('/health', signal)
}
