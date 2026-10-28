import { useEffect, useRef, useState } from 'react'
import { api, type Health, type IndexStatus, type WorkspaceInfo } from '../api'
import { coverageDetail, coverageSummary } from '../coverage'

type Props = {
  workspace: WorkspaceInfo | null
  health: Health | null
  reindexSignal: number
  onConfigure: () => void
  onChanged: () => void
  onError: (e: unknown) => void
  onGenerateDoc?: () => void
  generatingDoc?: boolean
  docProgress?: { percent: number; text: string } | null
  docError?: string
  docAvailable?: boolean
}

const STATE_LABEL: Record<IndexStatus['state'], string> = {
  IDLE: 'Sin indexar',
  RUNNING: 'Indexando…',
  DONE: 'Índice al día',
  FAILED: 'Falló la indexación',
  NOT_CONFIGURED: 'Sin repositorio',
}

export function RepoPanel({ workspace, health, reindexSignal, onConfigure, onChanged, onError, onGenerateDoc, generatingDoc = false, docProgress = null, docError = '', docAvailable = false }: Props) {
  const [status, setStatus] = useState<IndexStatus | null>(null)
  const [error, setError] = useState('')
  const wasRunning = useRef(false)
  const repo = workspace?.path ?? null

  useEffect(() => {
    let stop = false
    const tick = async () => {
      try {
        const s = await api.indexStatus()
        if (stop) return
        setStatus(s)
        if (wasRunning.current && s.state !== 'RUNNING') onChanged()
        wasRunning.current = s.state === 'RUNNING'
      } catch (e) {
        onError(e)
      }
    }
    void tick()
    const id = setInterval(() => void tick(), status?.state === 'RUNNING' ? 1000 : 5000)
    return () => {
      stop = true
      clearInterval(id)
    }
  }, [status?.state, repo, reindexSignal, onChanged, onError])

  const start = async () => {
    setError('')
    try {
      setStatus(await api.startIndex())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      onError(e)
    }
  }

  const running = status?.state === 'RUNNING'
  const total = status?.filesTotal ?? 0
  const pct = total > 0 ? Math.round(((status?.filesDone ?? 0) / total) * 100) : 0
  const hasIndex = (health?.index.files ?? 0) > 0
  const label = !repo
    ? 'Ningún repositorio seleccionado'
    : !status
      ? 'Consultando…'
      : status.state === 'IDLE' && hasIndex
        ? 'Índice cargado'
        : STATE_LABEL[status.state]

  return (
    <section className="card">
      <h2>Repositorio</h2>
      {repo && (
        <p className="repo-name" title={repo}>
          <strong>{workspace?.name}</strong>
          <span className="muted path">{repo}</span>
        </p>
      )}
      <p className="muted">{label}</p>
      {running && (
        <>
          <div className="bar"><div style={{ width: `${pct}%` }} /></div>
          <p className="muted">{status?.filesDone}/{total} ficheros</p>
        </>
      )}
      {status?.state === 'DONE' && (
        <>
          <p className="muted">
            {status.filesDone} indexados · {status.filesSkipped} sin cambios · {status.filesFailed} con error
          </p>
          {coverageSummary(status) && (
            <p className="muted" title={coverageDetail(status) || undefined}>
              Fuera del índice: {coverageSummary(status)}
            </p>
          )}
        </>
      )}
      {status?.state === 'FAILED' && status.message && <p className="warn">{status.message}</p>}
      {status?.embeddingsAvailable === false && <p className="warn">Sin embeddings: la búsqueda usa solo palabras clave.</p>}
      {error && <p className="warn">{error}</p>}
      <div className="btn-row">
        <button onClick={onConfigure} disabled={running} title={running ? 'Espere a que termine la indexación' : undefined}>
          Configurar…
        </button>
        {repo && (
          <button className="secondary" onClick={start} disabled={running || !health?.llm.reachable}>
            {hasIndex ? 'Reindexar' : 'Indexar'}
          </button>
        )}
        {repo && hasIndex && (
          <button
            className="doc-btn"
            onClick={onGenerateDoc}
            disabled={running || generatingDoc || !docAvailable || !health?.llm.reachable}
          >
            {generatingDoc ? 'Generando…' : 'Generar documentación'}
          </button>
        )}
      </div>
      {docError && <p className="warn">{docError}</p>}
      {docProgress && (
        <div className="doc-progress" role="status" aria-live="polite">
          <div className="doc-progress-bar" role="progressbar" aria-valuenow={docProgress.percent} aria-valuemin={0} aria-valuemax={100}>
            <div className="doc-progress-fill" style={{ width: `${Math.max(3, docProgress.percent)}%` }} />
          </div>
          <p className="muted">{docProgress.text} · {docProgress.percent}%</p>
          <p className="muted">Puede tardar varios minutos. No cierre esta ventana.</p>
        </div>
      )}
    </section>
  )
}
