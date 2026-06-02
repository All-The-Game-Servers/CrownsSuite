import { useEffect, useState } from 'react'
import { API, StatusResponse, InstanceRow } from './wails'
import { StatusHeader } from './components/StatusHeader'
import { PauseControl } from './components/PauseControl'
import { ResourceMeters } from './components/ResourceMeters'
import { InstancesList } from './components/InstancesList'

// Single-window app. No routing - the Keeper UI is deliberately minimal so
// non-technical hosts aren't overwhelmed. One screen, one big pause button,
// three meters, a list of instances.
//
// Data flow:
//   - Poll Status() every 2s (host stats, pause state)
//   - Poll Instances() every 5s (their state changes slowly)
//   - Pause/Unpause trigger immediate refreshes
export function App() {
  const [status, setStatus] = useState<StatusResponse | null>(null)
  const [instances, setInstances] = useState<InstanceRow[]>([])
  const [error, setError] = useState<string | undefined>()

  const refreshStatus = async () => {
    try {
      setStatus(await API.Status())
    } catch (e: any) {
      setError(String(e?.message ?? e))
    }
  }
  const refreshInstances = async () => {
    try {
      setInstances(await API.Instances() || [])
    } catch (e: any) {
      setError(String(e?.message ?? e))
    }
  }

  useEffect(() => {
    refreshStatus()
    refreshInstances()
    const t1 = setInterval(refreshStatus, 2000)
    const t2 = setInterval(refreshInstances, 5000)
    return () => { clearInterval(t1); clearInterval(t2) }
  }, [])

  const onPause = async () => {
    try {
      await API.Pause('manual')
      await Promise.all([refreshStatus(), refreshInstances()])
    } catch (e: any) { setError(String(e?.message ?? e)) }
  }
  const onUnpause = async () => {
    try {
      await API.Unpause()
      await Promise.all([refreshStatus(), refreshInstances()])
    } catch (e: any) { setError(String(e?.message ?? e)) }
  }

  if (!status) {
    return (
      <div className="h-full flex items-center justify-center label animate-pulse">
        Starting keeper…
      </div>
    )
  }

  return (
    <div className="h-full flex flex-col overflow-hidden">
      <StatusHeader status={status} />

      <div className="flex-1 overflow-auto px-8 py-6 space-y-8">
        <PauseControl
          paused={status.paused}
          pausedAt={status.paused_at}
          reason={status.pause_reason}
          onPause={onPause}
          onUnpause={onUnpause}
        />

        {status.host && <ResourceMeters host={status.host} />}

        <InstancesList instances={instances} paused={status.paused} />

        {error && (
          <div className="border-l-2 border-rust bg-rust/5 px-4 py-3 text-sm font-mono text-rust">
            {error}
          </div>
        )}
      </div>
    </div>
  )
}
