// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionList, sessionRepoName } from './SessionList'
import type { Session } from './sessionsApi'

function session(overrides: Partial<Session>): Session {
  return {
    id: 's1',
    cwd: 'C:\\projeler\\x',
    model: 'sonnet',
    status: 'idle',
    title: '',
    lastUserAt: 0,
    turns: 3,
    lastText: 'son mesaj',
    awaitingApproval: false,
    restoredShell: false,
    ...overrides,
  }
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('SessionList', () => {
  it('boş listede boş durum metni gösterir', () => {
    render(<SessionList sessions={[]} onSelect={vi.fn()} />)
    expect(screen.getByText('Oturum yok.')).toBeTruthy()
  })

  it('lastUserAt azalan sırada listeler — yeni oturum üstte', () => {
    const eski = session({ id: 'eski', lastUserAt: 100, title: 'Eski oturum' })
    const yeni = session({ id: 'yeni', lastUserAt: 200, title: 'Yeni oturum' })
    render(<SessionList sessions={[eski, yeni]} onSelect={vi.fn()} />)

    const buttons = [...document.querySelectorAll<HTMLButtonElement>('[data-session-row="true"]')]
    expect(buttons).toHaveLength(2)
    expect(buttons[0].textContent).toContain('Yeni oturum')
    expect(buttons[1].textContent).toContain('Eski oturum')
  })

  it('0 turlu eski oturumları gizlemez', () => {
    render(
      <SessionList
        sessions={[session({ id: 'eski-bos', title: 'Eski kayıt', turns: 0, lastUserAt: 1 })]}
        onSelect={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: /Eski kayıt/ })).toBeTruthy()
    expect(screen.queryByText(/gizli/)).toBeNull()
  })

  it('ilk 40 oturumu çizer, devamını kademeli gösterir', () => {
    const sessions = Array.from({ length: 45 }, (_, index) => session({
      id: `s-${index}`,
      title: `Oturum ${index}`,
      lastUserAt: 1000 - index,
    }))
    render(<SessionList sessions={sessions} onSelect={vi.fn()} />)

    expect(document.querySelectorAll('[data-session-row="true"]')).toHaveLength(40)
    fireEvent.click(screen.getByRole('button', { name: '5 oturum daha — devamını göster' }))
    expect(document.querySelectorAll('[data-session-row="true"]')).toHaveLength(45)
  })

  it('listenin sonu görünür olunca sonraki dilimi otomatik çizer', () => {
    let intersect: IntersectionObserverCallback | null = null
    class MockIntersectionObserver {
      constructor(callback: IntersectionObserverCallback) { intersect = callback }
      observe() {}
      disconnect() {}
      unobserve() {}
      takeRecords() { return [] }
    }
    vi.stubGlobal('IntersectionObserver', MockIntersectionObserver)
    const sessions = Array.from({ length: 45 }, (_, index) => session({
      id: `auto-${index}`,
      title: `Otomatik ${index}`,
      lastUserAt: 1000 - index,
    }))
    render(<SessionList sessions={sessions} onSelect={vi.fn()} />)

    expect(document.querySelectorAll('[data-session-row="true"]')).toHaveLength(40)
    act(() => intersect?.([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver))
    expect(document.querySelectorAll('[data-session-row="true"]')).toHaveLength(45)
  })

  it('onay bekleyen oturumu belirgin biçimde işaretler', () => {
    render(
      <SessionList
        sessions={[session({ awaitingApproval: true, title: 'Onay bekleyen' })]}
        onSelect={vi.fn()}
      />,
    )
    expect(screen.getByText('onay bekliyor')).toBeTruthy()
  })

  it('status running ise çalışıyor işareti gösterir', () => {
    render(
      <SessionList
        sessions={[session({ status: 'running', title: 'Koşan oturum' })]}
        onSelect={vi.fn()}
      />,
    )
    expect(screen.getByText('● çalışıyor')).toBeTruthy()
  })

  it('tıklama onSelecti doğru oturum id ile çağırır', () => {
    const onSelect = vi.fn()
    render(
      <SessionList
        sessions={[session({ id: 's7', title: 'Yedi numara' }), session({ id: 's8', title: 'Sekiz' })]}
        onSelect={onSelect}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: /Yedi numara/ }))

    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 's7' }))
  })

  it('seçili oturum vurgulanır (aria-current)', () => {
    render(
      <SessionList
        sessions={[session({ id: 's9', title: 'Dokuz' }), session({ id: 's10', title: 'On' })]}
        selectedId="s9"
        onSelect={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: /Dokuz/ }).getAttribute('aria-current')).toBe('true')
    expect(screen.getByRole('button', { name: /On/ }).getAttribute('aria-current')).toBeNull()
  })

  it('başlık yoksa cwd, o da yoksa id gösterilir', () => {
    render(
      <SessionList
        sessions={[
          session({ title: '', cwd: 'C:\\bos-baslik', lastText: '' }),
          session({ id: 'cidsiz', title: '', cwd: '', lastText: '' }),
        ]}
        onSelect={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: /C:\\bos-baslik/ })).toBeTruthy()
    expect([...document.querySelectorAll<HTMLButtonElement>('[data-session-row="true"]')]
      .some((button) => button.textContent?.includes('cidsiz'))).toBe(true)
  })

  it('başlığın önünde çalışma klasörünün repo adını gösterir', () => {
    render(<SessionList sessions={[session({ title: 'İş', cwd: 'C:\\Users\\Dev\\agtest' })]} onSelect={vi.fn()} />)
    expect(screen.getByText('agtest').getAttribute('title')).toBe('Repo: C:\\Users\\Dev\\agtest')
    expect(sessionRepoName('/home/user/proje/')).toBe('proje')
  })

  it('sağ tık menüsünden oturumu sabitler', async () => {
    const onAction = vi.fn().mockResolvedValue(undefined)
    render(
      <SessionList
        sessions={[session({ id: 'pin-1', title: 'Sabitlenecek', turns: 2 })]}
        onSelect={vi.fn()}
        actionSupport={() => ({ pin: true, archive: true, rename: true, delete: true })}
        onAction={onAction}
      />,
    )

    fireEvent.contextMenu(screen.getByRole('button', { name: /Sabitlenecek/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sabitle' }))

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'pin-1' }),
      'pin',
      undefined,
    ))
  })

  it('üç nokta menüsünden session kimliğini panoya kopyalar', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    render(<SessionList sessions={[session({ id: 'copy-42', title: 'Kopyala', turns: 1 })]} onSelect={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: 'Oturum işlemleri: copy-42' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Kimliği kopyala' }))

    await waitFor(() => expect(writeText).toHaveBeenCalledWith('copy-42'))
    expect(await screen.findByRole('status')).toHaveTextContent('Oturum kimliği kopyalandı')
  })

  it('silmeden önce kalıcı işlem onayı ister', async () => {
    const onAction = vi.fn().mockResolvedValue(undefined)
    render(
      <SessionList
        sessions={[session({ id: 'del-1', title: 'Silinecek', turns: 1 })]}
        onSelect={vi.fn()}
        actionSupport={() => ({ pin: false, archive: false, rename: false, delete: true })}
        onAction={onAction}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Oturum işlemleri: del-1' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sil' }))
    expect(await screen.findByRole('alertdialog')).toHaveTextContent('Bu işlem geri alınamaz')
    fireEvent.click(screen.getByRole('button', { name: 'Sil' }))

    await waitFor(() => expect(onAction).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'del-1' }),
      'delete',
      undefined,
    ))
  })

  it('oturum satırına orta tık (button 1) yapılınca onSelectNewTab çağrılır', () => {
    const onSelectNewTab = vi.fn()
    render(
      <SessionList
        sessions={[session({ id: 's-mid', title: 'Orta tık oturumu' })]}
        onSelect={vi.fn()}
        onSelectNewTab={onSelectNewTab}
      />,
    )

    fireEvent(
      screen.getByRole('button', { name: /Orta tık oturumu/ }),
      new MouseEvent('auxclick', { button: 1, bubbles: true }),
    )
    expect(onSelectNewTab).toHaveBeenCalledWith(expect.objectContaining({ id: 's-mid' }))
  })

  it('sağ tık menüsünde "Yeni sekmede aç" seçeneği oturumu yeni sekmede açar', async () => {
    const onSelectNewTab = vi.fn()
    render(
      <SessionList
        sessions={[session({ id: 's-right', title: 'Sağ tık oturumu' })]}
        onSelect={vi.fn()}
        onSelectNewTab={onSelectNewTab}
      />,
    )

    fireEvent.contextMenu(screen.getByRole('button', { name: /Sağ tık oturumu/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Yeni sekmede aç' }))

    expect(onSelectNewTab).toHaveBeenCalledWith(expect.objectContaining({ id: 's-right' }))
  })
})
