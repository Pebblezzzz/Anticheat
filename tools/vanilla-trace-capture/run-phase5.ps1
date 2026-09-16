$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$captureDir = Join-Path $env:SystemDrive 'phase5'
$trace = Join-Path $captureDir 'phase5-all.tsv'
$events = Join-Path $captureDir 'phase5-all-events.tsv'

New-Item -ItemType Directory -Force -Path $captureDir | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue $trace, $events

Push-Location $repo
try {
    Write-Host '== Phase 5: Maven regression suite =='
    mvn -B test

    Write-Host '== Phase 5: build 1.21.11 capture harness =='
    Push-Location (Join-Path $repo 'tools\vanilla-trace-capture')
    try {
        .\gradlew.bat build

        Write-Host '== Phase 5: launch controlled vanilla 1.21.11 batch capture =='
        Write-Host "Trace:  $trace"
        Write-Host "Events: $events"
        .\gradlew.bat --project-prop=phantom.capture.enabled=true `
            --project-prop=phantom.capture.scenario=all `
            --project-prop="phantom.capture.output=$trace" `
            --project-prop="phantom.capture.events=$events" `
            runClient
    }
    finally {
        Pop-Location
    }

    Write-Host '== Phase 5: audit captured vanilla trace =='
    Push-Location $repo
    try {
        mvn -B -Dphantom.phase5.trace="$trace" -Dtest=Phase5VanillaBatchAuditTest test
    }
    finally {
        Pop-Location
    }

    Write-Host ''
    Write-Host 'Phase 5 automated run finished.'
    Write-Host "Vanilla trace: $trace"
    Write-Host "Event trace:   $events"
}
finally {
    Pop-Location
}
