import { HostStats } from '../wails'

// Three horizontal meters: CPU, memory, disk.
// Color shifts from sage (green) to gold (yellow) to rust (red) at thresholds.

interface Props {
  host: HostStats
}

export function ResourceMeters({ host }: Props) {
  return (
    <section>
      <div className="section-num mb-3">§ resources</div>
      <div className="grid grid-cols-3 gap-4">
        <Meter
          label="CPU"
          percent={host.cpu_percent}
          sub={`${host.cpu_percent.toFixed(1)}%`}
        />
        <Meter
          label="Memory"
          percent={host.memory_percent}
          sub={`${formatBytes(host.memory_used)} of ${formatBytes(host.memory_total)}`}
        />
        <Meter
          label="Disk"
          percent={host.disk_percent}
          sub={`${formatBytes(host.disk_used)} of ${formatBytes(host.disk_total)}`}
        />
      </div>
    </section>
  )
}

function Meter({ label, percent, sub }: { label: string; percent: number; sub: string }) {
  const color = percent >= 85 ? 'bg-rust' : percent >= 65 ? 'bg-gold' : 'bg-sage'
  return (
    <div className="border border-iron bg-carbon px-4 py-4">
      <div className="flex items-baseline justify-between mb-3">
        <div className="label">{label}</div>
        <div className="font-mono text-xs text-bone">{percent.toFixed(0)}%</div>
      </div>
      <div className="meter">
        <div className={`meter-fill ${color}`} style={{ width: `${Math.min(100, percent)}%` }} />
      </div>
      <div className="font-mono text-[10px] text-pewter mt-2">{sub}</div>
    </div>
  )
}

function formatBytes(n: number): string {
  if (n === 0) return '0 B'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  const i = Math.min(Math.floor(Math.log2(n) / 10), units.length - 1)
  const v = n / Math.pow(1024, i)
  return `${v < 10 && i > 0 ? v.toFixed(1) : Math.round(v)} ${units[i]}`
}
