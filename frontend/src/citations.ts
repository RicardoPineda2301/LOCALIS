import type { Citation, CitationStatus } from './api'

export const CITATION_LABEL: Record<CitationStatus, string> = {
  VERIFIED: 'Verificada',
  FILE_NOT_FOUND: 'Fichero no encontrado',
  AMBIGUOUS_FILE: 'Fichero ambiguo',
  OUT_OF_RANGE: 'Líneas fuera de rango',
  NOT_IN_CONTEXT: 'El modelo no vio esas líneas',
}

export function citationText(c: Pick<Citation, 'path' | 'startLine' | 'endLine'>): string {
  return c.endLine > c.startLine ? `${c.path}:${c.startLine}-${c.endLine}` : `${c.path}:${c.startLine}`
}
