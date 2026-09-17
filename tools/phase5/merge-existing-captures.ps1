[CmdletBinding()]
param(
    [string]$CaptureRoot = "$env:SystemDrive\phase5",
    [string]$Output = "$env:SystemDrive\phase5\phase5-all.tsv"
)

$ErrorActionPreference = 'Stop'

$parts = @('part1','part2','part3','part4','part5')
$magic = '# phantom-phase5-trace version=2 protocol=minecraft-java-1.21.11 format=tsv'
$header = "tick`tclient_tick`treceive_nanos`tx`ty`tz`tvx`tvy`tvz`tyaw`tpitch`ton_ground`tforward`tstrafe`tjump`tsprint`tsneak`tpose`tgamemode`tfluid`tsubmerged`tclimbable`tgliding`tbase_movement_speed`tmodifiers`tspeed_amp`tslowness_amp`tjump_boost_amp`tlevitation`tslow_falling`tknockback_x`tknockback_y`tknockback_z`tvelocity_packet`tcorrection_id`tcorrection_pending`tworld_identity`tworld_tick`tcollision`tstep_attempted`tstep_succeeded`tcollision_x`tcollision_y`tcollision_z`tinput_source`tclient_version`tmissing_fields"

function Get-Metadata([string[]]$Lines, [string]$Key) {
    $prefix = "# $Key="
    $line = $Lines | Select-String -Pattern ([regex]::Escape($prefix)) | Select-Object -First 1
    if ($null -eq $line) { throw "Missing metadata: $Key" }
    return $line.Line.Substring($prefix.Length)
}

$allRows = [System.Collections.Generic.List[string]]::new()
$globalTick = 1L
$globalClientTick = 1L
$globalReceiveNanos = 0L
$sourceId = $null
$capturedAt = $null

foreach ($part in $parts) {
    $path = Join-Path $CaptureRoot "phase5-$part.tsv"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing existing Phase 5 capture: $path"
    }

    $lines = Get-Content -LiteralPath $path
    if ($lines.Count -lt 5) { throw "Trace is too short: $path" }
    if ($lines[0] -ne $magic) { throw "Unexpected trace magic in $path" }
    if ($lines[3] -ne $header) { throw "Unexpected trace header in $path" }

    $partSource = Get-Metadata $lines 'source_id'
    $partCapturedAt = Get-Metadata $lines 'captured_at_utc'
    if ($null -eq $sourceId) {
        $sourceId = $partSource
        $capturedAt = $partCapturedAt
    } elseif ($partSource -ne $sourceId) {
        throw "source_id mismatch: $path has '$partSource', expected '$sourceId'"
    }

    $rows = $lines | Select-Object -Skip 4 | Where-Object { $_ -and -not $_.StartsWith('#') }
    if ($rows.Count -eq 0) { throw "No data rows found in $path" }

    [long]$previousRawClientTick = -1
    [long]$previousRawReceive = -1
    [long]$partFirstClientTick = -1
    [long]$partFirstReceive = -1

    foreach ($line in $rows) {
        $c = $line -split "`t", -1
        if ($c.Count -ne 47) { throw "Invalid row in $path: expected 47 columns, got $($c.Count)" }

        [long]$rawClientTick = 0
        [long]$rawReceive = 0
        if (-not [long]::TryParse($c[1], [ref]$rawClientTick)) { throw "Invalid client_tick in $path: $($c[1])" }
        if (-not [long]::TryParse($c[2], [ref]$rawReceive)) { throw "Invalid receive_nanos in $path: $($c[2])" }

        if ($partFirstClientTick -lt 0) {
            $partFirstClientTick = $rawClientTick
            $partFirstReceive = $rawReceive
        }
        if ($previousRawClientTick -gt $rawClientTick) { throw "client_tick regressed inside $path" }
        if ($previousRawReceive -gt $rawReceive) { throw "receive_nanos regressed inside $path" }

        [long]$clientDelta = $rawClientTick - $partFirstClientTick
        [long]$receiveDelta = $rawReceive - $partFirstReceive
        $c[0] = [string]$globalTick
        $c[1] = [string]($globalClientTick + $clientDelta)
        $c[2] = [string]($globalReceiveNanos + $receiveDelta)

        if ($c[44] -match '^capture-post-tick:part[1-5]:(.+)$') {
            $c[44] = "capture-post-tick:all:$($Matches[1])"
        } elseif ($c[44] -notmatch '^capture-post-tick:all:') {
            throw "Unexpected input_source in $path: $($c[44])"
        }

        $allRows.Add(($c -join "`t"))
        $globalTick++
        $previousRawClientTick = $rawClientTick
        $previousRawReceive = $rawReceive
    }

    $globalClientTick = [long]$allRows.Count + 1L
    $globalReceiveNanos = [long]($globalReceiveNanos + ($previousRawReceive - $partFirstReceive))
}

$linesOut = [System.Collections.Generic.List[string]]::new()
$linesOut.Add($magic)
$linesOut.Add("# source_id=$sourceId")
$linesOut.Add("# captured_at_utc=$capturedAt")
$linesOut.Add($header)
$linesOut.Add('# phase5_merge=existing part1..part5 captures; measurements preserved; ticks/timing reindexed only for unified validation')
$allRows | ForEach-Object { $linesOut.Add($_) }

$parent = Split-Path -Parent $Output
New-Item -ItemType Directory -Force -Path $parent | Out-Null
Set-Content -LiteralPath $Output -Value $linesOut -Encoding utf8NoBOM

Write-Host "Merged existing Phase 5 captures: $($allRows.Count) rows"
Write-Host "Output: $Output"
