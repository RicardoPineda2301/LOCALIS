import { useEffect, useRef, useState } from 'react'
import { api, type Answer, type JobSnapshot } from '../api'
import { AnswerView } from './AnswerView'

const formatElapsed = (s: number) => (s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${s % 60} s`)

type Mode = 'explain' | 'diagnose'
type Msg =
  | { role: 'user'; mode: Mode; text: string; path?: string }
  | { role: 'assistant'; answer: Answer }
  | { role: 'error'; text: string }

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
  const [msgs, setMsgs] = useState<Msg[]>([])
  const [busy, setBusy] = useState(false)
  const [snap, setSnap] = useState<JobSnapshot | null>(null)
  const abort = useRef<AbortController | null>(null)
  const end = useRef<HTMLDivElement>(null)

  useEffect(() => {
    end.current?.scrollIntoView({ behavior: 'smooth' })
  }, [msgs, busy])

  useEffect(() => {
    if (path) setMode('explain')
  }, [path])

  // Si se cierra la pestaña o se desmonta el chat, no se deja al modelo trabajando para nadie.
  useEffect(() => () => abort.current?.abort(), [])

  const blocked = !llmReady ? 'Ollama no está disponible.' : indexedFiles === 0 ? 'Indexe el repositorio para poder preguntar.' : ''

  const send = async () => {
    const q = text.trim()
    if (busy || blocked || (!q && !(mode === 'explain' && path.trim()))) return
    const ctl = new AbortController()
    abort.current = ctl
    setMsgs((m) => [...m, { role: 'user', mode, text: q, path: mode === 'explain' ? path.trim() : undefined }])
    setText('')
    setSnap(null)
    setBusy(true)
    try {
      const answer = await api.runJob(
        mode === 'explain'
          ? { type: 'explain', path: path.trim() || undefined, question: q || undefined }
          : { type: 'diagnose', problem: q },
        setSnap,
        ctl.signal,
      )
      setMsgs((m) => [...m, { role: 'assistant', answer }])
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        setMsgs((m) => [...m, { role: 'error', text: 'Consulta cancelada.' }])
      } else {
        setMsgs((m) => [...m, { role: 'error', text: e instanceof Error ? e.message : String(e) }])
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
      </div>
      <div className="messages">
        {msgs.length === 0 && (
          <p className="muted empty">
            {mode === 'explain'
              ? 'Pregunte cómo funciona algo del código o indique un fichero para que se lo explique.'
              : 'Pegue un error o stack trace y LOCALIS buscará la causa probable en el código indexado.'}
          </p>
        )}
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
          placeholder={mode === 'explain' ? 'Escriba su pregunta…' : 'Pegue aquí el error o stack trace…'}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              void send()
            }
          }}
        />
        <button type="submit" disabled={busy || !!blocked}>Enviar</button>
      </form>
    </section>
  )
}
