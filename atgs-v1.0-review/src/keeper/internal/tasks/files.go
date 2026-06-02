package tasks

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/xkstudios/atgs/shared/protocol"
)

const maxFileReadBytes = 4 * 1024 * 1024

func (h *Handler) doInstanceFileList(ctx context.Context, raw json.RawMessage) (any, error) {
	var p protocol.InstanceFileListPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	root, target, cleanRel, err := h.resolveInstancePath(p.InstanceID, p.Path)
	if err != nil {
		return nil, err
	}
	if target == "" {
		target = root
	}
	entries, err := os.ReadDir(target)
	if err != nil {
		return nil, err
	}
	out := make([]protocol.InstanceFileEntry, 0, len(entries))
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil {
			return nil, err
		}
		entryRel := filepath.ToSlash(filepath.Join(cleanRel, entry.Name()))
		if entryRel == "." {
			entryRel = ""
		}
		out = append(out, protocol.InstanceFileEntry{
			Path:           entryRel,
			Name:           entry.Name(),
			IsDir:          entry.IsDir(),
			SizeBytes:      info.Size(),
			ModifiedAtUnix: info.ModTime().Unix(),
		})
	}
	return protocol.InstanceFileListResult{
		InstanceID: p.InstanceID,
		Path:       cleanRel,
		Entries:    out,
	}, nil
}

func (h *Handler) doInstanceFileRead(ctx context.Context, raw json.RawMessage) (any, error) {
	var p protocol.InstanceFileReadPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	_, target, cleanRel, err := h.resolveInstancePath(p.InstanceID, p.Path)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(target)
	if err != nil {
		return nil, err
	}
	if info.IsDir() {
		return nil, fmt.Errorf("path is a directory")
	}
	maxBytes := p.MaxBytes
	if maxBytes <= 0 || maxBytes > maxFileReadBytes {
		maxBytes = maxFileReadBytes
	}
	if info.Size() > maxBytes {
		return nil, fmt.Errorf("file exceeds max read size of %d bytes", maxBytes)
	}
	content, err := os.ReadFile(target)
	if err != nil {
		return nil, err
	}
	return protocol.InstanceFileReadResult{
		InstanceID: p.InstanceID,
		Path:       cleanRel,
		Content:    content,
		SizeBytes:  int64(len(content)),
	}, nil
}

func (h *Handler) doInstanceFileWrite(ctx context.Context, raw json.RawMessage) (any, error) {
	var p protocol.InstanceFileWritePayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	_, target, cleanRel, err := h.resolveInstancePath(p.InstanceID, p.Path)
	if err != nil {
		return nil, err
	}
	if p.CreateParents {
		if err := os.MkdirAll(filepath.Dir(target), 0o750); err != nil {
			return nil, err
		}
	}
	if err := os.WriteFile(target, p.Content, 0o640); err != nil {
		return nil, err
	}
	return protocol.InstanceFileWriteResult{
		InstanceID: p.InstanceID,
		Path:       cleanRel,
		SizeBytes:  int64(len(p.Content)),
	}, nil
}

func (h *Handler) doInstanceFileDelete(ctx context.Context, raw json.RawMessage) (any, error) {
	var p protocol.InstanceFileDeletePayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	_, target, cleanRel, err := h.resolveInstancePath(p.InstanceID, p.Path)
	if err != nil {
		return nil, err
	}
	if p.Recursive {
		if err := os.RemoveAll(target); err != nil {
			return nil, err
		}
	} else {
		if err := os.Remove(target); err != nil {
			return nil, err
		}
	}
	return protocol.InstanceFileDeleteResult{
		InstanceID: p.InstanceID,
		Path:       cleanRel,
		Deleted:    true,
	}, nil
}

func (h *Handler) doInstanceFileRename(ctx context.Context, raw json.RawMessage) (any, error) {
	var p protocol.InstanceFileRenamePayload
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	_, source, cleanPath, err := h.resolveInstancePath(p.InstanceID, p.Path)
	if err != nil {
		return nil, err
	}
	_, target, cleanTarget, err := h.resolveInstancePath(p.InstanceID, p.NewPath)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o750); err != nil {
		return nil, err
	}
	if err := os.Rename(source, target); err != nil {
		return nil, err
	}
	return protocol.InstanceFileRenameResult{
		InstanceID: p.InstanceID,
		Path:       cleanPath,
		NewPath:    cleanTarget,
	}, nil
}

func (h *Handler) resolveInstancePath(instanceID, rel string) (string, string, string, error) {
	root := filepath.Join(h.DataRoot, instanceID)
	cleanRel := strings.TrimSpace(rel)
	if cleanRel == "" || cleanRel == "." || cleanRel == "/" {
		return root, root, "", nil
	}
	cleanRel = filepath.ToSlash(filepath.Clean("/" + cleanRel))
	cleanRel = strings.TrimPrefix(cleanRel, "/")
	if cleanRel == "." || strings.HasPrefix(cleanRel, "../") || cleanRel == ".." {
		return "", "", "", fmt.Errorf("invalid path")
	}
	target := filepath.Join(root, filepath.FromSlash(cleanRel))
	if relPath, err := filepath.Rel(root, target); err != nil || strings.HasPrefix(relPath, "..") {
		return "", "", "", fmt.Errorf("path escapes instance root")
	}
	return root, target, cleanRel, nil
}
