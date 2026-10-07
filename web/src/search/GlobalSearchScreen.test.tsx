// @vitest-environment happy-dom

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GlobalSearchScreen } from './GlobalSearchScreen'

afterEach(() => vi.unstubAllGlobals())

describe('GlobalSearchScreen', () => {
  it('mesaj sonucunu sorgu ve ordinal ile sohbet deep-linkine çevirir', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      ok: true,
      query: 'delil',
      truncated: false,
      hits: [{
        id: 'h1', type: 'message', projectId: 'p1', projectPath: 'C:/dava', projectName: 'Dava',
        backend: 'codex-app', backendLabel: 'Codex', sessionId: 's1', container: 'direct',
        title: 'Delil incelemesi', role: 'agent', snippet: '…delil listesi…', rowId: '',
        matchOrdinal: 2, mtime: 10,
      }],
    }), { status: 200 })))
    const onOpenChat = vi.fn()
    render(<GlobalSearchScreen onOpenProject={vi.fn()} onOpenChat={onOpenChat} />)

    fireEvent.change(screen.getByLabelText('Global ara'), { target: { value: 'delil' } })
    const result = await screen.findByRole('button', { name: /Delil incelemesi/ }, { timeout: 2_000 })
    fireEvent.click(result)

    await waitFor(() => expect(onOpenChat).toHaveBeenCalledOnce())
    expect(onOpenChat.mock.calls[0][0]).toEqual(expect.objectContaining({
      backend: 'codex-app', sessionId: 's1', cwd: 'C:/dava', query: 'delil', matchOrdinal: 2,
    }))
  })
})
