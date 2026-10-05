# shellcheck shell=bash
# GPU (NVIDIA CUDA userspace) capability manifest — UC-101.
#
# OPT-IN capability: provisions the CUDA userspace (toolkit/libraries) into the
# per-session cache so a selected session can run CUDA workloads against the
# host GPU (AC#2). Absent until selected → non-GPU users pay no image-size or
# provisioning cost (AC#4). This is ONE of the two UC-101 layers; the OTHER —
# host-wide device/driver exposure — is NOT a devtool (a devtool hook only fires
# for a *selected* capability, which cannot satisfy "host-wide, no opt-in").
# That half lives in lib.sh inject_host_gpu_passthrough + docker-compose.gpu.yml
# and is driven by spawn.sh independently of this manifest. See docs/gpu.md.
#
# amd64-only: the CUDA toolkit is x86_64-only, so the selector shows this row
# disabled on a non-amd64 host rather than offering-then-breaking it (same
# precedent as the `android` capability, AC#11). See devtools.d/dind/manifest.sh
# for the sourcing contract.

# shellcheck source=../lib/versions.sh disable=SC1091
. "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")/.." && pwd)/lib/versions.sh"
# server-install.sh provides aisb_server_install_note / _reason used below.
# shellcheck source=../lib/server-install.sh disable=SC1091
. "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")/.." && pwd)/lib/server-install.sh"

ID="gpu"
LABEL="NVIDIA CUDA userspace ${AISB_CUDA_VERSION} (host GPU required; x86_64)"
DEPENDS_ON=()
APPLY_AT="session-spawn"
ARCH="amd64"
WARNING='Enabling the GPU capability installs the CUDA userspace into the session. GPU ACCESS ITSELF is host-wide on a GPU-capable Linux host (UC-101): the NVIDIA device nodes + driver ioctls are exposed to EVERY session, not only GPU users, which widens the "container is the trust boundary" surface for all sessions. This is the deliberate host-wide decision; the operator kill switch (AI_SANDBOX_GPU_DISABLED or ./setup.sh --gpu-disable) is the mitigation. Selecting this capability does NOT widen exposure further — it only adds the CUDA toolkit so the session can USE the already-exposed GPU. See docs/gpu.md for the trust-boundary tradeoff and sharing/contention model.'

# Host-side (spawn.sh): the GPU capability needs NO compose override of its own —
# device/driver passthrough is host-wide and handled by
# inject_host_gpu_passthrough (docker-compose.gpu.yml), independently of this
# capability. This hook only flags the per-capability env var (parity with java).
# It MUST NOT layer any override: doing so would couple the host-wide passthrough
# to this opt-in selection, breaking AC#1 ("no per-session opt-in").
devtool_spawn_env() {
    export AI_SANDBOX_DEVTOOL_GPU=1
}

# Server-side (setup.sh, UC-30): no hard host mutation needed for CUDA userspace
# (it caches into the session workspace at spawn). Surface the host prerequisite
# as a non-gating note so the operator sees it during setup WITHOUT blocking the
# selection (the capability degrades cleanly if the host has no GPU — AC#5). Uses
# the same note channel as dind. Returns 0 (never hard-gates).
devtool_server_install() {
    if ! host_gpu_available 2>/dev/null; then
        aisb_server_install_note "GPU: the CUDA userspace is selected, but this host has no usable NVIDIA GPU (driver + NVIDIA Container Toolkit + CDI spec required). The capability still installs into sessions, but CUDA will be unavailable until the host prerequisites are met and a session is respawned. Run \`./setup.sh --gpu-status\` to check, and see docs/gpu.md."
    else
        aisb_server_install_note "GPU: host NVIDIA GPU detected — CUDA userspace will provision into selecting sessions; passthrough is host-wide. Disable host-wide exposure any time with \`./setup.sh --gpu-disable\` (AC#10)."
    fi
}

# In-container (entrypoint.sh): install the CUDA userspace into the per-session
# cache and wire CUDA_HOME / PATH / LD_LIBRARY_PATH. Warn-and-continue on a
# non-GPU session (offline/degradation policy) — the aisandbox-gpu doctor is the
# compensating control that reports exactly which prerequisite is missing (AC#5).
devtool_provision() {
    /usr/local/bin/aisandbox-gpu install || return 1
}
