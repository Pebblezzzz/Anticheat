[CmdletBinding()]
param(
    [string]$CaptureRoot = "$env:SystemDrive\phase5",
    [string]$Output = "$env:SystemDrive\phase5\phase5-all.tsv"
)

$ErrorActionPreference = 'Stop'

$parts = @('part1','part2','part3','part4','part5')
$phaseOrder = @(
    'walk','sprint','jump','sneak','diagonal','collision','water','lava',
    'speed-effect','slowness-effect','jump-boost','step','tail','stairs',
    'climbable','edge-corner','swim-transition','glide','correction'
)
$magic = '# phantom-phase5-trace version=2 protocol=minecraft-java-1.21.11 format=tsv'
$header = "tick`tclient_tick`treceive_nanos`tx`ty`tz`tvx`tvy`tvz`tyaw`tpitch`ton_ground`tforward`tstrafe`tjump`tsprint`tsneak`tpose`tgamemode`tfluid`tsubmerged`tclimbable`tgliding`tbase_movement_speed`tmodifiers`tspeed_amp`tslowness_amp`tjump_boost_amp`tlevitation`tslow_falling`tknockback_x`tknockback_y`tknockback_z`tvelocity_packet`tcorrection_id`tcorrection_pending`tworld_identity`tworld_tick`tcollision`tstep_attempted`tstep_succeeded`tcollision_x`tcollision_y`tcollision_z`tinput_source`tclient_version`tmissing_fields"

function Get-Metadata([string[]]$Lines, [string]$Key) {
    $prefix = "# $Key="
    $line = $Lines | Select-String -Pattern ([regex]::Escape($prefix)) | Select-Object -First 1
    if ($null -eq $line) { throw "Missing metadata: $Key" }
    return $line.Line.Substring($prefix.Length)
}

function Get-NormalizedPhase([string]$Source) {
    if ($Source -match '^capture-post-tick:part[1-5]:(.+)$') { return $Matches[1] }
    if ($Source -match '^part[1-5]:(.+)$') { return $Matches[1] }
    if ($Source -match '^capture-post-tick:all:(.+)$') { return $Matches[1] }
    return $null
}

function Get-PartExpectedLaneZ([string]$Part, [string]$Phase) {
    $startIndex = switch ($Part) {
        'part1' { 0 }
        'part2' { 6 }
        'part3' { 10 }
        'part4' { 14 }
        'part5' { 17 }
        default { throw "Unknown part: $Part" }
    }
    $index = [Array]::IndexOf($phaseOrder, $Phase)
    if ($index -lt 0) { throw "Unknown Phase 5 phase: $Phase" }
    return -40 + 180 * ($index - $startIndex)
}

$allRows = [System.Collections.Generic.List[string]]::new()
$globalTick = 1L
$globalClientTick = 1L
$globalReceiveNanos = 0L
$sourceId = $null
$capturedAt = $null
$discardedBoundaryRows = 0

foreach ($part in $parts) {
    $path = Join-Path $CaptureRoot "phase5-$part.tsv"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing existing Phase 5 capture: ${path}"
    }

    $lines = [System.IO.File]::ReadAllLines($path)
    if ($lines.Count -lt 5) { throw "Trace is too short: ${path}" }
    if ($lines[0] -ne $magic) { throw "Unexpected trace magic in ${path}" }
    if ($lines[3] -ne $header) { throw "Unexpected trace header in ${path}" }

    $partSource = Get-Metadata $lines 'source_id'
    $partCapturedAt = Get-Metadata $lines 'captured_at_utc'
    if ($null -eq $sourceId) {
        $sourceId = $partSource
        $capturedAt = $partCapturedAt
    } elseif ($partSource -ne $sourceId) {
        throw "source_id mismatch: ${path} has '$partSource', expected '$sourceId'"
    }

    $rows = @($lines | Select-Object -Skip 4 | Where-Object { $_ -and -not $_.StartsWith('#') })
    if ($rows.Count -eq 0) { throw "No data rows found in ${path}" }

    [long]$previousRawClientTick = -1
    [long]$previousRawReceive = -1
    [long]$partFirstClientTick = -1
    [long]$partFirstReceive = -1
    $activePhase = $null
    $phaseAccepted = $false
    [long]$lastEmittedClientTick = $globalClientTick - 1L
    [long]$lastEmittedReceiveNanos = $globalReceiveNanos

    foreach ($line in $rows) {
        $c = $line.Split([char]9)
        if ($c.Count -ne 47) {
            $preview = $line.Substring(0, [Math]::Min(120, $line.Length)).Replace("`t", '<TAB>')
            throw "Invalid row in ${path}: expected 47 columns, got $($c.Count). Row starts: $preview"
        }

        [long]$rawClientTick = 0
        [long]$rawReceive = 0
        if (-not [long]::TryParse($c[1], [ref]$rawClientTick)) { throw "Invalid client_tick in ${path}: $($c[1])" }
        if (-not [long]::TryParse($c[2], [ref]$rawReceive)) { throw "Invalid receive_nanos in ${path}: $($c[2])" }

        if ($partFirstClientTick -lt 0) {
            $partFirstClientTick = $rawClientTick
            $partFirstReceive = $rawReceive
        }
        if ($previousRawClientTick -gt $rawClientTick) { throw "client_tick regressed inside ${path}" }
        if ($previousRawReceive -gt $rawReceive) { throw "receive_nanos regressed inside ${path}" }

        $phase = Get-NormalizedPhase $c[44]
        if ($null -eq $phase) {
            throw "Unexpected input_source in ${path}: $($c[44])"
        }
        if ($phase -ne $activePhase) {
            $activePhase = $phase
            $phaseAccepted = ($phase -eq 'setup')
        }

        # The harness can switch the phase label before the reset has visibly
        # settled. Drop only leading rows that are still in the previous lane;
        # preserve every row once the new phase reaches its expected lane.
        if (-not $phaseAccepted) {
            $expectedZ = Get-PartExpectedLaneZ $part $phase
            $z = [double]$c[5]
            if ([Math]::Abs($z - $expectedZ) -le 100.0) {
                $phaseAccepted = $true
            } else {
                $discardedBoundaryRows++
                $previousRawClientTick = $rawClientTick
                $previousRawReceive = $rawReceive
                continue
            }
        }

        [long]$clientDelta = $rawClientTick - $partFirstClientTick
        [long]$receiveDelta = $rawReceive - $partFirstReceive
        $emittedClientTick = $globalClientTick + $clientDelta
        $emittedReceiveNanos = $globalReceiveNanos + $receiveDelta

        # Preserve monotonic timing across part files. Raw client ticks and
        # receive timestamps are each independent per capture, and discarded
        # boundary rows mean their absolute spans cannot be reconstructed by
        # row count alone. Continue from the last emitted values instead.
        if ($emittedClientTick <= $lastEmittedClientTick) {
            $emittedClientTick = $lastEmittedClientTick + 1L
        }
        if ($emittedReceiveNanos < $lastEmittedReceiveNanos) {
            $emittedReceiveNanos = $lastEmittedReceiveNanos
        }

        $c[0] = [string]$globalTick
        $c[1] = [string]$emittedClientTick
        $c[2] = [string]$emittedReceiveNanos
        $c[44] = "capture-post-tick:all:$phase"

        $allRows.Add(($c -join "`t"))
        $globalTick++
        $lastEmittedClientTick = $emittedClientTick
        $lastEmittedReceiveNanos = $emittedReceiveNanos
        $previousRawClientTick = $rawClientTick
        $previousRawReceive = $rawReceive
    }

    if ($phaseAccepted -eq $false -and $activePhase -ne 'setup') {
        throw "No settled rows found for final phase '$activePhase' in ${path}"
    }

    $globalClientTick = $lastEmittedClientTick + 1L
    $globalReceiveNanos = $lastEmittedReceiveNanos
}

$linesOut = [System.Collections.Generic.List[string]]::new()
$linesOut.Add($magic)
$linesOut.Add("# source_id=$sourceId")
$linesOut.Add("# captured_at_utc=$capturedAt")
$linesOut.Add($header)
$linesOut.Add('# phase5_merge=existing part1..part5 captures; measurements preserved; only pre-settle phase-boundary rows are omitted and ticks/timing reindexed for unified validation')
$allRows | ForEach-Object { $linesOut.Add($_) }

$parent = Split-Path -Parent $Output
New-Item -ItemType Directory -Force -Path $parent | Out-Null
Set-Content -LiteralPath $Output -Value $linesOut -Encoding utf8NoBOM

Write-Host "Merged existing Phase 5 captures: $($allRows.Count) rows"
Write-Host "Discarded pre-settle boundary rows: $discardedBoundaryRows"
Write-Host "Output: $Output"