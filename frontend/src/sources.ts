import type { SourceRef } from './api'

/**
 * Número de fuente de cada fragmento, el mismo que vio el modelo ("=== [2] FILE: ... ==="): un número por fichero,
 * en orden de primera aparición, compartido por todos los fragmentos de ese fichero.
 */
export function sourceNumbers(sources: Pick<SourceRef, 'path'>[]): number[] {
  const byPath = new Map<string, number>()
  return sources.map((s) => {
    const n = byPath.get(s.path) ?? byPath.size + 1
    byPath.set(s.path, n)
    return n
  })
}
