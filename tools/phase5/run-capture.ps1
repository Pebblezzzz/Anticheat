[CmdletBinding()]
param(
    [switch]$SkipMaven,
    [switch]$SkipClient,
    [string]$CaptureRoot = "$env:SystemDrive\phase5"
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$harness = Join-Path $repo 'tools\vanilla-trace-capture'
$trace = Join-Path $CaptureRoot 'phase5-all.tsv'
$events = Join-Path $CaptureRoot 'phase5-all-events.tsv'

function Require-Command([string]$name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Required command '$name' was not found on PATH. Install Java 21 and Maven, then retry."
    }
}
function Invoke-Native([string]$exe,[string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code ${LASTEXITCODE}: $exe $($arguments -join ' ')" }
}

New-Item -ItemType Directory -Force -Path $CaptureRoot | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue $trace,$events

Require-Command 'java'
if (-not $SkipMaven) { Require-Command 'mvn' }

Push-Location $repo
try {
    if (-not $SkipMaven) {
        Write-Host '== Phase 5: Maven regression suite =='
        Invoke-Native 'mvn' @('-B','test')
    }

    Write-Host '== Phase 5: build vanilla 1.21.11 observation harness =='
    Push-Location $harness
    try {
        Invoke-Native '.\gradlew.bat' @('build')
        if (-not $SkipClient) {
            Write-Host ''
            Write-Host 'Minecraft will open now. Do not enable cheats or movement-altering mods.'
            Write-Host 'The observation harness drives only its own controlled capture scenarios.'
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
    }
    finally { Pop-Location }

    if (-not $SkipClient) {
        if (-not (Test-Path -LiteralPath $trace)) { throw "Vanilla trace was not produced: $trace" }
        if (-not (Test-Path -LiteralPath $events)) { throw "Vanilla event trace was not produced: $events" }
        Write-Host '== Phase 5: replay/audit captured trace =='
        Invoke-Native 'mvn' @('-B',"-Dphantom.phase5.trace=$trace",'-Dtest=Phase5VanillaBatchAuditTest,Phase5VanillaSimulationReplayTest','test')
    }

    Write-Host ''
    Write-Host 'Phase 5 local validation completed.'
    if (Test-Path -LiteralPath $trace) { Write-Host "Vanilla trace: $trace" }
    if (Test-Path -LiteralPath $events) { Write-Host "Event trace:   $events" }
}
finally { Pop-Location }
