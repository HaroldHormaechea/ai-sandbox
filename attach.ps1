#!/usr/bin/env pwsh
# attach.ps1 — attach to a running ai-sandbox-<N> session's tmux `main` window.
#
# Behavior:
#   - 0 running sessions  -> exit non-zero with a pointer at .\spawn.ps1
#   - exactly 1 running   -> attach directly (no prompt)
#   - >1 running          -> list and prompt (or honor --session <N>)
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib.ps1')

function Show-Usage {
    @"
Usage: .\attach.ps1 [--session <N>]

Attaches to the ai-sandbox-<N> session's tmux `main` window.

Flags:
  --session <N>   Attach to session <N> directly. Required when stdin is
                  redirected and more than one session is running.
  -h, --help      Show this message.
"@ | Write-Host
}

$RequestedN = $null
$idx = 0
while ($idx -lt $args.Count) {
    switch ($args[$idx]) {
        '--session' {
            if ($idx + 1 -ge $args.Count) {
                Write-Warn "--session requires a value"
                Show-Usage
                exit 2
            }
            $RequestedN = $args[$idx + 1]
            $idx += 2
        }
        { $_ -like '--session=*' } {
            $RequestedN = $args[$idx].Substring('--session='.Length)
            $idx++
        }
        '-h'     { Show-Usage; exit 0 }
        '--help' { Show-Usage; exit 0 }
        default  {
            Write-Warn "Unknown flag: $($args[$idx])"
            Show-Usage
            exit 2
        }
    }
}

$Sessions = @(Get-AiSandboxSessions | Where-Object { $_.State -eq 'running' })

if ($Sessions.Count -eq 0) {
    Write-Warn "No running ai-sandbox-* sessions."
    Write-Warn "Start one with: .\spawn.ps1"
    exit 1
}

function Attach-ToN {
    param([int]$N)
    $name = "ai-sandbox-$N"
    # AC25: pass -f and --project-directory on every docker compose call
    # for consistency; `compose exec` identifies the project by container
    # labels so the flags are functionally a no-op here. Kept for parity
    # with spawn/clean so every invocation looks the same.
    Invoke-AiSandboxCompose -p $name exec claude-sandbox tmux attach -t main
    exit $LASTEXITCODE
}

# Direct-pick path: --session <N>
if ($RequestedN) {
    $match = $Sessions | Where-Object { $_.N.ToString() -eq $RequestedN.ToString() } | Select-Object -First 1
    if (-not $match) {
        Write-Warn "No running session ai-sandbox-$RequestedN."
        exit 1
    }
    if ($match.Title -eq '(unavailable)') {
        Write-Warn "Session $($match.N) is running but tmux probe failed; cannot attach."
        exit 1
    }
    Attach-ToN -N $match.N
}

# Exactly one running → attach directly.
if ($Sessions.Count -eq 1) {
    $only = $Sessions[0]
    if ($only.Title -eq '(unavailable)') {
        Write-Warn "ai-sandbox-$($only.N) is running but tmux probe failed; cannot attach."
        exit 1
    }
    Attach-ToN -N $only.N
}

# Multiple running → list + prompt.
$stdinRedirected = $false
try { $stdinRedirected = [Console]::IsInputRedirected } catch { }

if ($stdinRedirected) {
    Write-Warn "Multiple sessions running; --session <N> is required when stdin is redirected."
    foreach ($s in $Sessions) {
        $line = "  ai-sandbox-$($s.N)  $($s.Title)"
        if ($s.Label) { $line += "  label=$($s.Label)" }
        Write-Warn $line
    }
    exit 1
}

Write-Host ""
Write-Host "  Running sessions:" -ForegroundColor White
Write-Host ""
for ($i = 0; $i -lt $Sessions.Count; $i++) {
    $s = $Sessions[$i]
    $line = ("  [{0}] ai-sandbox-{1,-3} {2}" -f ($i + 1), $s.N, $s.Title)
    if ($s.Label) { $line += "  label=$($s.Label)" }
    Write-Host $line
}

while ($true) {
    Write-Host ""
    $choice = Read-Host "  Pick a session [1-$($Sessions.Count)] or 'q' to quit"
    if ($choice -match '^[Qq]$') { exit 0 }
    if ($choice -notmatch '^\d+$') {
        Write-Warn "Enter a number between 1 and $($Sessions.Count) (or 'q')."
        continue
    }
    $n = [int]$choice
    if ($n -lt 1 -or $n -gt $Sessions.Count) {
        Write-Warn "Out of range."
        continue
    }
    Attach-ToN -N $Sessions[$n - 1].N
}
