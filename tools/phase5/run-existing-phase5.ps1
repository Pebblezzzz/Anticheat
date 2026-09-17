[CmdletBinding()]
param(
    [string]$CaptureRoot = "$env:SystemDrive\phase5"
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mergeScript = Join-Path $repo 'tools\phase5\merge-existing-captures.ps1'
$trace = Join-Path $CaptureRoot 'phase5-all.tsv'

function Require-Command([string]$name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Required command '$name' was not found on PATH."
    }
}
function Invoke-Native([string]$exe,[string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code ${LASTEXITCODE}: $exe $($arguments -join ' ')" }
}

Require-Command 'java'
Require-Command 'mvn'

Push-Location $repo
try {
    Write-Host '== Phase 5: reuse existing vanilla captures =='
    & $mergeScript -CaptureRoot $CaptureRoot -Output $trace
    if ($LASTEXITCODE -ne 0) { throw "Existing Phase 5 capture merge failed with exit code $LASTEXITCODE" }

    if (-not (Test-Path -LiteralPath $trace -PathType Leaf)) {
        throw "Merged Phase 5 trace was not produced: $trace"
    }

    Write-Host '== Phase 5: audit + vanilla-to-simulation replay =='
    Invoke-Native 'mvn' @('-B', "-Dphantom.phase5.trace=$trace", '-Dtest=Phase5VanillaBatchAuditTest,Phase5VanillaSimulationReplayTest', 'test')

    Write-Host ''
    Write-Host 'Phase 5 existing-capture replay finished successfully.'
    Write-Host "Trace: $trace"
}
finally { Pop-Location }
