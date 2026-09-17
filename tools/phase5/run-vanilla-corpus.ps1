param(
    [string]$GameRoot = "$PSScriptRoot\..\vanilla-trace-capture",
    [string]$OutputRoot = "C:\phase5\corpus",
    [ValidateSet("all","missing")]
    [string]$Selection = "all"
)

$ErrorActionPreference = "Stop"
$Gradle = Join-Path $GameRoot "gradlew.bat"
if (-not (Test-Path $Gradle)) { throw "Fabric capture project not found: $Gradle" }

$Scenarios = @(
    "idle","walk-forward","walk-backward","strafe-left","strafe-right","diagonal",
    "sprint-forward","sprint-strafe","sprint-diagonal","jump","sprint-jump","repeated-jumps",
    "controlled-ascent","controlled-apex","controlled-fall","ascent-apex-fall","landing","sneak",
    "slab-up","stairs-up","step-up","partial-collision","edge","corner","corner-sprint",
    "water-surface","deep-swimming","water-sprint","swim-transition","lava","ladder","ladder-sprint","vines",
    "speed-effect","slowness-effect","jump-boost","slow-falling","levitation","attribute-modifier",
    "knockback-ground","knockback-air","teleport-correction","teleport-water","glide","sleeping",
    "step-sprint-jump","water-jump","climb-jump","correction-after-knockback"
)

if ($Selection -eq "missing") {
    $Scenarios = @(
        "idle","walk-backward","strafe-left","strafe-right","sprint-strafe","sprint-jump","repeated-jumps",
        "controlled-ascent","controlled-apex","controlled-fall","landing","slab-up","step-up","partial-collision",
        "edge","corner-sprint","deep-swimming","water-sprint","ladder-sprint","vines","slow-falling","levitation",
        "attribute-modifier","knockback-ground","knockback-air","teleport-water","sleeping","step-sprint-jump",
        "water-jump","climb-jump","correction-after-knockback"
    )
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
& $Gradle --version | Out-Host
if ($LASTEXITCODE -ne 0) { throw "Gradle is unavailable" }

foreach ($Scenario in $Scenarios) {
    $Dir = Join-Path $OutputRoot $Scenario
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    $Trace = Join-Path $Dir "trace.tsv"
    $Events = Join-Path $Dir "events.tsv"
    $Metadata = Join-Path $Dir "metadata.json"

    [ordered]@{
        scenario = $Scenario
        protocol = "minecraft-java-1.21.11"
        fabric_loader = "0.18.2"
        yarn = "1.21.11+build.4"
        loom = "1.14.10"
        mode = "corpus"
        observation = "ClientPlayerEntity.tick HEAD input + move request/result + TAIL vanilla state"
        trace = $Trace
        events = $Events
        captured_at_utc = [DateTime]::UtcNow.ToString("o")
        note = "Real Minecraft Java 1.21.11 execution remains external to this repository automation; this script never fabricates a trace on capture failure."
    } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $Metadata

    Write-Host "=== Phase 5 vanilla corpus: $Scenario ===" -ForegroundColor Cyan
    Push-Location $GameRoot
    try {
        & $Gradle "--project-prop=phantom.capture.enabled=true" "--project-prop=phantom.capture.mode=corpus" "--project-prop=phantom.capture.scenario=$Scenario" "--project-prop=phantom.capture.output=$Trace" "--project-prop=phantom.capture.events=$Events" runClient
        if ($LASTEXITCODE -ne 0) { throw "Minecraft capture failed for scenario $Scenario" }
    } finally { Pop-Location }
}

Write-Host "Corpus capture pass complete: $OutputRoot" -ForegroundColor Green
