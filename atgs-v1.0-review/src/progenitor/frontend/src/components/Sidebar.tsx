import { useEffect, useState } from 'react'
import { App as WailsApp, WhoamiResponse } from '../wails'
import { ViewKey } from '../App'

interface Props {
  active: ViewKey
  onChange: (v: ViewKey) => void
  onDisconnect: () => void | Promise<void>
}

const nav: { key: ViewKey; label: string; num: string }[] = [
  { key: 'keepers', label: 'Keepers', num: 'I' },
  { key: 'instances', label: 'Instances', num: 'II' },
  { key: 'operations', label: 'Operations', num: 'III' },
  { key: 'backups', label: 'Backups', num: 'IV' },
  { key: 'schedules', label: 'Schedules', num: 'V' },
]

export function Sidebar({ active, onChange, onDisconnect }: Props) {
  const [whoami, setWhoami] = useState<WhoamiResponse | null>(null)

  useEffect(() => {
    WailsApp.Whoami().then(setWhoami).catch(() => setWhoami(null))
  }, [])

  return (
    <aside className="flex w-64 shrink-0 flex-col border-r border-iron bg-carbon">
      <header className="border-b border-iron px-6 pb-6 pt-7">
        <div className="font-display text-5xl leading-none tracking-tight text-gold">ATGS</div>
        <div className="mt-2 font-display text-sm italic text-parchment">Progenitor Console</div>
        <div className="mt-1 label">v1.1 operator surface</div>
      </header>

      <nav className="flex-1 py-4">
        <div className="label mb-2 px-6">Sections</div>
        {nav.map((item, i) => {
          const isActive = active === item.key
          return (
            <button
              key={item.key}
              onClick={() => onChange(item.key)}
              className={
                'enter flex w-full items-baseline gap-3 px-6 py-3 text-left transition-colors ' +
                (isActive ? 'bg-obsidian text-bone' : 'text-parchment hover:bg-obsidian/50 hover:text-bone')
              }
              style={{ animationDelay: `${60 * i}ms` }}
            >
              <span className={'w-6 font-mono text-xs ' + (isActive ? 'text-gold' : 'text-pewter')}>{item.num}.</span>
              <span className={'font-display text-xl ' + (isActive ? 'text-bone' : '')}>{item.label}</span>
              {isActive && <span className="ml-auto h-5 w-1 self-center bg-gold" />}
            </button>
          )
        })}
      </nav>

      <footer className="border-t border-iron px-6 py-5 text-xs">
        {whoami ? (
          <>
            <div className="label mb-2">Operator</div>
            <div className="truncate font-mono text-parchment" title={whoami.progenitor_id}>
              {whoami.progenitor_id}
            </div>
            <div className="mb-1 mt-3 label">Central</div>
            <div className="font-mono text-parchment">{whoami.server_version}</div>
          </>
        ) : (
          <div className="label">Identifying...</div>
        )}
        <button className="btn-ghost mt-4 w-full justify-center text-xs" onClick={onDisconnect}>
          Disconnect
        </button>
      </footer>
    </aside>
  )
}
