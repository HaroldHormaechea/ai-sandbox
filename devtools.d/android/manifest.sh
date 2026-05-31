# shellcheck shell=bash
# Android SDK capability manifest (UC-27). DEPENDS_ON java; amd64-only (x86_64
# system image / emulator — AC#11). On a non-amd64 host the selector shows this
# row disabled with a note rather than offering-then-breaking it. See
# devtools.d/dind/manifest.sh for the sourcing contract.

# shellcheck source=../lib/versions.sh disable=SC1091
. "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")/.." && pwd)/lib/versions.sh"

ID="android"
LABEL="Android SDK — platform-tools / build-tools ${AISB_ANDROID_BUILD_TOOLS_VERSION} / ${AISB_ANDROID_PLATFORM} (x86_64 emulator)"
DEPENDS_ON=("java")
APPLY_AT="session-spawn"
ARCH="amd64"
WARNING=""

# Host-side (spawn.sh): flag the capability and, when the host exposes hardware
# KVM, layer the KVM passthrough override so the emulator can boot an
# accelerated AVD. AI_SANDBOX_KVM_GID is consumed by docker-compose.kvm.yml's
# group_add (the runtime user must be in /dev/kvm's group to open it).
devtool_spawn_env() {
    export AI_SANDBOX_DEVTOOL_ANDROID=1
    if [ -e /dev/kvm ]; then
        export AI_SANDBOX_KVM_GID="$(host_kvm_gid)"
        _aisb_append_compose_override "docker-compose.kvm.yml"
    fi
}

# In-container (entrypoint.sh): install the Android SDK (cmdline-tools,
# platform-tools, build-tools, platform, system-image, emulator) into the
# per-session cache and wire ANDROID_HOME / ANDROID_SDK_ROOT + PATH (incl.
# build-tools/<ver>) for login and non-login shells (AC#10). Java must already
# be provisioned (DEPENDS_ON guarantees ordering via the resolver).
devtool_provision() {
    /usr/local/bin/aisandbox-android install || return 1
}
