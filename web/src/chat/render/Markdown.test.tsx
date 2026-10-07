// @vitest-environment happy-dom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Markdown } from './Markdown'

describe('Markdown', () => {
  it('başlıkları doğru düzeyde render eder', () => {
    render(<Markdown text={'# Büyük başlık\n\n## Orta başlık'} />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Büyük başlık')
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Orta başlık')
  })

  it('kalın ve satır içi kodu render eder', () => {
    const { container } = render(<Markdown text={'**kalın** ve `küçük kod`'} />)
    expect(container.querySelector('strong')).toHaveTextContent('kalın')
    const inline = container.querySelector('code')
    expect(inline).not.toBeNull()
    expect(inline).toHaveTextContent('küçük kod')
  })

  it('çitli kod bloğunu CodeBlock bileşenine yönlendirir', () => {
    render(<Markdown text={'```ts\nconst x = 1\n```'} />)
    expect(screen.getByText('ts')).toBeInTheDocument()
    expect(screen.getByText('const x = 1')).toBeInTheDocument()
  })

  it('dilsiz kod bloğu da CodeBlock bileşenine gider', () => {
    render(<Markdown text={'```\nblok içeriği\n```'} />)
    expect(screen.getByText('kod')).toBeInTheDocument()
    expect(screen.getByText('blok içeriği')).toBeInTheDocument()
  })

  it('listeyi render eder', () => {
    render(<Markdown text={'- birinci\n- ikinci'} />)
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
  })

  it('bağlantıyı yeni sekmede açılacak şekilde render eder', () => {
    render(<Markdown text={'[örnek site](https://example.com)'} />)
    const link = screen.getByRole('link', { name: 'örnek site' })
    expect(link).toHaveAttribute('href', 'https://example.com')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('GFM tablosunu render eder', () => {
    const { container } = render(<Markdown text={'| A | B |\n|---|---|\n| 1 | 2 |'} />)
    const table = screen.getByRole('table')
    expect(table).toBeInTheDocument()
    expect(screen.getAllByRole('row')).toHaveLength(2)
    expect(container.querySelector('th')).toHaveTextContent('A')
  })

  it('ham HTML düğüm olarak eklenmez, metin olarak görünür', () => {
    const { container } = render(
      <Markdown text={'<script>alert(1)</script>\n\n<img src="x" onerror="alert(1)">'} />,
    )
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toContain('<script>alert(1)</script>')
    expect(container.textContent).toContain('<img')
    expect(container.textContent).toContain('onerror')
  })
})
