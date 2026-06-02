import { useEffect, useState } from 'react'
import { App as WailsApp } from './wails'
import { Sidebar } from './components/Sidebar'
import { ConnectionSetup } from './views/ConnectionSetup'
import { KeepersView } from './views/KeepersView'
import { InstancesView } from './views/InstancesView'
import { BackupsView } from './views/BackupsView'
import { SchedulesView } from './views/SchedulesView'
import { OperationsView } from './views/OperationsView'

export type ViewKey = 'keepers' | 'instances' | 'operations' | 'backups' | 'schedules'

type AppState =
  | { kind: 'loading' }
  | { kind: 'setup'; error?: string }
  | { kind: 'connected' }

export function App() {
  const [state, setState] = useState<AppState>({ kind: 'loading' })
  const [view, setView] = useState<ViewKey>('instances')

  useEffect(() => {
    ;(async () => {
      try {
        const connected = await WailsApp.IsConnected()
        setState(connected ? { kind: 'connected' } : { kind: 'setup' })
      } catch (e: any) {
        setState({ kind: 'setup', error: String(e?.message ?? e) })
      }
    })()
  }, [])

  if (state.kind === 'loading') {
    return <FullBleedCentered>Loading...</FullBleedCentered>
  }

  if (state.kind === 'setup') {
    return <ConnectionSetup initialError={state.error} onConnected={() => setState({ kind: 'connected' })} />
  }

  return (
    <div className="flex h-full">
      <Sidebar
        active={view}
        onChange={setView}
        onDisconnect={async () => {
          await WailsApp.Disconnect()
          setState({ kind: 'setup' })
        }}
      />
      <main className="flex-1 overflow-auto p-6">
        {view === 'keepers' && <KeepersView />}
        {view === 'instances' && <InstancesView />}
        {view === 'operations' && <OperationsView />}
        {view === 'backups' && <BackupsView />}
        {view === 'schedules' && <SchedulesView />}
      </main>
    </div>
  )
}

function FullBleedCentered({ children }: { children: React.ReactNode }) {
  return <div className="flex h-full items-center justify-center text-muted">{children}</div>
}
