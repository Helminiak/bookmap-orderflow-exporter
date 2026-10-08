[CmdletBinding()]
param(
    [string]$Jar = '',
    [string]$HostName = '',
    [ValidateRange(1,65535)][int]$HealthPort = 5556,
    [ValidateRange(5,3600)][int]$Seconds = 60,
    [string]$JavaPath,
    [string]$ReportDirectory = '',
    [switch]$NoPrompt
)
$ErrorActionPreference = 'Stop'
# Resolve after parameter binding; retain the file's location across function/dot-source scopes.
$script:SmokeDirectory = [IO.Path]::GetDirectoryName($PSCommandPath)
if (-not $script:SmokeDirectory) { $script:SmokeDirectory = $PSScriptRoot }
if (-not $script:SmokeDirectory) { $script:SmokeDirectory = (Get-Location).Path }
if (-not $Jar) { $Jar = Join-Path $script:SmokeDirectory 'bookmap-orderflow-exporter-v0.5a.jar' }
if (-not $ReportDirectory) { $ReportDirectory = $script:SmokeDirectory }

function Measure-BridgeSample {
    param($Sample, $PreviousAck, $PreviousPublished)
    foreach ($name in 'protocol','session','state','invalid','overflows','acknowledged_seq','last_published_seq','receiver','journal') {
        if ($null -eq $Sample.PSObject.Properties[$name]) { throw "Health response is missing $name" }
    }
    if ($Sample.protocol -ne 'orderflow-live-v0.1') { throw 'Unsupported health protocol' }
    if ($Sample.invalid -isnot [bool] -or $Sample.invalid) { throw "Bridge INVALID: $($Sample.reason)" }
    if ($Sample.overflows -ne 0) { throw 'Bridge overflow detected' }
    if ($Sample.journal.writer_ok -isnot [bool] -or -not $Sample.journal.writer_ok) { throw 'Journal writer is not healthy' }
    if ($null -eq $Sample.journal.PSObject.Properties['overflows'] -or $Sample.journal.overflows -ne 0) {
        throw 'Journal overflow counter is missing or nonzero'
    }
    foreach ($name in 'acknowledged_seq','last_published_seq') {
        if ($Sample.$name -isnot [long] -and $Sample.$name -isnot [int]) { throw "Invalid sequence counter: $name" }
        if ($Sample.$name -lt 0) { throw "Negative sequence counter: $name" }
    }
    if ($Sample.acknowledged_seq -gt $Sample.last_published_seq) { throw 'ACK exceeds published sequence' }
    if ($null -ne $PreviousAck -and $Sample.acknowledged_seq -lt $PreviousAck) { throw 'Acknowledged sequence moved backwards' }
    if ($null -ne $PreviousPublished -and $Sample.last_published_seq -lt $PreviousPublished) { throw 'Published sequence moved backwards' }
    if ($Sample.journal.current_seq -isnot [long] -and $Sample.journal.current_seq -isnot [int]) { throw 'Journal current sequence is missing/invalid' }
    if ($Sample.journal.current_seq -lt $Sample.last_published_seq) { throw 'Published sequence exceeds source sequence' }
    return ($Sample.state -eq 'CONNECTED' -and $Sample.receiver -and $Sample.receiver -ne 'none')
}

function Get-LocalLanAddresses {
    $addresses = @()
    foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
        if ($nic.OperationalStatus -ne 'Up' -or $nic.NetworkInterfaceType -eq 'Loopback') { continue }
        $props = $nic.GetIPProperties()
        foreach ($address in $props.UnicastAddresses) {
            if ($address.Address.AddressFamily -ne 'InterNetwork') { continue }
            $ip = $address.Address.ToString()
            if ($ip -notmatch '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)') { continue }
            $addresses += $ip
        }
    }
    return @($addresses | Select-Object -Unique)
}

function Resolve-LocalHealthHost {
    param([string]$Runtime, [string]$AddonJar, [int]$Port, [string[]]$Addresses)
    # Reading health never registers a second market receiver.
    foreach ($candidate in @($Addresses) + @('127.0.0.1') | Select-Object -Unique) {
        $reply = & $Runtime -cp $AddonJar com.limacharlie.orderflow.BridgeHealthQuery $candidate $Port 1
        foreach ($line in $reply) {
            try {
                $health = $line | ConvertFrom-Json
                if ($health.protocol -eq 'orderflow-live-v0.1') { return $candidate }
            } catch { }
        }
    }
    return $null
}

function Find-JavaRuntime {
    param([string[]]$Candidates)
    if (-not $Candidates) {
        $Candidates = @()
        foreach ($root in @($env:ProgramW6432, $env:ProgramFiles, ${env:ProgramFiles(x86)}) | Select-Object -Unique) {
            if (-not $root) { continue }
            $Candidates += Join-Path $root 'Bookmap\jre\bin\java.exe'
            $Candidates += Join-Path $root 'Bookmap\runtime\bin\java.exe'
        }
        $Candidates += 'C:\Bookmap\jre\bin\java.exe'
        if ($env:JAVA_HOME) { $Candidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
        $command = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($command) { $Candidates += $command.Source }
        foreach ($root in @($env:ProgramW6432, $env:ProgramFiles) | Select-Object -Unique) {
            if (-not $root) { continue }
            foreach ($vendor in 'Java','Eclipse Adoptium','Microsoft','Amazon Corretto') {
                $folder = Join-Path $root $vendor
                if (Test-Path -LiteralPath $folder -PathType Container) {
                    foreach ($install in Get-ChildItem -LiteralPath $folder -Directory -ErrorAction SilentlyContinue) {
                        $Candidates += Join-Path $install.FullName 'bin\java.exe'
                    }
                }
            }
        }
    }
    foreach ($candidate in $Candidates | Select-Object -Unique) {
        if (-not $candidate -or -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        # Java writes its version to stderr. Do not turn that normal output into a fatal error.
        $savedPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $version = (& $candidate -version 2>&1 | Out-String)
            if ($LASTEXITCODE -eq 0 -and $version -match 'version "(\d+)(?:\.(\d+))?') {
                $major = [int]$Matches[1]
                if ($major -ge 17) { return $candidate }
            }
        } catch { } finally { $ErrorActionPreference = $savedPreference }
    }
    return $null
}

function Invoke-BridgeSmokeTest {
    $localAddresses = @(Get-LocalLanAddresses)
    Write-Host ("Windows LAN IP(s): " + ($localAddresses -join ', ')) -ForegroundColor Cyan
    if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { throw "Put the updated exporter JAR beside Smoke-Test.bat. Missing: $Jar" }
    if (-not $JavaPath) {
        $JavaPath = Find-JavaRuntime
        if ($JavaPath) { Write-Host "Using Java: $JavaPath" -ForegroundColor Cyan }
        elseif (-not $NoPrompt) {
            $JavaPath = (Read-Host 'Full path to Java 17+ java.exe (Bookmap runtime or installed Java)').Trim('"')
        }
    }
    if (-not $JavaPath -or -not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
        throw 'Java 17+ was not found. Use -JavaPath "C:\path\to\java.exe".'
    }
    if (-not $HostName) {
        Write-Host 'Looking for the Bookmap health listener on your LAN addresses and localhost...'
        $HostName = Resolve-LocalHealthHost $JavaPath $Jar $HealthPort $localAddresses
        if (-not $HostName) {
            $HostName = if ($localAddresses.Count -gt 0) { $localAddresses[0] } else { '127.0.0.1' }
            Write-Host "No health listener answered. Use Bookmap bind 0.0.0.0, enable bridge, and Apply/restart." -ForegroundColor Yellow
            Write-Host "If Bookmap reports cannot bind, another instance may own port $HealthPort. Use one exporter per port pair." -ForegroundColor Yellow
        }
    }
    Write-Host ''
    Write-Host 'ORDERFLOW - LIVE WINDOWS TO UBUNTU SMOKE TEST' -ForegroundColor Cyan
    Write-Host "Checking the Bookmap health channel at ${HostName}:$HealthPort for $Seconds seconds."
    Write-Host 'This reads health only; it does not register a second market receiver or change settings.'
    Write-Host ''
    Write-Host '1. On Ubuntu, start the private receiver against your Windows LAN IP.'
    Write-Host '2. In Bookmap, enable the bridge and Apply/restart for a fresh START.'
    Write-Host '3. Confirm Ubuntu shows HEALTHY. Leave Bookmap receiving events during this test.'
    Write-Host '   If bridge is already INVALID, restart Ubuntu receiver first, then Apply in Bookmap.'
    if (-not $NoPrompt) { [void](Read-Host 'Press Enter when both are ready') }
    [void](New-Item -ItemType Directory -Path $ReportDirectory -Force)
    $reportPath = Join-Path $ReportDirectory ('smoke-test-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.json')
    $samples = New-Object 'System.Collections.Generic.List[object]'
    $failures = New-Object 'System.Collections.Generic.List[string]'
    $session = $null; $firstAck = $null; $lastAck = $null; $lastPublished = $null; $connectedCount = 0; $queriesFailed = 0
    $firstPublished = $null; $lastAckProgressAt = Get-Date
    & $JavaPath -cp $Jar com.limacharlie.orderflow.BridgeHealthQuery $HostName $HealthPort $Seconds | ForEach-Object {
        try {
            $sample = $_ | ConvertFrom-Json
            if ($sample.query_error) {
                $queriesFailed++
                $failures.Add([string]$sample.query_error)
                Write-Host "NO REPLY: $($sample.query_error)" -ForegroundColor Yellow
            } else {
                $samples.Add($sample)
                if ($null -eq $session) { $session = $sample.session }
                if ($sample.session -ne $session) { throw 'Exporter restarted during test; rerun with a stable session' }
                $connected = Measure-BridgeSample $sample $lastAck $lastPublished
                if ($connected) { $connectedCount++ } else { $failures.Add("Receiver not connected: $($sample.state)") }
                if ($null -eq $firstAck) { $firstAck = [long]$sample.acknowledged_seq; $firstPublished = [long]$sample.last_published_seq }
                if ($null -eq $lastAck -or $sample.acknowledged_seq -gt $lastAck) { $lastAckProgressAt = Get-Date }
                if (((Get-Date) - $lastAckProgressAt).TotalSeconds -ge 5 -and $sample.journal.current_seq -gt $sample.acknowledged_seq) {
                    $failures.Add('Receiver ACK stalled with pending events. Check Ubuntu for integrity errors or disconnect.')
                }
                $lastAck = [long]$sample.acknowledged_seq; $lastPublished = [long]$sample.last_published_seq
                Write-Host ("{0}  published={1}  receiver ACK={2}  queue={3}/{4}  overflow={5}" -f
                    $sample.state,$lastPublished,$lastAck,$sample.queue_depth,$sample.queue_capacity,$sample.overflows)
            }
        } catch {
            $failures.Add($_.Exception.Message)
            Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red
        }
    }
    if ($LASTEXITCODE -ne 0) { $failures.Add('Health probe could not run. Check Java 17+ and the updated JAR.') }
    if ($samples.Count -lt 2) { $failures.Add('Too few valid health replies') }
    if ($connectedCount -lt 2) { $failures.Add('No confirmed continuous receiver connection') }
    $ackAdvance = if ($null -eq $firstAck -or $null -eq $lastAck) { 0 } else { $lastAck - $firstAck }
    $publishAdvance = if ($null -eq $firstPublished -or $null -eq $lastPublished) { 0 } else { $lastPublished - $firstPublished }
    $status = if ($failures.Count -gt 0) { 'FAIL' } elseif ($ackAdvance -le 0 -or $publishAdvance -le 0) { 'INCONCLUSIVE' } else { 'PASS' }
    $report = [ordered]@{
        status=$status; checked_at=(Get-Date).ToUniversalTime().ToString('o'); health_host=$HostName; health_port=$HealthPort
        requested_seconds=$Seconds; exporter_session=$session; acknowledged_events_during_test=$ackAdvance
        published_events_during_test=$publishAdvance; query_failures=$queriesFailed; failures=@($failures.ToArray()); samples=@($samples.ToArray())
        scope='Publisher health and validated receiver ACK progress. Confirm Ubuntu terminal identity/HEALTHY separately. Does not measure Bookmap slowdown or finalize/validate the disk archive.'
    }
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Host ''
    Write-Host "$status - acknowledged $ackAdvance new events. Report: $reportPath" -ForegroundColor $(if($status -eq 'PASS'){'Green'}else{'Yellow'})
    if ($status -eq 'INCONCLUSIVE') { Write-Host 'No new events were acknowledged. Rerun while Bookmap replay/live events are flowing.' }
    Write-Host 'Also verify Ubuntu is HEALTHY with zero gaps/duplicates. This test does not measure Bookmap slowdown.'
    if ($status -eq 'FAIL') { return 1 }
    if ($status -eq 'INCONCLUSIVE') { return 2 }
    return 0
}

# Dot-sourcing loads the validation functions for regression tests without starting a live probe.
if ($MyInvocation.InvocationName -ne '.') {
    try { exit (Invoke-BridgeSmokeTest) }
    catch { Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
}
