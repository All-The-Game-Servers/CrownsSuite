param(
    [string]$Structures = "terrain\src\main\resources\structures",
    [string]$Output = "build\terrain-preview"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$Script = Join-Path $Root "tools\terrain\render_ctpl_previews.py"

Push-Location $Root
try {
    py $Script --structures $Structures --output $Output --pattern "fh_*.ctpl"
    if ($LASTEXITCODE -ne 0) {
        throw "CrownsTerrain preview rendering failed."
    }
    Write-Host "CrownsTerrain Floor 1 previews written to $(Join-Path $Root $Output)"
} finally {
    Pop-Location
}
