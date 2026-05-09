#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# Use ANSI-C quoting so the variables hold real ESC chars and work
# anywhere (format strings, %s arguments, heredocs).
CYAN=$'\033[1;36m'
GREEN=$'\033[1;32m'
YELLOW=$'\033[1;33m'
MAGENTA=$'\033[1;35m'
BOLD=$'\033[1m'
RESET=$'\033[0m'

ok()    { printf "  %s✓%s %s\n" "$GREEN" "$RESET" "$1"; }
warn()  { printf "  %s!%s %s\n" "$YELLOW" "$RESET" "$1"; }
info()  { printf "  %s\n" "$1"; }
hr()    { printf "\n"; }

clear_screen() { printf "\033[H\033[2J"; }

screen_header() {
    local current="$1" total="$2" title="$3"
    printf "%s%s=== Step %s of %s: %s ===%s\n\n" \
        "$BOLD" "$CYAN" "$current" "$total" "$title" "$RESET"
}

press_enter() {
    printf "\n  %sPress Enter to continue%s " "$BOLD" "$RESET"
    read -r _
}

list_ssh_keys() {
    [ -d "$HOME/.ssh" ] || return 0
    for f in "$HOME/.ssh"/*; do
        [ -f "$f" ] || continue
        name="$(basename "$f")"
        case "$name" in
            *.pub|known_hosts*|config|authorized_keys|environment|*.bak)
                continue
                ;;
        esac
        echo "$name"
    done
}

claude_config_set_up() {
    [ -d claude-config ] || return 1
    for f in claude-config/* claude-config/.[!.]*; do
        [ -e "$f" ] || continue
        case "$(basename "$f")" in
            .gitkeep) ;;
            *) return 0 ;;
        esac
    done
    return 1
}

show_ssh_help_screen() {
    clear_screen
    printf "%s%s=== How to create an SSH key ===%s\n\n" "$BOLD" "$CYAN" "$RESET"
    printf "  1. In another terminal, run:\n"
    printf "       %sssh-keygen -t ed25519%s\n" "$MAGENTA" "$RESET"
    printf "  2. Press Enter to accept all defaults (you can leave the passphrase empty).\n"
    printf "  3. View your public key:\n"
    printf "       %scat ~/.ssh/id_ed25519.pub%s\n" "$MAGENTA" "$RESET"
    printf "  4. Copy the printed line and paste it into your git host:\n"
    printf "       GitHub:  %shttps://github.com/settings/ssh/new%s\n" "$MAGENTA" "$RESET"
    printf "       GitLab:  %shttps://gitlab.com/-/profile/keys%s\n" "$MAGENTA" "$RESET"
    printf "  5. Re-run this setup — id_ed25519 will appear in the list.\n"
    press_enter
}

# ── Welcome screen ───────────────────────────────────────────────────────────
clear_screen
printf "%s%s=== ai-sandbox setup ===%s\n\n" "$BOLD" "$CYAN" "$RESET"
info "This walks you through setting up the sandbox in 4 steps:"
hr
info "  1. SSH key      — register a key for git operations"
info "  2. Image        — build the container if needed"
info "  3. gh login     — optional, for the GitHub CLI (gh issue / pr / etc.)"
info "  4. Claude setup — /login, trust folder, accept bypass warning"
info "  5. Start        — bring the container up"
hr
printf "  Press Enter to begin (or %s/exit%s to quit).\n" "$MAGENTA" "$RESET"
read -r -p "  > " _intro
[ "${_intro:-}" = "/exit" ] && { echo "  Exiting setup."; exit 0; }

mkdir -p secrets workspace claude-config

# ── Step 1: SSH key ──────────────────────────────────────────────────────────
clear_screen
screen_header 1 5 "SSH key"

if [ -f secrets/git-key ]; then
    ok "Found at secrets/git-key"
    press_enter
else
    keys=()
    while IFS= read -r k; do
        keys+=("$k")
    done < <(list_ssh_keys)

    last_error=""
    while true; do
        clear_screen
        screen_header 1 5 "SSH key"
        warn "No SSH key at secrets/git-key"
        hr
        if [ "${#keys[@]}" -gt 0 ]; then
            info "Candidate keys in ~/.ssh:"
            for i in "${!keys[@]}"; do
                printf "    %d) %s\n" "$((i+1))" "${keys[$i]}"
            done
            hr
            printf "  Type a number, a path to a different key, %s/help%s, %s/skip%s, or %s/exit%s.\n" \
                "$MAGENTA" "$RESET" "$MAGENTA" "$RESET" "$MAGENTA" "$RESET"
        else
            info "No candidate keys found in ~/.ssh."
            hr
            printf "  Type a path to your private key, %s/help%s (create one), %s/skip%s, or %s/exit%s.\n" \
                "$MAGENTA" "$RESET" "$MAGENTA" "$RESET" "$MAGENTA" "$RESET"
        fi
        [ -n "$last_error" ] && warn "$last_error"
        read -r -p "  > " choice
        last_error=""
        case "$choice" in
            ""|/skip)
                warn "Skipped. Place your key at secrets/git-key before launching."
                break
                ;;
            /help)
                show_ssh_help_screen
                ;;
            /exit)
                echo "  Exiting setup."
                exit 0
                ;;
            *)
                if [[ "$choice" =~ ^[0-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le "${#keys[@]}" ]; then
                    src="$HOME/.ssh/${keys[$((choice-1))]}"
                else
                    src="$choice"
                fi
                if [ -f "$src" ]; then
                    cp "$src" secrets/git-key
                    chmod 600 secrets/git-key
                    ok "Copied $src → secrets/git-key"
                    break
                else
                    last_error="Not found: $src"
                fi
                ;;
        esac
    done
fi

# ── Step 2: Container image ──────────────────────────────────────────────────
clear_screen
screen_header 2 5 "Container image"
if docker image inspect ai-context:latest >/dev/null 2>&1; then
    ok "Image ai-context:latest already built"
    hr
    read -r -p "  Rebuild? [y/N]: " rebuild
    if [[ "${rebuild:-}" =~ ^[Yy]$ ]]; then
        hr
        docker compose build
        ok "Rebuilt"
    fi
else
    info "Building image (first time, takes a minute)..."
    hr
    docker compose build
    ok "Built"
fi

# ── Step 3: gh authentication ────────────────────────────────────────────────
clear_screen
screen_header 3 5 "GitHub CLI (gh) authentication — optional"
info "\`gh\` is the official GitHub command-line client (https://cli.github.com)."
info "Authenticating it lets you create PRs, list issues, and call the GitHub"
info "API from inside the sandbox. Cloning / pushing already works via SSH and"
info "does NOT require this — skip if you only need git operations."
hr
do_auth=false
if [ -f secrets/gh-token ]; then
    ok "Found token at secrets/gh-token"
    hr
    read -r -p "  Re-authenticate? [y/N]: " re_auth
    [[ "${re_auth:-}" =~ ^[Yy]$ ]] && do_auth=true
else
    warn "No gh token found"
    hr
    read -r -p "  Authenticate now? [y/N]: " want_auth
    [[ "${want_auth:-}" =~ ^[Yy]$ ]] && do_auth=true
fi

if [ "$do_auth" = true ]; then
    hr
    info "Launching one-off container for gh auth login..."
    hr
    docker run --rm -it \
        -v "$(pwd)/secrets:/etc/secrets" \
        --entrypoint sh \
        ai-context:latest \
        -c 'gh auth login --hostname github.com --git-protocol ssh --skip-ssh-key && gh auth token > /etc/secrets/gh-token && chmod 644 /etc/secrets/gh-token'
    ok "Token saved to secrets/gh-token"
fi

# ── Step 4: Claude Code first-run ────────────────────────────────────────────
clear_screen
screen_header 4 5 "Claude Code first-run"

do_claude_setup=false
if claude_config_set_up; then
    ok "Claude config already present in claude-config/"
    hr
    info "Looks like first-run was done before."
    read -r -p "  Re-do it anyway? [y/N]: " redo
    [[ "${redo:-}" =~ ^[Yy]$ ]] && do_claude_setup=true
else
    info "This launches Claude in a one-off container so you can complete:"
    info "  • /login         (Anthropic auth — one-time)"
    info "  • Trust folder   (the popup asking to trust /workspace/project-builder)"
    info "  • Bypass warning (the dialog about --dangerously-skip-permissions)"
    hr
    info "When done, type /exit inside Claude to return here."
    hr
    read -r -p "  Launch Claude now? [Y/n]: " launch
    if [[ ! "${launch:-}" =~ ^[Nn]$ ]]; then
        do_claude_setup=true
    fi
fi

if [ "$do_claude_setup" = true ]; then
    hr
    info "Launching Claude — answer the prompts, then type /exit to return."
    hr
    docker run --rm -it \
        -v "$(pwd)/workspace:/workspace" \
        -v "$(pwd)/claude-config:/home/claude/.claude" \
        -v "$(pwd)/secrets:/etc/secrets:ro" \
        ai-context:latest \
        claude --dangerously-skip-permissions
    ok "First-run setup complete"
fi

# ── Step 5: Start sandbox ────────────────────────────────────────────────────
clear_screen
screen_header 5 5 "Starting sandbox"
docker compose up -d
hr
ok "Container is running"
press_enter

# ── Done ─────────────────────────────────────────────────────────────────────
clear_screen
printf "%s%s=== Done! ===%s\n\n" "$BOLD" "$GREEN" "$RESET"
printf "  Attach to Claude:    %s./attach.sh%s\n" "$MAGENTA" "$RESET"
printf "  Stop the sandbox:    %sdocker compose down%s\n" "$MAGENTA" "$RESET"
printf "  Re-run this setup:   %s./setup.sh%s   (idempotent — safe any time)\n" "$MAGENTA" "$RESET"
hr
