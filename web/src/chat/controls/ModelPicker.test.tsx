// @vitest-environment happy-dom
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ModelPicker } from './ModelPicker'

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

describe('ModelPicker', () => {
  it('models prop varken GET atmaz; değişiklik doğru POST u atar ve onChanged çağırır', async () => {
    const fn = stubFetch(() => jsonResponse({ ok: true, model: 'opus' }))
    const onChanged = vi.fn()
    render(
      <ModelPicker backend="claude-app"
        sessionId="s1"
        models={[
          { id: 'sonnet', label: 'Sonnet' },
          { id: 'opus', label: 'Opus' },
        ]}
        value="sonnet"
        onChanged={onChanged}
      />,
    )
    expect(fn).not.toHaveBeenCalled()

    fireEvent.change(select(), { target: { value: 'opus' } })
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/claude-app/model')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ sessionId: 's1', model: 'opus' })
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith('opus'))
  })

  it('models prop yoksa GET /claude-app/models ten yükler ve seçenekleri gösterir', async () => {
    const fn = stubFetch((url) =>
      url === '/claude-app/models'
        ? jsonResponse({
            models: [
              { id: 'sonnet', label: 'Sonnet' },
              { id: 'opus', label: 'Opus' },
            ],
            defaultModel: 'sonnet',
          })
        : jsonResponse({ ok: true, model: 'opus' }),
    )
    const onChanged = vi.fn()
    render(<ModelPicker backend="claude-app" sessionId="s1" value="sonnet" onChanged={onChanged} />)

    await waitFor(() => expect(select().querySelectorAll('option')).toHaveLength(2))
    expect(fn.mock.calls[0][0]).toBe('/claude-app/models')

    fireEvent.change(select(), { target: { value: 'opus' } })
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith('opus'))
  })

  it('hata olunca eski değere döner, onChanged çağrılmaz ve hata görünür', async () => {
    stubFetch((url) =>
      url === '/claude-app/model' ? jsonResponse({ ok: false, error: 'model required' }) : jsonResponse({ ok: true }),
    )
    const onChanged = vi.fn()
    render(
      <ModelPicker backend="claude-app"
        sessionId="s1"
        models={[
          { id: 'sonnet' },
          { id: 'opus' },
        ]}
        value="sonnet"
        onChanged={onChanged}
      />,
    )

    fireEvent.change(select(), { target: { value: 'opus' } })
    await waitFor(() => expect(screen.getByText('model required')).toBeTruthy())
    expect(onChanged).not.toHaveBeenCalled()
    // Seçici props ile denetlendiği için DOM değeri değişmemeli.
    expect(select().value).toBe('sonnet')
  })

  it('backend DEĞİŞİNCE model listesi yeniden çekilir', async () => {
    // Ajan değiştirildiğinde eski backend'in modelleri seçicide asılı kalıyordu:
    // useEffect bağımlılığında `backend` yoktu. Lint yakaladı, bu test kilitliyor.
    const fn = stubFetch((url) =>
      jsonResponse({
        models: [{ id: url.includes('codex') ? 'gpt' : 'opus', label: 'M' }],
        defaultModel: '',
      }),
    )
    const view = render(<ModelPicker backend="claude-app" sessionId="s1" value="" onChanged={() => {}} />)
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1))
    expect(fn.mock.calls[0][0]).toBe('/claude-app/models')

    view.rerender(<ModelPicker backend="codex-app" sessionId="s1" value="" onChanged={() => {}} />)
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(2))
    expect(fn.mock.calls[1][0]).toBe('/codex-app/models')
  })

  it('omp ve runpod modellerini seçeneklerden eler', async () => {
    render(
      <ModelPicker
        backend="opencode-app"
        sessionId="s1"
        models={[
          { id: 'deepseek-v4', label: 'DeepSeek' },
          { id: 'runpod/runpod', label: 'Qwen3.8-27B · RunPod' },
          { id: 'omp/custom', label: 'OMP Model' },
        ]}
        value=""
        onChanged={() => {}}
      />,
    )
    const options = screen.getAllByRole('option').map((o) => (o as HTMLOptionElement).value)
    expect(options).toEqual(['deepseek-v4'])
  })
})
