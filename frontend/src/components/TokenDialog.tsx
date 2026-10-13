import { useState } from 'react'

export function TokenDialog({ initial, onSubmit }: { initial: string; onSubmit: (t: string) => void }) {
  const [value, setValue] = useState(initial)
  return (
    <div className="overlay">
      <form
        className="dialog"
        onSubmit={(e) => {
          e.preventDefault()
          onSubmit(value.trim())
        }}
      >
        <h2>Token de acceso</h2>
        <p className="muted">Este servidor exige un token (server.apiToken). Se guarda solo en esta pestaña.</p>
        <input type="password" autoFocus value={value} onChange={(e) => setValue(e.target.value)} />
        <button type="submit">Continuar</button>
      </form>
    </div>
  )
}
