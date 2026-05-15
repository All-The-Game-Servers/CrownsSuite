param(
    [string]$Workspace = "D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\Floor1Slice",
    [string]$WorldName = "crowns_floor_1_wp_slice",
    [string]$ExportRoot = "D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\ExportsTest2",
    [string]$WorldPainterRoot = "D:\CrownsSuiteTools\Apps\WorldPainter"
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$exportScript = Join-Path $scriptRoot "floor1_export_world.js"
$worldFile = Join-Path $Workspace "$WorldName.world"
$exportWorld = Join-Path $ExportRoot $WorldName

function Test-MinecraftWorldFolder {
    param([string]$Path)
    return (Test-Path (Join-Path $Path "level.dat")) -and
        (Test-Path (Join-Path $Path "region")) -and
        (Test-Path (Join-Path $Path "entities"))
}

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

if (-not (Test-Path $worldFile)) {
    throw "Missing $worldFile. Run tools\terrain\worldpainter\build_floor1_worldpainter.ps1 first."
}

$wpscript = Find-WpScript -Root $WorldPainterRoot
if (-not $wpscript) {
    throw "WorldPainter was not found. Install it under D:\CrownsSuiteTools\Apps\WorldPainter or set WORLDPAINTER_HOME."
}

New-Item -ItemType Directory -Force -Path $ExportRoot | Out-Null
if (Test-Path $exportWorld) {
    if (Test-MinecraftWorldFolder -Path $exportWorld) {
        Write-Host "Existing Minecraft world export is ready:" -ForegroundColor Green
        Write-Host "  $exportWorld"
        Write-Host "Copy this folder to the server root before running /cterrain admin generate 1."
        return
    }
    else {
        throw "Export target exists but does not look like a Minecraft world folder. Move or remove it manually before exporting: $exportWorld"
    }
}

$tempExportRoot = Join-Path $ExportRoot ("_wp_export_tmp_" + [DateTime]::UtcNow.ToString("yyyyMMddHHmmss"))
New-Item -ItemType Directory -Force -Path $tempExportRoot | Out-Null

& $wpscript $exportScript $worldFile $tempExportRoot
if ($LASTEXITCODE -ne 0) {
    throw "WorldPainter export failed."
}

$candidate = Get-ChildItem -LiteralPath $tempExportRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-MinecraftWorldFolder -Path $_.FullName } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

if (-not $candidate) {
    throw "WorldPainter finished, but no Minecraft world folder was found under $tempExportRoot."
}

Move-Item -LiteralPath $candidate.FullName -Destination $exportWorld
Remove-Item -LiteralPath $tempExportRoot -Force -ErrorAction SilentlyContinue

Write-Host "Minecraft world export ready:" -ForegroundColor Green
Write-Host "  $exportWorld"
Write-Host "Copy this folder to the server root before running /cterrain admin generate 1."
