// @vitest-environment happy-dom
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CodeBlock } from './CodeBlock'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('CodeBlock', () => {
  it('dil etiketini üst çubukta gösterir', () => {
    render(<CodeBlock code="const x = 1" language="ts" />)
    expect(screen.getByText('ts')).toBeInTheDocument()
    expect(screen.getByText('const x = 1')).toBeInTheDocument()
  })

  it('dil yoksa varsayılan "kod" etiketini gösterir', () => {
    render(<CodeBlock code="x" />)
    expect(screen.getByText('kod')).toBeInTheDocument()
  })

  it('pano API yoksa kopyala düğmesini gizler — çökmez', () => {
    vi.stubGlobal('navigator', {})
    render(<CodeBlock code="x" />)
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('kopyalayınca kodu panoya yazar ve kısa süre "Kopyalandı" der', async () => {
    vi.useFakeTimers()
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })

    render(<CodeBlock code="const x = 1" />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Kopyala' }))
    })

    expect(writeText).toHaveBeenCalledWith('const x = 1')
    expect(screen.getByText('Kopyalandı')).toBeInTheDocument()

    await act(async () => {
      vi.advanceTimersByTime(2000)
    })
    expect(screen.getByText('Kopyala')).toBeInTheDocument()
  })
})
