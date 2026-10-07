// @vitest-environment happy-dom
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EffortPicker } from './EffortPicker'

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } })
}

function stubFetch(handler: (url: string, init?: RequestInit) => Response) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

const select = () => screen.getByRole('combobox') as HTMLSelectElement

afterEach(() => vi.unstubAllGlobals())

describe('EffortPicker', () => {
  it('seçenekleri GET /claude-app/efforts tan yükler; değişiklik doğru POST u atar', async () => {
    const fn = stubFetch((url) =>
      url === '/claude-app/efforts'
        ? jsonResponse({ efforts: ['low', 'medium', 'high'] })
        : jsonResponse({ ok: true, effort: 'high' }),
    )
    const onChanged = vi.fn()
    render(<EffortPicker backend="claude-app" sessionId="s1" value="medium" onChanged={onChanged} />)

    await waitFor(() => expect(select().querySelectorAll('option')).toHaveLength(3))
    expect(fn.mock.calls[0][0]).toBe('/claude-app/efforts')

    fireEvent.change(select(), { target: { value: 'high' } })
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(2))
    const [url, init] = fn.mock.calls[1]
    expect(url).toBe('/claude-app/effort')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ sessionId: 's1', effort: 'high' })
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith('high'))
  })

  it('çabanın bir sonraki turda geçerli olacağını söyleyen not ipucunda durur', async () => {
    // Not eskiden çubukta ayrı bir satırdı ve üst çubuğu şişiriyordu;
    // artık seçicinin title'ında.
    stubFetch(() => jsonResponse({ efforts: ['low', 'high'] }))
    const { container } = render(
      <EffortPicker backend="claude-app" sessionId="s1" value="low" onChanged={() => {}} />,
    )
    await waitFor(() => expect(container.querySelector('[title]')).not.toBeNull())
    expect(container.querySelector('[title]')!.getAttribute('title')).toMatch(/bir sonraki turda/)
  })

  it('hata olunca eski değere döner, onChanged çağrılmaz ve hata görünür', async () => {
    stubFetch((url) =>
      url === '/claude-app/efforts'
        ? jsonResponse({ efforts: ['low', 'medium'] })
        : jsonResponse({ ok: false, error: 'geçersiz effort' }),
    )
    const onChanged = vi.fn()
    render(<EffortPicker backend="claude-app" sessionId="s1" value="low" onChanged={onChanged} />)
    await waitFor(() => expect(select().querySelectorAll('option')).toHaveLength(2))

    fireEvent.change(select(), { target: { value: 'medium' } })
    await waitFor(() => expect(screen.getByText('geçersiz effort')).toBeTruthy())
    expect(onChanged).not.toHaveBeenCalled()
    expect(select().value).toBe('low')
  })

  it('codex-app: boş effort "default" olarak görünür ve seçili kalır', async () => {
    stubFetch(() => jsonResponse({ efforts: ['low', 'high', 'xhigh'] }))
    render(<EffortPicker backend="codex-app" sessionId="s1" value="" onChanged={() => {}} />)

    await waitFor(() =>
      expect(select().options[select().selectedIndex]?.text).toBe('default'),
    )
    expect(select().value).toBe('')
    // "default" tek bir seçenek: ayrıca bir "—" satırı çizilmez.
    expect(Array.from(select().options).filter((o) => o.value === '')).toHaveLength(1)
  })

  it('mevcut effort geçici katalogda yoksa onu koruyup gösterir', async () => {
    stubFetch(() => jsonResponse({ efforts: ['low', 'high'] }))
    render(<EffortPicker backend="codex-app" sessionId="s1" value="xhigh" onChanged={() => {}} />)

    await waitFor(() => expect(select().value).toBe('xhigh'))
    expect(select().options[select().selectedIndex]?.text).toBe('xhigh')
  })
})
