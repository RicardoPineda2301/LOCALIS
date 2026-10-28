import type { IndexStatus, Omitted } from './api'

const MAX_EXTENSIONS = 6

function extLabel(key: string): string {
  return key.startsWith('(') || key.startsWith('.') ? key : `.${key}`
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`
}

/**
 * Resume lo que el índice dejó fuera, para que la cobertura sea visible: extensiones no indexadas (con su conteo),
 * ficheros de más de 1 MB, binarios y lo excluido por .gitignore / .localisignore. Devuelve '' si no se omitió nada.
 */
export function coverageSummary(status: Pick<IndexStatus, 'omitted' | 'filesBinary'>): string {
  const o: Partial<Omitted> = status.omitted ?? {}
  const parts: string[] = []
  const byExt = Object.entries(o.byExtension ?? {})
  if (byExt.length > 0) {
    const shown = byExt.slice(0, MAX_EXTENSIONS).map(([ext, n]) => `${n} ${extLabel(ext)}`)
    const rest = byExt.slice(MAX_EXTENSIONS).reduce((sum, [, n]) => sum + n, 0)
    parts.push(`sin indexar por extensión: ${shown.join(', ')}${rest > 0 ? ` y ${rest} más` : ''}`)
  }
  if ((o.tooLarge ?? 0) > 0) {
    parts.push(`${o.tooLarge} de más de 1 MB`)
  }
  if ((status.filesBinary ?? 0) > 0) {
    parts.push(plural(status.filesBinary ?? 0, 'binario', 'binarios'))
  }
  const ignored: string[] = []
  if ((o.ignoredFiles ?? 0) > 0) {
    ignored.push(plural(o.ignoredFiles ?? 0, 'fichero', 'ficheros'))
  }
  if ((o.ignoredDirs ?? 0) > 0) {
    ignored.push(plural(o.ignoredDirs ?? 0, 'carpeta', 'carpetas'))
  }
  if (ignored.length > 0) {
    parts.push(`${ignored.join(' y ')} por .gitignore/.localisignore`)
  }
  return parts.join(' · ')
}

/** Texto para el tooltip: ejemplos de rutas de lo omitido. '' si no hay. */
export function coverageDetail(status: Pick<IndexStatus, 'omitted'>): string {
  const o = status.omitted
  const lines: string[] = []
  if (o?.tooLargeSamples?.length) {
    lines.push(`De más de 1 MB: ${o.tooLargeSamples.join(', ')}`)
  }
  if (o?.ignoredSamples?.length) {
    lines.push(`Excluidos por .gitignore/.localisignore: ${o.ignoredSamples.join(', ')}`)
  }
  return lines.join('\n')
}
