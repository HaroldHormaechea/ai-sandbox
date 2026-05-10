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

function Show-IdentityHelpScreen {
    Clear-Host
    Write-Host "=== About git author identity ===" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  Every git commit records WHO made it via user.name and user.email."
    Write-Host "  Without them set, ""git commit"" fails inside the container with:"
    Write-Host "      *** Please tell me who you are."
    Write-Host ""
    Write-Host "  This step writes secrets/gitconfig (name + email). It is bind-mounted"
    Write-Host "  read-only into the container at /etc/secrets/gitconfig and applied"
    Write-Host "  via ""git config --global include.path"" so every commit Claude makes"
    Write-Host "  inherits these values."
    Write-Host ""
    Write-Host "  Defaults are detected from your host's ""git config --global"""
    Write-Host "  (and, as a hint, the comment in the SSH key's .pub file)."
    Write-Host "  Override anything by typing a new value."
    Write-Host ""
    Write-Host "  secrets/ is gitignored - your name and email never get committed"
    Write-Host "  to this repo, only to the projects you check in inside the container."
    Press-Enter
}

# Read a value from `git config` at a specific scope on the host.
# Returns "" on miss; trims whitespace.
function Get-HostGitConfigValue {
    param(
        [Parameter(Mandatory)][ValidateSet('global','system','unscoped')][string]$Scope,
        [Parameter(Mandatory)][string]$Key
    )
    $gitArgs = @()
    switch ($Scope) {
        'global'   { $gitArgs = @('config','--global',$Key) }
        'system'   { $gitArgs = @('config','--system',$Key) }
        'unscoped' { $gitArgs = @('config',$Key) }
    }
    $value = (& git @gitArgs 2>$null)
    if ($LASTEXITCODE -ne 0) { return "" }
    if ($null -eq $value) { return "" }
    return ($value | Out-String).Trim()
}

function Get-DefaultName {
    foreach ($scope in @('global','system','unscoped')) {
        $v = Get-HostGitConfigValue -Scope $scope -Key 'user.name'
        if ($v) { return $v }
    }
    return ""
}

function Get-DefaultEmail {
    foreach ($scope in @('global','system','unscoped')) {
        $v = Get-HostGitConfigValue -Scope $scope -Key 'user.email'
        if ($v) { return $v }
    }
    return ""
}

# Parse the comment field of an OpenSSH .pub file. Returns @{ Name = ...; Email = ... }
# (either field may be empty). Strict TLD-shaped email match;
# LHS-of-@ with dots becomes Title-Cased name.
function Parse-PubComment {
    param([Parameter(Mandatory)][string]$PubPath)
    $result = @{ Name = ""; Email = "" }
    if (-not (Test-Path $PubPath -PathType Leaf)) { return $result }
    $firstLine = Get-Content -Path $PubPath -TotalCount 1 -ErrorAction SilentlyContinue
    if (-not $firstLine) { return $result }
    # OpenSSH single-line format: "<type> <key-blob> <comment...>"
    $parts = $firstLine -split '\s+', 3
    if ($parts.Count -lt 3) { return $result }
    $comment = $parts[2].Trim()
    if (-not $comment) { return $result }

    if ($comment -match '^[^@\s]+@[^@\s]+\.[^@\s]+$') {
        $result.Email = $comment
        $lhs = $comment.Split('@')[0]
        if ($lhs.Contains('.')) {
            $words = $lhs.Split('.') | Where-Object { $_ } | ForEach-Object {
                if ($_.Length -ge 2) {
                    $_.Substring(0,1).ToUpper() + $_.Substring(1)
                } else {
                    $_.ToUpper()
                }
            }
            $result.Name = ($words -join ' ')
        } else {
            $result.Name = $lhs
        }
    } else {
        # Comment present but not an email-shaped string — keep it as a name hint.
        $result.Name = $comment
    }
    return $result
}

# Generic prompt-and-validate loop.
#   Read-IdentityField -Label "Author name " -Default "..." -Validator { param($v) ... }
# Returns the chosen value (string), or $null when the user typed /skip.
# Calls `exit 0` on /exit. /help shows the identity help screen.
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
            '^/help$' { Show-IdentityHelpScreen; continue }
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

function Read-GitConfigField {
    param([Parameter(Mandatory)][string]$Field)
    $v = (& git config --file (Join-Path $PSScriptRoot 'secrets\gitconfig') "user.$Field" 2>$null)
    if ($LASTEXITCODE -ne 0) { return "" }
    if ($null -eq $v) { return "" }
    return ($v | Out-String).Trim()
}

function Write-GitConfig {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Email
    )
    $content = "[user]`n    name = $Name`n    email = $Email`n"
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText(
        (Join-Path $PSScriptRoot 'secrets\gitconfig'),
        $content,
        $utf8NoBom
    )
}

# --- Welcome screen ----------------------------------------------------------
Clear-Host
Write-Host "=== ai-sandbox setup ===" -ForegroundColor Cyan
Write-Host ""
Write-Info "This walks you through setting up the sandbox in 6 steps:"
Write-Blank
Write-Info "  1. SSH key      - register a key for git operations"
Write-Info "  2. Git identity - set the author name + email for commits"
Write-Info "  3. Image        - build the container if needed"
Write-Info "  4. gh login     - optional, for the GitHub CLI (gh issue / pr / etc.)"
Write-Info "  5. Claude setup - /login, trust folder, accept bypass warning"
Write-Info "  6. Start        - bring the container up"
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
Write-Step 1 6 "SSH key"

$script:SshKeySource = $null

if (Test-Path .\secrets\git-key) {
    Write-Ok "Found at secrets/git-key"
    Press-Enter
} else {
    $keys = @(Get-SshKeys)
    $lastError = ""

    while ($true) {
        Clear-Host
        Write-Step 1 6 "SSH key"
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
            $script:SshKeySource = $src
            Write-Ok "Copied $src -> secrets/git-key"
            break
        } else {
            $lastError = "Not found: $src"
        }
    }
}

# --- Step 2: Git author identity ---------------------------------------------
Clear-Host
Write-Step 2 6 "Git author identity"

$cur = @{ Name = ""; Email = "" }
$action = $null

if (Test-Path .\secrets\gitconfig) {
    $cur.Name  = Read-GitConfigField name
    $cur.Email = Read-GitConfigField email
    Write-Ok "Found at secrets/gitconfig"
    Write-Info ("  name:  " + $(if ($cur.Name)  { $cur.Name }  else { "(none)" }))
    Write-Info ("  email: " + $(if ($cur.Email) { $cur.Email } else { "(none)" }))
    Write-Blank
    $lastError = ""
    while (-not $action) {
        if ($lastError) { Write-Warn $lastError; $lastError = "" }
        $resp = Read-Host "  [K]eep, [R]e-prompt, [E]dit, /help, /skip, /exit? [K/r/e]"
        switch -Regex ($resp) {
            '^$|^[Kk]$' { $action = 'keep' }
            '^[Rr]$'    { $action = 'reprompt' }
            '^[Ee]$'    { $action = 'edit' }
            '^/help$'   { Show-IdentityHelpScreen }
            '^/skip$'   { Write-Warn "Skipped. Existing secrets/gitconfig kept."; $action = 'keep' }
            '^/exit$'   { Write-Host "  Exiting setup."; exit 0 }
            default     { $lastError = "Type K, R, E, /help, /skip, or /exit." }
        }
    }
} else {
    $action = 'fresh'
}

$skipped = $false
if ($action -ne 'keep') {
    $defaultName  = Get-DefaultName
    $defaultEmail = Get-DefaultEmail

    if ($script:SshKeySource -and (Test-Path "$($script:SshKeySource).pub")) {
        $pub = Parse-PubComment "$($script:SshKeySource).pub"
        if (-not $defaultName  -and $pub.Name)  { $defaultName  = $pub.Name }
        if (-not $defaultEmail -and $pub.Email) { $defaultEmail = $pub.Email }
    }

    if ($action -eq 'edit') {
        $defaultName  = $cur.Name
        $defaultEmail = $cur.Email
    }

    $name  = Read-IdentityField -Label "Author name " -Default $defaultName  -Validator { param($v) [bool]$v.Trim() }
    if ($null -eq $name) { $skipped = $true }

    if (-not $skipped) {
        $email = Read-IdentityField -Label "Author email" -Default $defaultEmail -Validator { param($v) $v -match '^[^@\s]+@[^@\s]+\.[^@\s]+$' }
        if ($null -eq $email) { $skipped = $true }
    }

    if ($skipped) {
        Write-Warn "Skipped. Commits inside the container will fail until secrets/gitconfig exists."
    } else {
        Write-GitConfig -Name $name -Email $email
        Write-Ok "Wrote secrets/gitconfig"
    }
}

if (docker image inspect ai-context:latest 2>$null) {
    Write-Blank
    Write-Warn "An ai-context:latest image already exists. If it was built before"
    Write-Warn "this wizard step shipped, its entrypoint won't apply secrets/gitconfig."
    Write-Warn "Rebuild it at the next step (default is now Y when gitconfig is present)."
}

Press-Enter

# --- Step 3: Container image -------------------------------------------------
Clear-Host
Write-Step 3 6 "Container image"
if (docker images -q ai-context:latest) {
    Write-Ok "Image ai-context:latest already built"
    Write-Blank
    if (Test-Path .\secrets\gitconfig) {
        Write-Info "Recommended: rebuild so the image's entrypoint applies secrets/gitconfig."
        Write-Info "(Required if you cloned this repo before the git-identity step shipped.)"
        Write-Blank
        $rebuild = Read-Host "  Rebuild? [Y/n]"
        if ($rebuild -notmatch '^[Nn]') {
            Write-Blank
            docker compose build
            Write-Ok "Rebuilt"
        }
    } else {
        $rebuild = Read-Host "  Rebuild? [y/N]"
        if ($rebuild -match '^[Yy]') {
            Write-Blank
            docker compose build
            Write-Ok "Rebuilt"
        }
    }
} else {
    Write-Info "Building image (first time, takes a minute)..."
    Write-Blank
    docker compose build
    Write-Ok "Built"
}

# --- Step 4: gh authentication -----------------------------------------------
Clear-Host
Write-Step 4 6 "GitHub CLI (gh) authentication - optional"
Write-Info "``gh`` is the official GitHub command-line client (https://cli.github.com)."
Write-Info "Authenticating it lets you create PRs, list issues, and call the GitHub"
Write-Info "API from inside the sandbox. Cloning / pushing already works via SSH and"
Write-Info "does NOT require this - skip if you only need git operations."
Write-Blank
$doAuth = $false
if (Test-Path .\secrets\gh-token) {
    Write-Ok "Found token at secrets/gh-token"
    Write-Blank
    $reAuth = Read-Host "  Re-authenticate? [y/N]"
    if ($reAuth -match '^[Yy]') { $doAuth = $true }
} else {
    Write-Warn "No gh token found"
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

# --- Step 5: Claude Code first-run -------------------------------------------
Clear-Host
Write-Step 5 6 "Claude Code first-run"

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

# --- Step 6: Start sandbox ---------------------------------------------------
Clear-Host
Write-Step 6 6 "Starting sandbox"
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
