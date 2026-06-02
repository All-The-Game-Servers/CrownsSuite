import { useEffect, useState } from 'react'
import { App as WailsApp, Keeper, MintTokenResponse } from '../wails'
import { formatBytes, formatDate, formatPercent, formatRelative, stateTagClass, shortID } from '../lib/fmt'
import { PageHeader } from '../components/PageHeader'
import { ErrorBanner, EmptyState, Loading } from '../components/Misc'

export function KeepersView() {
  const [keepers, setKeepers] = useState<Keeper[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | undefined>()
  const [minted, setMinted] = useState<MintTokenResponse | null>(null)
  const [minting, setMinting] = useState(false)

  const refresh = async () => {
    setError(undefined)
    try {
      const ks = await WailsApp.ListKeepers()
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

  const mintToken = async () => {
    setMinting(true)
    try {
      const out = await WailsApp.MintEnrollmentToken(`progenitor ${new Date().toISOString()}`)
      setMinted(out)
    } catch (e: any) {
      setError(String(e?.message ?? e))
    } finally {
      setMinting(false)
    }
  }

  return (
    <div>
      <PageHeader
        num="I"
        title="Keepers"
        subtitle="Hosts enrolled to this Central, with live connectivity and last reported machine health."
        right={
          <button className="btn-gold" onClick={mintToken} disabled={minting}>
            {minting ? 'Minting...' : 'Mint enrollment token'}
          </button>
        }
      />

      {minted && (
        <div className="mb-8 border border-gold/40 bg-gold/5 px-6 py-5 enter">
          <div className="mb-3 flex items-start justify-between">
            <div>
              <div className="label mb-1 text-gold">Enrollment token, single use</div>
              <div className="section-sub">Expires {formatRelative(minted.expires_at)}.</div>
            </div>
            <button className="btn-ghost text-xs" onClick={() => setMinted(null)}>
              Dismiss
            </button>
          </div>
          <div className="break-all border border-iron bg-obsidian px-4 py-3 font-mono text-sm text-bone select-all">
            {minted.token}
          </div>
          <div className="mt-3 text-xs font-display italic text-pewter">
            Set <span className="font-mono text-parchment">ATGS_ENROLL_TOKEN</span> on the Keeper host and launch{' '}
            <span className="font-mono text-parchment">./keeper</span>.
          </div>
        </div>
      )}

      <ErrorBanner error={error} />

      {loading ? (
        <Loading />
      ) : keepers.length === 0 ? (
        <EmptyState>No Keepers enrolled. Mint a token above to add the first host.</EmptyState>
      ) : (
        <div className="border border-iron enter">
          <table className="data-table">
            <thead>
              <tr>
                <th>Keeper</th>
                <th>State</th>
                <th>Agent</th>
                <th>Resources</th>
                <th>Last Report</th>
                <th>Certificate</th>
              </tr>
            </thead>
            <tbody>
              {keepers.map((k) => (
                <tr key={k.id}>
                  <td>
                    <div className="font-display text-bone">{k.display_name || shortID(k.id)}</div>
                    <div className="font-mono text-[10px] text-pewter" title={k.id}>
                      {shortID(k.id)} • {k.platform}/{k.arch}
                    </div>
                    <div className="font-mono text-[10px] text-pewter/80">ws {shortID(k.workspace_id)}</div>
                    <div className="font-mono text-[10px] text-pewter">{k.hostname}</div>
                  </td>
                  <td>
                    <span className={stateTagClass(k.connected ? 'connected' : 'disconnected')}>
                      {k.connected ? 'connected' : 'offline'}
                    </span>
                    {k.revoked_at && <span className="tag-danger ml-1">revoked</span>}
                  </td>
                  <td className="font-mono text-xs text-parchment">{k.agent_version || '—'}</td>
                  <td className="font-mono text-xs text-pewter">
                    {k.resources ? (
                      <div className="space-y-1">
                        <div>cpu {formatPercent(k.resources.cpu_percent_used)}</div>
                        <div>
                          mem {formatBytes(k.resources.mem_used_bytes)} / {formatBytes(k.resources.mem_total_bytes)}
                        </div>
                        <div>
                          disk {formatBytes(k.resources.disk_used_bytes)} / {formatBytes(k.resources.disk_total_bytes)}
                        </div>
                      </div>
                    ) : (
                      '—'
                    )}
                  </td>
                  <td className="font-display italic text-parchment">
                    {k.resources?.reported_at ? formatRelative(k.resources.reported_at) : formatRelative(k.last_seen_at)}
                  </td>
                  <td className="font-mono text-xs text-pewter">exp {formatDate(k.cert_not_after)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
