param(
    [switch]$SkipCompile
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$downloads = Join-Path $root "downloads"
$buildRoot = Join-Path $root "build\verify-suite"

$modules = @(
    @{ Name = "api"; Jar = "CrownsAPI"; Version = "1.5.0"; Dependencies = @() },
    @{ Name = "economy"; Jar = "CrownsEconomy"; Version = "1.4.0"; Dependencies = @("api") },
    @{ Name = "admin"; Jar = "CrownsAdmin"; Version = "1.4.0"; Dependencies = @("api") },
    @{ Name = "events"; Jar = "CrownsEvents"; Version = "1.5.0"; Dependencies = @("api") },
    @{ Name = "drugs"; Jar = "CrownsDrugs"; Version = "1.4.0"; Dependencies = @("api") },
    @{ Name = "mmo"; Jar = "CrownsMMO"; Version = "1.8.0"; Dependencies = @("api") },
    @{ Name = "terrain"; Jar = "CrownsTerrain"; Version = "1.8.5"; Dependencies = @("api") }
)

function Get-DependencyClasspath {
    param([string[]]$ModuleDependencies)
    $jars = @(
        "paper-api.jar",
        "adventure-api.jar",
        "adventure-key.jar",
        "adventure-plain.jar",
        "examination-api.jar",
        "examination-string.jar",
        "bungeecord-chat.jar"
    ) | ForEach-Object { Join-Path $root $_ } | Where-Object { Test-Path $_ }
    $classes = $ModuleDependencies | ForEach-Object { Join-Path $buildRoot "$_\classes" } | Where-Object { Test-Path $_ }
    return (@($jars) + @($classes)) -join [IO.Path]::PathSeparator
}

function Compile-Module {
    param($Module)
    $src = Join-Path $root "$($Module.Name)\src\main\java"
    $res = Join-Path $root "$($Module.Name)\src\main\resources"
    $classes = Join-Path $buildRoot "$($Module.Name)\classes"
    $stage = Join-Path $buildRoot "$($Module.Name)\stage"
    Remove-Item -LiteralPath (Join-Path $buildRoot $Module.Name) -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $classes, $stage | Out-Null

    if (-not $SkipCompile) {
        $sources = Get-ChildItem -LiteralPath $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
        if ($sources.Count -eq 0) {
            throw "No Java sources found for $($Module.Name)"
        }
        $classpath = Get-DependencyClasspath $Module.Dependencies
        & javac -encoding UTF-8 --release 21 -cp $classpath -d $classes @sources
        if ($LASTEXITCODE -ne 0) {
            throw "javac failed for $($Module.Name)"
        }
    }

    Copy-Item -Path (Join-Path $classes "*") -Destination $stage -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path $res) {
        Copy-Item -Path (Join-Path $res "*") -Destination $stage -Recurse -Force
    }
    $pluginYml = Join-Path $stage "plugin.yml"
    if (-not (Test-Path $pluginYml)) {
        throw "Missing plugin.yml for $($Module.Name)"
    }
    $declared = (Select-String -Path $pluginYml -Pattern '^version:\s*["'']?([^"'']+)' | Select-Object -First 1).Matches.Groups[1].Value.Trim()
    if ($declared -ne $Module.Version) {
        throw "$($Module.Jar) plugin.yml version $declared does not match expected $($Module.Version)"
    }

    $jarPath = Join-Path $downloads "$($Module.Jar)-$($Module.Version).jar"
    Remove-Item -LiteralPath $jarPath -Force -ErrorAction SilentlyContinue
    $zipPath = "$jarPath.zip"
    Remove-Item -LiteralPath $zipPath -Force -ErrorAction SilentlyContinue
    Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zipPath -Force
    Move-Item -LiteralPath $zipPath -Destination $jarPath -Force
    return $jarPath
}

New-Item -ItemType Directory -Force -Path $downloads, $buildRoot | Out-Null
Remove-Item -Path (Join-Path $downloads "CrownsAPI-*.jar") -Force -ErrorAction SilentlyContinue
Remove-Item -Path (Join-Path $downloads "CrownsEconomy-*.jar") -Force -ErrorAction SilentlyContinue
Remove-Item -Path (Join-Path $downloads "CrownsAdmin-*.jar") -Force -ErrorAction SilentlyContinue
Remove-Item -Path (Join-Path $downloads "CrownsEvents-*.jar") -Force -ErrorAction SilentlyContinue
Remove-Item -Path (Join-Path $downloads "CrownsDrugs-*.jar") -Force -ErrorAction SilentlyContinue
Remove-Item -Path (Join-Path $downloads "CrownsMMO-*.jar") -Force -ErrorAction SilentlyContinue
Remove-Item -Path (Join-Path $downloads "CrownsTerrain-*.jar") -Force -ErrorAction SilentlyContinue

$jars = foreach ($module in $modules) {
    Compile-Module $module
}

$checksumPath = Join-Path $downloads "CHECKSUMS.txt"
$lines = @()
foreach ($file in Get-ChildItem -LiteralPath $downloads -File | Sort-Object Name) {
    if ($file.Name -eq "CHECKSUMS.txt") {
        continue
    }
    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName
    $lines += "$($hash.Hash.ToLowerInvariant())  $($file.Name)"
}
$lines | Set-Content -LiteralPath $checksumPath -Encoding UTF8

Write-Host "Verified and packaged $($jars.Count) Crowns Suite plugin jars."
Write-Host "Checksums refreshed: $checksumPath"
