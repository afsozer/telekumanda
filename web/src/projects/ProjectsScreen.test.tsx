// @vitest-environment happy-dom

import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ProjectsScreen } from './ProjectsScreen'

const project = {
  id: 'p1', path: 'C:/work/repo', name: 'repo', displayName: 'AgentBridge', exists: true,
  sessionCount: 1, runningCount: 1, outputCount: 0, newOutputCount: 0,
  providers: ['codex-app'], pinned: true, lastOpenedAt: '', lastActivityAt: '',
}

afterEach(() => vi.unstubAllGlobals())

describe('ProjectsScreen', () => {
  it('proje oturumunu sohbet hedefi olarak açar', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), 'http://test')
      if (init?.method === 'POST') return new Response(JSON.stringify({ ok: true }), { status: 200 })
      if (url.pathname === '/projects') return new Response(JSON.stringify({ ok: true, projects: [project] }), { status: 200 })
      if (url.pathname === '/projects/detail') return new Response(JSON.stringify({
        ok: true,
        project,
        sessions: [{
          backend: 'codex-app', backendLabel: 'Codex', sessionId: 's1', model: 'gpt-5',
          status: 'running', summary: 'Özet', title: 'Web işini tamamla', nativeSessionId: '',
          mtime: 1, live: true, pinned: false, archived: false,
          container: 'direct', threadId: '',
        }],
        outputs: [], artifacts: {}, security: { profile: 'standard' }, mcpProfile: {},
      }), { status: 200 })
      if (url.pathname === '/cowork/notes') return new Response(JSON.stringify({ ok: true, notes: [] }), { status: 200 })
      if (url.pathname === '/dirs') return new Response(JSON.stringify({ ok: true, base: project.path, parent: '', dirs: [] }), { status: 200 })
      return new Response(JSON.stringify({ ok: true }), { status: 200 })
    }))
    const onOpenChat = vi.fn()

    render(<ProjectsScreen initialProjectId="p1" onOpenChat={onOpenChat} onOpenNotes={vi.fn()} />)
    const session = await screen.findByRole('button', { name: /Web işini tamamla/ })
    fireEvent.click(session)

    expect(onOpenChat).toHaveBeenCalledWith(expect.objectContaining({
      backend: 'codex-app', sessionId: 's1', cwd: 'C:/work/repo', live: true,
    }))
  })
})
