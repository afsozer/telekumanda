// @vitest-environment happy-dom
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThoughtGroup } from './ThoughtGroup'
import type { StreamRow } from '../../lib/stream/protocol'

function row(rowId: string, text: string): StreamRow {
  return { rowId, role: 'agent', text, time: '', thoughtIndex: 0 }
}

describe('ThoughtGroup', () => {
  it('varsayılan kapalıdır, tıklayınca açılır', () => {
    render(<ThoughtGroup rows={[row('t1', 'gizli düşünce')]} />)
    const toggle = screen.getByRole('button')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('gizli düşünce')).toBeNull()

    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('gizli düşünce')).toBeInTheDocument()
  })

  it('birden çok düşüncede sayıyı gösterir', () => {
    render(<ThoughtGroup rows={[row('t1', 'bir'), row('t2', 'iki'), row('t3', 'üç')]} />)
    expect(screen.getByText('3 düşünce')).toBeInTheDocument()
  })

  it('tek düşüncede sayı yazmaz', () => {
    render(<ThoughtGroup rows={[row('t1', 'bir')]} />)
    expect(screen.getByText('düşünce')).toBeInTheDocument()
  })

  it('açılınca hepsi görünür', () => {
    render(<ThoughtGroup rows={[row('t1', 'bir'), row('t2', 'iki')]} />)
    fireEvent.click(screen.getByRole('button'))
    expect(screen.getByText('bir')).toBeInTheDocument()
    expect(screen.getByText('iki')).toBeInTheDocument()
  })

  it('boş metinli düşünceler sayılmaz', () => {
    render(<ThoughtGroup rows={[row('t1', 'bir'), row('t2', '   ')]} />)
    expect(screen.getByText('düşünce')).toBeInTheDocument()
  })

  it('hepsi boşsa hiç çizilmez', () => {
    const { container } = render(<ThoughtGroup rows={[row('t1', ''), row('t2', '  ')]} />)
    expect(container.firstChild).toBeNull()
  })
})
