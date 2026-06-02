import { useEffect, useState } from 'react'
import { App as WailsApp, ConnectionConfig } from '../wails'

// ConnectionSetup is the first screen an operator sees. It sets the tone for
// the whole app: serif display type, hairline rules, deliberate asymmetry.

interface Props {
  initialError?: string
  onConnected: () => void
}

export function ConnectionSetup({ initialError, onConnected }: Props) {
  const [centralURL, setCentralURL] = useState('https://127.0.0.1:8443')
  const [bundleDir, setBundleDir] = useState('')
  const [insecure, setInsecure] = useState(true)
  const [error, setError] = useState<string | undefined>(initialError)
  const [connecting, setConnecting] = useState(false)

  useEffect(() => {
    WailsApp.SavedConnection().then(cfg => {
      if (cfg) {
        setCentralURL(cfg.central_url)
        setBundleDir(cfg.bundle_dir)
        setInsecure(cfg.insecure_skip_verify)
      }
    }).catch(() => {})
  }, [])

  const submit = async () => {
    setError(undefined)
    setConnecting(true)
    try {
      const cfg: ConnectionConfig = {
        central_url: centralURL,
        bundle_dir: bundleDir,
        insecure_skip_verify: insecure,
      }
      await WailsApp.Connect(cfg)
      onConnected()
    } catch (e: any) {
      setError(String(e?.message ?? e))
    } finally {
      setConnecting(false)
    }
  }

  return (
    <div className="h-full flex">
      {/* Left panel — masthead, pure typography, nothing functional */}
      <div className="w-2/5 border-r border-iron px-16 py-20 flex flex-col justify-between bg-carbon">
        <div className="enter">
          <div className="section-number mb-6">§ 0 — enrollment</div>
          <div className="font-display text-[8rem] leading-[0.85] text-gold tracking-tighter">
            ATGS
          </div>
          <div className="mt-4 font-display italic text-3xl text-parchment">
            Progenitor Console
          </div>
          <div className="mt-8 flex items-center gap-4">
            <div className="h-px w-24 bg-gold/60" />
            <div className="label">v 0.5 · phase V</div>
          </div>
          <p className="mt-12 font-display text-xl text-parchment leading-relaxed max-w-md">
            A control surface for the fleet. Issue certificates, enroll
            Keepers, dispatch game servers, and move their volumes across the
            cosmos with an auditor's paper trail.
          </p>
        </div>

        <div className="enter" style={{ animationDelay: '400ms' }}>
          <div className="label mb-2">XKStudios</div>
          <div className="font-mono text-xs text-pewter leading-relaxed">
            Built by operators, for operators. Single-binary, self-hosted,
            mTLS-signed. No telemetry, no vendor lock.
          </div>
        </div>
      </div>

      {/* Right panel — the form */}
      <div className="flex-1 px-16 py-20 overflow-auto">
        <div className="max-w-lg enter" style={{ animationDelay: '120ms' }}>
          <div className="section-number mb-3">§ I — establish link</div>
          <h2 className="section-title mb-3">Connect to Central</h2>
          <p className="section-sub mb-10">
            Point the console at a running Central and present your
            operator certificate bundle.
          </p>

          <div className="space-y-8">
            <Field
              label="Central URL"
              hint="The keeper listener — port 8443 by default."
            >
              <input
                className="input-mono"
                value={centralURL}
                onChange={e => setCentralURL(e.target.value)}
                placeholder="https://central.example.com:8443"
              />
            </Field>

            <Field
              label="Certificate bundle directory"
              hint={
                <>
                  Contains <Mono>client.crt</Mono>, <Mono>client.key</Mono>,{' '}
                  <Mono>ca.crt</Mono>, <Mono>progenitor.id</Mono>. Generate on
                  Central with <Mono>central mint-progenitor-cert &lt;dir&gt;</Mono>.
                </>
              }
            >
              <input
                className="input-mono"
                value={bundleDir}
                onChange={e => setBundleDir(e.target.value)}
                placeholder="/home/operator/progenitor-bundle"
              />
            </Field>

            <label className="flex items-start gap-3 cursor-pointer select-none group">
              <span
                className={
                  'mt-0.5 w-4 h-4 border flex items-center justify-center flex-shrink-0 transition-colors ' +
                  (insecure ? 'bg-gold border-gold' : 'border-pewter group-hover:border-bone')
                }
              >
                {insecure && <span className="text-obsidian text-xs font-bold">✓</span>}
              </span>
              <input
                type="checkbox"
                className="sr-only"
                checked={insecure}
                onChange={e => setInsecure(e.target.checked)}
              />
              <div>
                <div className="text-bone">Skip server certificate verification</div>
                <div className="text-xs text-pewter mt-1 font-display italic">
                  Required when Central uses its self-issued development
                  certificate. Leave off in production.
                </div>
              </div>
            </label>

            {error && (
              <div className="border-l-2 border-rust bg-rust/5 px-4 py-3">
                <div className="label text-rust mb-1">Connection failed</div>
                <div className="text-parchment font-mono text-xs break-all">{error}</div>
              </div>
            )}

            <div className="pt-2">
              <button
                className="btn-gold px-8 py-3 text-base"
                disabled={connecting || !centralURL || !bundleDir}
                onClick={submit}
              >
                {connecting ? 'Establishing…' : 'Establish link →'}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function Field({
  label,
  hint,
  children,
}: {
  label: string
  hint?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <div>
      <div className="label mb-2">{label}</div>
      {children}
      {hint && <div className="mt-2 text-xs text-pewter leading-relaxed">{hint}</div>}
    </div>
  )
}

function Mono({ children }: { children: React.ReactNode }) {
  return <span className="font-mono text-parchment">{children}</span>
}
