import { afterEach, describe, expect, it } from 'vitest'
import { setActiveToken, streamUrl, withTicket } from './api'

afterEach(() => setActiveToken(null))

describe('streamUrl', () => {
  const loc = { protocol: 'http:', host: 'bridge-host:8787' }

  it('ws şeması ve köprü adresiyle kurar', () => {
    const url = streamUrl('/claude-app/stream', { session: 'abc' }, loc)
    expect(url.startsWith('ws://bridge-host:8787/claude-app/stream?')).toBe(true)
    expect(new URL(url).searchParams.get('session')).toBe('abc')
  })

  it('https altında wss kullanır — tailscale serve senaryosu', () => {
    const url = streamUrl('/claude-app/stream', {}, { protocol: 'https:', host: 'pc.ts.net' })
    expect(url.startsWith('wss://pc.ts.net/')).toBe(true)
  })

  it('token adrese girmez — akış tek kullanımlık biletle açılır', () => {
    setActiveToken('gizli')
    const url = streamUrl('/claude-app/stream', { session: 'abc' }, loc)
    expect(new URL(url).searchParams.has('token')).toBe(false)
    expect(url).not.toContain('gizli')
  })

  it('withTicket bileti ekler, öteki parametreleri korur', () => {
    const url = withTicket(streamUrl('/claude-app/stream', { session: 'abc' }, loc), 'tkt_1')
    const params = new URL(url).searchParams
    expect(params.get('ticket')).toBe('tkt_1')
    expect(params.get('session')).toBe('abc')
  })

  it('tanımsız ve boş parametreler atılır', () => {
    setActiveToken('t')
    const url = streamUrl('/claude-app/stream', { session: 'abc', since: undefined, delta: '' }, loc)
    const params = new URL(url).searchParams
    expect(params.has('since')).toBe(false)
    expect(params.has('delta')).toBe(false)
    expect(params.get('session')).toBe('abc')
  })

  it('sayısal parametreyi dizeye çevirir — since=0 elenmemeli', () => {
    const url = streamUrl('/claude-app/stream', { since: 0 }, loc)
    expect(new URL(url).searchParams.get('since')).toBe('0')
  })
})
