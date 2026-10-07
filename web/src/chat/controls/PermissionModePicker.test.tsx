// @vitest-environment happy-dom
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PermissionModePicker } from './PermissionModePicker'

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } })
}

function stubFetch(handler: (url: string, init?: RequestInit) => Response) {
  const fn = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fn)
  return fn
}

const select = () => screen.getByRole('combobox') as HTMLSelectElement
const optionLabels = () => screen.getAllByRole('option').map((option) => option.textContent)

afterEach(() => vi.unstubAllGlobals())

describe('PermissionModePicker', () => {
  it('modes prop unu etiketleriyle gösterir ve GET atmaz; değişiklik doğru POST u atar', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true, permissionMode: 'bypassPermissions' }))
    const onChanged = vi.fn()
    render(<PermissionModePicker backend="claude-app" sessionId="s1" modes={['', 'plan']} value="" onChanged={onChanged} />)

    expect(fn).not.toHaveBeenCalled()
    expect(optionLabels()).toEqual(['Normal', 'Plan'])

    fireEvent.change(select(), { target: { value: 'plan' } })
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/permission-mode')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ sessionId: 's1', mode: 'plan' })
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith('bypassPermissions'))
  })

  it('modes yoksa varsayılan CLI listesine düşer', () => {
    render(<PermissionModePicker backend="claude-app" sessionId="s1" value="plan" onChanged={vi.fn()} />)
    expect(optionLabels()).toEqual(['Normal', 'Otomatik', 'Plan', 'Düzenlemeleri otomatik kabul', 'İzinleri atla'])
  })

  it('hata olunca eski değere döner, onChanged çağrılmaz ve hata görünür', async () => {
    stubFetch(() => jsonResponse({ ok: false, error: 'session not found' }))
    const onChanged = vi.fn()
    render(<PermissionModePicker backend="claude-app" sessionId="s1" modes={['', 'plan']} value="plan" onChanged={onChanged} />)

    fireEvent.change(select(), { target: { value: '' } })
    await waitFor(() => expect(screen.getByText('session not found')).toBeTruthy())
    expect(onChanged).not.toHaveBeenCalled()
    expect(select().value).toBe('plan')
  })
})
