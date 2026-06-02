package store

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// RoutingEntry is one row of the authoritative routing table: a hostname and
// where to send traffic for it. Delivered to relays via snapshot or delta.
type RoutingEntry struct {
	RouteKind  string
	Protocol   string
	Hostname   string
	PublicPort int
	InstanceID uuid.UUID
	KeeperID   uuid.UUID
	HostPort   int
	Version    int64 // the routing_events.version when this entry was last set
}

// RoutingEvent is the delta form. Event types: "upsert" or "delete".
// On a delete, InstanceID/KeeperID/HostPort are zero and should be ignored.
type RoutingEvent struct {
	Version    int64
	At         time.Time
	EventType  string
	RouteKind  string
	Protocol   string
	Hostname   string
	PublicPort int
	InstanceID uuid.UUID
	KeeperID   uuid.UUID
	HostPort   int
}

// RoutingSnapshot returns the full current routing table plus the maximum
// version that contributed to it. Relays call this on first connect (or when
// their cache is empty).
//
// Implementation: for each hostname that currently resolves to something,
// take the most recent upsert event. Hostnames whose most recent event is a
// delete are excluded.
//
// Returns (entries, currentVersion, error). currentVersion is the max
// version in the log; relays should remember this to resume.
func (s *Store) RoutingSnapshot(ctx context.Context) ([]RoutingEntry, int64, error) {
	// Current version: max of routing_events. Zero if empty.
	var currentVersion int64
	if err := s.pool.QueryRow(ctx, `
		SELECT COALESCE(MAX(version), 0) FROM routing_events
	`).Scan(&currentVersion); err != nil {
		return nil, 0, err
	}

	// Most recent event per hostname, keeping only those whose latest event
	// is an upsert. DISTINCT ON (hostname) ... ORDER BY hostname, version DESC
	// picks the newest row per hostname in a single pass.
	rows, err := s.pool.Query(ctx, `
		SELECT DISTINCT ON (route_kind, hostname, COALESCE(public_port, 0))
		       route_kind, protocol, hostname, public_port, event_type, instance_id, keeper_id, host_port, version
		FROM routing_events
		ORDER BY route_kind, hostname, COALESCE(public_port, 0), version DESC
	`)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var out []RoutingEntry
	for rows.Next() {
		var (
			e           RoutingEntry
			eventType   string
			publicPort  *int
			instanceID  *uuid.UUID
			keeperID    *uuid.UUID
			hostPort    *int
		)
		if err := rows.Scan(&e.RouteKind, &e.Protocol, &e.Hostname, &publicPort, &eventType, &instanceID, &keeperID, &hostPort, &e.Version); err != nil {
			return nil, 0, err
		}
		if eventType != "upsert" {
			continue
		}
		if publicPort != nil {
			e.PublicPort = *publicPort
		}
		if instanceID != nil {
			e.InstanceID = *instanceID
		}
		if keeperID != nil {
			e.KeeperID = *keeperID
		}
		if hostPort != nil {
			e.HostPort = *hostPort
		}
		out = append(out, e)
	}
	return out, currentVersion, rows.Err()
}

// RoutingEventsSince returns all events with version > sinceVersion, in
// ascending version order. Used by relays to catch up from a known point.
// Caller should enforce a page size; this returns everything.
func (s *Store) RoutingEventsSince(ctx context.Context, sinceVersion int64) ([]RoutingEvent, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT version, at, event_type, route_kind, protocol, hostname, public_port, instance_id, keeper_id, host_port
		FROM routing_events
		WHERE version > $1
		ORDER BY version ASC
	`, sinceVersion)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []RoutingEvent
	for rows.Next() {
		var (
			e          RoutingEvent
			publicPort *int
			instanceID *uuid.UUID
			keeperID   *uuid.UUID
			hostPort   *int
		)
		if err := rows.Scan(&e.Version, &e.At, &e.EventType, &e.RouteKind, &e.Protocol, &e.Hostname, &publicPort, &instanceID, &keeperID, &hostPort); err != nil {
			return nil, err
		}
		if publicPort != nil {
			e.PublicPort = *publicPort
		}
		if instanceID != nil {
			e.InstanceID = *instanceID
		}
		if keeperID != nil {
			e.KeeperID = *keeperID
		}
		if hostPort != nil {
			e.HostPort = *hostPort
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// AppendRoutingUpsert records a hostname-to-instance routing assignment.
// Used when an instance is created with a hostname or has its hostname
// changed.
//
// Returns the new version number so the caller can publish a delta to
// live relay connections without waiting for a poll.
func (s *Store) AppendRoutingUpsert(ctx context.Context, routeKind string, hostname string, publicPort int, protocol string, instanceID, keeperID uuid.UUID, hostPort int) (int64, error) {
	var v int64
	err := s.pool.QueryRow(ctx, `
		INSERT INTO routing_events (event_type, route_kind, protocol, hostname, public_port, instance_id, keeper_id, host_port)
		VALUES ('upsert', $1, $2, $3, $4, $5, $6, $7)
		RETURNING version
	`, routeKind, protocol, hostname, nullableInt(publicPort), instanceID, keeperID, hostPort).Scan(&v)
	return v, err
}

// AppendRoutingDelete records that a hostname should no longer route.
// Used when an instance is deleted.
func (s *Store) AppendRoutingDelete(ctx context.Context, routeKind string, hostname string, publicPort int, protocol string) (int64, error) {
	var v int64
	err := s.pool.QueryRow(ctx, `
		INSERT INTO routing_events (event_type, route_kind, protocol, hostname, public_port)
		VALUES ('delete', $1, $2, $3, $4)
		RETURNING version
	`, routeKind, protocol, hostname, nullableInt(publicPort)).Scan(&v)
	return v, err
}

// SetInstanceHostname assigns (or clears) the hostname on an instance row.
// Uniqueness is enforced by the partial index on instances.hostname.
func (s *Store) SetInstanceHostname(ctx context.Context, instanceID uuid.UUID, hostname string) error {
	var h *string
	if hostname != "" {
		h = &hostname
	}
	_, err := s.pool.Exec(ctx, `
		UPDATE instances SET hostname = $2, updated_at = NOW() WHERE instance_id = $1
	`, instanceID, h)
	return err
}

// SetInstanceHostPort stores the host port Docker assigned on the Keeper.
// Separate from SetInstanceContainerID because the Keeper knows the container
// ID at create time but the port only after inspect.
func (s *Store) SetInstanceHostPort(ctx context.Context, instanceID uuid.UUID, hostPort int) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE instances SET host_port = $2, updated_at = NOW() WHERE instance_id = $1
	`, instanceID, hostPort)
	return err
}

func (s *Store) SetInstancePublicPort(ctx context.Context, instanceID uuid.UUID, publicPort int) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE instances SET public_port = $2, updated_at = NOW() WHERE instance_id = $1
	`, instanceID, nullableInt(publicPort))
	return err
}

// GetInstanceHostname returns the hostname assigned to an instance, or "" if
// none. Used during delete so we know what routing entry to retire.
func (s *Store) GetInstanceHostname(ctx context.Context, instanceID uuid.UUID) (string, error) {
	var h *string
	err := s.pool.QueryRow(ctx, `
		SELECT hostname FROM instances WHERE instance_id = $1
	`, instanceID).Scan(&h)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", err
	}
	if h == nil {
		return "", nil
	}
	return *h, nil
}

func (s *Store) GetInstanceRoute(ctx context.Context, instanceID uuid.UUID) (string, int, error) {
	var (
		h *string
		p *int
	)
	err := s.pool.QueryRow(ctx, `
		SELECT hostname, public_port FROM instances WHERE instance_id = $1
	`, instanceID).Scan(&h, &p)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", 0, ErrNotFound
	}
	if err != nil {
		return "", 0, err
	}
	var hostname string
	if h != nil {
		hostname = *h
	}
	var publicPort int
	if p != nil {
		publicPort = *p
	}
	return hostname, publicPort, nil
}

func (s *Store) AllocatePublicPort(ctx context.Context, minPort, maxPort int) (int, error) {
	var port int
	err := s.pool.QueryRow(ctx, `
		SELECT gs.port
		FROM generate_series($1, $2) AS gs(port)
		WHERE NOT EXISTS (
			SELECT 1
			FROM instances i
			WHERE i.public_port = gs.port
			  AND i.deleted_at IS NULL
		)
		ORDER BY gs.port
		LIMIT 1
	`, minPort, maxPort).Scan(&port)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, ErrNotFound
	}
	return port, err
}

func nullableInt(v int) any {
	if v <= 0 {
		return nil
	}
	return v
}
