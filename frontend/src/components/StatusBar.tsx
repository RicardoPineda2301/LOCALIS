import type { Health } from '../api'

function Pill({ ok, label, title }: { ok: boolean; label: string; title?: string }) {
  return (
    <span className={`pill ${ok ? 'ok' : 'bad'}`} title={title}>
      <span className="dot" /> {label}
    </span>
  )
}

export function StatusBar({ health, offline }: { health: Health | null; offline: boolean }) {
  if (offline) return <div className="status"><Pill ok={false} label="Servidor LOCALIS no responde" /></div>
  if (!health) return <div className="status">Conectando…</div>
  const { llm, index, license } = health
  const modelsOk = llm.chatModelInstalled && llm.embedModelInstalled
  const missing = [!llm.chatModelInstalled && llm.chatModel, !llm.embedModelInstalled && llm.embedModel]
    .filter(Boolean)
    .join(', ')
  return (
    <div className="status">
      <Pill ok={llm.reachable} label={llm.reachable ? 'Ollama conectado' : 'Ollama no disponible'} title={llm.detail} />
      {llm.reachable && (
        <Pill ok={modelsOk} label={modelsOk ? 'Modelos listos' : `Faltan modelos: ${missing}`} title={missing && `ollama pull ${missing}`} />
      )}
      <Pill ok={index.files > 0} label={`${index.files} ficheros · ${index.chunks} fragmentos`} />
      <Pill
        ok
        label={license.enforced ? `Licencia: ${license.customer ?? 'activa'}` : 'Modo desarrollo (sin licencia)'}
        title={license.expiresAt ? `Caduca: ${license.expiresAt}` : undefined}
      />
    </div>
  )
}
