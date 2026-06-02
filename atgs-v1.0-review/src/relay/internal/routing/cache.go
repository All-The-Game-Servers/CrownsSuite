// Package routing is the relay's local routing-table cache.
//
// The relay maintains a SQLite-backed mirror of Central's routing table so
// a fresh start doesn't immediately trigger a full snapshot pull. Lookup is
// O(1) via the in-memory map; the SQLite table is the durable backing.
//
// Writes go through Apply (for deltas) or Replace (for snapshots). Both
// update the in-memory map AND persist to SQLite in the same call so a
// crash between the two can't leave the cache inconsistent.
package routing

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

const schema = `
CREATE TABLE IF NOT EXISTS routing_entries (
    route_kind  TEXT NOT NULL,
    hostname    TEXT NOT NULL DEFAULT '',
    public_port INTEGER NOT NULL DEFAULT 0,
    instance_id TEXT NOT NULL,
    keeper_id   TEXT NOT NULL,
    host_port   INTEGER NOT NULL,
    protocol    TEXT NOT NULL DEFAULT 'tcp',
    version     INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (route_kind, hostname, public_port)
);

CREATE TABLE IF NOT EXISTS cache_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
`

const metaKeyKnownVersion = "known_version"

// Entry is one routing record as the relay sees it.
type Entry struct {
	RouteKind  string
	Hostname   string
	PublicPort int
	InstanceID string
	KeeperID   string
	HostPort   int
	Protocol   string
	Version    int64
}

// Cache is a concurrent-safe routing table, persisted to SQLite.
type Cache struct {
	db *sql.DB

	mu      sync.RWMutex
	byHost  map[string]Entry
	byPublicPort map[int]Entry
	version int64
}

// Open creates or reopens the cache at <stateDir>/routing.db.
func Open(stateDir string) (*Cache, error) {
	path := filepath.Join(stateDir, "routing.db")
	db, err := sql.Open("sqlite", path+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, fmt.Errorf("ping: %w", err)
	}
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("schema: %w", err)
	}
	if err := ensureSchema(db); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate schema: %w", err)
	}
	c := &Cache{
		db:     db,
		byHost: make(map[string]Entry),
		byPublicPort: make(map[int]Entry),
	}
	if err := c.loadFromDB(); err != nil {
		db.Close()
		return nil, fmt.Errorf("load cache: %w", err)
	}
	return c, nil
}

func (c *Cache) Close() error { return c.db.Close() }

// loadFromDB populates the in-memory map from SQLite at startup.
func (c *Cache) loadFromDB() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	rows, err := c.db.QueryContext(ctx, `
		SELECT route_kind, hostname, public_port, instance_id, keeper_id, host_port, protocol, version
		FROM routing_entries
	`)
	if err != nil {
		return err
	}
	defer rows.Close()

	for rows.Next() {
		var e Entry
		if err := rows.Scan(&e.RouteKind, &e.Hostname, &e.PublicPort, &e.InstanceID, &e.KeeperID, &e.HostPort, &e.Protocol, &e.Version); err != nil {
			return err
		}
		c.indexEntry(e)
	}
	if err := rows.Err(); err != nil {
		return err
	}

	// Load known_version.
	var raw string
	err = c.db.QueryRowContext(ctx, `SELECT value FROM cache_meta WHERE key = ?`, metaKeyKnownVersion).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		c.version = 0
		return nil
	}
	if err != nil {
		return err
	}
	fmt.Sscanf(raw, "%d", &c.version)
	return nil
}

// KnownVersion returns the highest version the cache has applied. Used by
// the sync client to decide whether to request snapshot or delta on connect.
func (c *Cache) KnownVersion() int64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.version
}

// Lookup returns the entry for a hostname, or (_, false) if absent.
// Case-insensitive; lookup normalizes to lowercase.
func (c *Cache) Lookup(hostname string) (Entry, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	e, ok := c.byHost[NormalizeHostname(hostname)]
	return e, ok
}

func (c *Cache) LookupPublicPort(publicPort int) (Entry, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	e, ok := c.byPublicPort[publicPort]
	return e, ok
}

// Size returns the number of entries in the cache. Useful for observability.
func (c *Cache) Size() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.byHost) + len(c.byPublicPort)
}

// Replace atomically swaps the cache contents with the given snapshot and
// records the new known_version.
func (c *Cache) Replace(ctx context.Context, entries []Entry, newVersion int64) error {
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, `DELETE FROM routing_entries`); err != nil {
		return err
	}
	stmt, err := tx.PrepareContext(ctx, `
		INSERT INTO routing_entries (route_kind, hostname, public_port, instance_id, keeper_id, host_port, protocol, version, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
	`)
	if err != nil {
		return err
	}
	now := time.Now().Unix()
	for _, e := range entries {
		if _, err := stmt.ExecContext(ctx, routeKind(e.RouteKind), normalizeRouteHostname(e), routePublicPort(e), e.InstanceID, e.KeeperID, e.HostPort, routeProtocol(e.Protocol), e.Version, now); err != nil {
			stmt.Close()
			return err
		}
	}
	stmt.Close()

	if _, err := tx.ExecContext(ctx,
		`INSERT INTO cache_meta (key, value) VALUES (?, ?)
		 ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
		metaKeyKnownVersion, fmt.Sprintf("%d", newVersion)); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}

	// Swap the in-memory map under the write lock.
	c.mu.Lock()
	c.byHost = make(map[string]Entry, len(entries))
	c.byPublicPort = make(map[int]Entry, len(entries))
	for _, e := range entries {
		e.RouteKind = routeKind(e.RouteKind)
		e.Hostname = normalizeRouteHostname(e)
		e.Protocol = routeProtocol(e.Protocol)
		c.indexEntry(e)
	}
	c.version = newVersion
	c.mu.Unlock()
	return nil
}

// Apply applies a single routing delta.
func (c *Cache) Apply(ctx context.Context, eventType string, e Entry) error {
		host := NormalizeHostname(e.Hostname)
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	now := time.Now().Unix()
	switch eventType {
	case "upsert":
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO routing_entries (route_kind, hostname, public_port, instance_id, keeper_id, host_port, protocol, version, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(route_kind, hostname, public_port) DO UPDATE SET
				instance_id = excluded.instance_id,
				keeper_id   = excluded.keeper_id,
				host_port   = excluded.host_port,
				protocol    = excluded.protocol,
				version     = excluded.version,
				updated_at  = excluded.updated_at
		`, routeKind(e.RouteKind), host, routePublicPort(e), e.InstanceID, e.KeeperID, e.HostPort, routeProtocol(e.Protocol), e.Version, now); err != nil {
			return err
		}
	case "delete":
		if _, err := tx.ExecContext(ctx, `DELETE FROM routing_entries WHERE route_kind = ? AND hostname = ? AND public_port = ?`, routeKind(e.RouteKind), host, routePublicPort(e)); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unknown event type: %s", eventType)
	}

	if _, err := tx.ExecContext(ctx,
		`INSERT INTO cache_meta (key, value) VALUES (?, ?)
		 ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
		metaKeyKnownVersion, fmt.Sprintf("%d", e.Version)); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}

	c.mu.Lock()
	switch eventType {
	case "upsert":
		e.RouteKind = routeKind(e.RouteKind)
		e.Hostname = host
		e.Protocol = routeProtocol(e.Protocol)
		c.indexEntry(e)
	case "delete":
		if routeKind(e.RouteKind) == "bedrock_udp" {
			delete(c.byPublicPort, e.PublicPort)
		} else {
			delete(c.byHost, host)
		}
	}
	if e.Version > c.version {
		c.version = e.Version
	}
	c.mu.Unlock()
	return nil
}

func NormalizeHostname(hostname string) string {
	// Lowercase without pulling in strings.ToLower for one call site.
	b := []byte(hostname)
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			b[i] = c + ('a' - 'A')
		}
	}
	return string(b)
}

func ensureSchema(db *sql.DB) error {
	required := map[string]string{
		"route_kind":  `ALTER TABLE routing_entries ADD COLUMN route_kind TEXT NOT NULL DEFAULT 'java_hostname'`,
		"hostname":    `ALTER TABLE routing_entries ADD COLUMN hostname TEXT NOT NULL DEFAULT ''`,
		"public_port": `ALTER TABLE routing_entries ADD COLUMN public_port INTEGER NOT NULL DEFAULT 0`,
		"protocol":    `ALTER TABLE routing_entries ADD COLUMN protocol TEXT NOT NULL DEFAULT 'tcp'`,
	}
	existing, err := tableColumns(db, "routing_entries")
	if err != nil {
		return err
	}
	for col, alter := range required {
		if existing[col] {
			continue
		}
		if _, err := db.Exec(alter); err != nil && !strings.Contains(strings.ToLower(err.Error()), "duplicate column") {
			return err
		}
	}
	if _, err := db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_routing_entries_route ON routing_entries(route_kind, hostname, public_port)`); err != nil {
		return err
	}
	return nil
}

func tableColumns(db *sql.DB, table string) (map[string]bool, error) {
	rows, err := db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	cols := make(map[string]bool)
	for rows.Next() {
		var (
			cid       int
			name      string
			typ       string
			notnull   int
			dfltValue any
			pk        int
		)
		if err := rows.Scan(&cid, &name, &typ, &notnull, &dfltValue, &pk); err != nil {
			return nil, err
		}
		cols[name] = true
	}
	return cols, rows.Err()
}

func normalize(hostname string) string {
	return NormalizeHostname(hostname)
}

func routeKind(kind string) string {
	if kind == "" {
		return "java_hostname"
	}
	return kind
}

func routeProtocol(protocol string) string {
	if protocol == "" {
		return "tcp"
	}
	return protocol
}

func normalizeRouteHostname(e Entry) string {
	if routeKind(e.RouteKind) == "java_hostname" {
		return normalize(e.Hostname)
	}
	return ""
}

func routePublicPort(e Entry) int {
	if routeKind(e.RouteKind) == "bedrock_udp" {
		return e.PublicPort
	}
	return 0
}

func (c *Cache) indexEntry(e Entry) {
	if routeKind(e.RouteKind) == "bedrock_udp" {
		c.byPublicPort[e.PublicPort] = e
		return
	}
	c.byHost[e.Hostname] = e
}

func (c *Cache) Entries() []Entry {
	c.mu.RLock()
	defer c.mu.RUnlock()
	out := make([]Entry, 0, len(c.byHost)+len(c.byPublicPort))
	for _, e := range c.byHost {
		out = append(out, e)
	}
	for _, e := range c.byPublicPort {
		out = append(out, e)
	}
	return out
}
