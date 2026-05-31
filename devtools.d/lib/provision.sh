# shellcheck shell=bash
# Shared helpers for the UC-27 in-container provisioning scripts
# (aisandbox-java / aisandbox-android). Sourced inside the session at
# /opt/ai-sandbox/devtools.d/lib/provision.sh. No side effects — functions only.

# aisb_prov_arch → normalize `uname -m` to amd64|arm64|<raw>.
aisb_prov_arch() {
    case "$(uname -m)" in
        x86_64|amd64)  printf 'amd64' ;;
        aarch64|arm64) printf 'arm64' ;;
        *)             uname -m ;;
    esac
}

# aisb_stitch_profile ENV_FILE MARKER → make LOGIN shells pick up ENV_FILE by
# adding a guarded `. ENV_FILE` to ~/.profile and ~/.bashrc (idempotent via the
# sentinel MARKER). This is the login-shell half of the AC#10 PATH guarantee
# (`sh -lc` resets PATH via /etc/profile, so the toolchain bins must be re-added
# there); the entrypoint handles non-login (`sh -c`) inheritance + BASH_ENV.
aisb_stitch_profile() {
    local env_file="$1" marker="$2" target
    for target in "$HOME/.profile" "$HOME/.bashrc"; do
        [ -f "$target" ] || : > "$target"
        if ! grep -qF "$marker" "$target" 2>/dev/null; then
            printf '\n%s\n[ -r %s ] && . %s\n' "$marker" "$env_file" "$env_file" >> "$target"
        fi
    done
}
