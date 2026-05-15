param(
    [string]$Output = "D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\Floor1Slice",
    [int]$Size = 2048
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptRoot)
$pythonScript = Join-Path $scriptRoot "build_floor1_masks.py"

New-Item -ItemType Directory -Force -Path $Output | Out-Null
py $pythonScript --output $Output --size $Size
if ($LASTEXITCODE -ne 0) {
    throw "Floor 1 mask generation failed."
}

Write-Host "Floor 1 WorldPainter masks are ready:" -ForegroundColor Green
Write-Host "  $Output"
Write-Host "Open floor1-composite-preview.png first for a quick read of the slice."
