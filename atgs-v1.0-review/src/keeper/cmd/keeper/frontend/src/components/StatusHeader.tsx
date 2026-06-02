import { StatusResponse } from '../wails'

// Top chrome of the window. Low-profile, identifies the keeper at a
// glance without stealing attention from the main controls.

export function StatusHeader({ status }: { status: StatusResponse }) {
  return (
    <header className="px-8 py-5 border-b border-iron bg-carbon">
      <div className="flex items-start justify-between gap-6">
        <div>
          <div className="font-display text-3xl text-gold leading-none tracking-tight">
            ATGS
          </div>
          <div className="font-display italic text-parchment text-base mt-0.5">
            Keeper Console
          </div>
        </div>
        <div className="text-right">
          <div className="label mb-1">This host</div>
          <div className="font-mono text-xs text-parchment truncate max-w-xs" title={status.keeper_id}>
            {status.keeper_id.slice(0, 8)}…
          </div>
          <div className="label mt-3 mb-1">Central</div>
          <div className="font-mono text-xs text-parchment truncate max-w-xs" title={status.central_url}>
            {status.central_url.replace(/^https?:\/\//, '')}
          </div>
        </div>
      </div>
    </header>
  )
}
