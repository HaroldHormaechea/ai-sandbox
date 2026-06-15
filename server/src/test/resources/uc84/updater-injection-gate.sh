#!/bin/sh
# UC-84 AC12 — adversarial parameter-injection gate for ai-sandbox-updater.sh.
#
# This harness PROVES that the single privileged updater consumes ZERO
# attacker-controlled input. It runs the REAL updater under a hermetic sandbox:
#
#   * PATH-early SHIMS for every privileged tool (curl, dpkg, dpkg-deb,
#     dpkg-query, systemctl, apt-get) that RECORD their argv to a log and fake
#     success. A fake "GitHub" responder (the curl shim) returns a known latest
#     server-v9.9.9 release + its _amd64.deb asset URL.
#   * ADVERSARIAL injection through every channel the script could conceivably
#     read: positional args (malicious filenames, fake versions, fake URLs,
#     shell-metacharacter payloads), and poisoned ENV vars (REPO/PKG/TRACK/ARCH/
#     deb_url/latest_ver/API_URL/GITHUB_TOKEN + a $(touch CANARY) payload). When
#     the canonical trigger dir is writable (root/CI), also seeds adversarial
#     file NAMES + CONTENTS into it.
#
# It then ASSERTS the updater acted ONLY on its own derived latest-release
# target (server-v9.9.9, package ai-sandbox-server), attached no Authorization,
# and fired no injected payload (canary never created). The test FAILS if any
# injected input altered the package installed, the version targeted, the
# command run, or smuggled a credential.
#
# Usage: updater-injection-gate.sh <path-to-ai-sandbox-updater.sh>
set -eu

UPDATER="${1:?usage: updater-injection-gate.sh <updater.sh>}"
[ -f "$UPDATER" ] || { echo "GATE-FAIL: updater not found at $UPDATER" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SHIM="$WORK/shim"
REC="$WORK/record.log"
CANARY="$WORK/PWNED-CANARY"
mkdir -p "$SHIM"
: > "$REC"

# The legit latest the (trusted) GitHub responder advertises. Everything the
# updater does MUST derive from THIS, never from the injected payloads below.
LEGIT_VER="9.9.9"
LEGIT_URL="https://github.com/HaroldHormaechea/ai-sandbox/releases/download/server-v${LEGIT_VER}/ai-sandbox-server_${LEGIT_VER}_amd64.deb"

# ── PATH-early shims ─────────────────────────────────────────────────────────
# curl: record argv; serve canned releases JSON for the API, a fake .deb for the
# asset download. Detects the output file via -o.
cat > "$SHIM/curl" <<SHIM_EOF
#!/bin/sh
echo "CURL \$*" >> "$REC"
out=""
url=""
prev=""
for a in "\$@"; do
    if [ "\$prev" = "-o" ]; then out="\$a"; fi
    case "\$a" in http*://*) url="\$a";; esac
    prev="\$a"
done
case "\$url" in
    *releases*)
        # Trusted "GitHub" releases list: legit latest + lower + android decoys.
        cat > "\$out" <<'JSON'
[
  {"tag_name":"server-v0.0.1","browser_download_url":"https://github.com/HaroldHormaechea/ai-sandbox/releases/download/server-v0.0.1/ai-sandbox-server_0.0.1_amd64.deb"},
  {"tag_name":"android-v100.0.0","browser_download_url":"https://github.com/HaroldHormaechea/ai-sandbox/releases/download/android-v100.0.0/ai-sandbox-android_100.0.0_amd64.deb"},
  {"tag_name":"server-v9.9.9","browser_download_url":"LEGIT_URL_PLACEHOLDER"}
]
JSON
        sed -i "s#LEGIT_URL_PLACEHOLDER#$LEGIT_URL#" "\$out"
        ;;
    *_amd64.deb)
        printf 'fake-deb-bytes' > "\$out"
        ;;
    *)
        printf '' > "\${out:-/dev/null}"
        ;;
esac
exit 0
SHIM_EOF

# dpkg: --compare-versions (unused here) -> false; -i -> record + success.
cat > "$SHIM/dpkg" <<SHIM_EOF
#!/bin/sh
echo "DPKG \$*" >> "$REC"
case "\$1" in
    --compare-versions) exit 1 ;;   # "installed >= latest" is false -> proceed
    -i) exit 0 ;;
    *) exit 0 ;;
esac
SHIM_EOF

# dpkg-deb: --info -> valid; -f <deb> Package -> the EXPECTED package name.
cat > "$SHIM/dpkg-deb" <<SHIM_EOF
#!/bin/sh
echo "DPKGDEB \$*" >> "$REC"
case "\$*" in
    *"-f"*"Package"*) echo "ai-sandbox-server" ;;
esac
exit 0
SHIM_EOF

# dpkg-query: report "not installed" (empty + non-zero) so the updater proceeds.
cat > "$SHIM/dpkg-query" <<SHIM_EOF
#!/bin/sh
echo "DPKGQUERY \$*" >> "$REC"
exit 1
SHIM_EOF

cat > "$SHIM/systemctl" <<SHIM_EOF
#!/bin/sh
echo "SYSTEMCTL \$*" >> "$REC"
exit 0
SHIM_EOF

cat > "$SHIM/apt-get" <<SHIM_EOF
#!/bin/sh
echo "APT \$*" >> "$REC"
exit 0
SHIM_EOF

chmod +x "$SHIM"/*

# ── Optionally seed the canonical trigger dir with adversarial files ─────────
# The updater clears it BLINDLY (find -delete) and never reads a name/byte, so
# these must be inert. Only seedable when running privileged (root/CI).
TRIGGER_DIR="/var/lib/ai-sandbox-server/update-trigger"
SEEDED_TRIGGER=0
if mkdir -p "$TRIGGER_DIR" 2>/dev/null && [ -w "$TRIGGER_DIR" ]; then
    : > "$TRIGGER_DIR/; rm -rf canary" 2>/dev/null || true
    : > "$TRIGGER_DIR/\$(touch $CANARY)" 2>/dev/null || true
    : > "$TRIGGER_DIR/--privileged" 2>/dev/null || true
    printf 'server-v100.0.0 https://evil.example/payload_amd64.deb ; touch %s\n' "$CANARY" \
        > "$TRIGGER_DIR/update.requested" 2>/dev/null || true
    SEEDED_TRIGGER=1
fi

# ── Run the REAL updater under maximal injection ─────────────────────────────
# Poisoned ENV (the script reassigns REPO/TRACK/PKG/ARCH itself, deriving its
# own target — these must NOT win) + a command-substitution env payload, plus
# adversarial POSITIONAL args (filenames, metacharacters, fake version/URL).
set +e
PATH="$SHIM:$PATH" \
REPO="evil/repo" \
TRACK="android-v" \
PKG="totally-evil-package" \
ARCH="arm64" \
SERVICE="evil.service" \
API_URL="https://evil.example/api" \
deb_url="https://evil.example/payload_amd64.deb" \
latest_ver="100.0.0" \
GITHUB_TOKEN="ghp_attackerinjectedsupersecrettoken" \
GH_TOKEN="ghp_attackerinjectedsupersecrettoken" \
EVIL="\$(touch $CANARY)" \
    "$UPDATER" \
        "; touch $CANARY" \
        '--privileged' \
        'server-v100.0.0' \
        'https://evil.example/payload_amd64.deb' \
        '$(touch '"$CANARY"')' \
        > "$WORK/stdout.log" 2>&1
RC=$?
set -e

echo "── updater output ──"
cat "$WORK/stdout.log"
echo "── recorded privileged commands ──"
cat "$REC"
echo "────────────────────"

fail() { echo "GATE-FAIL: $*" >&2; exit 1; }

# 1. The updater completed successfully on its self-determined target.
[ "$RC" -eq 0 ] || fail "updater exited $RC (expected 0)"

# 2. The CANARY command-substitution payload NEVER fired.
[ ! -e "$CANARY" ] || fail "injection canary fired — a payload was evaluated!"

# 3. No credential was ever attached on the wire.
if grep -qi "authorization" "$REC"; then fail "an Authorization header was sent (AC13 violation)"; fi
if grep -q "ghp_attackerinjected" "$REC"; then fail "an injected token leaked onto the wire"; fi
if grep -qi "bearer" "$REC"; then fail "a bearer credential was sent"; fi

# 4. The download + install targeted ONLY the legit derived latest (9.9.9),
#    never the injected 100.0.0 / evil URL / evil package.
grep -qF "$LEGIT_URL" "$REC" || fail "the legit server-v9.9.9 asset URL was not downloaded"
grep -q "DPKG -i .*ai-sandbox-server_${LEGIT_VER}_amd64.deb" "$REC" \
    || fail "dpkg -i did not install the derived ai-sandbox-server_${LEGIT_VER} package"

# 5. None of the injected values appear anywhere in what actually ran.
if grep -q "evil.example" "$REC"; then fail "an injected evil URL reached a privileged command"; fi
if grep -q "100.0.0" "$REC"; then fail "the injected version 100.0.0 was targeted"; fi
if grep -q "totally-evil-package" "$REC"; then fail "the injected package name was used"; fi
if grep -q "android-v" "$REC"; then fail "the injected android-v track was followed"; fi

# 6. The service that was (idempotently) enabled + restarted is the REAL one,
#    never the injected SERVICE=evil.service.
grep -q "SYSTEMCTL restart ai-sandbox-server.service" "$REC" \
    || fail "the real ai-sandbox-server.service was not restarted"
if grep -q "evil.service" "$REC"; then fail "the injected SERVICE name was restarted"; fi

# 7. If we managed to seed the trigger dir, the updater cleared it blindly and
#    consumed no name/content from it (it is now empty, and nothing leaked).
if [ "$SEEDED_TRIGGER" -eq 1 ]; then
    remaining="$(find "$TRIGGER_DIR" -mindepth 1 2>/dev/null | wc -l | tr -d ' ')"
    [ "$remaining" = "0" ] || fail "trigger dir was not cleared blindly ($remaining entries remain)"
    rmdir "$TRIGGER_DIR" 2>/dev/null || true
fi

echo "GATE-PASS: updater consumed zero injected input — acted only on derived server-v${LEGIT_VER}."
exit 0
