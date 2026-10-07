// @vitest-environment happy-dom
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PlanPanel } from './PlanPanel'
import type { PlanItem } from '../lib/stream/protocol'

const plan: PlanItem[] = [
  { text: 'Dosyaları oku', status: 'completed', itemId: 'i1' },
  { text: 'Testi yaz', status: 'in_progress', itemId: 'i2' },
  { text: 'Derle', status: 'pending', itemId: 'i3' },
]

describe('PlanPanel', () => {
  it('plan ve taslak yokken hiç çizilmez', () => {
    const { container } = render(<PlanPanel />)
    expect(container.firstChild).toBeNull()
  })

  it('boş plan ve boşluktan ibaret taslak da çizilmez', () => {
    const { container } = render(<PlanPanel plan={[]} draft="   " />)
    expect(container.firstChild).toBeNull()
  })

  it('maddeleri ve tamamlanan sayısını gösterir', () => {
    render(<PlanPanel plan={plan} />)
    expect(screen.getByText('Testi yaz')).toBeInTheDocument()
    expect(screen.getByText('1/3')).toBeInTheDocument()
  })

  it('durum etiketleri Türkçeleşir', () => {
    render(<PlanPanel plan={plan} />)
    expect(screen.getByText('bitti')).toBeInTheDocument()
    expect(screen.getByText('sürüyor')).toBeInTheDocument()
    expect(screen.getByText('bekliyor')).toBeInTheDocument()
  })

  it('bilinmeyen durum ham hâliyle gösterilir', () => {
    render(<PlanPanel plan={[{ text: 'x', status: 'blocked' }]} />)
    expect(screen.getByText('blocked')).toBeInTheDocument()
  })

  it('varsayılan AÇIK, katlanabilir', () => {
    render(<PlanPanel plan={plan} />)
    const toggle = screen.getByRole('button')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('Testi yaz')).toBeNull()
  })

  it('maddesiz serbest metin taslağı da gösterilir', () => {
    render(<PlanPanel draft="önce şunu yapacağım" />)
    expect(screen.getByText('önce şunu yapacağım')).toBeInTheDocument()
  })

  it('itemId yoksa da çöker değil', () => {
    render(<PlanPanel plan={[{ text: 'kimliksiz', status: 'pending' }]} />)
    expect(screen.getByText('kimliksiz')).toBeInTheDocument()
  })
})
