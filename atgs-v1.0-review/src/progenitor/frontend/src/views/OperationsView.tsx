import { useEffect, useMemo, useState } from 'react'
import { App as WailsApp, AuditEntry, Instance, Task } from '../wails'
import { formatRelative, shortID, stateTagClass } from '../lib/fmt'
import { PageHeader } from '../components/PageHeader'
import { ErrorBanner, EmptyState, Loading } from '../components/Misc'

export function OperationsView() {
  const [instances, setInstances] = useState<Instance[]>([])
  const [selectedInstance, setSelectedInstance] = useState('')
  const [logs, setLogs] = useState<string[]>([])
  const [logsTruncated, setLogsTruncated] = useState(false)
  const [tasks, setTasks] = useState<Task[]>([])
  const [audit, setAudit] = useState<AuditEntry[]>([])
  const [consoleInput, setConsoleInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | undefined>()
  const [status, setStatus] = useState<string | undefined>()

  const selectedLabel = useMemo(
    () => instances.find((i) => i.instance_id === selectedInstance)?.display_name || shortID(selectedInstance || ''),
    [instances, selectedInstance],
  )

  const refreshAll = async (instanceID = selectedInstance || '') => {
    setError(undefined)
    try {
      const instanceList = await WailsApp.ListAllInstances()
      setInstances(instanceList || [])
      const resolvedInstance = instanceID || instanceList?.[0]?.instance_id || ''
      if (!selectedInstance && resolvedInstance) {
        setSelectedInstance(resolvedInstance)
      }
      const [taskList, auditEntries] = await Promise.all([
        WailsApp.ListTasks(resolvedInstance, 20),
        WailsApp.ListAudit(20),
      ])
      setTasks(taskList || [])
      setAudit(auditEntries || [])
      if (resolvedInstance) {
        const logResult = await WailsApp.GetInstanceLogs(resolvedInstance, 200)
        setLogs(logResult.lines || [])
        setLogsTruncated(Boolean(logResult.truncated))
      } else {
        setLogs([])
        setLogsTruncated(false)
      }
    } catch (e: any) {
      setError(String(e?.message ?? e))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    refreshAll('')
    const t = setInterval(() => {
      refreshAll(selectedInstance)
    }, 5000)
    return () => clearInterval(t)
  }, [selectedInstance])

  const sendConsole = async () => {
    if (!selectedInstance || !consoleInput.trim()) return
    setBusy(true)
    setError(undefined)
    setStatus(undefined)
    try {
      const out = await WailsApp.WriteInstanceConsole(selectedInstance, consoleInput.trim())
      setConsoleInput('')
      setStatus(`Console input queued as task ${shortID(out.task_id)}.`)
      await refreshAll(selectedInstance)
    } catch (e: any) {
      setError(String(e?.message ?? e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <PageHeader
        num="III"
        title="Operations"
        subtitle="Live logs, bounded console input, recent task outcomes, and audit history for operator support."
      />

      <ErrorBanner error={error} />
      {status && (
        <div className="mb-6 border border-gold/30 bg-gold/5 px-4 py-3 text-sm text-parchment">
          {status}
        </div>
      )}

      {loading ? (
        <Loading />
      ) : instances.length === 0 ? (
        <EmptyState>Create an instance first to use logs and console.</EmptyState>
      ) : (
        <>
          <div className="mb-8 border border-iron bg-obsidian p-5 enter">
            <div className="mb-2 label">Instance</div>
            <select className="input-mono max-w-2xl" value={selectedInstance} onChange={(e) => setSelectedInstance(e.target.value)}>
              {instances.map((i) => (
                <option key={i.instance_id} value={i.instance_id}>
                  {i.display_name} - {shortID(i.instance_id)}
                </option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-8">
            <section className="border border-iron enter">
              <div className="border-b border-iron bg-carbon px-5 py-4">
                <div className="label mb-1">Logs</div>
                <div className="font-display text-xl text-bone">{selectedLabel}</div>
              </div>
              <div className="bg-obsidian px-5 py-5">
                <div className="mb-3 flex items-center justify-between text-xs text-pewter">
                  <span>{logsTruncated ? 'Showing last 200 lines' : 'Latest available lines'}</span>
                  <button className="btn-ghost text-xs" onClick={() => refreshAll(selectedInstance)}>
                    Refresh
                  </button>
                </div>
                <pre className="max-h-[28rem] overflow-auto border border-iron bg-carbon p-4 font-mono text-xs leading-6 text-parchment">
                  {logs.length > 0 ? logs.join('\n') : 'No logs returned.'}
                </pre>
              </div>
            </section>

            <section className="border border-iron enter">
              <div className="border-b border-iron bg-carbon px-5 py-4">
                <div className="label mb-1">Console</div>
                <div className="font-display text-xl text-bone">Bounded server input</div>
              </div>
              <div className="bg-obsidian px-5 py-5">
                <div className="mb-3 text-xs font-display italic text-pewter">
                  Sends one line to the running server console. No shell, no filesystem access.
                </div>
                <textarea
                  className="input-mono min-h-40"
                  value={consoleInput}
                  onChange={(e) => setConsoleInput(e.target.value)}
                  placeholder="say maintenance starts in 5 minutes"
                  maxLength={512}
                />
                <div className="mt-3 flex items-center justify-between text-xs text-pewter">
                  <span>{consoleInput.length}/512</span>
                  <button className="btn-gold" disabled={busy || !selectedInstance || !consoleInput.trim()} onClick={sendConsole}>
                    {busy ? 'Sending...' : 'Send to console'}
                  </button>
                </div>
              </div>
            </section>
          </div>

          <div className="mt-8 grid grid-cols-2 gap-8">
            <section className="border border-iron enter">
              <div className="border-b border-iron bg-carbon px-5 py-4">
                <div className="label mb-1">Recent tasks</div>
                <div className="font-display text-xl text-bone">Latest task outcomes for this instance</div>
              </div>
              <div className="bg-obsidian">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Kind</th>
                      <th>Status</th>
                      <th>Created</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tasks.length === 0 ? (
                      <tr>
                        <td colSpan={3} className="px-4 py-8 text-center text-sm text-pewter">
                          No tasks yet.
                        </td>
                      </tr>
                    ) : (
                      tasks.map((task) => (
                        <tr key={task.task_id}>
                          <td>
                            <div className="font-mono text-xs text-bone">{task.kind}</div>
                            <div className="font-mono text-[10px] text-pewter">{shortID(task.task_id)}</div>
                            {task.error_message && <div className="mt-1 text-[10px] text-rust">{task.error_message}</div>}
                          </td>
                          <td>
                            <span className={stateTagClass(task.status)}>{task.status}</span>
                          </td>
                          <td className="font-display text-sm italic text-parchment">{formatRelative(task.created_at)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </section>

            <section className="border border-iron enter">
              <div className="border-b border-iron bg-carbon px-5 py-4">
                <div className="label mb-1">Audit</div>
                <div className="font-display text-xl text-bone">Recent control-plane activity</div>
              </div>
              <div className="bg-obsidian">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Event</th>
                      <th>Actor</th>
                      <th>When</th>
                    </tr>
                  </thead>
                  <tbody>
                    {audit.length === 0 ? (
                      <tr>
                        <td colSpan={3} className="px-4 py-8 text-center text-sm text-pewter">
                          No audit entries returned.
                        </td>
                      </tr>
                    ) : (
                      audit.map((entry) => (
                        <tr key={entry.id}>
                          <td>
                            <div className="font-mono text-xs text-bone">{entry.kind}</div>
                            <div className="font-mono text-[10px] text-pewter">{JSON.stringify(entry.details || {})}</div>
                          </td>
                          <td className="font-mono text-xs text-parchment">{entry.actor}</td>
                          <td className="font-display text-sm italic text-parchment">{formatRelative(entry.at)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        </>
      )}
    </div>
  )
}
