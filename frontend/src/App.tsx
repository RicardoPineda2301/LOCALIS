import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, getToken, setToken, type Health, type WorkspaceInfo } from './api'
import { StatusBar } from './components/StatusBar'
import { RepoPanel } from './components/RepoPanel'
import { FileTree } from './components/FileTree'
import { FolderPicker } from './components/FolderPicker'
import { Chat } from './components/Chat'
import { TokenDialog } from './components/TokenDialog'

export default function App() {
  const [health, setHealth] = useState<Health | null>(null)
  const [offline, setOffline] = useState(false)
  const [needsToken, setNeedsToken] = useState(false)
  const [workspace, setWorkspace] = useState<WorkspaceInfo | null>(null)
  const [picking, setPicking] = useState(false)
  const [reindexSignal, setReindexSignal] = useState(0)
  const [filePath, setFilePath] = useState('')

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

  const generateDocumentation = useCallback(async () => {
    if (!workspace?.path) return
    try {
      const response = await api.generateAutoDoc({})
      // Crear un blob y descargar el archivo
      const blob = new Blob([response], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' })
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'Documentacion_Automatica.docx'
      document.body.appendChild(a)
      a.click()
      window.URL.revokeObjectURL(url)
      document.body.removeChild(a)
    } catch (e) {
      onError(e)
    }
  }, [workspace, onError])

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
            onGenerateDoc={generateDocumentation}
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
