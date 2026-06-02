// Package metrics gathers host-level resource stats for the keeper UI.
//
// Host metrics: CPU %, memory used/total, disk used/total on the data
// volume. Container-level stats come from the Docker runtime directly
// when the UI requests them.
//
// Everything is pull-based: the UI calls Host() on demand rather than
// subscribing to a push stream. gopsutil's syscalls are cheap (~sub-ms)
// so polling on a 2s tick from the UI is fine.
package metrics

import (
	"time"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
	"github.com/shirou/gopsutil/v4/mem"
)

// HostStats is what the UI renders in the meters at the top of the window.
type HostStats struct {
	CPUPercent    float64   `json:"cpu_percent"`
	MemoryUsed    uint64    `json:"memory_used"`
	MemoryTotal   uint64    `json:"memory_total"`
	MemoryPercent float64   `json:"memory_percent"`
	DiskUsed      uint64    `json:"disk_used"`
	DiskTotal     uint64    `json:"disk_total"`
	DiskPercent   float64   `json:"disk_percent"`
	SampledAt     time.Time `json:"sampled_at"`
}

// Host gathers current host stats. dataPath is the keeper's data root,
// which determines which filesystem the disk stats report on.
func Host(dataPath string) (*HostStats, error) {
	s := &HostStats{SampledAt: time.Now()}

	// CPU: gopsutil's Percent with a short interval gives a usable
	// snapshot (~100ms spent sleeping). Interval 0 with percpu=false
	// returns cumulative-since-last-call; we prefer interval-based
	// because the UI calls this infrequently and doesn't want to pay a
	// "first call is always 0%" gotcha.
	cpuPcts, err := cpu.Percent(100*time.Millisecond, false)
	if err == nil && len(cpuPcts) > 0 {
		s.CPUPercent = cpuPcts[0]
	}

	// Memory
	if vm, err := mem.VirtualMemory(); err == nil {
		s.MemoryUsed = vm.Used
		s.MemoryTotal = vm.Total
		s.MemoryPercent = vm.UsedPercent
	}

	// Disk on the data volume
	if du, err := disk.Usage(dataPath); err == nil {
		s.DiskUsed = du.Used
		s.DiskTotal = du.Total
		s.DiskPercent = du.UsedPercent
	}

	return s, nil
}
