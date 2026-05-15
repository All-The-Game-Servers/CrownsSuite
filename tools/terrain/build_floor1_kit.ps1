param(
    [string]$Blender = "D:\CrownsSuiteTools\Apps\Blender-5.1.1\blender-5.1.1-windows-x64\blender.exe",
    [string]$Workspace = "D:\CrownsSuiteTools\Projects\CrownsTerrain\Floor1Kit",
    [switch]$SkipBlender
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$BlendPath = Join-Path $Workspace "floor1_kit.blend"
$ExportRoot = Join-Path $Workspace "exports"
$JsonDir = Join-Path $ExportRoot "json"
$CtplDir = Join-Path $ExportRoot "ctpl"
$ManifestPath = Join-Path $ExportRoot "floor1_kit_manifest.json"
$ReportPath = Join-Path $ExportRoot "floor1_kit_report.json"
$ResourceStructures = Join-Path $RepoRoot "terrain\src\main\resources\structures"

New-Item -ItemType Directory -Force -Path $Workspace, $JsonDir, $CtplDir, $ResourceStructures | Out-Null

if (-not $SkipBlender) {
    if (-not (Test-Path -LiteralPath $Blender)) {
        throw "Blender was not found at '$Blender'. Pass -Blender with the installed blender.exe path."
    }
    & $Blender --background --python (Join-Path $ScriptDir "blender_generate_floor1_kit.py") -- $BlendPath
    if ($LASTEXITCODE -ne 0) {
        throw "Blender Floor 1 kit generation failed."
    }
    & $Blender --background $BlendPath --python (Join-Path $ScriptDir "blender_export_blocks.py") -- $JsonDir --collections --manifest $ManifestPath
    if ($LASTEXITCODE -ne 0) {
        throw "Blender Floor 1 kit export failed."
    }
}

Remove-Item -Path (Join-Path $CtplDir "*.ctpl") -Force -ErrorAction SilentlyContinue
foreach ($json in Get-ChildItem -LiteralPath $JsonDir -Filter *.json | Sort-Object Name) {
    $out = Join-Path $CtplDir ($json.BaseName + ".ctpl")
    py (Join-Path $ScriptDir "json_to_ctpl.py") $json.FullName $out
    if ($LASTEXITCODE -ne 0) {
        throw "JSON to .ctpl conversion failed for $($json.Name)."
    }
}

py (Join-Path $ScriptDir "validate_ctpl_kit.py") $CtplDir --report $ReportPath
if ($LASTEXITCODE -ne 0) {
    throw "CrownsTerrain Floor 1 kit validation failed."
}

Copy-Item -Path (Join-Path $CtplDir "*.ctpl") -Destination $ResourceStructures -Force

Write-Host "CrownsTerrain Floor 1 kit built successfully."
Write-Host "Editable Blender source: $BlendPath"
Write-Host "Manifest: $ManifestPath"
Write-Host "Report: $ReportPath"
Write-Host "Bundled templates copied to: $ResourceStructures"
