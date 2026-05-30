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
#
# Rule 0 — server pin wins. Under AI_SANDBOX_HOST_STATE_ROOT we already cd'd
# into it (above) and keep the historical relative .\workspace / .\workspace-<N>
# (resolved against the state-root cwd) — byte-identical to pre-relocation.
# Developer-mode runs resolve the workspace base OUTSIDE the repo via
# Get-AisbDevWorkspaceRoot so a stray `cp -a . workspace` can never recurse.
if ($env:AI_SANDBOX_HOST_STATE_ROOT) {
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
} else {
    $RepoRoot = (Get-Location).Path
    $WsRoot   = Get-AisbDevWorkspaceRoot
    if (-not $WsRoot) {
        # Unconfigured first run. On a non-interactive run we must NOT silently
        # pick a default (the operator may have a populated in-repo workspace to
        # migrate first); on a TTY, run the shared resolve-migrate-persist
        # routine so the choice is frozen for clean.ps1 too.
        $interactive = $true
        try { if ([Console]::IsInputRedirected) { $interactive = $false } } catch { }
        if (-not $interactive) {
            Write-Warn "Dev workspace root is not configured for this repo."
            Write-Warn "Run .\setup.ps1 interactively once to pick (and migrate to) a location,"
            Write-Warn "or set AI_SANDBOX_DEV_WORKSPACE_ROOT explicitly (use '.' to keep it in-repo)."
            exit 1
        }
        $WsRoot = Invoke-AisbDevWorkspaceSetup
    }

    # Recursion guard — the structural defence against the self-copy disk-filler.
    $sharedWs = Join-Path $WsRoot 'workspace'
    $guard = Test-AisbWorkspaceRecursion -RepoRoot $RepoRoot -WsPath $sharedWs
    if ($guard -eq 2) {
        Write-Warn "Refusing to spawn: the resolved workspace ($sharedWs) is, contains, or is an"
        Write-Warn "ancestor of the repo ($RepoRoot). A 'cp -a . workspace' here would recurse and"
        Write-Warn "fill the disk. Point AI_SANDBOX_DEV_WORKSPACE_ROOT at a directory outside the repo."
        exit 1
    } elseif ($guard -eq 1) {
        Write-Warn "Workspace ($sharedWs) is inside the repo tree — the recorded in-repo opt-in."
        Write-Warn "A 'cp -a . workspace' from the repo root would recurse; never copy this repo's"
        Write-Warn "working tree into the workspace (use git clone / archive / a bind mount instead)."
    }

    $WorkspaceHostPath    = Join-Path $WsRoot 'workspace'
    $ClaudeConfigHostPath = Join-Path $WsRoot 'claude-config'
    if ($WorkspaceMode -eq 'isolated') {
        $WorkspaceHostPath = Join-Path $WsRoot "workspace-$N"
        New-Item -ItemType Directory -Force -Path $WorkspaceHostPath | Out-Null
    }
    if ($ClaudeConfigMode -eq 'isolated') {
        $ClaudeConfigHostPath = Join-Path $WsRoot "claude-config-$N"
        New-Item -ItemType Directory -Force -Path $ClaudeConfigHostPath | Out-Null
    }
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

# UC22 — layer KVM passthrough for Android-testing images when /dev/kvm exists
# (Linux hosts). On Windows there is no /dev/kvm, so this is a no-op and the
# emulator-in-container path is unavailable there (build + JVM-test lane still
# works). Mirrors spawn.sh; also covers server-spawned sessions (AC13).
if (Test-ImageSupportsAndroid) {
    if (Test-Path '/dev/kvm') {
        $kvmOverride = 'docker-compose.kvm.yml'
        if ($env:AI_SANDBOX_COMPOSE_FILE) {
            $kvmOverride = Join-Path (Split-Path -Parent $env:AI_SANDBOX_COMPOSE_FILE) 'docker-compose.kvm.yml'
        }
        if (Test-Path $kvmOverride) {
            # UC22 BUG-1 fix — the runtime user must be IN /dev/kvm's group or
            # open() fails EACCES. Detect the host kvm GID and pass it as a
            # supplementary group (docker-compose.kvm.yml group_add). Mirrors
            # spawn.sh; on Windows this branch is unreachable (no /dev/kvm).
            $env:AI_SANDBOX_KVM_GID = Get-HostKvmGid
            if ($env:AI_SANDBOX_EXTRA_COMPOSE_FILES) {
                $env:AI_SANDBOX_EXTRA_COMPOSE_FILES = "$($env:AI_SANDBOX_EXTRA_COMPOSE_FILES) $kvmOverride"
            } else {
                $env:AI_SANDBOX_EXTRA_COMPOSE_FILES = $kvmOverride
            }
            Write-Info "  kvm           : /dev/kvm detected -> passthrough enabled (gid $($env:AI_SANDBOX_KVM_GID), $kvmOverride)"
        } else {
            Write-Warn "Android image + /dev/kvm present but $kvmOverride missing - emulator will be unaccelerated."
        }
    } else {
        Write-Info "  kvm           : no /dev/kvm on host -> emulator slow/unavailable (build+JVM-test lane unaffected)"
    }
}

# UC26 - read the persisted devtools selection (./.ai-sandbox-devtools or
# $AI_SANDBOX_HOST_STATE_ROOT/.ai-sandbox-devtools) and inject any spawn-time
# capabilities (env vars + compose override files) BEFORE Invoke-AiSandboxCompose.
# Persistence is the only source of truth - there is no spawn-time flag - so
# changes only propagate to NEW sessions (AC#7). No devtools enabled -> no-op
# and spawned sessions are byte-identical to today's behaviour (AC#6).
Invoke-InjectDevToolSpawnEnv
if ($env:AI_SANDBOX_DEVTOOL_DIND -eq '1') {
    Write-Info "  devtools      : DinD enabled (rootless dockerd will start inside the session)"
}

Invoke-AiSandboxCompose -p $Project up -d
if ($LASTEXITCODE -ne 0) {
    Write-Warn "docker compose up failed for $Project."
    Write-Warn "Counter NOT rolled back (monotonic by design); next spawn will use N=$($N + 1)."
    exit 1
}

Write-Ok "$Project is running. Attach with: .\attach.ps1 --session $N"
