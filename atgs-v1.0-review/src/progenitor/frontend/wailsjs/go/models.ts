export namespace client {
	
	export class Backup {
	    backup_id: string;
	    instance_id: string;
	    display_name: string;
	    status: string;
	    storage_mode: string;
	    total_bytes: number;
	    chunk_count: number;
	    encrypted: boolean;
	    // Go type: time
	    created_at: any;
	    // Go type: time
	    completed_at?: any;
	    error?: string;
	
	    static createFrom(source: any = {}) {
	        return new Backup(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.backup_id = source["backup_id"];
	        this.instance_id = source["instance_id"];
	        this.display_name = source["display_name"];
	        this.status = source["status"];
	        this.storage_mode = source["storage_mode"];
	        this.total_bytes = source["total_bytes"];
	        this.chunk_count = source["chunk_count"];
	        this.encrypted = source["encrypted"];
	        this.created_at = this.convertValues(source["created_at"], null);
	        this.completed_at = this.convertValues(source["completed_at"], null);
	        this.error = source["error"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class CreateBackupRequest {
	    display_name?: string;
	    storage_mode?: string;
	    encrypted?: boolean;
	    stop_during_backup?: boolean;
	
	    static createFrom(source: any = {}) {
	        return new CreateBackupRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.display_name = source["display_name"];
	        this.storage_mode = source["storage_mode"];
	        this.encrypted = source["encrypted"];
	        this.stop_during_backup = source["stop_during_backup"];
	    }
	}
	export class CreateBackupResponse {
	    backup_id: string;
	    task_id: string;
	    instance_id: string;
	    storage_mode: string;
	    encrypted: boolean;
	
	    static createFrom(source: any = {}) {
	        return new CreateBackupResponse(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.backup_id = source["backup_id"];
	        this.task_id = source["task_id"];
	        this.instance_id = source["instance_id"];
	        this.storage_mode = source["storage_mode"];
	        this.encrypted = source["encrypted"];
	    }
	}
	export class CreateInstanceRequest {
	    egg_id: string;
	    display_name: string;
	    hostname?: string;
	    env?: Record<string, string>;
	    memory_bytes: number;
	    cpu_shares: number;
	
	    static createFrom(source: any = {}) {
	        return new CreateInstanceRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.egg_id = source["egg_id"];
	        this.display_name = source["display_name"];
	        this.hostname = source["hostname"];
	        this.env = source["env"];
	        this.memory_bytes = source["memory_bytes"];
	        this.cpu_shares = source["cpu_shares"];
	    }
	}
	export class CreateInstanceResponse {
	    instance_id: string;
	    task_id: string;
	    hostname?: string;
	
	    static createFrom(source: any = {}) {
	        return new CreateInstanceResponse(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.instance_id = source["instance_id"];
	        this.task_id = source["task_id"];
	        this.hostname = source["hostname"];
	    }
	}
	export class CreateScheduleRequest {
	    cron_expr: string;
	    retention?: number;
	    storage_mode?: string;
	    encrypt?: boolean;
	
	    static createFrom(source: any = {}) {
	        return new CreateScheduleRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.cron_expr = source["cron_expr"];
	        this.retention = source["retention"];
	        this.storage_mode = source["storage_mode"];
	        this.encrypt = source["encrypt"];
	    }
	}
	export class Instance {
	    instance_id: string;
	    keeper_id: string;
	    egg_id: string;
	    display_name: string;
	    state: string;
	    memory_bytes: number;
	    cpu_shares: number;
	    // Go type: time
	    created_at: any;
	    // Go type: time
	    updated_at: any;
	
	    static createFrom(source: any = {}) {
	        return new Instance(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.instance_id = source["instance_id"];
	        this.keeper_id = source["keeper_id"];
	        this.egg_id = source["egg_id"];
	        this.display_name = source["display_name"];
	        this.state = source["state"];
	        this.memory_bytes = source["memory_bytes"];
	        this.cpu_shares = source["cpu_shares"];
	        this.created_at = this.convertValues(source["created_at"], null);
	        this.updated_at = this.convertValues(source["updated_at"], null);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class Keeper {
	    id: string;
	    version: string;
	    state: string;
	    // Go type: time
	    cert_not_after: any;
	    // Go type: time
	    created_at: any;
	    // Go type: time
	    last_seen_at?: any;
	
	    static createFrom(source: any = {}) {
	        return new Keeper(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.version = source["version"];
	        this.state = source["state"];
	        this.cert_not_after = this.convertValues(source["cert_not_after"], null);
	        this.created_at = this.convertValues(source["created_at"], null);
	        this.last_seen_at = this.convertValues(source["last_seen_at"], null);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class MintTokenResponse {
	    token: string;
	    // Go type: time
	    expires_at: any;
	
	    static createFrom(source: any = {}) {
	        return new MintTokenResponse(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.token = source["token"];
	        this.expires_at = this.convertValues(source["expires_at"], null);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class Schedule {
	    schedule_id: string;
	    instance_id: string;
	    cron_expr: string;
	    enabled: boolean;
	    retention: number;
	    encrypt: boolean;
	    // Go type: time
	    next_run_at: any;
	    // Go type: time
	    last_run_at?: any;
	    last_backup_id?: string;
	
	    static createFrom(source: any = {}) {
	        return new Schedule(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.schedule_id = source["schedule_id"];
	        this.instance_id = source["instance_id"];
	        this.cron_expr = source["cron_expr"];
	        this.enabled = source["enabled"];
	        this.retention = source["retention"];
	        this.encrypt = source["encrypt"];
	        this.next_run_at = this.convertValues(source["next_run_at"], null);
	        this.last_run_at = this.convertValues(source["last_run_at"], null);
	        this.last_backup_id = source["last_backup_id"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class WhoamiResponse {
	    progenitor_id: string;
	    ou: string[];
	    // Go type: time
	    cert_not_after: any;
	    server_version: string;
	
	    static createFrom(source: any = {}) {
	        return new WhoamiResponse(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.progenitor_id = source["progenitor_id"];
	        this.ou = source["ou"];
	        this.cert_not_after = this.convertValues(source["cert_not_after"], null);
	        this.server_version = source["server_version"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}

}

export namespace main {
	
	export class ConnectionConfig {
	    central_url: string;
	    bundle_dir: string;
	    insecure_skip_verify: boolean;
	
	    static createFrom(source: any = {}) {
	        return new ConnectionConfig(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.central_url = source["central_url"];
	        this.bundle_dir = source["bundle_dir"];
	        this.insecure_skip_verify = source["insecure_skip_verify"];
	    }
	}

}

