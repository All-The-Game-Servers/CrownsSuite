import { useEffect, useState } from 'react'
import { App as WailsApp, Instance, Keeper } from '../wails'
import { formatBytes, formatRelative, stateTagClass, shortID } from '../lib/fmt'
import { PageHeader } from '../components/PageHeader'
import { ErrorBanner, EmptyState, Loading } from '../components/Misc'

const OFFICIAL_EGGS = [
  {
    id: 'minecraft-java-paper',
    label: 'Minecraft Java (Paper)',
    kind: 'java',
    hostnameHint: 'play.example.com',
    notes: 'Best default for Java SMPs and plugins. Uses Java relay hostname routing.',
  },
  {
    id: 'minecraft-java-fabric',
    label: 'Minecraft Java (Fabric)',
    kind: 'java',
    hostnameHint: 'mods.example.com',
    notes: 'Official Fabric path for modded Java servers. Keep client and server mods aligned.',
  },
  {
    id: 'minecraft-bedrock',
    label: 'Minecraft Bedrock',
    kind: 'bedrock',
    hostnameHint: '',
    notes: 'Bedrock uses a public UDP relay port instead of Java hostname routing.',
  },
] as const

export function InstancesView() {
  const [instances, setInstances] = useState<Instance[]>([])
  const [keepers, setKeepers] = useState<Keeper[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | undefined>()
  const [showCreate, setShowCreate] = useState(false)

  const refresh = async () => {
    setError(undefined)
    try {
      const [is, ks] = await Promise.all([WailsApp.ListAllInstances(), WailsApp.ListKeepers()])
      setInstances(is || [])
      setKeepers(ks || [])
    } catch (e: any) {
      setError(String(e?.message ?? e))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    refresh()
    const t = setInterval(refresh, 5000)
    return () => clearInterval(t)
  }, [])

  const action = async (fn: () => Promise<any>) => {
    try {
      await fn()
      await refresh()
    } catch (e: any) {
      setError(String(e?.message ?? e))
    }
  }

  const keeperLabel = (id: string) => {
    const k = keepers.find((x) => x.id === id)
    return k ? k.display_name || shortID(k.id) : shortID(id)
  }

  return (
    <div>
      <PageHeader
        num="II"
        title="Instances"
        subtitle="Game servers across all Keepers. Route state, lifecycle, and operator actions live here."
        right={
          <button className="btn-gold" disabled={keepers.length === 0} onClick={() => setShowCreate((s) => !s)}>
            {showCreate ? 'Close' : 'New instance'}
          </button>
        }
      />

      <ErrorBanner error={error} />

      {showCreate && (
        <CreateInstancePanel
          keepers={keepers}
          onClose={() => setShowCreate(false)}
          onCreated={() => {
            setShowCreate(false)
            refresh()
          }}
        />
      )}

      {loading ? (
        <Loading />
      ) : instances.length === 0 ? (
        <EmptyState>No instances dispatched.</EmptyState>
      ) : (
        <div className="border border-iron enter">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Egg</th>
                <th>Keeper</th>
                <th>State</th>
                <th>Route</th>
                <th>Resources</th>
                <th>Created</th>
                <th className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {instances.map((i) => (
                <tr key={i.instance_id}>
                  <td>
                    <div className="font-display text-lg text-bone">{i.display_name}</div>
                    <div className="font-mono text-[10px] text-pewter">{shortID(i.instance_id)}</div>
                    <div className="font-mono text-[10px] text-pewter/80">ws {shortID(i.workspace_id)}</div>
                  </td>
                  <td className="font-mono text-xs text-parchment">{i.egg_id}</td>
                  <td className="font-mono text-xs text-pewter" title={i.keeper_id}>
                    {keeperLabel(i.keeper_id)}
                  </td>
                  <td>
                    <span className={stateTagClass(i.state)}>{i.state}</span>
                  </td>
                  <td className="font-mono text-xs text-pewter">
                    {i.hostname ? (
                      <div>{i.hostname}</div>
                    ) : typeof i.public_port === 'number' && i.public_port > 0 ? (
                      <div>udp:{i.public_port}</div>
                    ) : (
                      <div className="text-pewter/70">no route</div>
                    )}
                    {typeof i.host_port === 'number' && i.host_port > 0 && <div>keeper:{i.host_port}</div>}
                  </td>
                  <td className="font-mono text-xs text-parchment">
                    {formatBytes(i.memory_bytes)} <span className="text-pewter">/</span> {i.cpu_shares} cpu
                  </td>
                  <td className="font-display text-sm italic text-parchment">{formatRelative(i.created_at)}</td>
                  <td className="whitespace-nowrap text-right">
                    {i.state !== 'running' && (
                      <button className="btn-ghost mr-2 text-xs" onClick={() => action(() => WailsApp.StartInstance(i.instance_id))}>
                        Start
                      </button>
                    )}
                    {i.state === 'running' && (
                      <button className="btn-ghost mr-2 text-xs" onClick={() => action(() => WailsApp.StopInstance(i.instance_id))}>
                        Stop
                      </button>
                    )}
                    <button
                      className="btn-danger text-xs"
                      onClick={() => {
                        if (confirm(`Delete ${i.display_name}? This is permanent.`)) {
                          action(() => WailsApp.DeleteInstance(i.instance_id))
                        }
                      }}
                    >
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

function CreateInstancePanel({
  keepers,
  onClose,
  onCreated,
}: {
  keepers: Keeper[]
  onClose: () => void
  onCreated: () => void
}) {
  const [keeperID, setKeeperID] = useState(keepers[0]?.id ?? '')
  const [displayName, setDisplayName] = useState('')
  const [eggID, setEggID] = useState('minecraft-java-paper')
  const [hostname, setHostname] = useState('')
  const [memoryGB, setMemoryGB] = useState(2)
  const [cpuShares, setCpuShares] = useState(1024)
  const [error, setError] = useState<string | undefined>()
  const [busy, setBusy] = useState(false)
  const selectedEgg = OFFICIAL_EGGS.find((egg) => egg.id === eggID) ?? OFFICIAL_EGGS[0]
  const usesHostname = selectedEgg.kind === 'java'

  const submit = async () => {
    setError(undefined)
    setBusy(true)
    try {
      await WailsApp.CreateInstance(keeperID, {
        egg_id: eggID,
        display_name: displayName,
        hostname: usesHostname ? hostname || undefined : undefined,
        memory_bytes: memoryGB * 1024 * 1024 * 1024,
        cpu_shares: cpuShares,
      })
      onCreated()
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
          <div className="section-number mb-1">Section dispatch</div>
          <div className="font-display text-2xl text-bone">New instance</div>
        </div>
        <button className="btn-ghost text-xs" onClick={onClose}>
          Cancel
        </button>
      </div>
      <div className="grid grid-cols-2 gap-x-8 gap-y-5 bg-obsidian px-6 py-6">
        <Field label="Display name">
          <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="Lowlight SMP" />
        </Field>
        <Field label="Keeper">
          <select className="input-mono" value={keeperID} onChange={(e) => setKeeperID(e.target.value)}>
            {keepers.map((k) => (
              <option key={k.id} value={k.id}>
                {k.display_name || shortID(k.id)} - {k.connected ? 'connected' : 'offline'}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Egg">
          <select className="input-mono" value={eggID} onChange={(e) => setEggID(e.target.value)}>
            {OFFICIAL_EGGS.map((egg) => (
              <option key={egg.id} value={egg.id}>
                {egg.label}
              </option>
            ))}
          </select>
          <div className="mt-1 text-xs font-display italic text-pewter">{selectedEgg.notes}</div>
        </Field>
        <Field label="Hostname (optional)">
          <input
            className="input-mono"
            value={hostname}
            onChange={(e) => setHostname(e.target.value)}
            placeholder={selectedEgg.hostnameHint || 'Not used for this server type'}
            disabled={!usesHostname}
          />
          <div className="mt-1 text-xs font-display italic text-pewter">
            {usesHostname ? 'Java instances use hostname-based relay routing.' : 'Bedrock gets an auto-assigned relay UDP port after dispatch.'}
          </div>
        </Field>
        <Field label="Memory (GiB)">
          <input className="input-mono" type="number" min={1} value={memoryGB} onChange={(e) => setMemoryGB(Number(e.target.value))} />
        </Field>
        <Field label="CPU shares">
          <input className="input-mono" type="number" min={128} value={cpuShares} onChange={(e) => setCpuShares(Number(e.target.value))} />
        </Field>

        {error && <div className="col-span-2 border-l-2 border-rust bg-rust/5 px-4 py-2 font-mono text-xs text-rust">{error}</div>}

        <div className="col-span-2 flex justify-end pt-2">
          <button className="btn-gold" disabled={busy || !displayName || !keeperID || !eggID} onClick={submit}>
            {busy ? 'Dispatching...' : 'Dispatch'}
          </button>
        </div>
      </div>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="label mb-1.5">{label}</div>
      {children}
    </div>
  )
}
