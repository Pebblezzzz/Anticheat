$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$captureDir = Join-Path $env:SystemDrive 'phase5'
$trace = Join-Path $captureDir 'phase5-all.tsv'
$events = Join-Path $captureDir 'phase5-all-events.tsv'

function Invoke-Native([string] $exe, [string[]] $arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code $LASTEXITCODE: $exe $($arguments -join ' ')"
    }
}

New-Item -ItemType Directory -Force -Path $captureDir | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue $trace, $events

Push-Location $repo
try {
    Write-Host '== Phase 5: Maven regression suite =='
    Invoke-Native 'mvn' @('-B', 'test')

    Write-Host '== Phase 5: build 1.21.11 capture harness =='
    Push-Location (Join-Path $repo 'tools\vanilla-trace-capture')
    try {
        Invoke-Native '.\gradlew.bat' @('build')

        Write-Host '== Phase 5: launch controlled vanilla 1.21.11 batch capture =='
        Write-Host "Trace:  $trace"
        Write-Host "Events: $events"
        Invoke-Native '.\gradlew.bat' @(
            '--project-prop=phantom.capture.enabled=true',
            '--project-prop=phantom.capture.scenario=all',
            "--project-prop=phantom.capture.output=$trace",
            "--project-prop=phantom.capture.events=$events",
            'runClient'
        )
    }
    finally {
        Pop-Location
    }

    if (!(Test-Path -LiteralPath $trace)) { throw "Vanilla trace was not produced: $trace" }
    if (!(Test-Path -LiteralPath $events)) { throw "Vanilla event trace was not produced: $events" }

    Write-Host '== Phase 5: audit captured vanilla trace =='
    Invoke-Native 'mvn' @('-B', "-Dphantom.phase5.trace=$trace", '-Dtest=Phase5VanillaBatchAuditTest', 'test')

    Write-Host ''
    Write-Host 'Phase 5 automated run finished successfully.'
    Write-Host "Vanilla trace: $trace"
    Write-Host "Event trace:   $events"
}
finally {
    Pop-Location
}
