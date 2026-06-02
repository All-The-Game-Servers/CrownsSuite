import { useEffect, useState } from 'react'
import { App as WailsApp, Instance, Schedule } from '../wails'
import { formatRelative, shortID } from '../lib/fmt'
import { PageHeader } from '../components/PageHeader'
import { ErrorBanner, EmptyState, Loading } from '../components/Misc'

export function SchedulesView() {
  const [schedules, setSchedules] = useState<Schedule[]>([])
  const [instances, setInstances] = useState<Instance[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | undefined>()
  const [status, setStatus] = useState<string | undefined>()
  const [showCreate, setShowCreate] = useState(false)

  const refresh = async () => {
    setError(undefined)
    try {
      const [scheds, is] = await Promise.all([WailsApp.ListAllSchedules(), WailsApp.ListAllInstances()])
      setSchedules(scheds || [])
      setInstances(is || [])
    } catch (e: any) {
      setError(String(e?.message ?? e))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    refresh()
    const t = setInterval(refresh, 10000)
    return () => clearInterval(t)
  }, [])

  const instanceName = (id: string) => instances.find((i) => i.instance_id === id)?.display_name || shortID(id)

  return (
    <div>
      <PageHeader
        num="V"
        title="Schedules"
        subtitle="Cron-driven snapshots with retention and optional encryption."
        right={
          <button className="btn-gold" disabled={instances.length === 0} onClick={() => setShowCreate((s) => !s)}>
            {showCreate ? 'Close' : 'New schedule'}
          </button>
        }
      />

      <ErrorBanner error={error} />
      {status && <div className="mb-6 border border-gold/30 bg-gold/5 px-4 py-3 text-sm text-parchment">{status}</div>}

      {showCreate && (
        <CreateSchedulePanel
          instances={instances}
          onClose={() => setShowCreate(false)}
          onCreated={(scheduleID) => {
            setShowCreate(false)
            setStatus(`Created schedule ${shortID(scheduleID)}.`)
            refresh()
          }}
        />
      )}

      {loading ? (
        <Loading />
      ) : schedules.length === 0 ? (
        <EmptyState>No schedules defined. Create one above.</EmptyState>
      ) : (
        <div className="border border-iron enter">
          <table className="data-table">
            <thead>
              <tr>
                <th>Instance</th>
                <th>Cron</th>
                <th>Retention</th>
                <th>Encrypt</th>
                <th>Next run</th>
                <th>Last run</th>
                <th>Last backup</th>
                <th>State</th>
              </tr>
            </thead>
            <tbody>
              {schedules.map((s) => (
                <tr key={s.schedule_id}>
                  <td>
                    <div className="font-display text-bone">{instanceName(s.instance_id)}</div>
                    <div className="font-mono text-[10px] text-pewter">{shortID(s.instance_id)}</div>
                  </td>
                  <td className="font-mono text-xs text-gold">{s.cron_expr}</td>
                  <td className="font-mono text-xs text-parchment">{s.retention > 0 ? `keep ${s.retention}` : 'keep all'}</td>
                  <td className="text-xs text-parchment">{s.encrypt ? <span className="tag-warn">yes</span> : <span className="tag-mute">no</span>}</td>
                  <td className="font-display text-sm italic text-parchment">{formatRelative(s.next_run_at)}</td>
                  <td className="font-display text-sm italic text-parchment">{s.last_run_at ? formatRelative(s.last_run_at) : '—'}</td>
                  <td className="font-mono text-xs text-pewter">{s.last_backup_id ? shortID(s.last_backup_id) : '—'}</td>
                  <td>{s.enabled ? <span className="tag-ok">enabled</span> : <span className="tag-mute">disabled</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function CreateSchedulePanel({
  instances,
  onClose,
  onCreated,
}: {
  instances: Instance[]
  onClose: () => void
  onCreated: (scheduleID: string) => void
}) {
  const [instanceID, setInstanceID] = useState(instances[0]?.instance_id ?? '')
  const [cronExpr, setCronExpr] = useState('0 3 * * *')
  const [retention, setRetention] = useState(7)
  const [encrypt, setEncrypt] = useState(false)
  const [error, setError] = useState<string | undefined>()
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setError(undefined)
    setBusy(true)
    try {
      const out = await WailsApp.CreateSchedule(instanceID, {
        cron_expr: cronExpr,
        retention,
        encrypt,
      })
      onCreated(out.schedule_id)
    } catch (e: any) {
      setError(String(e?.message ?? e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="border border-iron mb-8 enter">
      <div className="flex items-center justify-between border-b border-iron bg-carbon px-6 py-4">
        <div>
          <div className="section-number mb-1">Schedule creation</div>
          <div className="font-display text-2xl text-bone">New schedule</div>
        </div>
        <button className="btn-ghost text-xs" onClick={onClose}>
          Cancel
        </button>
      </div>
      <div className="grid grid-cols-2 gap-x-8 gap-y-5 bg-obsidian px-6 py-6">
        <div className="col-span-2">
          <div className="label mb-1.5">Instance</div>
          <select className="input-mono" value={instanceID} onChange={(e) => setInstanceID(e.target.value)}>
            {instances.map((i) => (
              <option key={i.instance_id} value={i.instance_id}>
                {i.display_name} - {shortID(i.instance_id)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <div className="label mb-1.5">Cron expression</div>
          <input className="input-mono" value={cronExpr} onChange={(e) => setCronExpr(e.target.value)} placeholder="0 3 * * *" />
        </div>
        <div>
          <div className="label mb-1.5">Retention</div>
          <input className="input-mono" type="number" min={0} value={retention} onChange={(e) => setRetention(Number(e.target.value))} />
          <div className="mt-1 text-xs font-display italic text-pewter">0 keeps all backups. Positive values prune oldest beyond this count.</div>
        </div>
        <label className="col-span-2 flex cursor-pointer items-start gap-3 select-none">
          <input type="checkbox" checked={encrypt} onChange={(e) => setEncrypt(e.target.checked)} />
          <div>
            <div className="text-bone">Encrypt with Central master key</div>
            <div className="mt-1 text-xs font-display italic text-pewter">
              Requires <span className="font-mono text-parchment">ATGS_CENTRAL_BACKUP_MASTER_KEY</span> on Central.
            </div>
          </div>
        </label>
        {error && <div className="col-span-2 border-l-2 border-rust bg-rust/5 px-4 py-2 font-mono text-xs text-rust">{error}</div>}
        <div className="col-span-2 flex justify-end pt-2">
          <button className="btn-gold" disabled={busy || !instanceID || !cronExpr} onClick={submit}>
            {busy ? 'Creating...' : 'Create schedule'}
          </button>
        </div>
      </div>
    </div>
  )
}
