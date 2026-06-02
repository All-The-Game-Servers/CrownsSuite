import { useEffect, useMemo, useState } from 'react'
import { App as WailsApp, Backup, Instance } from '../wails'
import { formatBytes, formatRelative, stateTagClass, shortID } from '../lib/fmt'
import { PageHeader } from '../components/PageHeader'
import { ErrorBanner, EmptyState, Loading } from '../components/Misc'

export function BackupsView() {
  const [instances, setInstances] = useState<Instance[]>([])
  const [selectedInstance, setSelectedInstance] = useState('')
  const [restoreTarget, setRestoreTarget] = useState('')
  const [backups, setBackups] = useState<Backup[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | undefined>()
  const [status, setStatus] = useState<string | undefined>()

  const refreshInstances = async () => {
    const is = await WailsApp.ListAllInstances()
    setInstances(is || [])
    if (!selectedInstance && is && is.length > 0) {
      setSelectedInstance(is[0].instance_id)
      setRestoreTarget(is[0].instance_id)
    }
  }

  const refreshBackups = async (instanceID = selectedInstance) => {
    if (!instanceID) return
    const bs = await WailsApp.ListBackupsForInstance(instanceID)
    setBackups(bs || [])
  }

  useEffect(() => {
    ;(async () => {
      try {
        await refreshInstances()
      } catch (e: any) {
        setError(String(e?.message ?? e))
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  useEffect(() => {
    if (!selectedInstance) return
    setRestoreTarget((current) => current || selectedInstance)
    refreshBackups().catch((e: any) => setError(String(e?.message ?? e)))
    const t = setInterval(() => {
      refreshBackups().catch((e: any) => setError(String(e?.message ?? e)))
    }, 5000)
    return () => clearInterval(t)
  }, [selectedInstance])

  const selectedInstanceName = useMemo(
    () => instances.find((i) => i.instance_id === selectedInstance)?.display_name,
    [instances, selectedInstance],
  )

  const createBackup = async (encrypted: boolean) => {
    setError(undefined)
    setStatus(undefined)
    try {
      const out = await WailsApp.CreateBackup(selectedInstance, {
        display_name: `manual ${new Date().toISOString().slice(0, 19)}`,
        encrypted,
      })
      setStatus(`Backup queued as task ${shortID(out.task_id)}.`)
      await refreshBackups()
    } catch (e: any) {
      setError(String(e?.message ?? e))
    }
  }

  const restore = async (backupID: string) => {
    setError(undefined)
    setStatus(undefined)
    try {
      const out = await WailsApp.RestoreBackup(backupID, restoreTarget || selectedInstance)
      setStatus(`Restore queued as task ${shortID(out.task_id)} into ${shortID(out.target_instance_id)}.`)
    } catch (e: any) {
      setError(String(e?.message ?? e))
    }
  }

  const deleteBackup = async (backupID: string) => {
    if (!confirm('Delete this backup? Chunks with refcount 0 will be reclaimed on Central.')) return
    setError(undefined)
    setStatus(undefined)
    try {
      await WailsApp.DeleteBackup(backupID)
      setStatus(`Deleted backup ${shortID(backupID)}.`)
      await refreshBackups()
    } catch (e: any) {
      setError(String(e?.message ?? e))
    }
  }

  return (
    <div>
      <PageHeader
        num="IV"
        title="Backups"
        subtitle="Chunked snapshots with dedup and optional Central-side encryption."
      />

      <div className="mb-8 grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto_auto] items-end gap-4 enter">
        <div>
          <div className="label mb-1.5">Instance</div>
          <select className="input-mono" value={selectedInstance} onChange={(e) => setSelectedInstance(e.target.value)}>
            {instances.length === 0 && <option value="">No instances available</option>}
            {instances.map((i) => (
              <option key={i.instance_id} value={i.instance_id}>
                {i.display_name} - {shortID(i.instance_id)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <div className="label mb-1.5">Restore target</div>
          <select className="input-mono" value={restoreTarget} onChange={(e) => setRestoreTarget(e.target.value)}>
            {instances.map((i) => (
              <option key={i.instance_id} value={i.instance_id}>
                {i.display_name} - {shortID(i.instance_id)}
              </option>
            ))}
          </select>
        </div>
        <button className="btn-primary" disabled={!selectedInstance} onClick={() => createBackup(false)}>
          Snapshot
        </button>
        <button className="btn-gold" disabled={!selectedInstance} onClick={() => createBackup(true)}>
          Snapshot (encrypted)
        </button>
      </div>

      <ErrorBanner error={error} />
      {status && <div className="mb-6 border border-gold/30 bg-gold/5 px-4 py-3 text-sm text-parchment">{status}</div>}

      {loading ? (
        <Loading />
      ) : !selectedInstance ? (
        <EmptyState>Select an instance to view its snapshots.</EmptyState>
      ) : backups.length === 0 ? (
        <EmptyState>No snapshots yet for <span className="text-bone">{selectedInstanceName}</span>.</EmptyState>
      ) : (
        <div className="border border-iron enter">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Status</th>
                <th>Size</th>
                <th>Chunks</th>
                <th>Storage</th>
                <th>Created</th>
                <th className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {backups.map((b) => (
                <tr key={b.backup_id}>
                  <td>
                    <div className="font-display text-bone">{b.display_name || 'unnamed'}</div>
                    <div className="font-mono text-[10px] text-pewter">{shortID(b.backup_id)}</div>
                    {b.error && <div className="mt-1 text-[10px] text-rust">{b.error}</div>}
                  </td>
                  <td className="space-y-1">
                    <span className={stateTagClass(b.status)}>{b.status}</span>
                    {b.encrypted && <span className="tag-warn ml-1">encrypted</span>}
                  </td>
                  <td className="font-mono text-xs text-parchment">{formatBytes(b.total_bytes)}</td>
                  <td className="font-mono text-xs text-parchment">{b.chunk_count}</td>
                  <td className="font-mono text-xs text-pewter">{b.storage_mode}</td>
                  <td className="font-display text-sm italic text-parchment">{formatRelative(b.created_at)}</td>
                  <td className="whitespace-nowrap text-right">
                    {b.status === 'complete' && (
                      <button className="btn-ghost mr-2 text-xs" onClick={() => restore(b.backup_id)}>
                        Restore
                      </button>
                    )}
                    <button className="btn-danger text-xs" onClick={() => deleteBackup(b.backup_id)}>
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
