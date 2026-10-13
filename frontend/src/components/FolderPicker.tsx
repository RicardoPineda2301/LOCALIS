import { useCallback, useEffect, useState } from 'react'
import { api, type FolderEntry, type FolderListing } from '../api'

type Props = {
  initialPath: string | null
  onChoose: (path: string) => Promise<void>
  onClose: () => void
}

export function FolderPicker({ initialPath, onChoose, onClose }: Props) {
  const [listing, setListing] = useState<FolderListing | null>(null)
  const [address, setAddress] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)

  const go = useCallback(async (path: string, fallbackToTop = false) => {
    setLoading(true)
    setError('')
    try {
      const l = await api.listFolders(path)
      setListing(l)
      setAddress(l.path)
    } catch (e) {
      if (fallbackToTop) return go('')
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void go(initialPath ?? '', true)
  }, [go, initialPath])

  const choose = async () => {
    if (!listing?.path) return
    setSaving(true)
    setError('')
    try {
      await onChoose(listing.path)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setSaving(false)
    }
  }

  const atTop = listing?.path === ''
  const row = (f: FolderEntry, icon: string) => (
    <li key={f.path}>
      <button className="row" onClick={() => void go(f.path)}>
        <span>{icon}</span> {f.name}
      </button>
    </li>
  )

  return (
    <div className="overlay" onKeyDown={(e) => e.key === 'Escape' && onClose()}>
      <div className="dialog picker" role="dialog" aria-label="Seleccionar repositorio">
        <h2>Seleccionar repositorio</h2>
        <form
          className="address"
          onSubmit={(e) => {
            e.preventDefault()
            void go(address.trim())
          }}
        >
          <button type="button" disabled={!listing || atTop || loading} onClick={() => void go(listing?.parent ?? '')} title="Carpeta superior">
            ↑
          </button>
          <input value={address} onChange={(e) => setAddress(e.target.value)} placeholder="Escriba o pegue una ruta y pulse Enter" />
        </form>
        {error && <p className="warn">{error}</p>}
        <ul className="folders">
          {atTop && listing?.places?.map((p) => row(p, '⭐'))}
          {listing?.entries.map((f) => row(f, atTop ? '💽' : '📁'))}
          {listing && !loading && listing.entries.length === 0 && <li className="muted">No hay subcarpetas.</li>}
          {loading && <li className="muted">Cargando…</li>}
        </ul>
        <div className="actions">
          <span className="muted grow" title={listing?.path}>{atTop ? 'Elija una unidad o carpeta' : listing?.path}</span>
          <button className="secondary" onClick={onClose}>Cancelar</button>
          <button onClick={() => void choose()} disabled={atTop || !listing || saving}>
            {saving ? 'Abriendo…' : 'Elegir esta carpeta'}
          </button>
        </div>
      </div>
    </div>
  )
}
