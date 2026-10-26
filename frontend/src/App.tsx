import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, getToken, setToken, type Health, type WorkspaceInfo } from './api'
import { StatusBar } from './components/StatusBar'
import { RepoPanel } from './components/RepoPanel'
import { FileTree } from './components/FileTree'
import { FolderPicker } from './components/FolderPicker'
import { Chat } from './components/Chat'
import { TokenDialog } from './components/TokenDialog'
import { DocPickerDialog } from './components/DocPickerDialog'

export default function App() {
  const [health, setHealth] = useState<Health | null>(null)
  const [offline, setOffline] = useState(false)
  const [needsToken, setNeedsToken] = useState(false)
  const [workspace, setWorkspace] = useState<WorkspaceInfo | null>(null)
  const [picking, setPicking] = useState(false)
  const [reindexSignal, setReindexSignal] = useState(0)
  const [filePath, setFilePath] = useState('')
  const [generatingDoc, setGeneratingDoc] = useState(false)
  const [docProgress, setDocProgress] = useState<{ percent: number; text: string } | null>(null)
  const [docError, setDocError] = useState('')
  const [docTemplates, setDocTemplates] = useState<string[]>([])
  const [pickingDocs, setPickingDocs] = useState(false)

  const refresh = useCallback(async () => {
    try {
      setHealth(await api.health())
      setOffline(false)
    } catch {
      setOffline(true)
    }
  }, [])

  const onError = useCallback((e: unknown) => {
    if (e instanceof ApiError && e.status === 401) setNeedsToken(true)
  }, [])

  const loadWorkspace = useCallback(async () => {
    try {
      setWorkspace(await api.workspace())
    } catch (e) {
      onError(e)
    }
  }, [onError])

  useEffect(() => {
    void refresh()
    void loadWorkspace()
    api.docTemplates().then(setDocTemplates).catch(() => setDocTemplates([]))
    const id = setInterval(() => void refresh(), 10_000)
    return () => clearInterval(id)
  }, [refresh, loadWorkspace])

  const chooseRepo = async (path: string) => {
    const ws = await api.setWorkspace(path)
    setWorkspace(ws)
    setFilePath('')
    setPicking(false)
    await refresh()
    try {
      await api.startIndex()
    } catch {
      /* Sin Ollama o ya indexando: el usuario puede lanzarlo desde el panel */
    }
    setReindexSignal((n) => n + 1)
  }

  const generateDocumentation = useCallback(async (selected: string[]) => {
    if (!workspace?.path || generatingDoc || selected.length === 0) return
    setPickingDocs(false)
    setGeneratingDoc(true)
    setDocError('')
    setDocProgress({ percent: 0, text: 'Preparando…' })
    try {
      try {
        await api.startAutoDoc({ templates: selected })
      } catch (e) {
        // Si ya hay una generación en curso (p. ej. tras recargar la página) se sigue su progreso
        if (!(e instanceof ApiError && e.status === 409 && e.message.startsWith('Ya hay'))) throw e
      }
      let fileName = 'Documentacion_Automatica.docx'
      for (;;) {
        await new Promise((r) => setTimeout(r, 1000))
        const st = await api.autoDocStatus()
        if (st.state === 'FAILED') throw new Error(st.error ?? 'No se pudo generar la documentación')
        if (st.state === 'DONE') {
          if (st.fileName) fileName = st.fileName
          break
        }
        setDocProgress({ percent: st.percent, text: st.progress })
      }
      setDocProgress({ percent: 100, text: 'Descargando…' })
      const blob = await api.autoDocResult()
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = fileName
      document.body.appendChild(a)
      a.click()
      window.URL.revokeObjectURL(url)
      document.body.removeChild(a)
    } catch (e) {
      onError(e)
      setDocError(e instanceof Error ? e.message : 'No se pudo generar la documentación')
    } finally {
      setGeneratingDoc(false)
      setDocProgress(null)
    }
  }, [workspace, generatingDoc, onError])

  return (
    <div className="app">
      <header className="topbar">
        <h1>LOCALIS</h1>
        <span className="tagline">Asistente de IA 100% local para código legacy</span>
        <StatusBar health={health} offline={offline} />
      </header>
      <main className="layout">
        <aside className="side">
          <RepoPanel
            workspace={workspace}
            health={health}
            reindexSignal={reindexSignal}
            onConfigure={() => setPicking(true)}
            onChanged={refresh}
            onError={onError}
            onGenerateDoc={() => setPickingDocs(true)}
            generatingDoc={generatingDoc}
            docProgress={docProgress}
            docError={docError}
            docAvailable={docTemplates.length > 0}
          />
          {workspace?.path && (
            <FileTree
              key={workspace.path}
              rootName={workspace.name ?? workspace.path}
              selected={filePath}
              onSelect={setFilePath}
              onError={onError}
            />
          )}
        </aside>
        <Chat
          onError={onError}
          indexedFiles={health?.index.files ?? 0}
          llmReady={!!health?.llm.reachable}
          path={filePath}
          onPathChange={setFilePath}
        />
      </main>
      {picking && (
        <FolderPicker initialPath={workspace?.path ?? null} onChoose={chooseRepo} onClose={() => setPicking(false)} />
      )}
      {pickingDocs && (
        <DocPickerDialog
          templates={docTemplates}
          onConfirm={(selected) => void generateDocumentation(selected)}
          onCancel={() => setPickingDocs(false)}
        />
      )}
      {needsToken && (
        <TokenDialog
          initial={getToken()}
          onSubmit={(t) => {
            setToken(t)
            setNeedsToken(false)
            void refresh()
            void loadWorkspace()
          }}
        />
      )}
    </div>
  )
}
