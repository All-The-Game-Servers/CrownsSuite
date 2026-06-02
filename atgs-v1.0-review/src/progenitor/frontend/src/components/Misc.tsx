// Small reusable UI pieces used across views.

export function ErrorBanner({ error }: { error?: string }) {
  if (!error) return null
  return (
    <div className="mb-6 border-l-2 border-rust bg-rust/5 px-4 py-3 text-sm">
      <div className="label text-rust mb-1">Error</div>
      <div className="text-parchment font-mono text-xs">{error}</div>
    </div>
  )
}

export function EmptyState({ children }: { children: React.ReactNode }) {
  return (
    <div className="border border-iron border-dashed px-6 py-16 text-center">
      <div className="label mb-3">Empty</div>
      <div className="font-display italic text-parchment text-lg">{children}</div>
    </div>
  )
}

export function Loading() {
  return <div className="label text-pewter animate-pulse">Loading…</div>
}
