#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

function Write-Step {
    param([int]$Num, [int]$Total, [string]$Title)
    Write-Host ""
    Write-Host "[$Num/$Total] $Title" -ForegroundColor Cyan
}
function Write-Ok   { param([string]$Msg) Write-Host "  + $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "  ! $Msg" -ForegroundColor Yellow }
function Write-Info { param([string]$Msg) Write-Host "  $Msg" }

Write-Host "=== ai-sandbox setup ===" -ForegroundColor White
Write-Info "This walks through everything you need before launching the sandbox:"
Write-Info "SSH key -> build image -> (optional) gh login -> start container."

New-Item -ItemType Directory -Force -Path .\secrets, .\workspace, .\claude-config | Out-Null

# --- Step 1: SSH key ---------------------------------------------------------
Write-Step 1 4 "SSH key"
if (Test-Path .\secrets\git-key) {
    Write-Ok "Found at secrets/git-key"
} else {
    Write-Warn "No SSH key at secrets/git-key"
    Write-Info "You need an SSH private key registered with the git host you'll use"
    Write-Info "(GitHub / GitLab / etc). Enter a path to copy it from, or 'skip' to"
    Write-Info "place it manually later."
    $keyPath = Read-Host "  Path to existing private key"
    if ($keyPath -and $keyPath -ne 'skip') {
        if (Test-Path $keyPath) {
            Copy-Item $keyPath .\secrets\git-key
            Write-Ok "Copied to secrets/git-key"
        } else {
            Write-Warn "File not found at: $keyPath"
            Write-Warn "Place your key manually at secrets/git-key before launching."
        }
    } else {
        Write-Warn "Skipped. Place your key at secrets/git-key before launching."
    }
}

# --- Step 2: Container image -------------------------------------------------
Write-Step 2 4 "Container image"
docker image inspect ai-context:latest 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Ok "Image ai-context:latest already built"
    $rebuild = Read-Host "  Rebuild? [y/N]"
    if ($rebuild -match '^[Yy]') {
        docker compose build
        Write-Ok "Rebuilt"
    }
} else {
    Write-Info "Building image (first time, takes a minute)..."
    docker compose build
    Write-Ok "Built"
}

# --- Step 3: gh authentication -----------------------------------------------
Write-Step 3 4 "gh authentication (optional)"
$doAuth = $false
if (Test-Path .\secrets\gh-token) {
    Write-Ok "Found token at secrets/gh-token"
    $reAuth = Read-Host "  Re-authenticate? [y/N]"
    if ($reAuth -match '^[Yy]') { $doAuth = $true }
} else {
    Write-Warn "No gh token found"
    Write-Info "Optional. Enables gh issue / pr / api commands inside the sandbox."
    Write-Info "Skip if you only need git clone / push (those use SSH already)."
    $wantAuth = Read-Host "  Authenticate now? [y/N]"
    if ($wantAuth -match '^[Yy]') { $doAuth = $true }
}

if ($doAuth) {
    Write-Info "Launching one-off container for gh auth login..."
    docker run --rm -it `
        -v "${PWD}/secrets:/etc/secrets" `
        --entrypoint sh `
        ai-context:latest `
        -c 'gh auth login && gh auth token > /etc/secrets/gh-token && chmod 644 /etc/secrets/gh-token'
    Write-Ok "Token saved to secrets/gh-token"
}

# --- Step 4: Start sandbox ---------------------------------------------------
Write-Step 4 4 "Starting sandbox"
docker compose up -d
Write-Ok "Container is running"

Write-Host ""
Write-Host "=== Done! ===" -ForegroundColor White
Write-Info "Attach to Claude:   .\attach.ps1"
Write-Info "Stop the sandbox:   docker compose down"
Write-Info "Re-run this setup:  .\setup.ps1   (idempotent - safe any time)"
