[CmdletBinding()]
param(
    [ValidateSet('all','part1','part2','part3','part4','part5')]
    [string]$Part = 'all',
    [switch]$RunMaven,
    [switch]$RunAudit,
    [switch]$SkipBuild,
    [switch]$SkipClient,
    [string]$CaptureRoot = "$env:SystemDrive\phase5"
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$harness = Join-Path $repo 'tools\vanilla-trace-capture'
$trace = Join-Path $CaptureRoot "phase5-$Part.tsv"
$events = Join-Path $CaptureRoot "phase5-$Part-events.tsv"

function Require-Command([string]$name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Required command '$name' was not found on PATH."
    }
}
function Invoke-Native([string]$exe,[string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code ${LASTEXITCODE}: $exe $($arguments -join ' ')" }
}

$partDescriptions = @{
    all   = 'walk -> correction'
    part1 = 'walk, sprint, jump, sneak, diagonal, collision'
    part2 = 'water, lava, speed-effect, slowness-effect'
    part3 = 'jump-boost, step, tail, stairs'
    part4 = 'climbable, edge-corner, swim-transition'
    part5 = 'glide, correction'
}

New-Item -ItemType Directory -Force -Path $CaptureRoot | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue $trace,$events
Require-Command 'java'
if ($RunMaven -or $RunAudit) { Require-Command 'mvn' }

Push-Location $repo
try {
    Write-Host "== Phase 5 capture: $Part ($($partDescriptions[$Part])) =="
    if ($RunMaven) {
        Write-Host '== Maven regression suite =='
        Invoke-Native 'mvn' @('-B','test')
    }

    Push-Location $harness
    try {
        if (-not $SkipBuild) {
            Write-Host '== Build vanilla 1.21.11 observation harness =='
            Invoke-Native '.\gradlew.bat' @('build')
        }
        if (-not $SkipClient) {
            Write-Host "== Run $Part capture =="
            Write-Host "Trace:  $trace"
            Write-Host "Events: $events"
            Invoke-Native '.\gradlew.bat' @(
                '--project-prop=phantom.capture.enabled=true',
                "--project-prop=phantom.capture.scenario=$Part",
                "--project-prop=phantom.capture.output=$trace",
                "--project-prop=phantom.capture.events=$events",
                'runClient'
            )
        }
    }
    finally { Pop-Location }

    if ($RunAudit -and -not $SkipClient) {
        if (-not (Test-Path -LiteralPath $trace)) { throw "Vanilla trace was not produced: $trace" }
        if (-not (Test-Path -LiteralPath $events)) { throw "Vanilla event trace was not produced: $events" }
        Write-Host '== Replay/audit captured trace =='
        Invoke-Native 'mvn' @('-B',"-Dphantom.phase5.trace=$trace",'-Dtest=Phase5VanillaBatchAuditTest,Phase5VanillaSimulationReplayTest','test')
    }

    Write-Host ''
    Write-Host "Phase 5 $Part capture finished."
    Write-Host "Trace:  $trace"
    Write-Host "Events: $events"
}
finally { Pop-Location }
