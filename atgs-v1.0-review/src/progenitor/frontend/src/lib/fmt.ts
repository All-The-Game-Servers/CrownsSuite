// Shared formatting and state tag helpers.

export function formatBytes(n: number): string {
  if (n === 0) return '0 B'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  const i = Math.min(Math.floor(Math.log2(n) / 10), units.length - 1)
  const v = n / Math.pow(1024, i)
  return `${v < 10 && i > 0 ? v.toFixed(1) : Math.round(v)} ${units[i]}`
}

export function formatDate(iso: string | undefined | null): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString()
  } catch {
    return iso
  }
}

export function formatPercent(n: number | undefined | null): string {
  if (n === undefined || n === null || Number.isNaN(n)) return '—'
  return `${n.toFixed(1)}%`
}

export function formatRelative(iso: string | undefined | null): string {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  const diff = Date.now() - t
  const s = Math.abs(diff) / 1000
  if (s < 60) return diff >= 0 ? 'just now' : 'in <1m'
  const m = s / 60
  if (m < 60) return `${Math.round(m)}m ${diff >= 0 ? 'ago' : 'from now'}`
  const h = m / 60
  if (h < 24) return `${Math.round(h)}h ${diff >= 0 ? 'ago' : 'from now'}`
  const d = h / 24
  return `${Math.round(d)}d ${diff >= 0 ? 'ago' : 'from now'}`
}

export function stateTagClass(state: string): string {
  switch (state) {
    case 'running':
    case 'connected':
    case 'succeeded':
    case 'complete': return 'tag-success'
    case 'created':
    case 'pending':
    case 'queued':
    case 'dispatched':
    case 'uploading': return 'tag-warn'
    case 'failed':
    case 'timed_out':
    case 'disconnected': return 'tag-danger'
    default: return 'tag-muted'
  }
}

export function shortID(id: string): string {
  return id.slice(0, 8)
}
