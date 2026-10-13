import { useState } from 'react'
import type { Snippet } from '../api'

export function CodeSnippet({ snippet }: { snippet: Snippet }) {
  const [copied, setCopied] = useState(false)
  const lines = snippet.code.split('\n')
  const marked = new Set(snippet.highlight)
  const width = String(snippet.endLine).length

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(snippet.code)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* el portapapeles puede no estar disponible */
    }
  }

  return (
    <figure className="snippet">
      <figcaption>
        <span>
          <code>{snippet.path}</code>
          {snippet.symbol && <strong> · {snippet.symbol}()</strong>}
          <span className="muted"> · líneas {snippet.startLine}-{snippet.endLine}</span>
        </span>
        <button className="link" onClick={() => void copy()}>{copied ? 'Copiado' : 'Copiar'}</button>
      </figcaption>
      <pre>
        {lines.map((text, i) => {
          const n = snippet.startLine + i
          return (
            <div key={n} className={marked.has(n) ? 'hl' : ''}>
              <span className="ln">{String(n).padStart(width, ' ')}</span>
              <span className="mark">{marked.has(n) ? '▶' : ' '}</span>
              {text || ' '}
            </div>
          )
        })}
      </pre>
    </figure>
  )
}
