import type { Components } from 'react-markdown'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { CodeBlock } from './CodeBlock'
import styles from './Markdown.module.css'

const remarkPlugins = [remarkGfm]

/**
 * react-markdown her bileşene iç `node` detayını ekler. Bu yardımcı onu
 * ayıklar ki DOM'a geçmesin; kalan özellikler olduğu gibi aktarılır.
 */
function dropNode<P extends { node?: unknown }>(props: P): Omit<P, 'node'> {
  const { node: _node, ...rest } = props
  return rest
}

// react-markdown'a ham HTML desteği KASTEN eklenmiyor (rehype-raw yok):
// köprüden gelen metin ajan çıktısıdır, HTML'i kaçıp metin olarak gösterir —
// `<script>` DOM'a düğüm olarak asla giremez.
const components: Components = {
  a: (props) => (
    <a className={styles.link} target="_blank" rel="noopener noreferrer" {...dropNode(props)} />
  ),
  // Görsel kendiliğinden yüklenmez: ajan çıktısındaki `![](https://…?veri)` tıklama
  // olmadan istek atıp sohbetteki veriyi dışarı taşıyabilirdi. Bağlantı olarak gösterilir.
  img: ({ src, alt }) =>
    typeof src === 'string' && src ? (
      <a className={styles.link} href={src} target="_blank" rel="noopener noreferrer">
        {alt || src}
      </a>
    ) : null,
  p: (props) => <p className={styles.p} {...dropNode(props)} />,
  h1: (props) => <h1 className={styles.h1} {...dropNode(props)} />,
  h2: (props) => <h2 className={styles.h2} {...dropNode(props)} />,
  h3: (props) => <h3 className={styles.h3} {...dropNode(props)} />,
  ul: (props) => <ul className={styles.ul} {...dropNode(props)} />,
  ol: (props) => <ol className={styles.ol} {...dropNode(props)} />,
  li: (props) => <li className={styles.li} {...dropNode(props)} />,
  blockquote: (props) => <blockquote className={styles.blockquote} {...dropNode(props)} />,
  hr: (props) => <hr className={styles.hr} {...dropNode(props)} />,
  table: (props) => (
    <div className={styles.tableWrap}>
      <table className={styles.table} {...dropNode(props)} />
    </div>
  ),
  th: (props) => <th className={styles.th} {...dropNode(props)} />,
  td: (props) => <td className={styles.td} {...dropNode(props)} />,
  // Blok kod CodeBlock'a gider; react-markdown'ın pre sarmalayıcısı
  // CodeBlock'un kendi kutusunu bozar, o yüzden sadece çocukları bırakılır.
  pre: (props) => <>{dropNode(props).children}</>,
  code: ({ node, className, children, ...props }) => {
    // Dil sınıfı ya da birden çok satıra yayılan konum → blok kod.
    // (Çitsiz blokların dili yoktur; konum kontrolü onları da yakalar.)
    const language = /^language-(\w+)/.exec(className ?? '')?.[1]
    const isBlock =
      language !== undefined ||
      (node?.position != null && node.position.start.line !== node.position.end.line)
    if (isBlock) {
      return <CodeBlock code={String(children).replace(/\n$/, '')} language={language} />
    }
    return (
      <code className={styles.inlineCode} {...props}>
        {children}
      </code>
    )
  },
}

/** Köprüden gelen markdown metnini güvenli biçimde React ağacına çevirir. */
export function Markdown({ text }: { text: string }) {
  return (
    <div className={styles.root}>
      <ReactMarkdown remarkPlugins={remarkPlugins} components={components}>
        {text}
      </ReactMarkdown>
    </div>
  )
}
