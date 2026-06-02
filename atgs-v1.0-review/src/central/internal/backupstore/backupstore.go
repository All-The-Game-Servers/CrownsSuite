// Package backupstore abstracts over the two chunk storage backends Central
// supports: local filesystem under a configured root, and S3-compatible object
// storage (R2, MinIO, S3 itself).
//
// The interface is kept tiny on purpose: Put/Get/Delete/Exists of chunks by
// SHA-256 hash. The orchestrating code in the API layer handles retries,
// refcounts, and GC.
//
// Backends do NOT see backup_ids; they're pure content-addressable blob stores.
// This lets us dedupe across backups trivially.
package backupstore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Backend is the storage interface. Implementations must be safe for
// concurrent use.
type Backend interface {
	// Put stores the chunk. If a chunk with the same sha256 already exists,
	// Put should be a no-op (idempotent).
	Put(ctx context.Context, sha256 string, r io.Reader, size int64) (locator string, err error)

	// Get returns a reader for the chunk. Caller closes.
	Get(ctx context.Context, locator string) (io.ReadCloser, error)

	// Exists reports whether a chunk is present. Cheap; used by the resumable
	// uploader to short-circuit already-uploaded chunks.
	Exists(ctx context.Context, locator string) (bool, error)

	// Delete removes a chunk. Called during GC.
	Delete(ctx context.Context, locator string) error

	// Name returns a human-readable backend identifier, for logs.
	Name() string
}

// ErrNotFound is returned by Get/Delete when the chunk isn't there.
var ErrNotFound = errors.New("backupstore: not found")

// ---- central_fs backend ----

// FSBackend stores chunks on Central's local disk.
// Layout: <root>/chunks/<first-2-chars>/<sha256>
// Two-char sharding is enough to keep directory sizes manageable at Phase 4
// scale (256 dirs, each holding up to a few thousand files). Beyond that
// we'd shard deeper.
type FSBackend struct {
	root string
}

func NewFSBackend(root string) (*FSBackend, error) {
	if root == "" {
		return nil, errors.New("fs backend: root is empty")
	}
	if err := os.MkdirAll(filepath.Join(root, "chunks"), 0o750); err != nil {
		return nil, err
	}
	return &FSBackend{root: root}, nil
}

func (b *FSBackend) Name() string { return "central_fs:" + b.root }

func (b *FSBackend) pathFor(sha string) string {
	return filepath.Join(b.root, "chunks", sha[:2], sha)
}

// Put writes the chunk to <root>/chunks/xx/<sha>. Streams via a temp file so
// a partially written chunk never appears at the final path.
func (b *FSBackend) Put(ctx context.Context, sha string, r io.Reader, size int64) (string, error) {
	if err := validateSHA(sha); err != nil {
		return "", err
	}
	final := b.pathFor(sha)
	if err := os.MkdirAll(filepath.Dir(final), 0o750); err != nil {
		return "", err
	}
	if _, err := os.Stat(final); err == nil {
		// Already there: drain reader so caller doesn't leak bytes and return success.
		_, _ = io.Copy(io.Discard, r)
		return final, nil
	}
	tmp, err := os.CreateTemp(filepath.Dir(final), ".tmp-chunk-*")
	if err != nil {
		return "", err
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath) // no-op if rename succeeds

	if size > 0 {
		if _, err := io.CopyN(tmp, r, size); err != nil {
			tmp.Close()
			return "", fmt.Errorf("copy: %w", err)
		}
	} else {
		if _, err := io.Copy(tmp, r); err != nil {
			tmp.Close()
			return "", fmt.Errorf("copy: %w", err)
		}
	}
	if err := tmp.Close(); err != nil {
		return "", err
	}
	if err := os.Rename(tmpPath, final); err != nil {
		return "", err
	}
	return final, nil
}

func (b *FSBackend) Get(ctx context.Context, locator string) (io.ReadCloser, error) {
	f, err := os.Open(locator)
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	return f, err
}

func (b *FSBackend) Exists(ctx context.Context, locator string) (bool, error) {
	_, err := os.Stat(locator)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	return false, err
}

func (b *FSBackend) Delete(ctx context.Context, locator string) error {
	err := os.Remove(locator)
	if errors.Is(err, os.ErrNotExist) {
		return ErrNotFound
	}
	return err
}

// validateSHA sanity-checks the hash string to prevent directory traversal
// via a malicious keeper. sha256 is 64 hex chars, lowercase.
func validateSHA(sha string) error {
	if len(sha) != 64 {
		return fmt.Errorf("sha must be 64 chars, got %d", len(sha))
	}
	for _, c := range sha {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return fmt.Errorf("sha contains invalid char %q", c)
		}
	}
	return nil
}

// LocatorForSHA constructs the filesystem path a given hash WILL have in this
// backend. Used by the store layer to fill in central_fs_path.
func (b *FSBackend) LocatorForSHA(sha string) string {
	return b.pathFor(sha)
}

// ---- object_storage backend (S3-compatible) ----
//
// Phase 4 ships the interface and a stub implementation. Wiring in a real
// S3 client (aws-sdk-go-v2 or minio-go) is a follow-up; the structure is
// here so the API layer can already dispatch to it, and adding a concrete
// backend later is a drop-in replacement.

// ObjectStorageConfig is what a real S3Backend would need.
type ObjectStorageConfig struct {
	Endpoint   string // e.g. https://s3.us-east-1.amazonaws.com, or http://minio:9000
	Region     string
	Bucket     string
	Prefix     string // optional path prefix inside the bucket
	AccessKey  string
	SecretKey  string
	UseSSL     bool
}

// ObjectStorageStub is a deliberately-unimplemented backend that returns an
// error on every operation. Present so the codebase has a place to wire in
// S3 later without adding a dep now. Detection of unconfigured operation is
// a user-facing error message.
type ObjectStorageStub struct {
	cfg ObjectStorageConfig
}

func NewObjectStorageStub(cfg ObjectStorageConfig) *ObjectStorageStub {
	return &ObjectStorageStub{cfg: cfg}
}

func (b *ObjectStorageStub) Name() string {
	return "object_storage:" + b.cfg.Bucket + "/" + strings.TrimPrefix(b.cfg.Prefix, "/")
}

var errObjectStorageNotImplemented = errors.New(
	"object storage backend is not yet implemented; set ATGS_CENTRAL_BACKUP_STORAGE=central_fs for now. " +
		"Adding a concrete S3-compatible backend is a Phase 4.1 follow-up.",
)

func (b *ObjectStorageStub) Put(ctx context.Context, sha string, r io.Reader, size int64) (string, error) {
	return "", errObjectStorageNotImplemented
}
func (b *ObjectStorageStub) Get(ctx context.Context, locator string) (io.ReadCloser, error) {
	return nil, errObjectStorageNotImplemented
}
func (b *ObjectStorageStub) Exists(ctx context.Context, locator string) (bool, error) {
	return false, errObjectStorageNotImplemented
}
func (b *ObjectStorageStub) Delete(ctx context.Context, locator string) error {
	return errObjectStorageNotImplemented
}
