[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Blockbench = "D:\CrownsSuiteTools\Apps\Blockbench-5.1.4-portable.exe",
    [string]$SourceFolder = "resource-pack\source\blockbench"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$source = Join-Path $root $SourceFolder

if (-not (Test-Path $Blockbench)) {
    throw "Blockbench was not found at $Blockbench"
}
if (-not (Test-Path $source)) {
    throw "Blockbench source folder was not found at $source. Run python resource-pack\tools\generate_resource_pack.py first."
}

$models = Get-ChildItem -LiteralPath $source -Filter *.bbmodel | Sort-Object Name
if ($models.Count -eq 0) {
    throw "No .bbmodel files found in $source"
}

if ($PSCmdlet.ShouldProcess($Blockbench, "Open $($models.Count) Crowns Blockbench source models")) {
    Start-Process -FilePath $Blockbench -ArgumentList ($models | ForEach-Object { "`"$($_.FullName)`"" })
}
