#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

# ANSI escape sequences (modern Windows + PowerShell terminals support them).
$ESC = [char]27
$M   = "$ESC[1;35m"   # magenta — used to highlight commands
$R   = "$ESC[0m"

function Write-Step {
    param([int]$Num, [int]$Total, [string]$Title)
    Write-Host ("=== Step {0} of {1}: {2} ===" -f $Num, $Total, $Title) -ForegroundColor Cyan
    Write-Host ""
}
function Write-Ok    { param([string]$Msg) Write-Host "  + $Msg" -ForegroundColor Green }
function Write-Warn  { param([string]$Msg) Write-Host "  ! $Msg" -ForegroundColor Yellow }
function Write-Info  { param([string]$Msg) Write-Host "  $Msg" }
function Write-Blank { Write-Host "" }

function Press-Enter {
    Write-Host ""
    Write-Host "  Press Enter to continue " -ForegroundColor White -NoNewline
    [void](Read-Host)
}

function Test-ClaudeConfigSetUp {
    if (-not (Test-Path .\claude-config)) { return $false }
    $items = Get-ChildItem -Path .\claude-config -Force | Where-Object { $_.Name -ne '.gitkeep' }
    return ($items.Count -gt 0)
}

function Get-SshKeys {
    $sshDir = Join-Path $env:USERPROFILE ".ssh"
    if (-not (Test-Path $sshDir)) { return @() }
    Get-ChildItem -Path $sshDir -File | Where-Object {
        $name = $_.Name
        ($name -notmatch '\.(pub|bak)$') -and
        ($name -notmatch '^known_hosts') -and
        ($name -notin @('config','authorized_keys','environment'))
    } | Select-Object -ExpandProperty Name
}

function Show-SshHelpScreen {
    Clear-Host
    Write-Host "=== How to create an SSH key ===" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  1. In another PowerShell window, run:"
    Write-Host "       $M`ssh-keygen -t ed25519$R"
    Write-Host "  2. Press Enter to accept all defaults (you can leave the passphrase empty)."
    Write-Host "  3. View your public key:"
    Write-Host "       $M`Get-Content `$env:USERPROFILE\.ssh\id_ed25519.pub$R"
    Write-Host "  4. Copy the printed line and paste it into your git host:"
    Write-Host "       GitHub:  $M`https://github.com/settings/ssh/new$R"
    Write-Host "       GitLab:  $M`https://gitlab.com/-/profile/keys$R"
    Write-Host "  5. Re-run this setup - id_ed25519 will appear in the list."
    Press-Enter
}

# --- Welcome screen ----------------------------------------------------------
Clear-Host
Write-Host "=== ai-sandbox setup ===" -ForegroundColor Cyan
Write-Host ""
Write-Info "This walks you through setting up the sandbox in 4 steps:"
Write-Blank
Write-Info "  1. SSH key      - register a key for git operations"
Write-Info "  2. Image        - build the container if needed"
Write-Info "  3. gh login     - optional, for GitHub API operations"
Write-Info "  4. Claude setup - /login, trust folder, accept bypass warning"
Write-Info "  5. Start        - bring the container up"
Write-Blank
Write-Host "  Press Enter to begin (or $M/exit$R to quit)."
$intro = Read-Host "  >"
if ($intro -eq '/exit') {
    Write-Host "  Exiting setup."
    exit 0
}

New-Item -ItemType Directory -Force -Path .\secrets, .\workspace, .\claude-config | Out-Null

# --- Step 1: SSH key ---------------------------------------------------------
Clear-Host
Write-Step 1 5 "SSH key"

if (Test-Path .\secrets\git-key) {
    Write-Ok "Found at secrets/git-key"
    Press-Enter
} else {
    $keys = @(Get-SshKeys)
    $lastError = ""

    while ($true) {
        Clear-Host
        Write-Step 1 5 "SSH key"
        Write-Warn "No SSH key at secrets/git-key"
        Write-Blank
        if ($keys.Count -gt 0) {
            Write-Info "Candidate keys in ~/.ssh:"
            for ($i = 0; $i -lt $keys.Count; $i++) {
                Write-Host ("    {0}) {1}" -f ($i + 1), $keys[$i])
            }
            Write-Blank
            Write-Host "  Type a number, a path to a different key, $M/help$R, $M/skip$R, or $M/exit$R."
        } else {
            Write-Info "No candidate keys found in ~/.ssh."
            Write-Blank
            Write-Host "  Type a path to your private key, $M/help$R (create one), $M/skip$R, or $M/exit$R."
        }
        if ($lastError) { Write-Warn $lastError }
        $choice = Read-Host "  >"
        $lastError = ""

        if (-not $choice -or $choice -eq '/skip') {
            Write-Warn "Skipped. Place your key at secrets/git-key before launching."
            break
        }
        if ($choice -eq '/help') {
            Show-SshHelpScreen
            continue
        }
        if ($choice -eq '/exit') {
            Write-Host "  Exiting setup."
            exit 0
        }
        $src = $null
        if ($choice -match '^\d+$') {
            $idx = [int]$choice - 1
            if ($idx -ge 0 -and $idx -lt $keys.Count) {
                $src = Join-Path $env:USERPROFILE ".ssh\$($keys[$idx])"
            }
        }
        if (-not $src) { $src = $choice }
        if (Test-Path $src -PathType Leaf) {
            Copy-Item $src .\secrets\git-key
            Write-Ok "Copied $src -> secrets/git-key"
            break
        } else {
            $lastError = "Not found: $src"
        }
    }
}

# --- Step 2: Container image -------------------------------------------------
Clear-Host
Write-Step 2 5 "Container image"
if (docker images -q ai-context:latest) {
    Write-Ok "Image ai-context:latest already built"
    Write-Blank
    $rebuild = Read-Host "  Rebuild? [y/N]"
    if ($rebuild -match '^[Yy]') {
        Write-Blank
        docker compose build
        Write-Ok "Rebuilt"
    }
} else {
    Write-Info "Building image (first time, takes a minute)..."
    Write-Blank
    docker compose build
    Write-Ok "Built"
}

# --- Step 3: gh authentication -----------------------------------------------
Clear-Host
Write-Step 3 5 "gh authentication (optional)"
$doAuth = $false
if (Test-Path .\secrets\gh-token) {
    Write-Ok "Found token at secrets/gh-token"
    Write-Blank
    $reAuth = Read-Host "  Re-authenticate? [y/N]"
    if ($reAuth -match '^[Yy]') { $doAuth = $true }
} else {
    Write-Warn "No gh token found"
    Write-Blank
    Write-Info "Optional. Enables gh issue / pr / api commands inside the sandbox."
    Write-Info "Skip if you only need git clone / push (those use SSH already)."
    Write-Blank
    $wantAuth = Read-Host "  Authenticate now? [y/N]"
    if ($wantAuth -match '^[Yy]') { $doAuth = $true }
}

if ($doAuth) {
    Write-Blank
    Write-Info "Launching one-off container for gh auth login..."
    Write-Blank
    docker run --rm -it `
        -v "${PWD}/secrets:/etc/secrets" `
        --entrypoint sh `
        ai-context:latest `
        -c 'gh auth login --hostname github.com --git-protocol ssh --skip-ssh-key && gh auth token > /etc/secrets/gh-token && chmod 644 /etc/secrets/gh-token'
    Write-Ok "Token saved to secrets/gh-token"
}

# --- Step 4: Claude Code first-run -------------------------------------------
Clear-Host
Write-Step 4 5 "Claude Code first-run"

$doClaudeSetup = $false
if (Test-ClaudeConfigSetUp) {
    Write-Ok "Claude config already present in claude-config/"
    Write-Blank
    Write-Info "Looks like first-run was done before."
    $redo = Read-Host "  Re-do it anyway? [y/N]"
    if ($redo -match '^[Yy]') { $doClaudeSetup = $true }
} else {
    Write-Info "This launches Claude in a one-off container so you can complete:"
    Write-Info "  - /login         (Anthropic auth - one-time)"
    Write-Info "  - Trust folder   (the popup asking to trust /workspace/project-builder)"
    Write-Info "  - Bypass warning (the dialog about --dangerously-skip-permissions)"
    Write-Blank
    Write-Info "When done, type /exit inside Claude to return here."
    Write-Blank
    $launch = Read-Host "  Launch Claude now? [Y/n]"
    if ($launch -notmatch '^[Nn]') { $doClaudeSetup = $true }
}

if ($doClaudeSetup) {
    Write-Blank
    Write-Info "Launching Claude - answer the prompts, then type /exit to return."
    Write-Blank
    docker run --rm -it `
        -v "${PWD}/workspace:/workspace" `
        -v "${PWD}/claude-config:/home/claude/.claude" `
        -v "${PWD}/secrets:/etc/secrets:ro" `
        ai-context:latest `
        claude --dangerously-skip-permissions
    Write-Ok "First-run setup complete"
}

# --- Step 5: Start sandbox ---------------------------------------------------
Clear-Host
Write-Step 5 5 "Starting sandbox"
docker compose up -d
Write-Blank
Write-Ok "Container is running"
Press-Enter

# --- Done --------------------------------------------------------------------
Clear-Host
Write-Host "=== Done! ===" -ForegroundColor Green
Write-Host ""
Write-Host "  Attach to Claude:    $M.\attach.ps1$R"
Write-Host "  Stop the sandbox:    $M`docker compose down$R"
Write-Host "  Re-run this setup:   $M.\setup.ps1$R   (idempotent - safe any time)"
Write-Host ""
