#!/usr/bin/env pwsh
# spawn.ps1 — launch a new ai-sandbox-<N> session.
#
# See README.md § "Spawning additional sessions" for full usage.
#
# Exits non-zero on docker failure; on docker failure the counter is NOT
# rolled back (N is monotonic by design — AC3 in use-cases/02-...).
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib.ps1')

# UC05 § AC11,AC25 — same install-mode routing as spawn.sh: when the
# management server sets AI_SANDBOX_HOST_STATE_ROOT, route every write
# (counter, lockdir, per-session dirs) under that root instead of the
# read-only install dir. Developer-mode runs leave the var unset.
if ($env:AI_SANDBOX_HOST_STATE_ROOT) {
    New-Item -ItemType Directory -Force -Path $env:AI_SANDBOX_HOST_STATE_ROOT | Out-Null
    Set-Location $env:AI_SANDBOX_HOST_STATE_ROOT
}

function Show-Usage {
    @"
Usage: .\spawn.ps1 [flags]

Launches a new Claude session as Docker Compose project ai-sandbox-<N>.

Flags:
  --isolated-workspace      Give this session its own .\workspace-<N>\ folder.
  --shared-workspace        Use the shared .\workspace\ (default).
  --isolated-claude-config  Give this session its own .\claude-config-<N>\.
  --shared-claude-config    Use the shared .\claude-config\ (default).
  --label <value>           Set the com.ai-sandbox.label container label.
  --non-interactive         Never prompt; use defaults for unspecified flags.
  -h, --help                Show this message.

Non-interactive mode is also engaged automatically when stdin is redirected.
"@ | Write-Host
}

# --- Parse flags -------------------------------------------------------------
$WorkspaceMode    = ''   # 'shared' | 'isolated' | ''
$ClaudeConfigMode = ''
$Label            = ''
$LabelSet         = $false
$NonInteractive   = $false

$idx = 0
while ($idx -lt $args.Count) {
    switch ($args[$idx]) {
        '--isolated-workspace'     { $WorkspaceMode = 'isolated'; $idx++ }
        '--shared-workspace'       { $WorkspaceMode = 'shared';   $idx++ }
        '--isolated-claude-config' { $ClaudeConfigMode = 'isolated'; $idx++ }
        '--shared-claude-config'   { $ClaudeConfigMode = 'shared';   $idx++ }
        '--label' {
            if ($idx + 1 -ge $args.Count) {
                Write-Warn "--label requires a value"
                Show-Usage
                exit 2
            }
            $Label    = $args[$idx + 1]
            $LabelSet = $true
            $idx += 2
        }
        { $_ -like '--label=*' } {
            $Label    = $args[$idx].Substring('--label='.Length)
            $LabelSet = $true
            $idx++
        }
        '--non-interactive' { $NonInteractive = $true; $idx++ }
        '-h'                { Show-Usage; exit 0 }
        '--help'            { Show-Usage; exit 0 }
        default {
            Write-Warn "Unknown flag: $($args[$idx])"
            Show-Usage
            exit 2
        }
    }
}

# Auto-engage non-interactive when stdin is redirected (not a console).
try {
    if (-not $NonInteractive -and [Console]::IsInputRedirected) {
        $NonInteractive = $true
    }
} catch {
    # Some hosts (e.g. older PowerShell) lack IsInputRedirected. Leave as-is.
}

# --- Resolve workspace / claude-config modes ---------------------------------
if (-not $WorkspaceMode) {
    if ($NonInteractive) {
        $WorkspaceMode = 'shared'
    } else {
        $resp = Read-Host "  Workspace: [s]hared (default) or [i]solated? [S/i]"
        if ($resp -match '^[Ii]') { $WorkspaceMode = 'isolated' } else { $WorkspaceMode = 'shared' }
    }
}
if (-not $ClaudeConfigMode) {
    if ($NonInteractive) {
        $ClaudeConfigMode = 'shared'
    } else {
        $resp = Read-Host "  Claude config: [s]hared (default) or [i]solated? [S/i]"
        if ($resp -match '^[Ii]') { $ClaudeConfigMode = 'isolated' } else { $ClaudeConfigMode = 'shared' }
    }
}

# --- Acquire counter, read, increment, write, release ------------------------
$null = Acquire-CounterLock -TimeoutSeconds 30
if (-not $script:AiSandboxMutex) {
    Write-Warn "Could not acquire the ai-sandbox-counter mutex — aborting."
    exit 1
}

try {
    $counterFile = Join-Path $PSScriptRoot '.ai-sandbox-counter'
    if (-not (Test-Path $counterFile)) {
        # Defensive: setup.ps1 should have created this at 0, but tolerate
        # missing. File holds the last issued N (increment-before-use), so
        # 0 -> first issue is ai-sandbox-1.
        Set-Content -Path $counterFile -Value '0' -NoNewline
    }
    $cur = (Get-Content -Path $counterFile -Raw -ErrorAction SilentlyContinue)
    if ($null -eq $cur) { $cur = '' }
    $cur = $cur.Trim()
    $curN = 0
    if (-not [int]::TryParse($cur, [ref]$curN)) {
        Write-Warn "Counter file $counterFile is corrupt (value: '$cur'). Resetting to 1."
        $curN = 0
    }
    $N = $curN + 1
    $tmp = "$counterFile.tmp.$PID"
    Set-Content -Path $tmp -Value $N -NoNewline
    Move-Item -Force $tmp $counterFile
} finally {
    Release-CounterLock
}

$Project = "ai-sandbox-$N"

# --- Pre-create per-session host dirs if isolation chosen --------------------
$WorkspaceHostPath    = './workspace'
$ClaudeConfigHostPath = './claude-config'

if ($WorkspaceMode -eq 'isolated') {
    $WorkspaceHostPath = "./workspace-$N"
    New-Item -ItemType Directory -Force -Path $WorkspaceHostPath | Out-Null
}
if ($ClaudeConfigMode -eq 'isolated') {
    $ClaudeConfigHostPath = "./claude-config-$N"
    New-Item -ItemType Directory -Force -Path $ClaudeConfigHostPath | Out-Null
}

# --- Launch ------------------------------------------------------------------
$env:AI_SANDBOX_WORKSPACE_HOST_PATH     = $WorkspaceHostPath
$env:AI_SANDBOX_CLAUDE_CONFIG_HOST_PATH = $ClaudeConfigHostPath
$env:AI_SANDBOX_LABEL                   = $Label

Write-Info "Spawning $Project"
Write-Info "  workspace     : $WorkspaceHostPath"
Write-Info "  claude-config : $ClaudeConfigHostPath"
if ($LabelSet -and $Label) {
    Write-Info "  label         : $Label"
}

Invoke-AiSandboxCompose -p $Project up -d
if ($LASTEXITCODE -ne 0) {
    Write-Warn "docker compose up failed for $Project."
    Write-Warn "Counter NOT rolled back (monotonic by design); next spawn will use N=$($N + 1)."
    exit 1
}

Write-Ok "$Project is running. Attach with: .\attach.ps1 --session $N"
