#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# Shared helpers (color/format primitives, prompt_field, session enumeration).
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

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

show_identity_help_screen() {
    clear_screen
    printf "%s%s=== About git author identity ===%s\n\n" "$BOLD" "$CYAN" "$RESET"
    printf "  Every git commit records WHO made it via user.name and user.email.\n"
    printf "  Without them set, \"git commit\" fails inside the container with:\n"
    printf "      *** Please tell me who you are.\n\n"
    printf "  This step writes secrets/gitconfig (name + email). It is bind-mounted\n"
    printf "  read-only into the container at /etc/secrets/gitconfig and applied\n"
    printf "  via \"git config --global include.path\" so every commit Claude makes\n"
    printf "  inherits these values.\n\n"
    printf "  Defaults are detected from your host's \"git config --global\"\n"
    printf "  (and, as a hint, the comment in the SSH key's .pub file).\n"
    printf "  Override anything by typing a new value.\n\n"
    printf "  secrets/ is gitignored — your name and email never get committed\n"
    printf "  to this repo, only to the projects you check in inside the container.\n"
    press_enter
}

# Read a value from `git config` at a specific scope on the host.
# Trims trailing whitespace; returns empty on miss.
host_git_config_value() {
    local scope="$1" key="$2" value=""
    case "$scope" in
        global)   value=$(git config --global "$key" 2>/dev/null || true) ;;
        system)   value=$(git config --system "$key" 2>/dev/null || true) ;;
        unscoped) value=$(git config "$key" 2>/dev/null || true) ;;
    esac
    # Trim trailing CR/whitespace.
    printf "%s" "${value%$'\r'}"
}

detect_default_name() {
    local v
    for scope in global system unscoped; do
        v=$(host_git_config_value "$scope" user.name)
        if [ -n "$v" ]; then
            default_name="$v"
            return 0
        fi
    done
    default_name=""
}

detect_default_email() {
    local v
    for scope in global system unscoped; do
        v=$(host_git_config_value "$scope" user.email)
        if [ -n "$v" ]; then
            default_email="$v"
            return 0
        fi
    done
    default_email=""
}

# Parse an OpenSSH .pub file's comment field for a name/email hint.
# Sets the globals pub_name and pub_email. The OpenSSH single-line format is
# "<type> <key-blob> <comment...>", so the comment is everything from the
# third whitespace-delimited field onward.
parse_pub_comment() {
    local pub_path="$1" first_line comment lhs word title
    pub_name=""
    pub_email=""
    [ -f "$pub_path" ] || return 0
    IFS= read -r first_line < "$pub_path" || return 0
    # Strip CR (Windows-edited keys), then drop the first two whitespace-runs.
    first_line="${first_line%$'\r'}"
    comment="${first_line#* }"
    comment="${comment#* }"
    # If nothing was stripped, there was no comment.
    [ "$comment" = "$first_line" ] && return 0
    # Trim leading/trailing whitespace.
    comment="${comment#"${comment%%[![:space:]]*}"}"
    comment="${comment%"${comment##*[![:space:]]}"}"
    [ -z "$comment" ] && return 0

    if printf "%s" "$comment" | grep -Eq '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'; then
        pub_email="$comment"
        lhs="${comment%@*}"
        if printf "%s" "$lhs" | grep -q '\.'; then
            title=""
            local IFS_BAK="$IFS"
            IFS='.'
            for word in $lhs; do
                IFS="$IFS_BAK"
                if [ -n "$word" ]; then
                    local first rest
                    first=$(printf "%s" "$word" | cut -c1 | tr '[:lower:]' '[:upper:]')
                    rest=$(printf "%s" "$word" | cut -c2-)
                    if [ -z "$title" ]; then
                        title="${first}${rest}"
                    else
                        title="${title} ${first}${rest}"
                    fi
                fi
                IFS='.'
            done
            IFS="$IFS_BAK"
            pub_name="$title"
        else
            pub_name="$lhs"
        fi
    else
        # Comment present but not an email — treat the whole thing as a name hint.
        pub_name="$comment"
    fi
}

# Per-field secondary fallback. Only fills a slot the primary cascade left empty.
apply_pub_fallback() {
    if [ -z "$default_name" ] && [ -n "$pub_name" ]; then
        default_name="$pub_name"
    fi
    if [ -z "$default_email" ] && [ -n "$pub_email" ]; then
        default_email="$pub_email"
    fi
}

validate_name() {
    local v="$1"
    # Non-empty after whitespace trim.
    v="${v#"${v%%[![:space:]]*}"}"
    v="${v%"${v##*[![:space:]]}"}"
    [ -n "$v" ]
}

validate_email() {
    local v="$1"
    printf "%s" "$v" | grep -Eq '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'
}

# prompt_field is provided by lib.sh.

read_gitconfig_field() {
    local field="$1"
    git config --file secrets/gitconfig "user.$field" 2>/dev/null || true
}

write_gitconfig() {
    local name="$1" email="$2"
    cat > secrets/gitconfig <<EOF
[user]
    name = $name
    email = $email
EOF
    chmod 644 secrets/gitconfig
}

# ── Welcome screen ───────────────────────────────────────────────────────────
clear_screen
printf "%s%s=== ai-sandbox setup ===%s\n\n" "$BOLD" "$CYAN" "$RESET"
info "This walks you through setting up the sandbox in 6 steps:"
hr
info "  1. SSH key      — register a key for git operations"
info "  2. Git identity — set the author name + email for commits"
info "  3. Image        — build the container if needed"
info "  4. gh login     — optional, for the GitHub CLI (gh issue / pr / etc.)"
info "  5. Claude setup — /login, trust folder, accept bypass warning"
info "  6. Start        — bring the container up"
hr
printf "  Press Enter to begin (or %s/exit%s to quit).\n" "$MAGENTA" "$RESET"
read -r -p "  > " _intro
[ "${_intro:-}" = "/exit" ] && { echo "  Exiting setup."; exit 0; }

mkdir -p secrets workspace claude-config

# ── Step 1: SSH key ──────────────────────────────────────────────────────────
clear_screen
screen_header 1 6 "SSH key"

SSH_KEY_SOURCE=""

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
        screen_header 1 6 "SSH key"
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
                    SSH_KEY_SOURCE="$src"
                    ok "Copied $src → secrets/git-key"
                    break
                else
                    last_error="Not found: $src"
                fi
                ;;
        esac
    done
fi

# ── Step 2: Git author identity ──────────────────────────────────────────────
clear_screen
screen_header 2 6 "Git author identity"

cur_name=""
cur_email=""
action=""

if [ -f secrets/gitconfig ]; then
    cur_name=$(read_gitconfig_field name)
    cur_email=$(read_gitconfig_field email)
    ok "Found at secrets/gitconfig"
    info "  name:  ${cur_name:-(none)}"
    info "  email: ${cur_email:-(none)}"
    hr
    last_error=""
    while [ -z "$action" ]; do
        if [ -n "$last_error" ]; then
            warn "$last_error"
            last_error=""
        fi
        printf "  [K]eep, [R]e-prompt, [E]dit, %s/help%s, %s/skip%s, %s/exit%s? [K/r/e]: " \
            "$MAGENTA" "$RESET" "$MAGENTA" "$RESET" "$MAGENTA" "$RESET"
        read -r resp
        case "$resp" in
            ""|K|k) action=keep ;;
            R|r)    action=reprompt ;;
            E|e)    action=edit ;;
            /help)  show_identity_help_screen ;;
            /skip)
                warn "Skipped. Existing secrets/gitconfig kept."
                action=keep
                ;;
            /exit)
                echo "  Exiting setup."
                exit 0
                ;;
            *) last_error="Type K, R, E, /help, /skip, or /exit." ;;
        esac
    done
else
    action=fresh
fi

if [ "$action" != "keep" ]; then
    default_name=""
    default_email=""
    pub_name=""
    pub_email=""

    detect_default_name
    detect_default_email

    if [ -n "${SSH_KEY_SOURCE:-}" ] && [ -f "${SSH_KEY_SOURCE}.pub" ]; then
        parse_pub_comment "${SSH_KEY_SOURCE}.pub"
        apply_pub_fallback
    fi

    if [ "$action" = "edit" ]; then
        default_name="$cur_name"
        default_email="$cur_email"
    fi

    skipped=""
    name=""
    email=""
    if name=$(prompt_field "Author name " "$default_name" validate_name); then
        :
    else
        skipped=1
    fi
    if [ -z "$skipped" ]; then
        if email=$(prompt_field "Author email" "$default_email" validate_email); then
            :
        else
            skipped=1
        fi
    fi

    if [ -n "$skipped" ]; then
        warn "Skipped. Commits inside the container will fail until secrets/gitconfig exists."
    else
        write_gitconfig "$name" "$email"
        ok "Wrote secrets/gitconfig (mode 0644)"
    fi
fi

# Migration nudge — reinforces Step 3's flipped default.
if docker image inspect ai-context:latest >/dev/null 2>&1; then
    hr
    warn "An ai-context:latest image already exists. If it was built before"
    warn "this wizard step shipped, its entrypoint won't apply secrets/gitconfig."
    warn "Rebuild it at the next step (default is now Y when gitconfig is present)."
fi

press_enter

# ── Step 3: Container image ──────────────────────────────────────────────────
clear_screen
screen_header 3 6 "Container image"

# ── UC22 — optional toolchain selection ──────────────────────────────────────
# Data-driven menu of optional toolchains baked into ai-context:latest. The
# selection persists in ./.ai-sandbox-toolchains (gitignored, via lib.sh) and is
# passed to `docker compose build` as build args. Today the only optional
# toolchain is "android"; add a row here + a build arg to offer more without
# reworking the plumbing (AC16).
info "Optional toolchains to bake into the image:"
hr

HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
    x86_64|amd64) HOST_IS_AMD64=1 ;;
    *)            HOST_IS_AMD64=0 ;;
esac

selected_android=0
toolchain_is_enabled android && selected_android=1

# Android testing — amd64 only (x86_64 system image; arm64 is a documented
# follow-up, AC15). On non-amd64 hosts we surface the limitation instead of
# offering a broken option or failing the wizard.
if [ "$HOST_IS_AMD64" -eq 1 ]; then
    if [ "$selected_android" -eq 1 ]; then
        read -r -p "  Include Android testing (JDK 21 + Android SDK + headless emulator)? [Y/n]: " a_resp
        if [[ "${a_resp:-}" =~ ^[Nn]$ ]]; then selected_android=0; else selected_android=1; fi
    else
        read -r -p "  Include Android testing (JDK 21 + Android SDK + headless emulator)? [y/N]: " a_resp
        if [[ "${a_resp:-}" =~ ^[Yy]$ ]]; then selected_android=1; else selected_android=0; fi
    fi
else
    warn "Android testing is amd64-only — not available on this $HOST_ARCH host (arm64 is a documented follow-up). Skipping."
    selected_android=0
fi

# Persist the selection and export build args for `docker compose build`.
toolchain_selection=()
[ "$selected_android" -eq 1 ] && toolchain_selection+=("android")
write_enabled_toolchains ${toolchain_selection[@]+"${toolchain_selection[@]}"}
export AI_SANDBOX_TOOLCHAIN_ANDROID="$selected_android"

if [ "$selected_android" -eq 1 ]; then
    ok "Android testing enabled — image will include JDK 21 + Android SDK (build-tools 36.0.0, android-36)."
    warn "The Android image is larger (~+1.5 GB) and its first build is slower than the base image."
    # If an Android selection is active but the existing image lacks the
    # toolchain, a rebuild is required for it to take effect.
    if docker image inspect ai-context:latest >/dev/null 2>&1 && ! image_supports_android; then
        warn "The existing ai-context:latest image has NO Android toolchain — rebuild below to bake it in."
    fi
else
    ok "Base image only (no optional toolchains)."
fi
hr

if docker image inspect ai-context:latest >/dev/null 2>&1; then
    ok "Image ai-context:latest already built"
    hr
    if [ -f secrets/gitconfig ]; then
        info "Recommended: rebuild so the image's entrypoint applies secrets/gitconfig."
        info "(Required if you cloned this repo before the git-identity step shipped.)"
        hr
        read -r -p "  Rebuild? [Y/n]: " rebuild
        if [[ ! "${rebuild:-}" =~ ^[Nn]$ ]]; then
            hr
            docker compose build
            ok "Rebuilt"
        fi
    else
        read -r -p "  Rebuild? [y/N]: " rebuild
        if [[ "${rebuild:-}" =~ ^[Yy]$ ]]; then
            hr
            docker compose build
            ok "Rebuilt"
        fi
    fi
else
    info "Building image (first time, takes a minute)..."
    hr
    docker compose build
    ok "Built"
fi

# ── Step 4: gh authentication ────────────────────────────────────────────────
clear_screen
screen_header 4 6 "GitHub CLI (gh) authentication — optional"
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

# ── Step 5: Claude Code first-run ────────────────────────────────────────────
clear_screen
screen_header 5 6 "Claude Code first-run"

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

# ── Step 6: Initialize counter & spawn first session ────────────────────────
clear_screen
screen_header 6 6 "Starting first session"

# Ensure the monotonic session counter exists. The file holds the last issued
# N (increment-before-use): initializing it to 0 makes the first ./spawn.sh
# issue ai-sandbox-1, per AC4.
COUNTER_FILE="./.ai-sandbox-counter"
if [ ! -f "$COUNTER_FILE" ]; then
    printf "0\n" > "$COUNTER_FILE"
    ok "Initialized $COUNTER_FILE (next spawn → ai-sandbox-1)"
fi

# Legacy migration: take down the old unnumbered `ai-sandbox` Compose project
# from before multi-session was introduced. Silent no-op if it isn't there.
# We use the text output here so we don't depend on jq being installed yet.
if docker compose ls -a 2>/dev/null \
    | awk 'NR>1 {print $1}' \
    | grep -qx 'ai-sandbox'; then
    info "Found legacy unnumbered ai-sandbox project — bringing it down."
    docker compose -p ai-sandbox down --remove-orphans 2>/dev/null || true
    ok "Legacy project removed"
fi

# Idempotency: skip spawn if ANY ai-sandbox-* project already exists. We
# parse `docker compose ls -a` text output here rather than the JSON form
# so the check works even when `jq` is not yet installed on the host.
EXISTING_NAME="$(docker compose ls -a 2>/dev/null \
    | awk 'NR>1 && $1 ~ /^ai-sandbox-[0-9]+$/ {print $1; exit}')"
if [ -n "$EXISTING_NAME" ]; then
    ok "$EXISTING_NAME already exists — skipping spawn"
else
    info "Spawning ai-sandbox-1..."
    hr
    ./spawn.sh --non-interactive
fi
press_enter

# ── Done ─────────────────────────────────────────────────────────────────────
clear_screen
printf "%s%s=== Done! ===%s\n\n" "$BOLD" "$GREEN" "$RESET"
printf "  Attach to Claude:        %s./attach.sh%s\n"  "$MAGENTA" "$RESET"
printf "  Spawn another session:   %s./spawn.sh%s\n"   "$MAGENTA" "$RESET"
printf "  Clean a session:         %s./clean.sh%s\n"   "$MAGENTA" "$RESET"
printf "  Re-run this setup:       %s./setup.sh%s   (idempotent — safe any time)\n" "$MAGENTA" "$RESET"
hr
