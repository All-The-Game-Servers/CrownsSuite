param(
    [string]$WorldPainterRoot = "D:\CrownsSuiteTools\Apps\WorldPainter"
)

$ErrorActionPreference = "Stop"

function Find-WpScript {
    param([string]$Root)
    $candidates = @()
    if ($env:WORLDPAINTER_HOME) {
        $candidates += $env:WORLDPAINTER_HOME
    }
    $candidates += $Root
    if (Test-Path "D:\CrownsSuiteTools\Apps") {
        $candidates += Get-ChildItem -LiteralPath "D:\CrownsSuiteTools\Apps" -Directory -Filter "WorldPainter*" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    }
    $candidates += "$env:ProgramFiles\WorldPainter"
    $candidates += "${env:ProgramFiles(x86)}\WorldPainter"

    foreach ($candidate in $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique) {
        $direct = @(
            (Join-Path $candidate "wpscript.exe"),
            (Join-Path $candidate "wpscript.bat"),
            (Join-Path $candidate "WorldPainter\wpscript.exe"),
            (Join-Path $candidate "WorldPainter\wpscript.bat")
        )
        foreach ($path in $direct) {
            if (Test-Path $path) {
                return (Resolve-Path $path).Path
            }
        }
        $recursive = Get-ChildItem -LiteralPath $candidate -Recurse -File -Include "wpscript.exe","wpscript.bat" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($recursive) {
            return $recursive.FullName
        }
    }
    return $null
}

$wpscript = Find-WpScript -Root $WorldPainterRoot
if (-not $wpscript) {
    Write-Host "WorldPainter wpscript was not found." -ForegroundColor Yellow
    Write-Host "Expected portable install root: $WorldPainterRoot"
    Write-Host "Install or copy WorldPainter to D:\CrownsSuiteTools\Apps\WorldPainter, or set WORLDPAINTER_HOME to its folder."
    exit 1
}

Write-Host "WorldPainter wpscript found:" -ForegroundColor Green
Write-Host "  $wpscript"
Write-Host "Version probing is skipped because wpscript treats command-line probes as script paths."
