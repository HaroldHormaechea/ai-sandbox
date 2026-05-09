#!/bin/sh
set -e

PROJECT_DIR=/workspace/project-builder
KEY_FILE=/etc/secrets/git-key
TOKEN_FILE=/etc/secrets/gh-token

# Claude Code stores some global state (trusted folders, onboarding, default
# mode, theme) at ~/.claude.json — a file outside the ~/.claude/ directory we
# mount. Symlink it inside that mounted dir so the state persists across runs.
ln -sf "$HOME/.claude/.claude.json" "$HOME/.claude.json"

if [ -f "$KEY_FILE" ]; then
    mkdir -p "$HOME/.ssh"
    chmod 700 "$HOME/.ssh"
    cp "$KEY_FILE" "$HOME/.ssh/git-key"
    chmod 600 "$HOME/.ssh/git-key"
    cat > "$HOME/.ssh/config" <<EOF
Host *
  IdentityFile $HOME/.ssh/git-key
  IdentitiesOnly yes
  StrictHostKeyChecking accept-new
EOF
    chmod 600 "$HOME/.ssh/config"

    # Ensure `gh repo clone` and friends use SSH instead of HTTPS.
    gh config set git_protocol ssh >/dev/null 2>&1 || true
else
    echo "WARNING: no SSH key at $KEY_FILE — git over SSH will not work." >&2
fi

# Authenticate gh from the token file if present (used for API ops:
# gh issue list, gh pr create, etc. — cloning still goes over SSH).
if [ -f "$TOKEN_FILE" ] && ! gh auth status >/dev/null 2>&1; then
    gh auth login --with-token < "$TOKEN_FILE" || echo "WARNING: gh auth login failed." >&2
fi

if [ ! -d "$PROJECT_DIR/.git" ]; then
    echo "Cloning project-builder into $PROJECT_DIR..."
    git clone git@github.com:HaroldHormaechea/project-builder.git "$PROJECT_DIR" \
        || echo "WARNING: clone failed; continuing without project." >&2
fi

START_DIR="$PROJECT_DIR"
[ -d "$START_DIR" ] || START_DIR=/workspace

# If a command is passed (e.g. one-off setup runs), execute it in the project
# directory after bootstrap. Otherwise launch the persistent tmux session.
if [ "$#" -gt 0 ]; then
    cd "$START_DIR"
    exec "$@"
fi

# Wrap claude in a restart loop so /exit drops back into a fresh claude
# session inside the same tmux window — the session never dies.
tmux new-session -d -s main -c "$START_DIR" \
    'while true; do claude --dangerously-skip-permissions; sleep 1; done'

exec tail -f /dev/null
