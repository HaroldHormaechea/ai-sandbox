#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

$ESC = [char]27
$M   = "$ESC[1;35m"
$R   = "$ESC[0m"

function Remove-FolderContents {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return }
    Get-ChildItem -Path $Path -Force | Where-Object { $_.Name -ne '.gitkeep' } | ForEach-Object {
        Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Clear-Host
Write-Host "=== Clean environment ===" -ForegroundColor Red
Write-Host ""
Write-Host "  This will PERMANENTLY DELETE the following:" -ForegroundColor Red
Write-Host ""
Write-Host "    secrets/git-key, secrets/gh-token" -ForegroundColor Yellow
Write-Host "      (the copies inside this project - your originals in ~/.ssh and your"
Write-Host "       host gh login are untouched)"
Write-Host ""
Write-Host "    claude-config/*" -ForegroundColor Yellow
Write-Host "      (you'll have to /login and re-accept the trust + bypass dialogs)"
Write-Host ""
Write-Host "    workspace/*" -ForegroundColor Yellow
Write-Host "      (ALL FILES - cloned projects, uncommitted local work, everything)"
Write-Host ""
Write-Host "    ai-context:latest docker image" -ForegroundColor Yellow
Write-Host "      (the next .\setup.ps1 will rebuild it from scratch - slower)"
Write-Host ""
Write-Host "  It will also stop and remove the running container."
Write-Host ""
Write-Host "  After this, $M.\setup.ps1$R starts everything fresh."
Write-Host ""
$confirm = Read-Host "  Type 'yes' to confirm (anything else cancels)"
if ($confirm -ne 'yes') {
    Write-Host ""
    Write-Host "  Cancelled. Nothing was changed."
    exit 0
}

Write-Host ""
Write-Host "  Stopping container and removing image..."
docker compose down --rmi all

Write-Host "  Removing copied secrets..."
Remove-Item -Path .\secrets\git-key, .\secrets\gh-token -Force -ErrorAction SilentlyContinue

Write-Host "  Removing Claude config..."
Remove-FolderContents .\claude-config

Write-Host "  Removing workspace contents..."
Remove-FolderContents .\workspace

Write-Host ""
Write-Host "  + Clean. Run .\setup.ps1 to start fresh." -ForegroundColor Green
