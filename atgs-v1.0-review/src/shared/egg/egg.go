// Package egg defines the "egg" manifest format, which describes how to run
// a specific kind of game server (Minecraft Java Paper, Velocity proxy, etc).
//
// Origin: this concept was developed in the original Node.js ATGS panel. The
// Go implementation here is a port. The directory shape is preserved so
// existing eggs can be dropped in with minor edits.
//
// Layout of an egg on disk:
//
//	eggs/
//	  minecraft-java-paper/
//	    config.json          # this manifest
//	    install.sh           # runs once at instance creation (optional)
//	    run.sh               # not used in Docker mode; kept for reference
//
// Config.json example:
//
//	{
//	  "id": "minecraft-java-paper",
//	  "name": "Minecraft Java (Paper)",
//	  "description": "Paper-based Minecraft Java server.",
//	  "docker_image": "itzg/minecraft-server:java21",
//	  "env": {
//	    "EULA": "TRUE",
//	    "TYPE": "PAPER",
//	    "VERSION": "LATEST",
//	    "MEMORY": "2G"
//	  },
//	  "ports": [
//	    { "container_port": 25565, "protocol": "tcp" }
//	  ],
//	  "data_volumes": ["/data"],
//	  "stop_command": "stop",
//	  "stop_timeout_secs": 30
//	}
package egg

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// Manifest is the parsed form of config.json.
type Manifest struct {
	ID           string            `json:"id"`
	Name         string            `json:"name"`
	Description  string            `json:"description,omitempty"`
	DockerImage  string            `json:"docker_image"`
	Env          map[string]string `json:"env,omitempty"`
	Ports        []EggPort         `json:"ports,omitempty"`
	DataVolumes  []string          `json:"data_volumes,omitempty"`
	StopCommand  string            `json:"stop_command,omitempty"`
	StopTimeoutSecs int            `json:"stop_timeout_secs,omitempty"`
}

// EggPort describes a port the server exposes. host_port is assigned
// dynamically by the Keeper when an instance is created.
type EggPort struct {
	ContainerPort int    `json:"container_port"`
	Protocol      string `json:"protocol"`
	Description   string `json:"description,omitempty"`

	// Public (Phase 8): when true, the keeper binds the host port to
	// 0.0.0.0 so remote clients can connect directly. When false (default),
	// the host port is bound to 127.0.0.1 and remote clients must come in
	// through the relay. Public ports are still useful for direct-connect
	// operator workflows, but relay-routed instances no longer depend on
	// exposing them publicly.
	Public bool `json:"public,omitempty"`
}

// Validate checks the manifest for the fields that are required for an
// egg to be usable. Returns a descriptive error if anything is missing.
func (m *Manifest) Validate() error {
	if m.ID == "" {
		return errors.New("egg.id is required")
	}
	if m.Name == "" {
		return errors.New("egg.name is required")
	}
	if m.DockerImage == "" {
		return errors.New("egg.docker_image is required")
	}
	for i, p := range m.Ports {
		if p.ContainerPort <= 0 || p.ContainerPort > 65535 {
			return fmt.Errorf("egg.ports[%d].container_port %d is out of range", i, p.ContainerPort)
		}
		if p.Protocol != "tcp" && p.Protocol != "udp" {
			return fmt.Errorf("egg.ports[%d].protocol must be tcp or udp, got %q", i, p.Protocol)
		}
	}
	if m.StopTimeoutSecs < 0 {
		return errors.New("egg.stop_timeout_secs must be non-negative")
	}
	return nil
}

// LoadFromDir reads and validates a single egg from its directory.
func LoadFromDir(dir string) (*Manifest, error) {
	data, err := os.ReadFile(filepath.Join(dir, "config.json"))
	if err != nil {
		return nil, fmt.Errorf("read config.json: %w", err)
	}
	var m Manifest
	if err := json.Unmarshal(data, &m); err != nil {
		return nil, fmt.Errorf("decode config.json: %w", err)
	}
	if err := m.Validate(); err != nil {
		return nil, fmt.Errorf("validate %s: %w", dir, err)
	}
	return &m, nil
}

// Registry holds all eggs available on a Keeper. Safe for concurrent read.
type Registry struct {
	byID map[string]*Manifest
}

// LoadRegistry walks an eggs directory and loads every subdirectory that
// contains a config.json. Failures on individual eggs are logged via the
// errorSink callback but do not abort the whole load, so one broken egg
// doesn't take down the Keeper.
func LoadRegistry(rootDir string, errorSink func(dir string, err error)) (*Registry, error) {
	r := &Registry{byID: make(map[string]*Manifest)}
	entries, err := os.ReadDir(rootDir)
	if err != nil {
		return nil, fmt.Errorf("read eggs dir: %w", err)
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		dir := filepath.Join(rootDir, e.Name())
		m, err := LoadFromDir(dir)
		if err != nil {
			if errorSink != nil {
				errorSink(dir, err)
			}
			continue
		}
		if _, exists := r.byID[m.ID]; exists {
			if errorSink != nil {
				errorSink(dir, fmt.Errorf("duplicate egg id %q", m.ID))
			}
			continue
		}
		r.byID[m.ID] = m
	}
	return r, nil
}

// Get returns an egg by ID, or nil if not found.
func (r *Registry) Get(id string) *Manifest {
	return r.byID[id]
}

// All returns a slice of every loaded manifest, in undefined order.
func (r *Registry) All() []*Manifest {
	out := make([]*Manifest, 0, len(r.byID))
	for _, m := range r.byID {
		out = append(out, m)
	}
	return out
}

// Count returns the number of loaded eggs.
func (r *Registry) Count() int { return len(r.byID) }
