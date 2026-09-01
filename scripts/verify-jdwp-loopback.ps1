param()

$ErrorActionPreference = "Stop"
$repository = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$settings = Get-Content -LiteralPath (Join-Path $repository "config\agent-settings.json") `
    -Raw -Encoding UTF8 | ConvertFrom-Json

function Expand-Setting([string]$value) {
    return $value.Replace("%USERPROFILE%", $env:USERPROFILE).Replace("%LOCALAPPDATA%", $env:LOCALAPPDATA)
}

function Resolve-JavaHome([string]$configured, [string]$fallback, [string]$role) {
    $javaHome = if ([string]::IsNullOrWhiteSpace($configured)) { $fallback } else { Expand-Setting $configured }
    if ([string]::IsNullOrWhiteSpace($javaHome)) { throw "$role Java home is not configured" }
    $resolved = [System.IO.Path]::GetFullPath($javaHome)
    if (-not (Test-Path -LiteralPath (Join-Path $resolved "bin\java.exe") -PathType Leaf)) {
        throw "$role Java executable is missing under: $resolved"
    }
    return $resolved
}

$agentHome = Resolve-JavaHome $settings.agentJavaHome $env:JAVA_HOME "Agent"
$targetHome = Resolve-JavaHome $settings.targetJavaHome $agentHome "Target"
$workspace = [System.IO.Path]::GetFullPath((Expand-Setting $settings.workspaceDirectory))
$runId = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmssfff") + "-" + [guid]::NewGuid().ToString("N")
$runDirectory = Join-Path $workspace "environment-checks\jdwp-loopback\$runId"
$classes = Join-Path $runDirectory "classes"
$collectorOutput = Join-Path $runDirectory "collector"
$logs = Join-Path $runDirectory "logs"
New-Item -ItemType Directory -Path $classes,$collectorOutput,$logs -Force | Out-Null

$source = Join-Path $repository "scripts\fixtures\jdwp-loopback\JdwpLoopbackProbe.java"
$template = Join-Path $repository "scripts\fixtures\jdwp-loopback\collector-plan.template.json"
$collectorJar = Join-Path $repository "tools\jdwp-collector\jdwp-batch-collector.jar"
if (-not (Test-Path -LiteralPath $collectorJar -PathType Leaf)) {
    throw "JDWP Collector JAR is missing. Run scripts\build-agent.ps1 first."
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()
$sourceLines = Get-Content -LiteralPath $source
$traceLine = 0
for ($index = 0; $index -lt $sourceLines.Count; $index++) {
    if ($sourceLines[$index].Contains("JDWP_LOOPBACK_TRACEPOINT")) { $traceLine = $index + 1; break }
}
if ($traceLine -eq 0) { throw "JDWP loopback tracepoint marker is missing" }
$plan = Join-Path $runDirectory "collector-plan.json"
$planText = (Get-Content -LiteralPath $template -Raw -Encoding UTF8).Replace(
    "__PORT__", $port.ToString()).Replace("__LINE__", $traceLine.ToString())
[System.IO.File]::WriteAllText($plan, $planText, [System.Text.UTF8Encoding]::new($false))

$targetJob = $null
$failure = $null
$status = "FAILED"
try {
    & (Join-Path $targetHome "bin\javac.exe") -g -d $classes $source
    if ($LASTEXITCODE -ne 0) { throw "JDWP loopback Probe compilation failed" }
    $targetStdout = Join-Path $logs "target-stdout.log"
    $targetStderr = Join-Path $logs "target-stderr.log"
    $targetJava = Join-Path $targetHome "bin\java.exe"
    $targetJob = Start-Job -ScriptBlock {
        param($java, $jdwpPort, $classPath, $stdoutPath, $stderrPath)
        & $java "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:$jdwpPort" `
            -cp $classPath JdwpLoopbackProbe 1> $stdoutPath 2> $stderrPath
        return [int]$LASTEXITCODE
    } -ArgumentList $targetJava,$port,$classes,$targetStdout,$targetStderr

    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline -and $targetJob.State -eq "Running") {
        if ((Test-Path -LiteralPath $targetStdout -PathType Leaf) -and
                (Get-Content -LiteralPath $targetStdout -Raw -ErrorAction SilentlyContinue) `
                    -match "Listening for transport dt_socket at address: $port") {
            $ready = $true
        }
        if (-not $ready) { Start-Sleep -Milliseconds 100 }
    }
    if (-not $ready) { throw "JDWP target did not open the loopback port" }

    & (Join-Path $agentHome "bin\java.exe") --add-modules jdk.jdi -jar $collectorJar `
        collect --plan $plan --output $collectorOutput `
        1> (Join-Path $logs "collector-stdout.log") `
        2> (Join-Path $logs "collector-stderr.log")
    if ($LASTEXITCODE -ne 0) { throw "JDWP Collector exited with code $LASTEXITCODE" }
    $completedJob = Wait-Job -Job $targetJob -Timeout 10
    if ($null -eq $completedJob) { throw "JDWP target did not resume and exit" }
    $targetExitCode = Receive-Job -Job $targetJob | Select-Object -Last 1
    if ($targetExitCode -ne 0) { throw "JDWP target exited with code $targetExitCode" }

    $trace = Join-Path $collectorOutput "raw-trace.jsonl"
    $manifest = Join-Path $collectorOutput "collection-manifest.json"
    if (-not (Test-Path -LiteralPath $trace -PathType Leaf) -or (Get-Item $trace).Length -eq 0) {
        throw "JDWP raw trace is missing or empty"
    }
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { throw "JDWP manifest is missing" }
    $traceText = Get-Content -LiteralPath $trace -Raw -Encoding UTF8
    if ($traceText -notmatch 'marker' -or $traceText -notmatch '42' `
            -or $traceText -notmatch '"conditionResult":"MATCHED"') {
        throw "JDWP raw trace does not contain the matched marker=42 snapshot"
    }
    $manifestData = Get-Content -LiteralPath $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifestData.observedHitCounts.'probe-marker' -ne 1 `
            -or $manifestData.matchedHitCounts.'probe-marker' -ne 1 `
            -or $manifestData.capturedHitCounts.'probe-marker' -ne 1 `
            -or $manifestData.conditionUnavailableCounts.'probe-marker' -notin @(0, $null)) {
        throw "JDWP manifest conditional hit counters are invalid"
    }
    $targetText = Get-Content -LiteralPath (Join-Path $logs "target-stdout.log") -Raw -Encoding UTF8
    if ($targetText -notmatch 'JDWP_LOOPBACK_TARGET_OK marker=42') {
        throw "JDWP target completion marker is missing"
    }
    $status = "PASSED"
}
catch {
    $failure = $_.Exception.Message
}
finally {
    if ($null -ne $targetJob) {
        if ($targetJob.State -eq "Running") { Stop-Job -Job $targetJob -ErrorAction SilentlyContinue }
        Remove-Job -Job $targetJob -Force -ErrorAction SilentlyContinue
    }
    $summary = [ordered]@{
        schemaVersion = "1.0"
        status = $status
        agentJavaHome = $agentHome
        targetJavaHome = $targetHome
        port = $port
        marker = 42
        failure = $failure
    } | ConvertTo-Json
    [System.IO.File]::WriteAllText(
        (Join-Path $runDirectory "verification-summary.json"), $summary,
        [System.Text.UTF8Encoding]::new($false))
}

if ($status -ne "PASSED") { throw "JDWP_LOOPBACK_FAILED: $failure. Evidence: $runDirectory" }
Write-Output "JDWP_LOOPBACK_OK $runDirectory"
