package routing

import (
	"context"
	"path/filepath"
	"testing"
)

func TestCacheSupportsJavaAndBedrockRoutes(t *testing.T) {
	dir := t.TempDir()
	cache, err := Open(dir)
	if err != nil {
		t.Fatalf("open cache: %v", err)
	}
	defer cache.Close()

	entries := []Entry{
		{
			RouteKind:  "java_hostname",
			Hostname:   "Paper.Mine.BZ",
			InstanceID: "instance-java",
			KeeperID:   "keeper-java",
			HostPort:   25580,
			Protocol:   "tcp",
			Version:    1,
		},
		{
			RouteKind:  "bedrock_udp",
			PublicPort: 19140,
			InstanceID: "instance-bedrock",
			KeeperID:   "keeper-bedrock",
			HostPort:   19132,
			Protocol:   "udp",
			Version:    1,
		},
	}
	if err := cache.Replace(context.Background(), entries, 1); err != nil {
		t.Fatalf("replace: %v", err)
	}

	javaEntry, ok := cache.Lookup("paper.mine.bz")
	if !ok {
		t.Fatal("expected java route")
	}
	if javaEntry.HostPort != 25580 || javaEntry.RouteKind != "java_hostname" {
		t.Fatalf("unexpected java entry: %+v", javaEntry)
	}

	bedrockEntry, ok := cache.LookupPublicPort(19140)
	if !ok {
		t.Fatal("expected bedrock route")
	}
	if bedrockEntry.HostPort != 19132 || bedrockEntry.RouteKind != "bedrock_udp" {
		t.Fatalf("unexpected bedrock entry: %+v", bedrockEntry)
	}
	if cache.Size() != 2 {
		t.Fatalf("size = %d, want 2", cache.Size())
	}

	reopened, err := Open(filepath.Dir(filepath.Join(dir, "routing.db")))
	if err != nil {
		t.Fatalf("reopen cache: %v", err)
	}
	defer reopened.Close()
	if _, ok := reopened.Lookup("paper.mine.bz"); !ok {
		t.Fatal("expected java route after reopen")
	}
	if _, ok := reopened.LookupPublicPort(19140); !ok {
		t.Fatal("expected bedrock route after reopen")
	}
}

func TestCacheApplyDeleteBedrockRoute(t *testing.T) {
	cache, err := Open(t.TempDir())
	if err != nil {
		t.Fatalf("open cache: %v", err)
	}
	defer cache.Close()

	entry := Entry{
		RouteKind:  "bedrock_udp",
		PublicPort: 19150,
		InstanceID: "instance-bedrock",
		KeeperID:   "keeper-bedrock",
		HostPort:   19133,
		Protocol:   "udp",
		Version:    5,
	}
	if err := cache.Apply(context.Background(), "upsert", entry); err != nil {
		t.Fatalf("upsert: %v", err)
	}
	if _, ok := cache.LookupPublicPort(19150); !ok {
		t.Fatal("expected bedrock route after upsert")
	}

	entry.Version = 6
	if err := cache.Apply(context.Background(), "delete", entry); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, ok := cache.LookupPublicPort(19150); ok {
		t.Fatal("bedrock route should be gone")
	}
}
