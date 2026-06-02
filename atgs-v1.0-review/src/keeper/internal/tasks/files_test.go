package tasks

import (
	"path/filepath"
	"testing"
)

func TestResolveInstancePathAllowsScopedPaths(t *testing.T) {
	h := &Handler{DataRoot: filepath.Join("C:", "atgs", "instances")}
	root, target, rel, err := h.resolveInstancePath("abc123", "mods/server.properties")
	if err != nil {
		t.Fatalf("resolveInstancePath returned error: %v", err)
	}
	expectedRoot := filepath.Join("C:", "atgs", "instances", "abc123")
	if root != expectedRoot {
		t.Fatalf("root = %q, want %q", root, expectedRoot)
	}
	if rel != filepath.ToSlash(filepath.Join("mods", "server.properties")) {
		t.Fatalf("rel = %q", rel)
	}
	if target != filepath.Join(expectedRoot, "mods", "server.properties") {
		t.Fatalf("target = %q", target)
	}
}

func TestResolveInstancePathRejectsEscape(t *testing.T) {
	h := &Handler{DataRoot: filepath.Join("C:", "atgs", "instances")}
	if _, _, _, err := h.resolveInstancePath("abc123", "../../Windows/system32"); err == nil {
		t.Fatal("expected path escape error")
	}
}
