package protocol

// Task kinds for bounded remote file management. Scope is always limited to
// an instance's app-data root on the keeper.
const (
	TaskInstanceFileList   TaskKind = "instance.file.list"
	TaskInstanceFileRead   TaskKind = "instance.file.read"
	TaskInstanceFileWrite  TaskKind = "instance.file.write"
	TaskInstanceFileDelete TaskKind = "instance.file.delete"
	TaskInstanceFileRename TaskKind = "instance.file.rename"
)

type InstanceFileEntry struct {
	Path            string `json:"path"`
	Name            string `json:"name"`
	IsDir           bool   `json:"is_dir"`
	SizeBytes       int64  `json:"size_bytes"`
	ModifiedAtUnix  int64  `json:"modified_at_unix"`
}

type InstanceFileListPayload struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path,omitempty"`
}

type InstanceFileListResult struct {
	InstanceID string              `json:"instance_id"`
	Path       string              `json:"path"`
	Entries    []InstanceFileEntry `json:"entries"`
}

type InstanceFileReadPayload struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	MaxBytes   int64  `json:"max_bytes,omitempty"`
}

type InstanceFileReadResult struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	Content    []byte `json:"content"`
	SizeBytes  int64  `json:"size_bytes"`
}

type InstanceFileWritePayload struct {
	InstanceID    string `json:"instance_id"`
	Path          string `json:"path"`
	Content       []byte `json:"content"`
	CreateParents bool   `json:"create_parents,omitempty"`
}

type InstanceFileWriteResult struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	SizeBytes  int64  `json:"size_bytes"`
}

type InstanceFileDeletePayload struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	Recursive  bool   `json:"recursive,omitempty"`
}

type InstanceFileDeleteResult struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	Deleted    bool   `json:"deleted"`
}

type InstanceFileRenamePayload struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	NewPath    string `json:"new_path"`
}

type InstanceFileRenameResult struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	NewPath    string `json:"new_path"`
}
