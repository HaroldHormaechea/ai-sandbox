#!/usr/bin/env bash
set -euo pipefail
docker compose exec claude-sandbox tmux attach -t main
