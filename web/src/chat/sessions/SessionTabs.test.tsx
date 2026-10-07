// @vitest-environment happy-dom

import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SessionTabs, type SessionTab } from './SessionTabs'

const TABS: SessionTab[] = [
  { key: 'codex-app:1', backend: 'codex-app', backendLabel: 'Codex', sessionId: '1', cwd: 'C:\\work\\repo', title: 'İlk iş' },
  { key: 'claude-app:2', backend: 'claude-app', backendLabel: 'Claude', sessionId: '2', cwd: 'C:\\other', title: 'İkinci iş', status: 'running' },
]

describe('SessionTabs', () => {
  it('oturumları seçer, kapatır ve yeni sekme açar', () => {
    const onSelect = vi.fn()
    const onClose = vi.fn()
    const onNew = vi.fn()
    render(<SessionTabs tabs={TABS} activeKey={TABS[0].key} onSelect={onSelect} onClose={onClose} onNew={onNew} />)

    expect(screen.getByRole('tab', { name: /İlk iş/ })).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(screen.getByRole('tab', { name: /İkinci iş/ }))
    fireEvent.click(screen.getByRole('button', { name: /İkinci iş sekmesini kapat/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Yeni oturum' }))

    expect(onSelect).toHaveBeenCalledWith('claude-app:2')
    expect(onClose).toHaveBeenCalledWith('claude-app:2')
    expect(onNew).toHaveBeenCalledOnce()
  })

  it('sekme üzerinde mouse orta click (button 1) yapılınca sekmeyi kapatır', () => {
    const onClose = vi.fn()
    render(<SessionTabs tabs={TABS} activeKey={TABS[0].key} onSelect={vi.fn()} onClose={onClose} onNew={vi.fn()} />)

    fireEvent(screen.getByRole('tab', { name: /İkinci iş/ }), new MouseEvent('auxclick', { button: 1, bubbles: true }))
    expect(onClose).toHaveBeenCalledWith('claude-app:2')
  })
})
