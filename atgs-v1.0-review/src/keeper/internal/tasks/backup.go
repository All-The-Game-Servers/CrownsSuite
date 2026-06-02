package tasks

import (
	"archive/tar"
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/xkstudios/atgs/shared/protocol"
)

// ---- backup.create ----
//
// Flow:
//   1. Look up instance's data dir: <DataRoot>/<instance_id>/
//   2. If StopDuringBackup, stop the container (graceful, short timeout).
//   3. tar the dir to a pipe, read in chunk-sized slices from the pipe.
//   4. For each chunk:
//        sha256 it (while reading into memory)
//        if encrypted: AES-256-GCM encrypt; ciphertext = nonce||body||tag
//        HEAD Central to see if it already has this hash (resume dedupe)
//        if not, PUT the bytes
//        append to manifest
//   5. If we stopped the container, restart it.
//   6. Return BackupCreateResult with the manifest.

func (h *Handler) doBackupCreate(ctx context.Context, rawPayload json.RawMessage) (any, error) {
	var p protocol.BackupCreatePayload
	if err := json.Unmarshal(rawPayload, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	if p.ChunkSizeBytes < 64*1024 {
		return nil, fmt.Errorf("chunk size too small: %d", p.ChunkSizeBytes)
	}

	start := time.Now()
	log := h.Log.With("backup_id", p.BackupID, "instance_id", p.InstanceID)
	log.Info("starting backup")

	inst, err := h.Store.GetInstance(ctx, p.InstanceID)
	if err != nil {
		return nil, fmt.Errorf("unknown instance: %w", err)
	}

	volumeDir := filepath.Join(h.DataRoot, p.InstanceID)
	if _, err := os.Stat(volumeDir); err != nil {
		// Empty data dir is not an error; we'll produce an empty backup.
		if os.IsNotExist(err) {
			if err := os.MkdirAll(volumeDir, 0o755); err != nil {
				return nil, fmt.Errorf("ensure volume dir: %w", err)
			}
		} else {
			return nil, fmt.Errorf("stat volume dir: %w", err)
		}
	}

	stoppedForBackup := false
	if p.StopDuringBackup && inst.ContainerID != "" {
		if err := h.Runtime.StopInstance(ctx, inst.ContainerID, 30*time.Second); err != nil {
			log.Warn("stop-during-backup failed, continuing with hot backup", "err", err)
		} else {
			stoppedForBackup = true
			log.Info("instance stopped for backup")
		}
	}

	// Set up AES-GCM cipher if encrypted.
	var gcm cipher.AEAD
	var keyFingerprint string
	if p.Encrypted {
		if len(p.EncryptionKey) != 32 {
			return nil, fmt.Errorf("expected 32-byte encryption key, got %d bytes", len(p.EncryptionKey))
		}
		block, err := aes.NewCipher(p.EncryptionKey)
		if err != nil {
			return nil, fmt.Errorf("aes cipher: %w", err)
		}
		gcm, err = cipher.NewGCM(block)
		if err != nil {
			return nil, fmt.Errorf("gcm: %w", err)
		}
		sum := sha256.Sum256(p.EncryptionKey)
		keyFingerprint = "sha256:" + hex.EncodeToString(sum[:8])
	}

	// Build HTTP client for chunk upload. Uses the keeper's mTLS identity.
	httpClient := &http.Client{
		Timeout: 5 * time.Minute,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				Certificates:       []tls.Certificate{h.Identity.Certificate},
				RootCAs:            h.Identity.CACertPool,
				InsecureSkipVerify: h.InsecureSkipVerify,
				MinVersion:         tls.VersionTLS12,
			},
		},
	}

	// Pipe: tar writer on one end, chunker reader on the other.
	// A goroutine walks the dir and writes tar entries; the main flow pulls
	// chunks off the pipe. Tar walks in a goroutine so we don't need to
	// buffer the whole archive in memory.
	pr, pw := io.Pipe()
	tarErrCh := make(chan error, 1)
	go func() {
		tarErrCh <- writeTarFromDir(pw, volumeDir)
		_ = pw.Close()
	}()

	var (
		chunks     []protocol.BackupChunkRef
		totalBytes int64
		seq        int
	)
	buf := make([]byte, p.ChunkSizeBytes)

	for {
		if ctx.Err() != nil {
			_ = pr.Close()
			return nil, ctx.Err()
		}
		// Fill one chunk's worth. io.ReadFull stops early at EOF; we accept
		// a short final chunk.
		n, err := io.ReadFull(pr, buf)
		if n > 0 {
			chunkBody := buf[:n]
			if p.Encrypted {
				nonce := make([]byte, gcm.NonceSize())
				if _, err := rand.Read(nonce); err != nil {
					return nil, fmt.Errorf("nonce: %w", err)
				}
				enc := make([]byte, 0, len(nonce)+n+gcm.Overhead())
				enc = append(enc, nonce...)
				enc = gcm.Seal(enc, nonce, chunkBody, nil)
				chunkBody = enc
			}
			sum := sha256.Sum256(chunkBody)
			sha := hex.EncodeToString(sum[:])

			if err := h.uploadChunkIfAbsent(ctx, httpClient, p.ChunkUploadURL, sha, chunkBody); err != nil {
				return nil, fmt.Errorf("chunk %d upload: %w", seq, err)
			}
			chunks = append(chunks, protocol.BackupChunkRef{
				Seq:    seq,
				SHA256: sha,
				Size:   len(chunkBody),
			})
			totalBytes += int64(len(chunkBody))
			seq++
		}
		if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("read chunk: %w", err)
		}
	}
	if err := <-tarErrCh; err != nil {
		return nil, fmt.Errorf("tar: %w", err)
	}

	if stoppedForBackup {
		if err := h.Runtime.StartInstance(ctx, inst.ContainerID); err != nil {
			log.Warn("restart after backup failed", "err", err)
		} else {
			log.Info("instance restarted after backup")
		}
	}

	manifest := protocol.BackupManifest{
		Chunks:         chunks,
		TotalSize:      totalBytes,
		ArchiveFormat:  "tar",
		Encrypted:      p.Encrypted,
		KeyFingerprint: keyFingerprint,
		CreatedAt:      time.Now().UTC(),
	}

	duration := time.Since(start)
	log.Info("backup complete",
		"chunks", len(chunks),
		"bytes", totalBytes,
		"duration_ms", duration.Milliseconds())

	return protocol.BackupCreateResult{
		BackupID:   p.BackupID,
		Manifest:   manifest,
		TotalBytes: totalBytes,
		ChunkCount: len(chunks),
		DurationMS: duration.Milliseconds(),
	}, nil
}

// writeTarFromDir walks dir and writes a tar archive to w. Paths in the
// archive are relative to dir so restore to a different instance_id's
// directory produces the same tree shape.
func writeTarFromDir(w io.Writer, dir string) error {
	tw := tar.NewWriter(w)
	defer tw.Close()

	return filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(dir, path)
		if err != nil {
			return err
		}
		if rel == "." {
			return nil
		}
		// filepath.Walk uses OS-native separators; tar uses forward slashes.
		tarName := filepath.ToSlash(rel)

		hdr, err := tar.FileInfoHeader(info, "")
		if err != nil {
			return err
		}
		hdr.Name = tarName
		if err := tw.WriteHeader(hdr); err != nil {
			return err
		}
		if info.Mode().IsRegular() {
			f, err := os.Open(path)
			if err != nil {
				return err
			}
			_, err = io.Copy(tw, f)
			_ = f.Close()
			if err != nil {
				return err
			}
		}
		return nil
	})
}

// uploadChunkIfAbsent sends one chunk to Central. HEADs first to skip
// already-present chunks (dedupe + resumability). The PUT is idempotent
// Central-side (201 first time, 200 after).
func (h *Handler) uploadChunkIfAbsent(ctx context.Context, c *http.Client, baseURL, sha string, body []byte) error {
	chunkURL := strings.TrimRight(baseURL, "/") + "/" + sha

	// HEAD first: 200 = already there, skip.
	headReq, err := http.NewRequestWithContext(ctx, http.MethodHead, chunkURL, nil)
	if err != nil {
		return err
	}
	headResp, err := c.Do(headReq)
	if err == nil {
		_ = headResp.Body.Close()
		if headResp.StatusCode == http.StatusOK {
			return nil // dedupe hit
		}
		if headResp.StatusCode >= 500 {
			return fmt.Errorf("HEAD returned %d", headResp.StatusCode)
		}
		// 404 or other -> proceed to PUT.
	}

	putReq, err := http.NewRequestWithContext(ctx, http.MethodPut, chunkURL, bytes.NewReader(body))
	if err != nil {
		return err
	}
	putReq.ContentLength = int64(len(body))
	putReq.Header.Set("Content-Type", "application/octet-stream")
	putResp, err := c.Do(putReq)
	if err != nil {
		return err
	}
	defer putResp.Body.Close()
	if putResp.StatusCode != http.StatusCreated && putResp.StatusCode != http.StatusOK {
		bodyBytes, _ := io.ReadAll(putResp.Body)
		return fmt.Errorf("PUT returned %d: %s", putResp.StatusCode, string(bodyBytes))
	}
	return nil
}

// ---- backup.restore ----

func (h *Handler) doBackupRestore(ctx context.Context, rawPayload json.RawMessage) (any, error) {
	var p protocol.BackupRestorePayload
	if err := json.Unmarshal(rawPayload, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	start := time.Now()
	log := h.Log.With("backup_id", p.BackupID, "target_instance_id", p.TargetInstanceID)
	log.Info("starting restore", "chunks", len(p.Manifest.Chunks))

	// Set up AES-GCM cipher if encrypted.
	var gcm cipher.AEAD
	if p.Encrypted {
		if len(p.EncryptionKey) != 32 {
			return nil, fmt.Errorf("expected 32-byte encryption key, got %d bytes", len(p.EncryptionKey))
		}
		block, err := aes.NewCipher(p.EncryptionKey)
		if err != nil {
			return nil, err
		}
		gcm, err = cipher.NewGCM(block)
		if err != nil {
			return nil, err
		}
	}

	httpClient := &http.Client{
		Timeout: 5 * time.Minute,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				Certificates:       []tls.Certificate{h.Identity.Certificate},
				RootCAs:            h.Identity.CACertPool,
				InsecureSkipVerify: h.InsecureSkipVerify,
				MinVersion:         tls.VersionTLS12,
			},
		},
	}

	// Stream: chunk downloader on one side, tar extractor on the other.
	pr, pw := io.Pipe()
	downloadErrCh := make(chan error, 1)
	go func() {
		defer pw.Close()
		for _, ref := range p.Manifest.Chunks {
			body, err := h.downloadChunk(ctx, httpClient, p.ChunkDownloadURL, ref.SHA256)
			if err != nil {
				downloadErrCh <- fmt.Errorf("download chunk %s: %w", ref.SHA256, err)
				return
			}
			// Verify content hash.
			sum := sha256.Sum256(body)
			gotSHA := hex.EncodeToString(sum[:])
			if gotSHA != ref.SHA256 {
				downloadErrCh <- fmt.Errorf("chunk %s hash mismatch: got %s", ref.SHA256, gotSHA)
				return
			}
			plain := body
			if p.Encrypted {
				if len(body) < gcm.NonceSize() {
					downloadErrCh <- fmt.Errorf("chunk %s too short for nonce", ref.SHA256)
					return
				}
				nonce := body[:gcm.NonceSize()]
				ct := body[gcm.NonceSize():]
				plain, err = gcm.Open(nil, nonce, ct, nil)
				if err != nil {
					downloadErrCh <- fmt.Errorf("decrypt chunk %s: %w", ref.SHA256, err)
					return
				}
			}
			if _, err := pw.Write(plain); err != nil {
				downloadErrCh <- fmt.Errorf("write to pipe: %w", err)
				return
			}
		}
		downloadErrCh <- nil
	}()

	// Extract tar into target volume dir.
	targetDir := filepath.Join(h.DataRoot, p.TargetInstanceID)
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		return nil, fmt.Errorf("mkdir target: %w", err)
	}
	bytesWritten, extractErr := extractTarTo(pr, targetDir)
	_ = pr.Close()

	if dlErr := <-downloadErrCh; dlErr != nil {
		return nil, dlErr
	}
	if extractErr != nil {
		return nil, fmt.Errorf("extract: %w", extractErr)
	}

	duration := time.Since(start)
	log.Info("restore complete",
		"bytes_restored", bytesWritten,
		"duration_ms", duration.Milliseconds())

	return protocol.BackupRestoreResult{
		BackupID:         p.BackupID,
		TargetInstanceID: p.TargetInstanceID,
		BytesRestored:    bytesWritten,
		DurationMS:       duration.Milliseconds(),
	}, nil
}

func (h *Handler) downloadChunk(ctx context.Context, c *http.Client, baseURL, sha string) ([]byte, error) {
	url := strings.TrimRight(baseURL, "/") + "/" + sha
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET %s: status %d", url, resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

// extractTarTo extracts a tar stream into destDir. Returns total bytes of
// file content written. Rejects paths that would escape destDir via ../ or
// absolute paths, as a defense against a hostile backup.
func extractTarTo(r io.Reader, destDir string) (int64, error) {
	tr := tar.NewReader(r)
	var total int64
	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return total, err
		}
		if strings.Contains(hdr.Name, "..") || strings.HasPrefix(hdr.Name, "/") {
			return total, fmt.Errorf("unsafe path in archive: %s", hdr.Name)
		}
		target := filepath.Join(destDir, filepath.FromSlash(hdr.Name))
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, os.FileMode(hdr.Mode)&0o755); err != nil {
				return total, err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return total, err
			}
			f, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, os.FileMode(hdr.Mode)&0o644)
			if err != nil {
				return total, err
			}
			n, err := io.Copy(f, tr)
			_ = f.Close()
			if err != nil {
				return total, err
			}
			total += n
		default:
			// Skip symlinks and device files; backups don't need them for our workloads.
		}
	}
	return total, nil
}
