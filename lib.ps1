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
