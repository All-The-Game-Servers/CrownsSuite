// Wails-bound methods from the Go App struct.
//
// In production, Wails generates this file automatically at `wails dev` /
// `wails build` time into frontend/wailsjs/go/main/App.js. During pure
// frontend dev (e.g. running Vite directly for styling work), the window.go
// object doesn't exist; we shim it so the UI renders without crashing.

export interface ConnectionConfig {
  central_url: string
  bundle_dir: string
  insecure_skip_verify: boolean
}

export interface WhoamiResponse {
  progenitor_id: string
  ou: string[]
  cert_not_after: string
  server_version: string
}

export interface Keeper {
  id: string
  workspace_id: string
  display_name: string
  platform: string
  arch: string
  hostname: string
  agent_version: string
  cert_not_after: string
  enrolled_at: string
  last_seen_at?: string
  revoked_at?: string
  connected: boolean
  public_key_fingerprint: string
  resources?: KeeperResourcesSnapshot
}

export interface KeeperResourcesSnapshot {
  reported_at: string
  cpu_cores: number
  cpu_percent_used: number
  mem_total_bytes: number
  mem_used_bytes: number
  disk_total_bytes: number
  disk_used_bytes: number
}

export interface Instance {
  instance_id: string
  workspace_id: string
  keeper_id: string
  egg_id: string
  display_name: string
  state: string
  hostname?: string
  host_port?: number
  public_port?: number
  memory_bytes: number
  cpu_shares: number
  created_at: string
  updated_at: string
}

export interface Backup {
  backup_id: string
  instance_id: string
  display_name: string
  status: string
  storage_mode: string
  total_bytes: number
  chunk_count: number
  encrypted: boolean
  created_at: string
  completed_at?: string
  error?: string
}

export interface Schedule {
  schedule_id: string
  workspace_id: string
  instance_id: string
  cron_expr: string
  enabled: boolean
  retention: number
  encrypt: boolean
  next_run_at: string
  last_run_at?: string
  last_backup_id?: string
}

export interface MintTokenResponse {
  token: string
  expires_at: string
}

export interface CreateInstanceRequest {
  egg_id: string
  display_name: string
  hostname?: string
  env?: Record<string, string>
  memory_bytes: number
  cpu_shares: number
}

export interface CreateInstanceResponse {
  instance_id: string
  task_id: string
  hostname?: string
  public_port?: number
}

export interface CreateBackupRequest {
  display_name?: string
  storage_mode?: string
  encrypted?: boolean
  stop_during_backup?: boolean
}

export interface CreateBackupResponse {
  backup_id: string
  task_id: string
  instance_id: string
  storage_mode: string
  encrypted: boolean
}

export interface RestoreBackupResponse {
  backup_id: string
  target_instance_id: string
  task_id: string
}

export interface InstanceLogsTailResult {
  instance_id: string
  lines: string[]
  truncated: boolean
}

export interface ConsoleWriteResponse {
  task_id: string
}

export interface CreateScheduleRequest {
  cron_expr: string
  retention?: number
  storage_mode?: string
  encrypt?: boolean
}

export interface Task {
  task_id: string
  keeper_id: string
  instance_id?: string
  kind: string
  status: string
  error_code?: string
  error_message?: string
  created_at: string
  dispatched_at?: string
  acked_at?: string
  completed_at?: string
  timeout_secs: number
  result?: any
}

export interface AuditEntry {
  id: number
  at: string
  kind: string
  actor: string
  keeper_id?: string
  details: Record<string, any>
}

// The runtime shape Wails provides at runtime.
interface WailsGoApp {
  Connect(cfg: ConnectionConfig): Promise<void>
  Disconnect(): Promise<void>
  IsConnected(): Promise<boolean>
  SavedConnection(): Promise<ConnectionConfig | null>
  Whoami(): Promise<WhoamiResponse>
  ListKeepers(): Promise<Keeper[]>
  MintEnrollmentToken(note: string): Promise<MintTokenResponse>
  ListAllInstances(): Promise<Instance[]>
  CreateInstance(keeperID: string, req: CreateInstanceRequest): Promise<CreateInstanceResponse>
  StartInstance(instanceID: string): Promise<void>
  StopInstance(instanceID: string): Promise<void>
  DeleteInstance(instanceID: string): Promise<void>
  GetInstanceLogs(instanceID: string, lines: number): Promise<InstanceLogsTailResult>
  WriteInstanceConsole(instanceID: string, input: string): Promise<ConsoleWriteResponse>
  ListBackupsForInstance(instanceID: string): Promise<Backup[]>
  CreateBackup(instanceID: string, req: CreateBackupRequest): Promise<CreateBackupResponse>
  RestoreBackup(backupID: string, targetInstanceID: string): Promise<RestoreBackupResponse>
  DeleteBackup(backupID: string): Promise<void>
  ListAllSchedules(): Promise<Schedule[]>
  CreateSchedule(instanceID: string, req: CreateScheduleRequest): Promise<Schedule>
  ListTasks(instanceID: string, limit: number): Promise<Task[]>
  ListAudit(limit: number): Promise<AuditEntry[]>
}

declare global {
  interface Window {
    go?: {
      main?: {
        App?: WailsGoApp
      }
    }
  }
}

// The proxy resolves window.go.main.App at call time, so if the runtime
// isn't injected yet (race on first render) we still pick it up on subsequent
// calls. All methods return rejected promises when not in a Wails context.
function resolveApp(): WailsGoApp | null {
  if (typeof window === 'undefined') return null
  return window.go?.main?.App ?? null
}

export const App: WailsGoApp = new Proxy({} as WailsGoApp, {
  get(_target, prop: string) {
    return (...args: any[]) => {
      const app = resolveApp()
      if (!app) {
        return Promise.reject(
          new Error('Wails runtime not available. Run through `wails dev` or a built binary.')
        )
      }
      const fn = (app as any)[prop]
      if (typeof fn !== 'function') {
        return Promise.reject(new Error(`Method ${prop} not bound`))
      }
      return fn.apply(app, args)
    }
  },
})
