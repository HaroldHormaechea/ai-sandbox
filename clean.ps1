#!/usr/bin/env pwsh
# clean.ps1 — bring down ai-sandbox sessions.
#
#   .\clean.ps1 <N>      — clean session N only (down -v, optional per-N dirs).
#   .\clean.ps1 --all    — clean every ai-sandbox-* session.
#   .\clean.ps1          — interactively pick from running sessions.
#
# NEVER touches: shared .\workspace, shared .\claude-config, .\secrets, or
# .\.ai-sandbox-counter. The counter is monotonic across cleans (AC3, AC14).
# For a full factory reset, see README § "Resetting".
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib.ps1')

# UC05 § AC11,AC25 — match spawn.ps1: route per-session host state under
# /var/lib/ai-sandbox-server/sessions/ when the management server sets
# AI_SANDBOX_HOST_STATE_ROOT. Developer-mode runs leave the var unset.
if ($env:AI_SANDBOX_HOST_STATE_ROOT) {
    New-Item -ItemType Directory -Force -Path $env:AI_SANDBOX_HOST_STATE_ROOT | Out-Null
    Set-Location $env:AI_SANDBOX_HOST_STATE_ROOT
}

# --- Resolve the per-session host-dir base ----------------------------------
#
# Rule 0 — server pin wins. Under AI_SANDBOX_HOST_STATE_ROOT the per-session
# dirs live under the state-root cwd as the historical relative
# .\workspace-<N> / .\claude-config-<N>, so the base is '.'. Developer-mode
# runs resolve the SAME out-of-tree base spawn.ps1 used (read from the frozen
# state file — clean only ever READS, never writes/migrates). If the dev base
# can't be resolved (no spawn has ever run in this repo), fall back to '.' —
# the per-session dirs simply won't exist and the rm guards are no-ops.
$SessionDirBase = '.'
if (-not $env:AI_SANDBOX_HOST_STATE_ROOT) {
    $resolvedBase = Get-AisbDevWorkspaceRoot
    if ($resolvedBase) { $SessionDirBase = $resolvedBase }
}

function Show-Usage {
    @"
Usage: .\clean.ps1 [<N>] [flags]
       .\clean.ps1 --all [flags]

Brings down one or more ai-sandbox-<N> Compose projects and removes their
containers and volumes. Per-session host directories (.\workspace-<N>\,
.\claude-config-<N>\) are wiped only when they exist.

Flags:
  --session <N>            Clean session N (same as positional arg).
  --all                    Clean every ai-sandbox-* session.
  --keep-workspace         Don't delete .\workspace-<N>\ if it exists.
  --keep-claude-config     Don't delete .\claude-config-<N>\ if it exists.
  --non-interactive        Never prompt. With no target, exits non-zero.
  -h, --help               Show this message.

NEVER touches: .\workspace, .\claude-config, .\secrets, .\.ai-sandbox-counter.
"@ | Write-Host
}

$TargetN          = ''
$DoAll            = $false
$KeepWorkspace    = $false
$KeepClaudeConfig = $false
$NonInteractive   = $false

$idx = 0
while ($idx -lt $args.Count) {
    switch ($args[$idx]) {
        '--session' {
            if ($idx + 1 -ge $args.Count) {
                Write-Warn "--session requires a value"
                Show-Usage
                exit 2
            }
            $TargetN = $args[$idx + 1]
            $idx += 2
        }
        { $_ -like '--session=*' } {
            $TargetN = $args[$idx].Substring('--session='.Length)
            $idx++
        }
        '--all'                { $DoAll = $true; $idx++ }
        '--keep-workspace'     { $KeepWorkspace = $true; $idx++ }
        '--keep-claude-config' { $KeepClaudeConfig = $true; $idx++ }
        '--non-interactive'    { $NonInteractive = $true; $idx++ }
        '-h'                   { Show-Usage; exit 0 }
        '--help'               { Show-Usage; exit 0 }
        default {
            if ($args[$idx] -like '--*') {
                Write-Warn "Unknown flag: $($args[$idx])"
                Show-Usage
                exit 2
            }
            if (-not $TargetN) {
                $TargetN = $args[$idx]
                $idx++
            } else {
                Write-Warn "Unexpected positional arg: $($args[$idx])"
                Show-Usage
                exit 2
            }
        }
    }
}

try {
    if (-not $NonInteractive -and [Console]::IsInputRedirected) {
        $NonInteractive = $true
    }
} catch { }

if ($TargetN -and $TargetN -notmatch '^\d+$') {
    Write-Warn "Invalid session number: '$TargetN'"
    exit 2
}

function Clean-One {
    param([Parameter(Mandatory)][int]$N)
    $name = "ai-sandbox-$N"
    Write-Info "Cleaning $name"
    # AC9 — capture the real teardown exit code. `$ErrorActionPreference =
    # 'Stop'` is set at the top of this script, and on PowerShell 7.4+ a
    # non-zero NATIVE exit can surface as a terminating error, so wrap the
    # call to capture the code either way. `| Out-Null` keeps compose stdout
    # off Clean-One's return pipeline (a function emits ALL uncaptured output,
    # so only the trailing `return <int>` may reach the caller).
    $downRc = 0
    try {
        Invoke-AiSandboxCompose -p $name down -v --remove-orphans 2>$null | Out-Null
        $downRc = $LASTEXITCODE
        if ($null -eq $downRc) { $downRc = 0 }
    } catch {
        $downRc = 1
    }

    # Per-session isolated dirs live under $SessionDirBase (the same out-of-tree
    # base spawn.ps1 resolved in dev mode, or '.' under a server pin). Using the
    # resolved base — not a hardcoded .\workspace-<N> — is what prevents the
    # silent leak after relocation.
    $wsDir = Join-Path $SessionDirBase "workspace-$N"
    $ccDir = Join-Path $SessionDirBase "claude-config-$N"
    if (-not $KeepWorkspace -and (Test-Path $wsDir)) {
        Write-Info "  rm -rf $wsDir"
        Remove-Item -Recurse -Force $wsDir
    }
    if (-not $KeepClaudeConfig -and (Test-Path $ccDir)) {
        Write-Info "  rm -rf $ccDir"
        Remove-Item -Recurse -Force $ccDir
    }

    # If `down` reported failure, re-check whether containers actually remain
    # before treating it as fatal: a non-zero exit with nothing left running
    # (already-gone / never-existed) is tolerated; lingering containers are a
    # genuine teardown failure that MUST propagate (AC9). $remaining is
    # captured into a variable (not emitted) so only `return` reaches caller.
    if ($downRc -ne 0) {
        $remaining = $null
        try {
            $remaining = Invoke-AiSandboxCompose -p $name ps -q 2>$null
        } catch {
            $remaining = $null
        }
        if ($remaining) {
            Write-Warn "$name: teardown failed (compose down exit $downRc; containers still present)"
            return 1
        }
    }
    Write-Ok "$name cleaned"
    return 0
}

# --- Branch 1: --all --------------------------------------------------------
if ($DoAll) {
    if ($TargetN) {
        Write-Warn "--all and <N> are mutually exclusive."
        exit 2
    }
    $all = @(Get-AiSandboxSessions -IncludeStopped | Select-Object -ExpandProperty N)
    if ($all.Count -eq 0) {
        Write-Info "No ai-sandbox-* sessions to clean."
        exit 0
    }
    $rc = 0
    foreach ($n in $all) {
        $r = Clean-One -N $n
        if ($r -ne 0) { $rc = $r }
    }
    exit $rc
}

# --- Branch 2: explicit <N> -------------------------------------------------
if ($TargetN) {
    # The management server's clean path — a teardown failure MUST yield a
    # non-zero exit so the server returns 500 and the client surfaces it (AC9).
    $rc = Clean-One -N ([int]$TargetN)
    exit $rc
}

# --- Branch 3: no-arg — list running and prompt -----------------------------
if ($NonInteractive) {
    Write-Warn "Non-interactive: a target is required. Pass <N>, --session <N>, or --all."
    exit 2
}

$sessions = @(Get-AiSandboxSessions | Where-Object { $_.State -eq 'running' })
if ($sessions.Count -eq 0) {
    Write-Info "No running ai-sandbox-* sessions to clean."
    exit 0
}

Write-Host ""
Write-Host "  Running sessions:" -ForegroundColor White
Write-Host ""
for ($i = 0; $i -lt $sessions.Count; $i++) {
    $s = $sessions[$i]
    $line = ("  [{0}] ai-sandbox-{1,-3} {2}" -f ($i + 1), $s.N, $s.Title)
    if ($s.Label) { $line += "  label=$($s.Label)" }
    Write-Host $line
}

while ($true) {
    Write-Host ""
    $choice = Read-Host "  Pick [1-$($sessions.Count)], 'all', or 'cancel'"
    if ($choice -match '^(cancel|c|q)$') { exit 0 }
    if ($choice -match '^(all|a)$') {
        $rc = 0
        foreach ($s in $sessions) {
            $r = Clean-One -N $s.N
            if ($r -ne 0) { $rc = $r }
        }
        exit $rc
    }
    if ($choice -notmatch '^\d+$') {
        Write-Warn "Enter a number, 'all', or 'cancel'."
        continue
    }
    $n = [int]$choice
    if ($n -lt 1 -or $n -gt $sessions.Count) {
        Write-Warn "Out of range."
        continue
    }
    $rc = Clean-One -N $sessions[$n - 1].N
    exit $rc
}
