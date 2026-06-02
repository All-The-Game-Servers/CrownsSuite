// Package resources samples live machine state for the Keeper's periodic
// resource report.
//
// The Keeper sends ResourcesReport frames over the control channel so
// Central can display and (eventually) schedule against them. As noted in
// the protocol: these values are self-reported and must not be trusted for
// billing or capacity guarantees without independent verification.
package resources

import (
	"context"
	"runtime"
	"time"

	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/mem"

	"github.com/xkstudios/atgs/shared/protocol"
)

// Sample returns the current resource snapshot. The CPU percent is measured
// over a short interval so it reflects a real number, not a zero.
func Sample(ctx context.Context) protocol.ResourcesReport {
	out := protocol.ResourcesReport{
		CPUCores:       runtime.NumCPU(),
		ReportedAtUnix: time.Now().Unix(),
	}

	// CPU percent over a 200ms window. Short enough to not delay the reporting
	// loop noticeably.
	cpuCtx, cancel := context.WithTimeout(ctx, 1*time.Second)
	defer cancel()
	if pct, err := cpu.PercentWithContext(cpuCtx, 200*time.Millisecond, false); err == nil && len(pct) > 0 {
		out.CPUPercentUsed = pct[0]
	}

	if vm, err := mem.VirtualMemoryWithContext(ctx); err == nil {
		out.MemTotalBytes = vm.Total
		out.MemUsedBytes = vm.Used
	}
	if u, err := disk.UsageWithContext(ctx, diskRoot()); err == nil {
		out.DiskTotalBytes = u.Total
		out.DiskUsedBytes = u.Used
	}
	return out
}

// diskRoot returns the mountpoint to measure. On Windows we use C:\; on
// *nix we use the root filesystem. Operators running with a dedicated
// ATGS data volume on a different mount will want a future env var override.
func diskRoot() string {
	if runtime.GOOS == "windows" {
		return `C:\`
	}
	return "/"
}
