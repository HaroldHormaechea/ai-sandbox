#!/bin/sh
# ai-sandbox-updater.sh — UC-84 root-owned, PARAMETER-FREE self-updater.
#
# This is the SINGLE privileged executable the (non-root) ai-sandbox-server can
# cause to run, and it accepts NO parameters whatsoever. It is launched by
# ai-sandbox-updater.service (Type=oneshot, root) which is in turn activated by
# ai-sandbox-updater.path watching the server-writable trigger dir. The server's
# only capability is "make that dir non-empty"; it passes this script no
# version, no path, no flags, and no env.
#
# SECURITY MODEL (AC8/AC11/AC12 — DO NOT WEAKEN):
#   * Consumes ZERO external input. It ignores "$@", reads no env-borne config,
#     and — crucially — NEVER reads the name or content of anything in the
#     trigger dir. It clears that dir BLINDLY and then derives its own target.
#   * Self-determines the target: the latest server-v* GitHub release of the
#     hardcoded public repo, downloaded over HTTPS (system CA trust), installed
#     with dpkg, then the service is (idempotently) enabled and restarted.
#   * No GitHub credentials, ever (AC13): no token, no Authorization header, no
#     gh auth — the lookup and the download are fully unauthenticated against
#     the public repo.
#   * A compromised server can therefore at most request "update to the latest
#     published release" — never an arbitrary package, version, path, or command.
#
# TRUST LIMITATION (explicit, accepted): there is no detached-signature chain on
# the .deb. Trust derives from (a) HTTPS + system CA validation of the GitHub /
# release-asset host and (b) the hardcoded repo + asset-name pattern. dpkg-deb
# integrity + an explicit package-name check are the only artifact-level guards.
set -eu

# ── Hardcoded, non-overridable target identity (AC11/AC15) ────────────────────
REPO="HaroldHormaechea/ai-sandbox"
TRACK="server-v"              # only this release track; android-v* is ignored
ARCH="amd64"                  # release ships ..._amd64.deb
PKG="ai-sandbox-server"
SERVICE="ai-sandbox-server.service"
TRIGGER_DIR="/var/lib/ai-sandbox-server/update-trigger"
API_URL="https://api.github.com/repos/${REPO}/releases?per_page=100"

log() { echo "ai-sandbox-updater: $*"; }
fail() { echo "ai-sandbox-updater: ERROR: $*" >&2; exit 1; }

# Deliberately ignore any arguments — the trigger is parameter-free and so are we.
if [ "$#" -ne 0 ]; then
    log "ignoring $# unexpected argument(s) — this updater takes none"
fi

# 1. Clear the trigger dir BLINDLY. We never read a filename or byte from it;
#    find -delete removes every entry (incl. dotfiles) without interpreting any.
#    Done first so the empty→non-empty .path watch re-arms for the next request.
if [ -d "$TRIGGER_DIR" ]; then
    find "$TRIGGER_DIR" -mindepth 1 -delete 2>/dev/null || true
    log "cleared trigger dir $TRIGGER_DIR"
fi

# 2. Self-determine the latest server-v* release + its _amd64.deb asset URL.
#    We parse ONLY the asset download URLs, which themselves encode the tag:
#      https://github.com/<repo>/releases/download/server-vX.Y.Z/..._amd64.deb
#    so no JSON tool (jq/python) is required — just curl + coreutils.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
json="$work/releases.json"

log "querying $API_URL (unauthenticated)"
if ! curl -fsSL \
        --connect-timeout 10 --max-time 60 \
        -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        -H "User-Agent: ai-sandbox-updater" \
        -o "$json" "$API_URL"; then
    fail "could not fetch the GitHub releases list (network/rate-limit)"
fi

# Extract every server-v*_<ARCH>.deb asset download URL, pair each with the
# version embedded in its /download/server-vX.Y.Z/ path segment, sort by version
# (sort -V), and take the newest.
best_line="$(
    grep -oE "https://[^\"]*/download/${TRACK}[^\"/]*/[^\"]*_${ARCH}\.deb" "$json" \
        | while IFS= read -r url; do
              ver="$(printf '%s\n' "$url" | sed -nE "s#.*/download/${TRACK}([^/]+)/.*#\1#p")"
              [ -n "$ver" ] && printf '%s\t%s\n' "$ver" "$url"
          done \
        | sort -V \
        | tail -n 1
)"

[ -n "$best_line" ] || fail "no ${TRACK}*_${ARCH}.deb asset found in the latest releases"
latest_ver="$(printf '%s' "$best_line" | cut -f1)"
deb_url="$(printf '%s' "$best_line" | cut -f2-)"
log "latest ${TRACK}${latest_ver} → $deb_url"

# 3. Skip if we are already at (or ahead of) the latest — avoids a needless
#    reinstall + restart if the watch fires with nothing newer to install.
installed_ver="$(dpkg-query -W -f='${Version}' "$PKG" 2>/dev/null || true)"
if [ -n "$installed_ver" ] && dpkg --compare-versions "$installed_ver" ge "$latest_ver"; then
    log "installed version $installed_ver is already >= $latest_ver — nothing to do"
    exit 0
fi

# 4. Download the .deb over HTTPS (system CA trust; redirects to the asset host
#    are followed by -L). No auth header (AC13).
deb="$work/${PKG}_${latest_ver}_${ARCH}.deb"
log "downloading $deb_url"
if ! curl -fsSL --connect-timeout 10 --max-time 600 -o "$deb" "$deb_url"; then
    fail "download failed: $deb_url"
fi

# 5. Verify the artifact: dpkg-deb integrity + the package name MUST be ours.
#    (No signature chain exists — accepted limitation, see header.)
if ! dpkg-deb --info "$deb" >/dev/null 2>&1; then
    fail "downloaded file is not a valid .deb (integrity check failed)"
fi
pkg_name="$(dpkg-deb -f "$deb" Package 2>/dev/null || true)"
if [ "$pkg_name" != "$PKG" ]; then
    fail "downloaded .deb is package '$pkg_name', expected '$PKG' — refusing to install"
fi
log "verified .deb (package=$pkg_name, version=$latest_ver)"

# 6. Install. The server's own prerm (on upgrade) stops + disables
#    ai-sandbox-server; that does NOT affect this independent oneshot.
export DEBIAN_FRONTEND=noninteractive
log "installing $deb"
if ! dpkg -i "$deb"; then
    # Best-effort dependency repair, then retry once.
    apt-get install -y -f >/dev/null 2>&1 || true
    dpkg -i "$deb" || fail "dpkg -i failed for $deb"
fi

# 7. Re-enable (idempotent) + restart the service. enable lives HERE — not in
#    postinst — so a plain operator install keeps its manual-enable choice while
#    a self-update guarantees the service comes back enabled after the upgrade's
#    prerm disabled it.
if command -v systemctl >/dev/null 2>&1; then
    systemctl enable "$SERVICE" >/dev/null 2>&1 || log "warning: could not enable $SERVICE"
    log "restarting $SERVICE"
    systemctl restart "$SERVICE" || fail "could not restart $SERVICE"
else
    log "warning: systemctl not present — installed $latest_ver but cannot restart $SERVICE"
fi

log "self-update to $latest_ver complete"
