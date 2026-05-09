#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"
docker compose exec claude-sandbox tmux attach -t main
