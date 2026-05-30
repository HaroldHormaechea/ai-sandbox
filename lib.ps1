# Shared helpers for ai-sandbox operator scripts (setup, spawn, attach, clean).
#
# Dot-sourced from each entry-point script via:
#   . (Join-Path $PSScriptRoot 'lib.ps1')
#
# Sourcing must be side-effect-free (no network, no filesystem writes, no
# prompts) so that thin wrappers can pull in the helpers without surprising
# the operator.

# --- Color/format helpers ----------------------------------------------------
$script:ESC = [char]27
$script:M   = "$($script:ESC)[1;35m"   # magenta
$script:R   = "$($script:ESC)[0m"

function Write-Ok    { param([string]$Msg) Write-Host "  + $Msg" -ForegroundColor Green }
function Write-Warn  { param([string]$Msg) Write-Host "  ! $Msg" -ForegroundColor Yellow }
function Write-Info  { param([string]$Msg) Write-Host "  $Msg" }
function Write-Blank { Write-Host "" }

function Write-Step {
    param([int]$Num, [int]$Total, [string]$Title)
    Write-Host ("=== Step {0} of {1}: {2} ===" -f $Num, $Total, $Title) -ForegroundColor Cyan
    Write-Host ""
}

function Press-Enter {
    Write-Host ""
    Write-Host "  Press Enter to continue " -ForegroundColor White -NoNewline
    [void](Read-Host)
}

# --- Identity-prompt helper (extracted from setup.ps1) -----------------------
# Generic prompt-and-validate loop.
#   Read-IdentityField -Label "Author name " -Default "..." -Validator { param($v) ... }
# Returns the chosen value (string), or $null when the user typed /skip.
# Calls `exit 0` on /exit. /help shows the identity help screen if defined.
function Read-IdentityField {
    param(
        [Parameter(Mandatory)][string]$Label,
        [string]$Default,
        [Parameter(Mandatory)][scriptblock]$Validator
    )
    $lastError = ""
    while ($true) {
        if ($lastError) { Write-Warn $lastError; $lastError = "" }
        $suffix = if ($Default) { "[$Default]" } else { "" }
        $resp = Read-Host "  $Label$suffix"
        switch -Regex ($resp) {
            '^/exit$' { Write-Host "  Exiting setup."; exit 0 }
            '^/skip$' { return $null }
            '^/help$' {
                if (Get-Command Show-IdentityHelpScreen -ErrorAction SilentlyContinue) {
                    Show-IdentityHelpScreen
                }
                continue
            }
            '^$' {
                if ($Default) { return $Default }
                $lastError = "Required."
                continue
            }
            default {
                $trimmed = $resp.Trim()
                if (& $Validator $trimmed) { return $trimmed }
                $lastError = "Invalid format."
            }
        }
    }
}

# --- Counter lock (named Mutex) ---------------------------------------------
#
# Windows / .NET provides a system-wide named mutex; we use the "Global\"
# prefix so the lock spans terminal sessions on the same machine. Acquire
# blocks for up to TimeoutSeconds (default 10) and returns the Mutex handle.
# The caller passes the handle back to Release-CounterLock when done.

$script:AiSandboxMutex = $null

function Acquire-CounterLock {
    param([int]$TimeoutSeconds = 10)
    if ($script:AiSandboxMutex) { return $script:AiSandboxMutex }
    $createdNew = $false
    $mutex = New-Object System.Threading.Mutex($false, 'Global\ai-sandbox-counter', [ref]$createdNew)
    try {
        $acquired = $mutex.WaitOne([TimeSpan]::FromSeconds($TimeoutSeconds))
    } catch [System.Threading.AbandonedMutexException] {
        # Previous holder died without releasing — we now own it.
        $acquired = $true
    }
    if (-not $acquired) {
        Write-Warn "Timed out waiting for the ai-sandbox-counter mutex."
        $mutex.Dispose()
        return $null
    }
    $script:AiSandboxMutex = $mutex
    return $mutex
}

function Release-CounterLock {
    if (-not $script:AiSandboxMutex) { return }
    try { $script:AiSandboxMutex.ReleaseMutex() } catch { }
    try { $script:AiSandboxMutex.Dispose() }     catch { }
    $script:AiSandboxMutex = $null
}

# --- Session enumeration -----------------------------------------------------
#
# Get-AiSandboxSessions [-IncludeStopped]
#
# Returns an array of PSCustomObjects, one per ai-sandbox-* compose project:
#   N            = session number (int)
#   Name         = full project name (e.g. "ai-sandbox-3")
#   State        = "running" or "exited" (etc.)
#   ContainerId  = id of the claude-sandbox service container, or ""
#   Label        = com.ai-sandbox.label value, or ""
#   Title        = normalized tmux window title, "(idle)", or "(unavailable)"
function Get-AiSandboxSessions {
    param([switch]$IncludeStopped)

    $args = @('compose','ls','--format','json')
    if ($IncludeStopped) { $args += '--all' }

    $raw = & docker @args 2>$null
    if (-not $raw) { return @() }

    $text = ($raw | Out-String).Trim()
    if (-not $text) { return @() }

    $projects = @()
    try {
        $parsed = $text | ConvertFrom-Json -ErrorAction Stop
        if ($parsed -is [System.Array]) {
            $projects = $parsed
        } else {
            $projects = @($parsed)
        }
    } catch {
        # Compose may emit one JSON object per line. Fall back to per-line parse.
        $projects = @()
        foreach ($line in ($text -split "`r?`n")) {
            $trimmed = $line.Trim()
            if (-not $trimmed) { continue }
            try { $projects += ($trimmed | ConvertFrom-Json -ErrorAction Stop) } catch { }
        }
    }

    $out = @()
    foreach ($p in $projects) {
        if (-not $p) { continue }
        $name = $p.Name
        if (-not $name) { continue }
        if (-not $name.StartsWith('ai-sandbox-')) { continue }
        $nstr = $name.Substring('ai-sandbox-'.Length)
        $n = 0
        if (-not [int]::TryParse($nstr, [ref]$n)) { continue }

        $stateRaw = ''
        if ($p.PSObject.Properties.Match('Status').Count -gt 0) {
            $stateRaw = $p.Status
        } elseif ($p.PSObject.Properties.Match('State').Count -gt 0) {
            $stateRaw = $p.State
        }
        $state = ($stateRaw -split '\s+')[0].ToLower()
        if ($state.StartsWith('running')) { $state = 'running' }
        elseif ($state.StartsWith('exited')) { $state = 'exited' }

        $cid = (& docker compose -p $name ps -q claude-sandbox 2>$null | Select-Object -First 1)
        if ($null -eq $cid) { $cid = '' } else { $cid = $cid.Trim() }

        $label = ''
        $title = '(unavailable)'
        if ($cid) {
            $label = (& docker inspect --format '{{ index .Config.Labels "com.ai-sandbox.label" }}' $cid 2>$null)
            if ($null -eq $label) { $label = '' } else { $label = $label.Trim() }
            if ($label -eq '<no value>') { $label = '' }
            if ($state -eq 'running') {
                $rawTitle = (& docker compose -p $name exec -T claude-sandbox tmux display-message -p -t main '#W' 2>$null)
                if ($null -ne $rawTitle) { $rawTitle = $rawTitle.Trim() }
                $title = Normalize-TmuxTitle $rawTitle
            }
        }

        $out += [pscustomobject]@{
            N           = $n
            Name        = $name
            State       = $state
            ContainerId = $cid
            Label       = $label
            Title       = $title
        }
    }
    return ,$out
}

function Normalize-TmuxTitle {
    param([string]$Title)
    if (-not $Title) { return '(idle)' }
    $t = $Title.Trim()
    if (-not $t) { return '(idle)' }
    switch ($t) {
        'bash'   { return '(idle)' }
        'sh'     { return '(idle)' }
        'claude' { return '(idle)' }
        default  { return $t }
    }
}

# --- docker compose wrapper (UC05 § AC25,AC26,AC27) -------------------------
#
# Invoke-AiSandboxCompose <docker-compose-args...>
#
# Mirror of lib.sh's `ai_sandbox_compose`. Prepends `-f <AI_SANDBOX_COMPOSE_FILE>`
# and `--project-directory <AI_SANDBOX_HOST_STATE_ROOT>` when those env vars
# are non-empty, then invokes `docker compose`. Both unset → behaves
# identically to a bare `docker compose` call.
function Invoke-AiSandboxCompose {
    $flags = @()
    $base = $env:AI_SANDBOX_COMPOSE_FILE
    # UC22 — make the base explicit when override files are requested in
    # developer mode, else a bare `-f <override>` makes compose ignore the
    # default docker-compose.yml.
    if ($env:AI_SANDBOX_EXTRA_COMPOSE_FILES -and -not $base) {
        $base = 'docker-compose.yml'
    }
    if ($base) { $flags += @('-f', $base) }
    # UC22 — optional override compose files (e.g. docker-compose.kvm.yml).
    if ($env:AI_SANDBOX_EXTRA_COMPOSE_FILES) {
        foreach ($extra in ($env:AI_SANDBOX_EXTRA_COMPOSE_FILES -split '\s+')) {
            if ($extra) { $flags += @('-f', $extra) }
        }
    }
    if ($env:AI_SANDBOX_HOST_STATE_ROOT) {
        $flags += @('--project-directory', $env:AI_SANDBOX_HOST_STATE_ROOT)
    }
    & docker compose @flags @args
}

# --- UC26 — development-tools selection state --------------------------------
#
# Mirror of lib.sh's devtools helpers. Each enabled capability persists as a
# whitespace-separated `<id>     <apply_at>` line in a gitignored file at the
# repo root. `apply_at` is `image-build` or `session-spawn`. v1's only entry is
# `dind` (session-spawn). Catalog rows below mirror _aisb_devtool_catalog in
# lib.sh — keep the two in sync.
$script:AiSandboxDevToolsFile = if ($env:AISB_DEVTOOLS_FILE) { $env:AISB_DEVTOOLS_FILE } else { '.ai-sandbox-devtools' }

$script:AiSandboxDevToolsCatalog = @(
    [pscustomobject]@{
        Id      = 'dind'
        ApplyAt = 'session-spawn'
        Label   = 'Enable Docker-in-Docker (rootless; lets sessions run docker / docker compose inside their sandbox container)'
        Warning = 'Enabling Docker-in-Docker (rootless) lets code running inside a session start its own docker / docker compose commands. The rootless daemon runs as the non-root session user with no host-socket bind, so it does NOT widen the host trust boundary - but it DOES widen what code inside a session can reach (the session can now launch and inspect containers). Project policy is "the container is the trust boundary"; enabling this is a deliberate, opt-in expansion of that boundary.'
    }
)

function Get-DevToolCatalogIds {
    return $script:AiSandboxDevToolsCatalog | ForEach-Object { $_.Id }
}

function Get-DevToolLabel {
    param([Parameter(Mandatory)][string]$Id)
    $row = $script:AiSandboxDevToolsCatalog | Where-Object { $_.Id -eq $Id } | Select-Object -First 1
    if ($null -eq $row) { return $null }
    return $row.Label
}

function Get-DevToolApplyAt {
    param([Parameter(Mandatory)][string]$Id)
    $row = $script:AiSandboxDevToolsCatalog | Where-Object { $_.Id -eq $Id } | Select-Object -First 1
    if ($null -eq $row) { return $null }
    return $row.ApplyAt
}

function Get-DevToolWarning {
    param([Parameter(Mandatory)][string]$Id)
    $row = $script:AiSandboxDevToolsCatalog | Where-Object { $_.Id -eq $Id } | Select-Object -First 1
    if ($null -eq $row) { return $null }
    return $row.Warning
}

function Test-DevToolEnabled {
    param([Parameter(Mandatory)][string]$Id)
    if (-not (Test-Path $script:AiSandboxDevToolsFile)) { return $false }
    foreach ($line in (Get-Content $script:AiSandboxDevToolsFile)) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        if ($trimmed.StartsWith('#')) { continue }
        $first = ($trimmed -split '\s+', 2)[0]
        if ($first -eq $Id) { return $true }
    }
    return $false
}

function Read-EnabledDevTools {
    if (-not (Test-Path $script:AiSandboxDevToolsFile)) { return @() }
    $ids = @()
    foreach ($line in (Get-Content $script:AiSandboxDevToolsFile)) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        if ($trimmed.StartsWith('#')) { continue }
        $first = ($trimmed -split '\s+', 2)[0]
        if ($first) { $ids += $first }
    }
    return $ids
}

function Write-EnabledDevTools {
    param([string[]]$Ids = @())
    $lines = @()
    foreach ($id in $Ids) {
        $applyAt = Get-DevToolApplyAt -Id $id
        if (-not $applyAt) {
            Write-Warn "Unknown devtool id '$id' - skipping."
            continue
        }
        $lines += ("{0}`t{1}" -f $id, $applyAt)
    }
    if ($lines.Count -eq 0) {
        Set-Content -Path $script:AiSandboxDevToolsFile -Value '' -NoNewline
    } else {
        Set-Content -Path $script:AiSandboxDevToolsFile -Value $lines
    }
}

# Invoke-InjectDevToolSpawnEnv - consult the persisted ledger and, for each
# enabled capability whose apply_at is `session-spawn`, set the env vars /
# append the compose override files that spawn.ps1 needs. Idempotent.
function Invoke-InjectDevToolSpawnEnv {
    foreach ($id in (Read-EnabledDevTools)) {
        switch ($id) {
            'dind' {
                $env:AI_SANDBOX_DEVTOOL_DIND = '1'
                $dindOverride = 'docker-compose.dind.yml'
                if ($env:AI_SANDBOX_COMPOSE_FILE) {
                    $dindOverride = Join-Path (Split-Path -Parent $env:AI_SANDBOX_COMPOSE_FILE) 'docker-compose.dind.yml'
                }
                if (Test-Path $dindOverride) {
                    if ($env:AI_SANDBOX_EXTRA_COMPOSE_FILES) {
                        $env:AI_SANDBOX_EXTRA_COMPOSE_FILES = "$($env:AI_SANDBOX_EXTRA_COMPOSE_FILES) $dindOverride"
                    } else {
                        $env:AI_SANDBOX_EXTRA_COMPOSE_FILES = $dindOverride
                    }
                } else {
                    Write-Warn "DinD enabled but $dindOverride missing - sessions will start without the DinD override."
                }
            }
            default {
                # image-build capabilities (none today) need no spawn-time env.
            }
        }
    }
}

# --- UC22 — toolchain selection state ----------------------------------------
#
# Mirror of lib.sh's toolchain helpers. The operator's optional-toolchain
# choices persist in a gitignored newline-delimited file at the repo root and
# drive `docker compose build` build args.
$script:AiSandboxToolchainsFile = if ($env:AISB_TOOLCHAINS_FILE) { $env:AISB_TOOLCHAINS_FILE } else { '.ai-sandbox-toolchains' }

function Test-ToolchainEnabled {
    param([Parameter(Mandatory)][string]$Id)
    if (-not (Test-Path $script:AiSandboxToolchainsFile)) { return $false }
    return ((Get-Content $script:AiSandboxToolchainsFile) -contains $Id)
}

function Write-EnabledToolchains {
    param([string[]]$Ids = @())
    Set-Content -Path $script:AiSandboxToolchainsFile -Value $Ids
}

# Test-ImageSupportsAndroid [IMAGE] — $true if the built image carries the
# Android toolchain label (runtime source of truth for KVM passthrough).
function Test-ImageSupportsAndroid {
    param([string]$Image = 'ai-context:latest')
    $val = (& docker image inspect $Image --format '{{ index .Config.Labels "com.ai-sandbox.toolchain.android" }}' 2>$null)
    return ($val -eq '1')
}

# UC22 (AC6 fallback) — glibc base for the Android variant. Mirrors lib.sh's
# AISB_ANDROID_BASE_DEFAULT. The emulator's QEMU binary can't load under gcompat
# on musl (missing posix_fallocate64), so the Android image builds on a glibc
# (Debian) base. node:20-bookworm-slim ships a modern Node + npm.
$script:AiSandboxAndroidBaseDefault = if ($env:AISB_ANDROID_BASE_DEFAULT) { $env:AISB_ANDROID_BASE_DEFAULT } else { 'node:20-bookworm-slim' }

# Export-AndroidBuildEnv ENABLED — set the build args `docker compose build`
# reads for the Android variant. When enabled, exports AI_SANDBOX_ANDROID_BASE
# (honouring an operator override) so compose flips FROM onto the glibc base;
# when disabled, leaves it unset so compose's
# `${AI_SANDBOX_ANDROID_BASE:-alpine:latest}` keeps the lean Alpine image (AC4).
function Export-AndroidBuildEnv {
    param([string]$Enabled = '0')
    if ($Enabled -eq '1') {
        $env:AI_SANDBOX_TOOLCHAIN_ANDROID = '1'
        if (-not $env:AI_SANDBOX_ANDROID_BASE) {
            $env:AI_SANDBOX_ANDROID_BASE = $script:AiSandboxAndroidBaseDefault
        }
    }
}

# Get-HostKvmGid — echo the host's kvm group GID, or '0' if none. Used by
# spawn.ps1 to pass /dev/kvm's group as a supplementary group (UC22 BUG-1) so
# the runtime user can open the device. Linux-only path (Windows has no
# /dev/kvm); mirrors lib.sh host_kvm_gid.
function Get-HostKvmGid {
    $gid = ''
    if (Test-Path '/dev/kvm') {
        $gid = (& stat -c '%g' /dev/kvm 2>$null)
    }
    if (-not $gid) {
        $line = (& getent group kvm 2>$null)
        if ($line) { $gid = ($line -split ':')[2] }
    }
    if (-not $gid) { $gid = '0' }
    return "$gid".Trim()
}

# --- Dev-mode workspace root (relocate-out-of-tree) -------------------------
#
# Mirror of lib.sh's aisb_dev_workspace_root family. The dev workspace lives
# OUTSIDE the repo working tree by default so a stray `cp -a . workspace` can
# never recurse into the freshly-created `.\workspace` and fill the disk.
#
# `Get-AisbDevWorkspaceRoot` returns an ABSOLUTE base dir; spawn and clean both
# call it so they always agree. Shared workspace = `<root>\workspace`; isolated
# = `<root>\workspace-<N>` with claude-config sibling `<root>\claude-config-<N>`.
#
# Precedence (highest first):
#   Rule 0 — server pin wins (AI_SANDBOX_WORKSPACE_HOST_PATH /
#            AI_SANDBOX_HOST_STATE_ROOT). Enforced by the CALLER; the guard here
#            is defensive.
#   Rule 1 — explicit override AI_SANDBOX_DEV_WORKSPACE_ROOT, used verbatim.
#   Rule 2 — persisted absolute path in the gitignored state file
#            `<repo>\.ai-sandbox-workspace-root` (the determinism anchor).
#   Rule 3 — first-run default `$env:LOCALAPPDATA\ai-sandbox` (persisted only by
#            the interactive setup path, not by this read-only resolver).
$script:AisbDevWorkspaceStateFile = if ($env:AISB_DEV_WORKSPACE_STATE_FILE) { $env:AISB_DEV_WORKSPACE_STATE_FILE } else { '.ai-sandbox-workspace-root' }

# Convert-AisbToAbsolute PATH → an absolute path even if PATH doesn't exist yet.
function Convert-AisbToAbsolute {
    param([Parameter(Mandatory)][string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Get-Location).Path $Path)
}

# Get-AisbDevWorkspaceDefaultRoot → the Rule-3 default base dir for this host.
function Get-AisbDevWorkspaceDefaultRoot {
    $base = $env:LOCALAPPDATA
    if (-not $base) { $base = Join-Path $HOME '.local/state' }   # non-Windows pwsh
    return (Join-Path $base 'ai-sandbox')
}

# Get-AisbDevWorkspaceRoot → resolve the dev workspace base (Rules 1-3).
# Read-only; never writes the state file, never prompts. Returns $null when the
# state file is absent AND no override is set (an unconfigured first run); the
# caller decides whether that is fatal.
function Get-AisbDevWorkspaceRoot {
    # Rule 0 (defensive).
    if ($env:AI_SANDBOX_WORKSPACE_HOST_PATH -or $env:AI_SANDBOX_HOST_STATE_ROOT) {
        Write-Warn "Get-AisbDevWorkspaceRoot called under a server pin — ignoring (Rule 0)."
        return $null
    }
    # Rule 1 — explicit override.
    if ($env:AI_SANDBOX_DEV_WORKSPACE_ROOT) {
        return (Convert-AisbToAbsolute $env:AI_SANDBOX_DEV_WORKSPACE_ROOT)
    }
    # Rule 2 — persisted choice.
    if (Test-Path $script:AisbDevWorkspaceStateFile) {
        $persisted = (Get-Content -Path $script:AisbDevWorkspaceStateFile -Raw -ErrorAction SilentlyContinue)
        if ($persisted) {
            $persisted = $persisted.Trim()
            if ($persisted) { return (Convert-AisbToAbsolute $persisted) }
        }
    }
    # Rule 3 — first-run default, not persisted here. Signal "unconfigured".
    return $null
}

# Write-AisbDevWorkspaceRoot ABS_PATH → persist ABS_PATH to the state file.
function Write-AisbDevWorkspaceRoot {
    param([Parameter(Mandatory)][string]$AbsPath)
    Set-Content -Path $script:AisbDevWorkspaceStateFile -Value $AbsPath
}

# Test-AisbDirHasRealContent DIR → $true if DIR holds anything but a tracked
# `.gitkeep`. `.gitkeep`-awareness matters: a fresh clone's `.\workspace` is
# non-empty (it holds `.gitkeep`), and must NOT be classified as legacy.
function Test-AisbDirHasRealContent {
    param([Parameter(Mandatory)][string]$Dir)
    if (-not (Test-Path $Dir)) { return $false }
    foreach ($item in (Get-ChildItem -Force -Path $Dir -ErrorAction SilentlyContinue)) {
        if ($item.Name -ne '.gitkeep') { return $true }
    }
    return $false
}

# Test-AisbHasLegacyInRepoWorkspace → $true if the repo (cwd) has a populated
# `.\workspace` (ignoring `.gitkeep`) OR any `.\workspace-*` isolated dir.
function Test-AisbHasLegacyInRepoWorkspace {
    if (Test-AisbDirHasRealContent '.\workspace') { return $true }
    if (Get-ChildItem -Directory -Force -Path '.' -Filter 'workspace-*' -ErrorAction SilentlyContinue) {
        return $true
    }
    return $false
}

# Invoke-AisbDevWorkspaceSetup → interactive resolve-migrate-persist, mirror of
# lib.sh's aisb_dev_workspace_setup. Returns the chosen ABSOLUTE root. Refuses
# (throws) on a non-interactive run that finds a legacy in-repo workspace.
function Invoke-AisbDevWorkspaceSetup {
    # Already recorded → freeze.
    if (Test-Path $script:AisbDevWorkspaceStateFile) {
        $existing = (Get-Content -Path $script:AisbDevWorkspaceStateFile -Raw -ErrorAction SilentlyContinue)
        if ($existing) {
            $existing = $existing.Trim()
            if ($existing) { return (Convert-AisbToAbsolute $existing) }
        }
    }
    # Explicit override is authoritative; persist so clean agrees too.
    if ($env:AI_SANDBOX_DEV_WORKSPACE_ROOT) {
        $override = Convert-AisbToAbsolute $env:AI_SANDBOX_DEV_WORKSPACE_ROOT
        Write-AisbDevWorkspaceRoot $override
        return $override
    }

    $repoRoot    = (Get-Location).Path
    $defaultRoot = Get-AisbDevWorkspaceDefaultRoot

    if (Test-AisbHasLegacyInRepoWorkspace) {
        $interactive = $true
        try { if ([Console]::IsInputRedirected) { $interactive = $false } } catch { }
        if (-not $interactive) {
            Write-Warn "A populated in-repo workspace was found, but this is a non-interactive run."
            Write-Warn "Refusing to migrate or keep it silently (it may be a large / live-git tree)."
            Write-Warn "Re-run .\setup.ps1 interactively, or set AI_SANDBOX_DEV_WORKSPACE_ROOT (use '.'"
            Write-Warn "to deliberately keep it in the repo)."
            throw "Dev workspace root is unconfigured and a legacy in-repo workspace exists."
        }
        Write-Host ""
        Write-Host "  Workspace relocation" -ForegroundColor White
        Write-Info "The dev workspace now lives OUTSIDE the repo by default, so a stray"
        Write-Info "'cp -a . workspace' can never recurse and fill your disk."
        Write-Info "An existing in-repo workspace was detected at:"
        Write-Info "    $repoRoot\workspace (and/or workspace-*)"
        Write-Info "New default location:"
        Write-Info "    $defaultRoot"
        $resp = Read-Host "  [m]igrate to the new location, or [k]eep it in the repo? [M/k]"
        if ($resp -match '^[Kk]') {
            Write-AisbDevWorkspaceRoot $repoRoot
            Write-Ok "Keeping the workspace in the repo (recorded in $script:AisbDevWorkspaceStateFile)."
            return $repoRoot
        }
        New-Item -ItemType Directory -Force -Path $defaultRoot | Out-Null
        $candidates = @()
        if (Test-AisbDirHasRealContent '.\workspace') { $candidates += (Get-Item '.\workspace') }
        $candidates += @(Get-ChildItem -Directory -Force -Path '.' -Filter 'workspace-*' -ErrorAction SilentlyContinue)
        foreach ($d in $candidates) {
            $dest = Join-Path $defaultRoot $d.Name
            if (Test-Path $dest) {
                Write-Warn "$dest already exists — leaving $($d.Name) in place to avoid clobbering."
                continue
            }
            # Cross-volume Move-Item becomes copy+delete and can be slow on a
            # large tree; say so up front so it doesn't look hung.
            Write-Info "Moving $($d.Name) -> $defaultRoot\ (may take a while if large or on another disk)..."
            Move-Item -Path $d.FullName -Destination $dest
            # The shared .\workspace dir carries the TRACKED workspace\.gitkeep
            # bind-mount placeholder; moving the whole dir would delete it from
            # the working tree. Re-seed it so the repo stays clean and a fresh
            # clone still finds the placeholder.
            if ($d.Name -eq 'workspace') {
                New-Item -ItemType Directory -Force -Path '.\workspace' | Out-Null
                New-Item -ItemType File -Force -Path '.\workspace\.gitkeep' | Out-Null
            }
        }
        Write-AisbDevWorkspaceRoot $defaultRoot
        Write-Ok "Workspace relocated to $defaultRoot (recorded in $script:AisbDevWorkspaceStateFile)."
        return $defaultRoot
    }

    # No legacy tree — persist the safe default so spawn and clean agree.
    Write-AisbDevWorkspaceRoot $defaultRoot
    return $defaultRoot
}

# Test-AisbWorkspaceRecursion REPO_ROOT WS_PATH → recursion guard (mirror of
# lib.sh's aisb_check_workspace_recursion). Returns:
#   2 (HARD FAIL) — WS is, contains, or is an ancestor of the repo root.
#   1 (WARN)      — WS is a strict descendant inside the repo (recorded opt-in).
#   0             — WS safely outside the repo tree.
function Test-AisbWorkspaceRecursion {
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][string]$WsPath
    )
    $sep = [System.IO.Path]::DirectorySeparatorChar
    # Canonicalize the repo root (it exists).
    $repoC = $RepoRoot
    try { $repoC = (Resolve-Path -LiteralPath $RepoRoot -ErrorAction Stop).Path } catch { }
    # Canonicalize the deepest existing ancestor of WS, re-appending the tail so
    # a not-yet-created workspace still canonicalizes.
    $probe = $WsPath
    $tail  = ''
    while ($probe -and -not (Test-Path -LiteralPath $probe)) {
        $tail   = "$sep$(Split-Path -Leaf $probe)$tail"
        $parent = Split-Path -Parent $probe
        if (-not $parent -or $parent -eq $probe) { break }
        $probe = $parent
    }
    $wsC = $WsPath
    if ($probe -and (Test-Path -LiteralPath $probe)) {
        try { $wsC = (Resolve-Path -LiteralPath $probe -ErrorAction Stop).Path + $tail } catch { }
    }

    $repoNorm = $repoC.TrimEnd($sep)
    $wsNorm   = $wsC.TrimEnd($sep)
    if ($wsNorm -eq $repoNorm) { return 2 }
    if ($repoNorm.StartsWith($wsNorm + $sep)) { return 2 }   # ws is an ancestor of repo
    if ($wsNorm.StartsWith($repoNorm + $sep)) { return 1 }   # ws strictly inside repo → warn
    return 0
}
