import { useCallback, useEffect, useState } from 'react'
import { api, type TreeEntry } from '../api'

type Props = {
  rootName: string
  selected: string
  onSelect: (path: string) => void
  onError: (e: unknown) => void
}

export function FileTree({ rootName, selected, onSelect, onError }: Props) {
  const [children, setChildren] = useState<Record<string, TreeEntry[]>>({})
  const [open, setOpen] = useState<Set<string>>(new Set(['']))
  const [error, setError] = useState('')

  const load = useCallback(
    async (dir: string) => {
      try {
        const entries = await api.tree(dir)
        setChildren((c) => ({ ...c, [dir]: entries }))
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e))
        onError(e)
      }
    },
    [onError],
  )

  useEffect(() => {
    void load('')
  }, [load])

  const toggle = (dir: string) => {
    const next = new Set(open)
    if (next.has(dir)) next.delete(dir)
    else {
      next.add(dir)
      if (!children[dir]) void load(dir)
    }
    setOpen(next)
  }

  const render = (dir: string, depth: number) =>
    (children[dir] ?? []).map((e) => {
      const pad = { paddingLeft: 8 + depth * 14 }
      if (e.directory) {
        const isOpen = open.has(e.path)
        return (
          <li key={e.path}>
            <button className="row" style={pad} onClick={() => toggle(e.path)} aria-expanded={isOpen}>
              <span className="chev">{isOpen ? '▾' : '▸'}</span> {e.name}
            </button>
            {isOpen && <ul>{children[e.path] ? render(e.path, depth + 1) : <li className="muted" style={pad}>Cargando…</li>}</ul>}
          </li>
        )
      }
      return (
        <li key={e.path}>
          <button
            className={`row file ${e.indexable ? '' : 'dim'} ${selected === e.path ? 'sel' : ''}`}
            style={pad}
            disabled={!e.indexable}
            title={e.indexable ? 'Clic para explicar este archivo' : 'No se indexa (tipo no soportado o archivo vacío)'}
            onClick={() => onSelect(e.path)}
          >
            <span className="chev" /> {e.name}
          </button>
        </li>
      )
    })

  return (
    <section className="card tree">
      <h2 title={rootName}>{rootName.toUpperCase()}</h2>
      {error && <p className="warn">{error}</p>}
      <ul className="tree-list">{render('', 0)}</ul>
      {children[''] && children[''].length === 0 && <p className="muted">La carpeta está vacía.</p>}
    </section>
  )
}
