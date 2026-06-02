// Wails-bound methods from the Go GUIAPI struct.
// Mirrors cmd/keeper/main_gui.go. Wails replaces this at build time with
// generated bindings; the hand-written version exists so Vite can type-check
// the frontend in isolation.

export interface HostStats {
  cpu_percent: number
  memory_used: number
  memory_total: number
  memory_percent: number
  disk_used: number
  disk_total: number
  disk_percent: number
  sampled_at: string
}

export interface StatusResponse {
  keeper_id: string
  version: string
  central_url: string
  data_root: string
  paused: boolean
  pause_reason?: string
  paused_at?: string
  host?: HostStats
}

export interface InstanceRow {
  instance_id: string
  egg_id: string
  display_name: string
  state: string
  container_id?: string
}

interface WailsGUIAPI {
  Status(): Promise<StatusResponse>
  Instances(): Promise<InstanceRow[]>
  Pause(reason: string): Promise<void>
  Unpause(): Promise<void>
}

declare global {
  interface Window {
    go?: {
      main?: {
        GUIAPI?: WailsGUIAPI
      }
    }
  }
}

function resolve(): WailsGUIAPI | null {
  if (typeof window === 'undefined') return null
  return window.go?.main?.GUIAPI ?? null
}

export const API: WailsGUIAPI = new Proxy({} as WailsGUIAPI, {
  get(_t, prop: string) {
    return (...args: any[]) => {
      const api = resolve()
      if (!api) {
        return Promise.reject(new Error('Wails runtime not available'))
      }
      const fn = (api as any)[prop]
      if (typeof fn !== 'function') {
        return Promise.reject(new Error(`Method ${prop} not bound`))
      }
      return fn.apply(api, args)
    }
  },
})
