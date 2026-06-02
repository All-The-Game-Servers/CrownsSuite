param(
    [string]$Version = "1.0.1"
)

$ErrorActionPreference = "Stop"

function Find-Tool($candidates, $name) {
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    throw "Unable to find $name"
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        $joined = ($Arguments -join " ")
        throw "Command failed: $FilePath $joined"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$go = Find-Tool @("C:\Users\sirgi\tools\go\bin\go.exe", "C:\Program Files\Go\bin\go.exe") "go"
$wails = Find-Tool @("C:\Users\sirgi\go\bin\wails.exe") "wails"
$tar = Find-Tool @() "tar"

$distDir = Join-Path $repoRoot "dist"
$packageRoot = Join-Path $repoRoot ("atgs-v" + $Version)
$archivePath = Join-Path $repoRoot ("atgs-v" + $Version + ".tar.gz")

Remove-Item -LiteralPath $distDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $packageRoot -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $archivePath -Force -ErrorAction SilentlyContinue

New-Item -ItemType Directory -Force -Path $distDir | Out-Null

Push-Location $repoRoot
try {
    Invoke-Checked -FilePath $go -Arguments @("build", "-o", (Join-Path $distDir "central-linux-amd64"), ".\central\cmd\central")
    Invoke-Checked -FilePath $go -Arguments @("build", "-tags", "keeper_headless", "-o", (Join-Path $distDir "keeper-linux-amd64"), ".\keeper\cmd\keeper")
    $env:GOOS = "windows"
    $env:GOARCH = "amd64"
    Invoke-Checked -FilePath $go -Arguments @("build", "-tags", "keeper_headless", "-o", (Join-Path $distDir "keeper-windows-amd64.exe"), ".\keeper\cmd\keeper")
    Remove-Item Env:GOOS
    Remove-Item Env:GOARCH

    Push-Location (Join-Path $repoRoot "progenitor")
    try {
        $env:PATH = ("{0};{1}" -f (Split-Path $go -Parent), $env:PATH)
        Invoke-Checked -FilePath $wails -Arguments @("build", "-platform", "windows/amd64", "-skipbindings")
        $wailsOutput = Join-Path $repoRoot "progenitor\build\bin\progenitor.exe"
        if (-not (Test-Path $wailsOutput)) {
            throw "Expected Wails output not found at $wailsOutput"
        }
        Copy-Item -LiteralPath $wailsOutput -Destination (Join-Path $distDir "progenitor-windows-amd64.exe") -Force
    } finally {
        Pop-Location
    }

    New-Item -ItemType Directory -Force -Path $packageRoot | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot "README.md") -Destination (Join-Path $packageRoot "README.md")
    Copy-Item -LiteralPath (Join-Path $repoRoot "docs") -Destination (Join-Path $packageRoot "docs") -Recurse
    Copy-Item -LiteralPath (Join-Path $repoRoot "deploy") -Destination (Join-Path $packageRoot "deploy") -Recurse
    Copy-Item -LiteralPath (Join-Path $repoRoot "eggs") -Destination (Join-Path $packageRoot "eggs") -Recurse
    Copy-Item -LiteralPath (Join-Path $repoRoot "migrations") -Destination (Join-Path $packageRoot "migrations") -Recurse
    Copy-Item -LiteralPath $distDir -Destination (Join-Path $packageRoot "dist") -Recurse

    & $tar -czf $archivePath -C $repoRoot ("atgs-v" + $Version)
    Write-Host "Packaged release at $archivePath"
} finally {
    Pop-Location
}
