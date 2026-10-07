import { describe, expect, it } from 'vitest'
import { filterSessions } from './filterSessions'
import type { Session } from './sessionsApi'

function session(partial: Partial<Session>): Session {
  return {
    id: 'x',
    cwd: '',
    model: 'claude-opus-5',
    status: 'idle',
    title: '',
    lastUserAt: 0,
    turns: 0,
    lastText: '',
    awaitingApproval: false,
    restoredShell: false,
    ...partial,
  }
}

const ids = (list: Session[]) => list.map((s) => s.id)

describe('filterSessions', () => {
  const liste = [
    session({ id: 'gercek', turns: 5, title: 'Proje', lastUserAt: 300 }),
    session({ id: 'bos1' }),
    session({ id: 'bos2' }),
    session({ id: 'calisan', status: 'running', lastUserAt: 200 }),
  ]

  it('0 turlu ve eski oturumlar dahil hepsini gösterir', () => {
    const { visible } = filterSessions(liste)
    expect(visible).toHaveLength(4)
    expect(ids(visible)).toEqual(['gercek', 'calisan', 'bos1', 'bos2'])
  })

  it('lastUserAt azalan sıralanır', () => {
    const { visible } = filterSessions(liste)
    expect(ids(visible)).toEqual(['gercek', 'calisan', 'bos1', 'bos2'])
  })

  it('sabitli oturum daha eski olsa da listenin başına gelir', () => {
    const rows = [
      session({ id: 'yeni', turns: 1, lastUserAt: 500 }),
      session({ id: 'sabit', turns: 1, lastUserAt: 100, pinned: true }),
    ]
    expect(ids(filterSessions(rows).visible)).toEqual(['sabit', 'yeni'])
  })

  it('arama başlık, cwd, son metin ve modelde geçer', () => {
    const arama = [
      session({ id: 'a', turns: 1, title: 'Dilekçe taslağı' }),
      session({ id: 'b', turns: 1, cwd: 'C:/is/proje' }),
      session({ id: 'c', turns: 1, lastText: 'testleri koştur' }),
      session({ id: 'd', turns: 1, model: 'claude-fable-5' }),
    ]
    expect(ids(filterSessions(arama, { query: 'dilekçe' }).visible)).toEqual(['a'])
    expect(ids(filterSessions(arama, { query: 'proje' }).visible)).toEqual(['b'])
    expect(ids(filterSessions(arama, { query: 'testleri' }).visible)).toEqual(['c'])
    expect(ids(filterSessions(arama, { query: 'fable' }).visible)).toEqual(['d'])
  })

  it('arama büyük/küçük harf ayırmaz', () => {
    const arama = [session({ id: 'a', turns: 1, title: 'Dilekçe' })]
    expect(ids(filterSessions(arama, { query: 'DİLEKÇE' }).visible)).toEqual(['a'])
  })

  it('boşlukları kırpılmış arama boş sayılır', () => {
    expect(filterSessions(liste, { query: '   ' }).visible).toHaveLength(4)
  })

  it('boş liste çökmez', () => {
    expect(filterSessions([])).toEqual({ visible: [] })
  })
})
