export type Health = {
  status: string
  llm: {
    reachable: boolean
    chatModelInstalled: boolean
    embedModelInstalled: boolean
    detail: string
    chatModel: string
    embedModel: string
  }
  index: { files: number; chunks: number; embeddedChunks: number }
  license: { enforced: boolean; customer?: string; expiresAt?: string }
}

export type IndexStatus = {
  state: 'IDLE' | 'RUNNING' | 'DONE' | 'FAILED' | 'NOT_CONFIGURED'
  filesTotal?: number
  filesDone?: number
  filesSkipped?: number
  filesFailed?: number
  embeddingsAvailable?: boolean
  message?: string
}

export type CitationStatus = 'VERIFIED' | 'FILE_NOT_FOUND' | 'AMBIGUOUS_FILE' | 'OUT_OF_RANGE' | 'NOT_IN_CONTEXT'

export type Citation = { raw: string; path: string; startLine: number; endLine: number; status: CitationStatus }
export type SourceRef = { path: string; startLine: number; endLine: number; symbol: string | null }

export type Snippet = {
  path: string
  startLine: number
  endLine: number
  symbol: string | null
  code: string
  highlight: number[]
}

export type Answer = {
  answer: string
  allCitationsVerified: boolean
  citations: Citation[]
  snippets: Snippet[]
  sources: SourceRef[]
  warnings: string[]
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

const TOKEN_KEY = 'localis.apiToken'
export const getToken = () => sessionStorage.getItem(TOKEN_KEY) ?? ''
export const setToken = (t: string) => (t ? sessionStorage.setItem(TOKEN_KEY, t) : sessionStorage.removeItem(TOKEN_KEY))

async function request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(path, { method, headers, signal, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  let data: unknown = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    /* respuesta no JSON */
  }
  if (!res.ok && res.status !== 202) {
    const msg = (data as { error?: string } | null)?.error ?? `Error ${res.status}`
    throw new ApiError(res.status, msg)
  }
  return data as T
}

export type WorkspaceInfo = { path: string | null; name: string | null }
export type FolderEntry = { name: string; path: string }
export type FolderListing = { path: string; parent: string | null; entries: FolderEntry[]; places?: FolderEntry[] }
export type TreeEntry = { name: string; path: string; directory: boolean; indexable: boolean; size: number }

export type JobSnapshot = {
  id: string
  state: 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED'
  progress: string
  elapsedSeconds: number
  result?: Answer
  error?: string
  status?: number
}

export type JobRequest = { type: 'explain'; path?: string; question?: string } | { type: 'diagnose'; problem: string }

const sleep = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const id = setTimeout(resolve, ms)
    signal?.addEventListener('abort', () => {
      clearTimeout(id)
      reject(new DOMException('Cancelado', 'AbortError'))
    })
  })

/**
 * Lanza un analisis en segundo plano y espera su resultado consultando su progreso. Si se aborta la señal, cancela el
 * trabajo en el servidor (si no, el modelo seguiria trabajando y las siguientes preguntas harian cola).
 */
async function runJob(job: JobRequest, onProgress: (s: JobSnapshot) => void, signal?: AbortSignal): Promise<Answer> {
  const started = await request<JobSnapshot>('POST', '/api/job', job)
  try {
    for (;;) {
      await sleep(1200, signal)
      const snap = await request<JobSnapshot>('GET', `/api/job?id=${encodeURIComponent(started.id)}`, undefined, signal)
      onProgress(snap)
      if (snap.state === 'DONE') return snap.result as Answer
      if (snap.state === 'FAILED') throw new ApiError(snap.status ?? 500, snap.error ?? 'Error desconocido')
      if (snap.state === 'CANCELLED') throw new DOMException('Cancelado', 'AbortError')
    }
  } catch (e) {
    if ((e as Error).name === 'AbortError') {
      void request('DELETE', `/api/job?id=${encodeURIComponent(started.id)}`).catch(() => undefined)
    }
    throw e
  }
}

export const api = {
  runJob,
  health: () => request<Health>('GET', '/api/health'),
  workspace: () => request<WorkspaceInfo>('GET', '/api/workspace'),
  setWorkspace: (path: string) => request<WorkspaceInfo>('POST', '/api/workspace', { path }),
  listFolders: (path: string) => request<FolderListing>('GET', `/api/fs/list?path=${encodeURIComponent(path)}`),
  tree: (path: string) => request<TreeEntry[]>('GET', `/api/repo/tree?path=${encodeURIComponent(path)}`),
  indexStatus: () => request<IndexStatus>('GET', '/api/index/status'),
  startIndex: () => request<IndexStatus>('POST', '/api/index'),
  explain: (question: string, path: string, signal?: AbortSignal) =>
    request<Answer>('POST', '/api/explain', { question: question || undefined, path: path || undefined }, signal),
  diagnose: (problem: string, signal?: AbortSignal) => request<Answer>('POST', '/api/diagnose', { problem }, signal),
}
