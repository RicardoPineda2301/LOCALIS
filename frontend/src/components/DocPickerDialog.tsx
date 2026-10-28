import { useEffect, useRef, useState } from 'react'

const label = (t: string) => t.replace(/_Template$/, '').replace(/_/g, ' ')

type Props = {
  templates: string[]
  onConfirm: (selected: string[]) => void
  onCancel: () => void
}

export function DocPickerDialog({ templates, onConfirm, onCancel }: Props) {
  const [selected, setSelected] = useState<string[]>([])
  const allRef = useRef<HTMLInputElement>(null)
  const all = templates.length > 0 && selected.length === templates.length

  useEffect(() => {
    if (allRef.current) allRef.current.indeterminate = selected.length > 0 && !all
  }, [selected, all])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onCancel()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onCancel])

  const toggle = (t: string, on: boolean) => setSelected((s) => (on ? [...s, t] : s.filter((x) => x !== t)))

  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onCancel()}>
      <div className="dialog doc-dialog" role="dialog" aria-modal="true" aria-labelledby="doc-dialog-title">
        <h2 id="doc-dialog-title">Generar documentación</h2>
        <p className="muted">Elija los documentos que desea generar a partir del repositorio.</p>
        <div className="doc-list">
          <label className="doc-check doc-check-all">
            <input
              ref={allRef}
              type="checkbox"
              checked={all}
              onChange={(e) => setSelected(e.target.checked ? [...templates] : [])}
            />
            Seleccionar todos
          </label>
          {templates.map((t) => (
            <label key={t} className="doc-check">
              <input type="checkbox" checked={selected.includes(t)} onChange={(e) => toggle(t, e.target.checked)} />
              {label(t)}
            </label>
          ))}
        </div>
        <p className="muted">
          {selected.length === 0
            ? 'Ningún documento seleccionado'
            : selected.length === 1
              ? 'Se descargará un archivo .docx'
              : `Se descargará un .zip con ${selected.length} documentos`}
        </p>
        <div className="btn-row">
          <button className="secondary" onClick={onCancel}>
            Cancelar
          </button>
          <button className="doc-btn" disabled={selected.length === 0} onClick={() => onConfirm(selected)}>
            Generar{selected.length > 0 ? ` (${selected.length})` : ''}
          </button>
        </div>
      </div>
    </div>
  )
}
