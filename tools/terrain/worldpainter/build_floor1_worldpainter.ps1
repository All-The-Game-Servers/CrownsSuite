param(
    [string]$Workspace = "D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\Floor1Slice",
    [string]$WorldName = "crowns_floor_1_wp_slice",
    [string]$WorldPainterRoot = "D:\CrownsSuiteTools\Apps\WorldPainter"
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$createScript = Join-Path $scriptRoot "floor1_create_world.js"
$heightmap = Join-Path $Workspace "floor1-heightmap.png"
$worldFile = Join-Path $Workspace "$WorldName.world"

function Find-WpScript {
    param([string]$Root)
    $roots = @()
    if ($env:WORLDPAINTER_HOME) { $roots += $env:WORLDPAINTER_HOME }
    $roots += $Root
    if (Test-Path "D:\CrownsSuiteTools\Apps") {
        $roots += Get-ChildItem -LiteralPath "D:\CrownsSuiteTools\Apps" -Directory -Filter "WorldPainter*" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    }
    $roots += "$env:ProgramFiles\WorldPainter"
    $roots += "${env:ProgramFiles(x86)}\WorldPainter"
    foreach ($root in $roots | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique) {
        foreach ($path in @((Join-Path $root "wpscript.exe"), (Join-Path $root "wpscript.bat"))) {
            if (Test-Path $path) { return (Resolve-Path $path).Path }
        }
        $found = Get-ChildItem -LiteralPath $root -Recurse -File -Include "wpscript.exe","wpscript.bat" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

if (-not (Test-Path $heightmap)) {
    throw "Missing $heightmap. Run tools\terrain\worldpainter\build_floor1_masks.ps1 first."
}

$wpscript = Find-WpScript -Root $WorldPainterRoot
if (-not $wpscript) {
    throw "WorldPainter was not found. Install it under D:\CrownsSuiteTools\Apps\WorldPainter or set WORLDPAINTER_HOME."
}

& $wpscript $createScript $heightmap $worldFile $WorldName
if ($LASTEXITCODE -ne 0) {
    throw "WorldPainter project build failed."
}

Write-Host "WorldPainter project created:" -ForegroundColor Green
Write-Host "  $worldFile"
