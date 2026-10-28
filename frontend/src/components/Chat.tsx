import { useEffect, useRef, useState } from 'react'
import { api, type Answer, type ChatTurn, type JobSnapshot } from '../api'
import { AnswerView } from './AnswerView'

const formatElapsed = (s: number) => (s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${s % 60} s`)

type Mode = 'explain' | 'diagnose' | 'code'
type Msg =
  | { role: 'user'; mode: Mode; text: string; path?: string }
  | { role: 'assistant'; answer: Answer }
  | { role: 'error'; text: string }

const EMPTY: Record<Mode, string> = {
  explain: 'Pregunte cómo funciona algo del código o indique un fichero para que se lo explique.',
  diagnose: 'Pegue un error o stack trace y LOCALIS buscará la causa probable en el código indexado.',
  code: 'Pida código nuevo, algoritmos, funciones o ayuda para programar. Aquí el modelo responde directamente, sin limitarse al repositorio indexado. La conversación se recuerda hasta que pulse "Nueva conversación".',
}
const PLACEHOLDER: Record<Mode, string> = {
  explain: 'Escriba su pregunta…',
  diagnose: 'Pegue aquí el error o stack trace…',
  code: 'Describa qué necesita programar…',
}

type Props = {
  onError: (e: unknown) => void
  indexedFiles: number
  llmReady: boolean
  path: string
  onPathChange: (p: string) => void
}

export function Chat({ onError, indexedFiles, llmReady, path, onPathChange }: Props) {
  const [mode, setMode] = useState<Mode>('explain')
  const [text, setText] = useState('')
  const [all, setAll] = useState<Record<Mode, Msg[]>>({ explain: [], diagnose: [], code: [] })
  const [busy, setBusy] = useState(false)
  const [snap, setSnap] = useState<JobSnapshot | null>(null)
  const abort = useRef<AbortController | null>(null)
  const end = useRef<HTMLDivElement>(null)
  const msgs = all[mode]
  const add = (m: Mode, msg: Msg) => setAll((a) => ({ ...a, [m]: [...a[m], msg] }))

  useEffect(() => {
    end.current?.scrollIntoView({ behavior: 'smooth' })
  }, [msgs, busy])

  useEffect(() => {
    if (path) setMode('explain')
  }, [path])

  // Si se cierra la pestaña o se desmonta el chat, no se deja al modelo trabajando para nadie.
  useEffect(() => () => abort.current?.abort(), [])

  // La pestaña Programar no usa el repositorio, así que no exige indexarlo.
  const blocked = !llmReady
    ? 'Ollama no está disponible.'
    : mode !== 'code' && indexedFiles === 0
      ? 'Indexe el repositorio para poder preguntar.'
      : ''

  const send = async () => {
    const q = text.trim()
    if (busy || blocked || (!q && !(mode === 'explain' && path.trim()))) return
    const ctl = new AbortController()
    abort.current = ctl
    const sentMode = mode
    // Programar es una conversación: se envía el historial para que el modelo recuerde lo anterior
    const history: ChatTurn[] = all.code.flatMap((m): ChatTurn[] =>
      m.role === 'user' ? [{ role: 'user', content: m.text }] : m.role === 'assistant' ? [{ role: 'assistant', content: m.answer.answer }] : [],
    )
    add(sentMode, { role: 'user', mode: sentMode, text: q, path: sentMode === 'explain' ? path.trim() : undefined })
    setText('')
    setSnap(null)
    setBusy(true)
    try {
      const answer = await api.runJob(
        sentMode === 'explain'
          ? { type: 'explain', path: path.trim() || undefined, question: q || undefined }
          : sentMode === 'diagnose'
            ? { type: 'diagnose', problem: q }
            : { type: 'code', messages: [...history, { role: 'user', content: q }] },
        setSnap,
        ctl.signal,
      )
      add(sentMode, { role: 'assistant', answer })
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        add(sentMode, { role: 'error', text: 'Consulta cancelada.' })
      } else {
        add(sentMode, { role: 'error', text: e instanceof Error ? e.message : String(e) })
        onError(e)
      }
    } finally {
      setBusy(false)
      setSnap(null)
      abort.current = null
    }
  }

  return (
    <section className="chat">
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={mode === 'explain'} onClick={() => setMode('explain')}>Explicar / preguntar</button>
        <button role="tab" aria-selected={mode === 'diagnose'} onClick={() => setMode('diagnose')}>Diagnosticar error</button>
        <button role="tab" aria-selected={mode === 'code'} onClick={() => setMode('code')}>Programar</button>
      </div>
      <div className="messages">
        {msgs.length === 0 && <p className="muted empty">{EMPTY[mode]}</p>}
        {msgs.map((m, i) =>
          m.role === 'user' ? (
            <div key={i} className="msg user">
              {m.path && <code>{m.path}</code>}
              <pre>{m.text}</pre>
            </div>
          ) : m.role === 'assistant' ? (
            <div key={i} className="msg bot"><AnswerView answer={m.answer} /></div>
          ) : (
            <div key={i} className="msg err">{m.text}</div>
          ),
        )}
        {busy && (
          <div className="msg bot">
            <span className="spinner" /> {snap?.progress ?? 'Iniciando'}…
            {snap ? <span className="muted"> · {formatElapsed(snap.elapsedSeconds)}</span> : null}{' '}
            <button className="link" onClick={() => abort.current?.abort()}>Cancelar</button>
            {snap && snap.elapsedSeconds > 120 && (
              <p className="muted slow">
                Está tardando: el modelo local es lento en este equipo. Puede cancelar sin problema; al hacerlo, el
                modelo deja de trabajar.
              </p>
            )}
          </div>
        )}
        <div ref={end} />
      </div>
      {blocked && <p className="warn">{blocked}</p>}
      <form
        className="composer"
        onSubmit={(e) => {
          e.preventDefault()
          void send()
        }}
      >
        {mode === 'explain' && (
          <input
            placeholder="Fichero (opcional), p. ej. src/main/java/Foo.java"
            value={path}
            onChange={(e) => onPathChange(e.target.value)}
          />
        )}
        <textarea
          rows={3}
          placeholder={PLACEHOLDER[mode]}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              void send()
            }
          }}
        />
        <div className="composer-actions">
          {mode === 'code' && msgs.length > 0 && (
            <button type="button" className="secondary" disabled={busy} onClick={() => setAll((a) => ({ ...a, code: [] }))}>
              Nueva conversación
            </button>
          )}
          <button type="submit" disabled={busy || !!blocked}>Enviar</button>
        </div>
      </form>
    </section>
  )
}
