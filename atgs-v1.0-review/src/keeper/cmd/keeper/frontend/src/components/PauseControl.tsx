// PauseControl is the central piece of the Keeper UI - the big button
// that non-technical hosts reach for when they want their machine back.
//
// When paused:
//   - Large copper-toned "Resume" button
//   - Shows how long it's been paused
//   - Brief explainer of what pausing does
// When running:
//   - Sage-outlined "Pause" button
//   - Confirmation before actually pausing (misclicks here are costly
//     since they interrupt ongoing games)

import { useState } from 'react'

interface Props {
  paused: boolean
  pausedAt?: string
  reason?: string
  onPause: () => void
  onUnpause: () => void
}

export function PauseControl({ paused, pausedAt, onPause, onUnpause }: Props) {
  const [confirming, setConfirming] = useState(false)

  if (paused) {
    return (
      <section className="border border-copper/40 bg-copper/5 px-6 py-6">
        <div className="flex items-start justify-between gap-4">
          <div className="flex-1">
            <div className="section-num mb-2">§ paused</div>
            <h2 className="display text-3xl">Machine paused</h2>
            <p className="section-sub font-display italic text-parchment mt-2 max-w-lg">
              All running game servers are frozen in memory but use no CPU.
              Players see their connection time out but can reconnect the
              moment you resume.
            </p>
            {pausedAt && (
              <div className="label mt-4">
                Paused at <span className="text-parchment">{pausedAt}</span>
              </div>
            )}
          </div>
          <button className="btn-gold px-8 py-3 text-lg" onClick={onUnpause}>
            Resume →
          </button>
        </div>
      </section>
    )
  }

  if (confirming) {
    return (
      <section className="border border-gold/40 bg-gold/5 px-6 py-6">
        <div className="flex items-start justify-between gap-4">
          <div className="flex-1">
            <div className="section-num mb-2">§ confirm pause</div>
            <h2 className="display text-3xl">Pause this machine?</h2>
            <p className="section-sub font-display italic text-parchment mt-2 max-w-lg">
              Running game servers will freeze. Anyone connected will lose
              their session until you resume. This takes effect immediately.
            </p>
          </div>
          <div className="flex flex-col gap-2">
            <button className="btn-danger px-8 py-3 text-lg" onClick={() => { setConfirming(false); onPause() }}>
              Yes, pause
            </button>
            <button className="btn text-sm" onClick={() => setConfirming(false)}>
              Cancel
            </button>
          </div>
        </div>
      </section>
    )
  }

  return (
    <section className="border border-sage/30 bg-sage/5 px-6 py-6">
      <div className="flex items-start justify-between gap-4">
        <div className="flex-1">
          <div className="section-num mb-2">§ running</div>
          <h2 className="display text-3xl">This machine is active</h2>
          <p className="section-sub font-display italic text-parchment mt-2 max-w-lg">
            Game servers are running and serving players. Pause when you
            want your machine's full resources for something else.
          </p>
        </div>
        <button className="btn px-8 py-3 text-lg border-copper/40 text-copper hover:bg-copper/10 hover:border-copper" onClick={() => setConfirming(true)}>
          Pause machine
        </button>
      </div>
    </section>
  )
}
