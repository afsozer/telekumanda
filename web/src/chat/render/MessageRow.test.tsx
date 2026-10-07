// @vitest-environment happy-dom
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { StreamRow } from '../../lib/stream/protocol'
import { MessageRow } from './MessageRow'

function row(partial: Partial<StreamRow>): StreamRow {
  return { rowId: 'r1', role: 'agent', text: '', time: '', thoughtIndex: -1, ...partial }
}

describe('MessageRow', () => {
  it('kullanıcı satırı: saat ve markdown gövdesi', () => {
    const { container } = render(
      <MessageRow row={row({ role: 'user', text: '**merhaba**', time: '14:05' })} />,
    )
    expect(screen.getByText('14:05')).toBeInTheDocument()
    expect(container.querySelector('strong')).toHaveTextContent('merhaba')
  })

  it('kullanıcı baloncuğunda rol etiketi YOKTUR', () => {
    // Baloncuk sağda ve dolu zeminde; kimin yazdığını konum zaten söylüyor,
    // rozet gürültü olurdu.
    render(<MessageRow row={row({ role: 'user', text: 'selam' })} />)
    expect(screen.queryByText('kullanıcı')).toBeNull()
  })

  it('ajan baloncuğu rol etiketi taşır', () => {
    render(<MessageRow row={row({ role: 'agent', text: 'selam' })} />)
    expect(screen.getByText('ajan')).toBeInTheDocument()
  })

  it('bilinen roller Türkçeleşir', () => {
    render(<MessageRow row={row({ role: 'system', text: 'not' })} />)
    expect(screen.getByText('sistem')).toBeInTheDocument()
  })

  it('bilinmeyen rol ham hâliyle etiketlenir', () => {
    render(<MessageRow row={row({ role: 'hook', text: 'not' })} />)
    expect(screen.getByText('hook')).toBeInTheDocument()
  })

  it('boş metinli satır hiç render edilmez', () => {
    const { container } = render(<MessageRow row={row({ text: '' })} />)
    expect(container.firstChild).toBeNull()
  })

  it('yalnız boşluk içeren satır da render edilmez', () => {
    const { container } = render(<MessageRow row={row({ text: '   ' })} />)
    expect(container.firstChild).toBeNull()
  })
})

describe('MessageRow — mesaj aksiyonları', () => {
  it('onFork/onRewind verilince kullanıcı satırında düğmeler çizilir', () => {
    render(
      <MessageRow
        row={row({ role: 'user', text: 'selam' })}
        onFork={() => {}}
        onRewind={() => {}}
      />,
    )
    expect(screen.getByRole('button', { name: 'Çatalla' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Geri sar' })).toBeInTheDocument()
  })

  it('aksiyonlar ajan satırında çizilmez — dallanma noktası kullanıcının sorusudur', () => {
    render(
      <MessageRow row={row({ role: 'agent', text: 'selam' })} onFork={() => {}} onRewind={() => {}} />,
    )
    expect(screen.queryByRole('button', { name: 'Çatalla' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Geri sar' })).toBeNull()
  })

  it('props verilmezse aksiyon düğmesi çizilmez', () => {
    render(<MessageRow row={row({ role: 'user', text: 'selam' })} />)
    expect(screen.queryByRole('button', { name: 'Çatalla' })).toBeNull()
  })

  it('Çatalla tıklanınca onFork çağrılır', async () => {
    const { fireEvent } = await import('@testing-library/react')
    const onFork = vi.fn()
    render(<MessageRow row={row({ role: 'user', text: 'selam' })} onFork={onFork} />)
    fireEvent.click(screen.getByRole('button', { name: 'Çatalla' }))
    expect(onFork).toHaveBeenCalledTimes(1)
  })

  it('actionError yalnız kendi satırında görünür', () => {
    render(
      <MessageRow
        row={row({ role: 'user', text: 'selam' })}
        onFork={() => {}}
        actionError="tur sürerken çatallanamaz"
      />,
    )
    expect(screen.getByText('tur sürerken çatallanamaz')).toBeInTheDocument()
  })
})
