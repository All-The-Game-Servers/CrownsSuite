param(
    [switch]$CrossRelay,
    [switch]$KeepArtifacts
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

function Wait-Http($url, $timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-RestMethod -Uri $url -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Timed out waiting for $url"
}

function Wait-TaskResult($baseUrl, $taskId, $timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $task = Invoke-RestMethod -Uri "$baseUrl/api/v1/tasks/$taskId" -TimeoutSec 5
        if ($task.status -in @("succeeded", "failed", "timed_out")) {
            return $task
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for task $taskId"
}

function Start-LoggedProcess($filePath, $argumentList, $environment, $logPath, $workingDirectory) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $filePath
    $psi.Arguments = $argumentList
    $psi.WorkingDirectory = $workingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($key in $environment.Keys) {
        $psi.Environment[$key] = [string]$environment[$key]
    }

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $proc.Start() | Out-Null

    $writer = [System.IO.StreamWriter]::new($logPath, $false)
    $stdoutHandler = [System.Diagnostics.DataReceivedEventHandler]{
        param($sender, $args)
        if ($null -ne $args.Data) {
            $writer.WriteLine($args.Data)
            $writer.Flush()
        }
    }
    $stderrHandler = [System.Diagnostics.DataReceivedEventHandler]{
        param($sender, $args)
        if ($null -ne $args.Data) {
            $writer.WriteLine($args.Data)
            $writer.Flush()
        }
    }
    $proc.add_OutputDataReceived($stdoutHandler)
    $proc.add_ErrorDataReceived($stderrHandler)
    $proc.BeginOutputReadLine()
    $proc.BeginErrorReadLine()

    return @{
        Process = $proc
        Writer = $writer
    }
}

function Stop-LoggedProcess($handle) {
    if (-not $handle) {
        return
    }
    try {
        if ($handle.Process -and -not $handle.Process.HasExited) {
            $handle.Process.Kill($true)
            $handle.Process.WaitForExit(5000) | Out-Null
        }
    } catch {
    }
    try {
        if ($handle.Writer) {
            $handle.Writer.Dispose()
        }
    } catch {
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$go = Find-Tool @("C:\Users\sirgi\tools\go\bin\go.exe", "C:\Program Files\Go\bin\go.exe") "go"
$docker = Find-Tool @() "docker"

$runRoot = Join-Path $env:TEMP ("atgs-phase3-local-" + [guid]::NewGuid().ToString("N"))
$binDir = Join-Path $runRoot "bin"
$centralState = Join-Path $runRoot "central"
$relayAState = Join-Path $runRoot "relay-a"
$relayBState = Join-Path $runRoot "relay-b"
$keeperState = Join-Path $runRoot "keeper"
$logsDir = Join-Path $runRoot "logs"

New-Item -ItemType Directory -Force -Path $binDir, $centralState, $relayAState, $keeperState, $logsDir | Out-Null
if ($CrossRelay) {
    New-Item -ItemType Directory -Force -Path $relayBState | Out-Null
}

$composeFile = Join-Path $repoRoot "deploy\postgres\docker-compose.yml"
$databaseUrl = "postgres://atgs:atgs@127.0.0.1:5432/atgs?sslmode=disable"
$adminBase = "http://127.0.0.1:8080"
$keeperBase = "https://127.0.0.1:8443"

$processes = @()

try {
    try {
        Invoke-Checked -FilePath $docker -Arguments @("info")
    } catch {
        throw "Docker is required for the local smoke test. Start Docker Desktop or another Docker daemon first."
    }

    Invoke-Checked -FilePath $docker -Arguments @("compose", "-f", $composeFile, "up", "-d", "postgres")
    $postgresReady = $false
    for ($i = 0; $i -lt 30; $i++) {
        try {
            & $docker exec atgs-postgres-dev pg_isready -U atgs | Out-Null
            $postgresReady = $true
            break
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $postgresReady) {
        throw "Postgres container did not become ready. Check Docker and the deploy/postgres compose stack."
    }

    Push-Location $repoRoot
    try {
        Invoke-Checked -FilePath $go -Arguments @("build", "-o", (Join-Path $binDir "central.exe"), ".\central\cmd\central")
        Invoke-Checked -FilePath $go -Arguments @("build", "-o", (Join-Path $binDir "relay.exe"), ".\relay\cmd\relay")
        Invoke-Checked -FilePath $go -Arguments @("build", "-o", (Join-Path $binDir "keeper.exe"), ".\keeper\cmd\keeper")
        Invoke-Checked -FilePath $go -Arguments @("build", "-o", (Join-Path $binDir "fake_minecraft.exe"), ".\scripts\fake_minecraft.go")
        Invoke-Checked -FilePath $go -Arguments @("build", "-o", (Join-Path $binDir "fake_client.exe"), ".\scripts\fake_client.go")
    } finally {
        Pop-Location
    }

    $commonEnv = @{
        "ATGS_CENTRAL_DATABASE_URL" = $databaseUrl
        "ATGS_CENTRAL_CA_DIR" = (Join-Path $centralState "ca")
        "ATGS_CENTRAL_ADMIN_ADDR" = "127.0.0.1:8080"
        "ATGS_CENTRAL_KEEPER_ADDR" = "127.0.0.1:8443"
        "ATGS_CENTRAL_DEV" = "true"
    }
    foreach ($key in $commonEnv.Keys) {
        Set-Item -Path ("Env:" + $key) -Value $commonEnv[$key]
    }

    Invoke-Checked -FilePath (Join-Path $binDir "central.exe") -Arguments @("bootstrap-ca")
    Invoke-Checked -FilePath (Join-Path $binDir "central.exe") -Arguments @("migrate")
    Invoke-Checked -FilePath (Join-Path $binDir "central.exe") -Arguments @("mint-relay-cert", $relayAState)
    if ($CrossRelay) {
        Invoke-Checked -FilePath (Join-Path $binDir "central.exe") -Arguments @("mint-relay-cert", $relayBState)
    }

    $centralHandle = Start-LoggedProcess -filePath (Join-Path $binDir "central.exe") -argumentList "serve" -environment $commonEnv -logPath (Join-Path $logsDir "central.log") -workingDirectory $repoRoot
    $processes += $centralHandle
    Wait-Http "$adminBase/api/v1/version" 20

    $relayAEnv = @{
        "ATGS_RELAY_STATE_DIR" = $relayAState
        "ATGS_RELAY_CENTRAL_SYNC_URL" = "wss://127.0.0.1:8443/api/v1/relay-sync"
        "ATGS_RELAY_INGRESS_ADDR" = "127.0.0.1:25565"
        "ATGS_RELAY_DATA_ADDR" = "127.0.0.1:7443"
        "ATGS_RELAY_PEER_ADDR" = "127.0.0.1:7444"
        "ATGS_RELAY_DEV" = "true"
    }
    if ($CrossRelay) {
        $relayAEnv["ATGS_RELAY_PEERS"] = "127.0.0.1:7544"
    }
    $relayAHandle = Start-LoggedProcess -filePath (Join-Path $binDir "relay.exe") -argumentList "serve" -environment $relayAEnv -logPath (Join-Path $logsDir "relay-a.log") -workingDirectory $repoRoot
    $processes += $relayAHandle

    if ($CrossRelay) {
        $relayBEnv = @{
            "ATGS_RELAY_STATE_DIR" = $relayBState
            "ATGS_RELAY_CENTRAL_SYNC_URL" = "wss://127.0.0.1:8443/api/v1/relay-sync"
            "ATGS_RELAY_INGRESS_ADDR" = "127.0.0.1:25566"
            "ATGS_RELAY_DATA_ADDR" = "127.0.0.1:7543"
            "ATGS_RELAY_PEER_ADDR" = "127.0.0.1:7544"
            "ATGS_RELAY_PEERS" = "127.0.0.1:7444"
            "ATGS_RELAY_DEV" = "true"
        }
        $relayBHandle = Start-LoggedProcess -filePath (Join-Path $binDir "relay.exe") -argumentList "serve" -environment $relayBEnv -logPath (Join-Path $logsDir "relay-b.log") -workingDirectory $repoRoot
        $processes += $relayBHandle
    }

    Start-Sleep -Seconds 3

    $tokenResp = Invoke-RestMethod -Method Post -Uri "$adminBase/api/v1/enrollment-tokens" -ContentType "application/json" -Body '{"note":"phase3 local smoke"}'
    $keeperEnv = @{
        "ATGS_ENROLL_TOKEN" = $tokenResp.token
        "ATGS_KEEPER_STATE_DIR" = $keeperState
        "ATGS_KEEPER_EGGS_DIR" = (Join-Path $repoRoot "eggs")
        "ATGS_KEEPER_CENTRAL_URL" = $keeperBase
        "ATGS_KEEPER_FAKE_DOCKER" = "true"
        "ATGS_KEEPER_INSECURE_TLS" = "true"
        "ATGS_KEEPER_HEADLESS" = "true"
        "ATGS_KEEPER_RELAY_DATA_URLS" = $(if ($CrossRelay) { "wss://127.0.0.1:7543/ws/data" } else { "wss://127.0.0.1:7443/ws/data" })
    }
    $keeperHandle = Start-LoggedProcess -filePath (Join-Path $binDir "keeper.exe") -argumentList "--headless" -environment $keeperEnv -logPath (Join-Path $logsDir "keeper.log") -workingDirectory $repoRoot
    $processes += $keeperHandle

    Start-Sleep -Seconds 4

    $keepers = Invoke-RestMethod -Uri "$adminBase/api/v1/keepers"
    if (-not $keepers.keepers -or $keepers.keepers.Count -lt 1) {
        throw "No keepers enrolled"
    }
    $keeperId = $keepers.keepers[0].id

    $createBody = @{
        egg_id = "minecraft-java-paper"
        display_name = "Lowlight SMP"
        hostname = "lowlight.mine.bz"
        memory_bytes = 2147483648
        cpu_shares = 1024
    } | ConvertTo-Json
    $createResp = Invoke-RestMethod -Method Post -Uri "$adminBase/api/v1/keepers/$keeperId/instances" -ContentType "application/json" -Body $createBody
    $createTask = Wait-TaskResult $adminBase $createResp.task_id 45
    if ($createTask.status -ne "succeeded") {
        throw "Create task failed: $($createTask | ConvertTo-Json -Depth 6)"
    }
    $hostPort = [int]$createTask.result.host_port
    if ($hostPort -le 0) {
        throw "Create task did not return a usable host_port"
    }

    $startResp = Invoke-RestMethod -Method Post -Uri "$adminBase/api/v1/instances/$($createResp.instance_id)/start"
    $startTask = Wait-TaskResult $adminBase $startResp.task_id 30
    if ($startTask.status -ne "succeeded") {
        throw "Start task failed: $($startTask | ConvertTo-Json -Depth 6)"
    }
    if ([int]$startTask.result.host_port -gt 0) {
        $hostPort = [int]$startTask.result.host_port
    }

    $fakeServerHandle = Start-LoggedProcess -filePath (Join-Path $binDir "fake_minecraft.exe") -argumentList "$hostPort" -environment @{} -logPath (Join-Path $logsDir "fake-minecraft.log") -workingDirectory $repoRoot
    $processes += $fakeServerHandle
    Start-Sleep -Seconds 1

    $clientOutput = & (Join-Path $binDir "fake_client.exe") "127.0.0.1:25565" "lowlight.mine.bz"
    if ($LASTEXITCODE -ne 0 -or $clientOutput.Trim() -ne "OK") {
        throw "Java relay smoke failed"
    }

    Write-Host "ATGS Phase 3 local smoke passed."
    Write-Host "Cross relay: $CrossRelay"
    Write-Host "Run artifacts: $runRoot"
} finally {
    foreach ($proc in $processes) {
        Stop-LoggedProcess $proc
    }
    try {
        & $docker compose -f $composeFile down -v | Out-Null
    } catch {
    }
    if (-not $KeepArtifacts -and (Test-Path $runRoot)) {
        Remove-Item -LiteralPath $runRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
