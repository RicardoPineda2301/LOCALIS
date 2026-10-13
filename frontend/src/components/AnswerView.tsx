import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { Answer } from '../api'
import { CITATION_LABEL, citationText } from '../citations'
import { CodeSnippet } from './CodeSnippet'

export function AnswerView({ answer }: { answer: Answer }) {
  return (
    <div className="answer">
      <div className="markdown">
        <Markdown remarkPlugins={[remarkGfm]}>{answer.answer}</Markdown>
      </div>
      {answer.snippets?.length > 0 && (
        <section className="involved">
          <h4>Código implicado</h4>
          {answer.snippets.map((s) => (
            <CodeSnippet key={`${s.path}:${s.startLine}`} snippet={s} />
          ))}
        </section>
      )}
      {answer.citations.length > 0 && (
        <div className="chips">
          {answer.citations.map((c, i) => (
            <span key={i} className={`chip ${c.status === 'VERIFIED' ? 'ok' : 'bad'}`} title={CITATION_LABEL[c.status]}>
              {c.status === 'VERIFIED' ? '✓' : '⚠'} {citationText(c)}
            </span>
          ))}
        </div>
      )}
      {answer.sources.length > 0 && (
        <details>
          <summary>Fuentes consultadas ({answer.sources.length})</summary>
          <ul>
            {answer.sources.map((s, i) => (
              <li key={i}>
                <code>{citationText(s)}</code> {s.symbol && <span className="muted">· {s.symbol}</span>}
              </li>
            ))}
          </ul>
        </details>
      )}
      {answer.warnings.map((w, i) => (
        <p key={i} className="warn">{w}</p>
      ))}
    </div>
  )
}
