# shellcheck shell=bash
# Java 21 capability manifest (UC-27). Standalone JDK; the Android capability
# DEPENDS_ON it. See devtools.d/dind/manifest.sh for the sourcing contract.

# shellcheck source=../lib/versions.sh disable=SC1091
. "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")/.." && pwd)/lib/versions.sh"

ID="java"
LABEL="Java ${AISB_JAVA_MAJOR} (${AISB_JAVA_FLAVOR} ${AISB_JAVA_BUILD})"
DEPENDS_ON=()
APPLY_AT="session-spawn"
ARCH="any"
WARNING=""

# Host-side (spawn.sh): no compose override needed — the JDK needs no extra
# devices or relaxed profiles. The generic AI_SANDBOX_DEVTOOLS list (exported by
# inject_devtool_spawn_env) is what tells the container to provision it; this
# hook only flags the per-capability env var for any override that reads it.
devtool_spawn_env() {
    export AI_SANDBOX_DEVTOOL_JAVA=1
}

# In-container (entrypoint.sh): install the Temurin JDK into the per-session
# cache and wire JAVA_HOME + PATH for both login and non-login shells (AC#10).
# The aisandbox-java helper sources devtools.d/lib/versions.sh for the exact
# build, so the install matches the LABEL above.
devtool_provision() {
    /usr/local/bin/aisandbox-java install || return 1
}
