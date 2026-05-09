#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

CYAN='\033[1;36m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
RESET='\033[0m'

step()   { printf "\n${CYAN}[%s/%s] %s${RESET}\n" "$1" "$2" "$3"; }
ok()     { printf "  ${GREEN}✓${RESET} %s\n" "$1"; }
warn()   { printf "  ${YELLOW}!${RESET} %s\n" "$1"; }
info()   { printf "  %s\n" "$1"; }

printf "${BOLD}=== ai-sandbox setup ===${RESET}\n"
info "This walks through everything you need before launching the sandbox:"
info "SSH key → build image → (optional) gh login → start container."

mkdir -p secrets workspace claude-config

# ── Step 1: SSH key ──────────────────────────────────────────────────────────
step 1 4 "SSH key"
if [ -f secrets/git-key ]; then
    ok "Found at secrets/git-key"
else
    warn "No SSH key at secrets/git-key"
    info "You need an SSH private key registered with the git host you'll use"
    info "(GitHub / GitLab / etc). Enter a path to copy it from, or 'skip' to"
    info "place it manually later."
    read -r -p "  Path to existing private key: " key_path
    if [ -n "${key_path:-}" ] && [ "$key_path" != "skip" ]; then
        if [ -f "$key_path" ]; then
            cp "$key_path" secrets/git-key
            chmod 600 secrets/git-key
            ok "Copied to secrets/git-key"
        else
            warn "File not found at: $key_path"
            warn "Place your key manually at secrets/git-key before launching."
        fi
    else
        warn "Skipped. Place your key at secrets/git-key before launching."
    fi
fi

# ── Step 2: Container image ──────────────────────────────────────────────────
step 2 4 "Container image"
if docker image inspect ai-context:latest >/dev/null 2>&1; then
    ok "Image ai-context:latest already built"
    read -r -p "  Rebuild? [y/N]: " rebuild
    if [[ "${rebuild:-}" =~ ^[Yy]$ ]]; then
        docker compose build
        ok "Rebuilt"
    fi
else
    info "Building image (first time, takes a minute)..."
    docker compose build
    ok "Built"
fi

# ── Step 3: gh authentication ────────────────────────────────────────────────
step 3 4 "gh authentication (optional)"
do_auth=false
if [ -f secrets/gh-token ]; then
    ok "Found token at secrets/gh-token"
    read -r -p "  Re-authenticate? [y/N]: " re_auth
    [[ "${re_auth:-}" =~ ^[Yy]$ ]] && do_auth=true
else
    warn "No gh token found"
    info "Optional. Enables gh issue / pr / api commands inside the sandbox."
    info "Skip if you only need git clone / push (those use SSH already)."
    read -r -p "  Authenticate now? [y/N]: " want_auth
    [[ "${want_auth:-}" =~ ^[Yy]$ ]] && do_auth=true
fi

if [ "$do_auth" = true ]; then
    info "Launching one-off container for gh auth login..."
    docker run --rm -it \
        -v "$(pwd)/secrets:/etc/secrets" \
        --entrypoint sh \
        ai-context:latest \
        -c 'gh auth login && gh auth token > /etc/secrets/gh-token && chmod 644 /etc/secrets/gh-token'
    ok "Token saved to secrets/gh-token"
fi

# ── Step 4: Start sandbox ────────────────────────────────────────────────────
step 4 4 "Starting sandbox"
docker compose up -d
ok "Container is running"

printf "\n${BOLD}=== Done! ===${RESET}\n"
info "Attach to Claude:   ./attach.sh"
info "Stop the sandbox:   docker compose down"
info "Re-run this setup:  ./setup.sh   (idempotent — safe any time)"
