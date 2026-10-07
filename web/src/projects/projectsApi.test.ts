import { afterEach, describe, expect, it, vi } from 'vitest'
import { setActiveToken } from '../lib/api'
import { getProjectDetail, listProjects } from './projectsApi'

afterEach(() => {
  vi.unstubAllGlobals()
  setActiveToken(null)
})

describe('projectsApi', () => {
  it('proje listesini ve kodlanmış detay kimliğini okur', async () => {
    setActiveToken('t')
    const fetchMock = vi.fn(async (path: string) => new Response(JSON.stringify(
      path === '/projects'
        ? { ok: true, projects: [{ id: 'p 1', path: 'C:\\repo' }] }
        : { ok: true, project: { id: 'p 1' }, sessions: [], outputs: [], artifacts: {} },
    ), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    expect(await listProjects()).toHaveLength(1)
    expect((await getProjectDetail('p 1')).project.id).toBe('p 1')
    expect(fetchMock.mock.calls[1][0]).toBe('/projects/detail?id=p%201')
  })
})
