import { InstanceRow } from '../wails'

// Compact list of game servers running on this keeper. Minimal intentionally;
// the Progenitor Console is the right place for deep instance management.

interface Props {
  instances: InstanceRow[]
  paused: boolean
}

export function InstancesList({ instances, paused }: Props) {
  return (
    <section>
      <div className="flex items-baseline justify-between mb-3">
        <div className="section-num">§ instances on this host</div>
        <div className="label">{instances.length} total</div>
      </div>

      {instances.length === 0 ? (
        <div className="border border-iron border-dashed px-6 py-10 text-center">
          <div className="font-display italic text-parchment">
            No game servers assigned yet.
          </div>
          <div className="label mt-2">
            Central will place instances here as needed.
          </div>
        </div>
      ) : (
        <div className="border border-iron">
          {instances.map((i, idx) => (
            <div
              key={i.instance_id}
              className={
                'flex items-center gap-4 px-4 py-3 ' +
                (idx < instances.length - 1 ? 'border-b border-iron' : '')
              }
            >
              <div className="flex-1 min-w-0">
                <div className="font-display text-lg text-bone truncate">
                  {i.display_name || 'unnamed'}
                </div>
                <div className="font-mono text-[10px] text-pewter">
                  {i.egg_id} · {i.instance_id.slice(0, 8)}
                </div>
              </div>
              <div className="flex-shrink-0">
                <InstanceTag state={i.state} paused={paused} />
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}

function InstanceTag({ state, paused }: { state: string; paused: boolean }) {
  // When the whole keeper is paused, running instances are actually in a
  // paused-by-SIGSTOP state. Show that accurately.
  if (paused && state === 'running') {
    return <span className="tag-pause">frozen</span>
  }
  switch (state) {
    case 'running': return <span className="tag-ok">running</span>
    case 'paused':  return <span className="tag-pause">paused</span>
    case 'stopped': return <span className="tag-mute">stopped</span>
    case 'created': return <span className="tag-warn">created</span>
    case 'failed':  return <span className="tag-alert">failed</span>
    default:        return <span className="tag-mute">{state}</span>
  }
}
