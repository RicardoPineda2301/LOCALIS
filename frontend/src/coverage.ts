import type { IndexStatus } from './api'

const MAX_EXTENSIONS = 6

function extLabel(key: string): string {
  return key.startsWith('(') || key.startsWith('.') ? key : `.${key}`
}

/**
 * Resume lo que el índice dejó fuera, para que la cobertura sea visible: extensiones no indexadas (con su conteo),
 * ficheros de más de 1 MB y binarios. Devuelve '' si no se omitió nada.
 */
export function coverageSummary(status: Pick<IndexStatus, 'omittedByExtension' | 'omittedTooLarge' | 'filesBinary'>): string {
  const parts: string[] = []
  const byExt = Object.entries(status.omittedByExtension ?? {})
  if (byExt.length > 0) {
    const shown = byExt.slice(0, MAX_EXTENSIONS).map(([ext, n]) => `${n} ${extLabel(ext)}`)
    const rest = byExt.slice(MAX_EXTENSIONS).reduce((sum, [, n]) => sum + n, 0)
    parts.push(`sin indexar por extensión: ${shown.join(', ')}${rest > 0 ? ` y ${rest} más` : ''}`)
  }
  if ((status.omittedTooLarge ?? 0) > 0) {
    parts.push(`${status.omittedTooLarge} de más de 1 MB`)
  }
  if ((status.filesBinary ?? 0) > 0) {
    parts.push(`${status.filesBinary} binario${status.filesBinary === 1 ? '' : 's'}`)
  }
  return parts.join(' · ')
}
