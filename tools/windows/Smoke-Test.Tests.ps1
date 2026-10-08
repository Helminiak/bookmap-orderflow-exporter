$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Smoke-Test.ps1')
function New-Sample {
    return ('{"protocol":"orderflow-live-v0.1","session":"synthetic-session","state":"CONNECTED","invalid":false,"overflows":0,"acknowledged_seq":10,"last_published_seq":12,"receiver":"synthetic-receiver","journal":{"writer_ok":true,"overflows":0,"current_seq":12}}' | ConvertFrom-Json)
}
if (-not (Measure-BridgeSample (New-Sample) 9 11)) { throw 'Healthy sample was rejected' }
foreach ($case in 'invalid','overflow','writer','journal-overflow','ack-regression','published-regression','ack-ahead','missing-field','missing-source-seq') {
    $sample = New-Sample
    $previousAck = 9; $previousPublished = 11
    switch ($case) {
        'invalid' { $sample.invalid = $true }
        'overflow' { $sample.overflows = 1 }
        'writer' { $sample.journal.writer_ok = $false }
        'journal-overflow' { $sample.journal.overflows = 1 }
        'ack-regression' { $previousAck = 11 }
        'published-regression' { $previousPublished = 13 }
        'ack-ahead' { $sample.acknowledged_seq = 13 }
        'missing-source-seq' { $sample.journal.PSObject.Properties.Remove('current_seq') }
        'missing-field' { $sample.PSObject.Properties.Remove('invalid') }
    }
    $rejected = $false
    try { [void](Measure-BridgeSample $sample $previousAck $previousPublished) } catch { $rejected = $true }
    if (-not $rejected) { throw "Accepted invalid sample: $case" }
}
$waiting = New-Sample; $waiting.state = 'WAITING_RECEIVER'
if (Measure-BridgeSample $waiting 9 11) { throw 'Waiting state counted as connected' }

# End-to-end launcher decision/report coverage using a fake Java health process, no real market receiver.
$temp = Join-Path ([IO.Path]::GetTempPath()) ('orderflow-launcher-' + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $temp)
try {
    $fakeJava = Join-Path $temp 'java.cmd'
    $dummyJar = Join-Path $temp 'fixture.jar'
    Set-Content $dummyJar 'synthetic'
    $healthy = New-Sample
    $later = New-Sample; $later.acknowledged_seq = 11; $later.last_published_seq = 13; $later.journal.current_seq = 13
    $script = Join-Path $PSScriptRoot 'Smoke-Test.ps1'
    foreach ($case in 'pass','idle','invalid','no-reply','stalled-ack') {
        $one = $healthy | ConvertTo-Json -Compress -Depth 6
        $two = $later | ConvertTo-Json -Compress -Depth 6
        $expected = 0; $delay = ''
        switch ($case) {
            'idle' { $two = $one; $expected = 2 }
            'invalid' { $bad = New-Sample; $bad.invalid = $true; $two = $bad | ConvertTo-Json -Compress -Depth 6; $expected = 1 }
            'stalled-ack' { $stalled = New-Sample; $stalled.journal.current_seq = 30; $two = $stalled | ConvertTo-Json -Compress -Depth 6; $delay = 'powershell.exe -NoProfile -Command "Start-Sleep -Seconds 6"'; $expected = 1 }
            'no-reply' { $one = '{"query_error":"synthetic timeout"}'; $two = $one; $expected = 1 }
        }
        # Plain synthetic values contain no cmd expansion/metacharacters.
        Set-Content $fakeJava ("@echo off`r`necho $one`r`n$delay`r`necho $two`r`nexit /b 0`r`n") -Encoding ASCII
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -Jar $dummyJar -JavaPath $fakeJava -ReportDirectory $temp -Seconds 5 -NoPrompt
        if ($LASTEXITCODE -ne $expected) { throw "$case expected exit $expected, got $LASTEXITCODE" }
    }
} finally { Remove-Item -LiteralPath $temp -Recurse -Force }
Write-Host 'Smoke launcher health validation and PASS/FAIL/INCONCLUSIVE reports passed.'
