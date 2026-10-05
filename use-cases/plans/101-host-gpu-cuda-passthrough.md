---
plan_for: use-cases/101-host-gpu-cuda-passthrough.md
work_branch: feat/uc-101-host-gpu-cuda-passthrough
team: ai-sandbox-uc-101
approved: 2026-10-05
---

# Implementation plan — UC-101 Host NVIDIA GPU (CUDA) passthrough

Analyst↔challenger loop complete: **challenger APPROVED** (no Critical/Major; 5 minors
folded in). Profiles (`profile-java-server-architecture`, `profile-java-call-graph-tool`)
are Java/Spring-server profiles → **N/A** to this container/shell-layer work.

## Recommended scope — feasibility-spike / enablement increment

Ship the full host-wide passthrough **wiring** + the opt-in `aisandbox-gpu` capability +
graceful no-GPU degradation + kill switch + docs. The **nested rootless-DinD GPU proof
(AC3) is explicitly owner-must-verify-on-a-GPU-host** — shipped as a coherent enablement
seam with a manual runbook, NOT claimed "done." This env has no NVIDIA GPU, so
AC1(live)/AC2/AC3 cannot be auto-verified.

## Architecture — two decoupled layers (challenger-confirmed)

1. **Host-wide device/driver exposure** (AC1, AC3, AC9, AC10) — NOT a devtool (a
   `devtool_spawn_env` hook only fires for a *selected* capability, so it cannot satisfy
   "host-wide, no opt-in"). A new `inject_host_gpu_passthrough` in spawn.sh layers
   `docker-compose.gpu.yml` gated on host-GPU-detection + Linux + kill-switch. Uses **CDI**
   (`nvidia.com/gpu=all`): the NVIDIA Container Toolkit injects driver libs + `nvidia-smi` +
   device nodes automatically, so AC1 needs no per-session opt-in. CDI is the documented
   rootless/nested path (AC6).
2. **CUDA userspace** (AC2, AC4) — the opt-in `aisandbox-gpu` capability, cache-provisioned,
   absent until selected; non-selectors pay no cost.

## Files Affected

**Production (developer):**
- `devtools.d/gpu/manifest.sh` (new) — `ID="gpu"`, version-bearing `LABEL`, `DEPENDS_ON=()`,
  `APPLY_AT="session-spawn"`, `ARCH="amd64"` (CUDA toolkit x86_64-only; selector disables row
  on non-amd64, android AC11 precedent), trust-boundary `WARNING`. Hooks:
  `devtool_provision`→`aisandbox-gpu install`; `devtool_server_install`→surface host-prereq
  notes via existing `aisb_server_install_note`/`_reason` (no hard-gate). No compose-layering
  here (passthrough is host-wide).
- `container-bin/aisandbox-gpu` (new) — `install`/`doctor`/`env` mirroring `aisandbox-java`;
  installs CUDA userspace into `/workspace/environment-utilities/gpu/` cache; `env.sh` stitched
  via `aisb_stitch_profile`. `doctor` = graceful-degradation reporter (AC5): missing
  `/dev/nvidia*`/`libcuda`/CDI/kill-switch → clear actionable error naming the missing prereq,
  never a crash. **[minor 4]** nested-DinD CDI wiring must be order-independent/idempotent —
  write a drop-in the inner `aisandbox-dind` reads on start, NOT mutate a running dockerd (gpu
  has no `DEPENDS_ON` ordering vs dind).
- `docker-compose.gpu.yml` (new) — CDI device passthrough + `AI_SANDBOX_GPU=1`/`NVIDIA_*` env;
  composes additively with dind+kvm overrides. **[minor 5]** exact CDI Compose syntax
  (`devices: ["nvidia.com/gpu=all"]` vs `gpus:` vs daemon `features: cdi: true`) is a
  developer-verify item pinned against brief's `docker_compose: v2+`; live validity is owner-GPU-host.
- `devtools.d/lib/versions.sh` (modify) — add `AISB_CUDA_VERSION` (single source for LABEL + install).
- `lib.sh` (modify) — `host_gpu_available()` (Linux-gated; driver present AND CDI spec
  resolvable), `host_gpu_disabled()` (kill switch: env `AI_SANDBOX_GPU_DISABLED=1` or persisted
  sentinel), `inject_host_gpu_passthrough()` (Linux→kill-switch→detect→layer override + env;
  else no-op → byte-identical to today).
- `spawn.sh` (modify) — call `inject_host_gpu_passthrough` before `ai_sandbox_compose up`;
  `info` logging for GPU-on / no-GPU / disabled-by-switch, **[minor 3]** AND a distinct line for
  driver-present-but-no-CDI-spec ("GPU/driver detected but no CDI spec — run `nvidia-ctk cdi
  generate`") so that state isn't a silent no-op (tightens AC5/AC7).
- `setup.sh` (modify) — host GPU prereq detection + operator docs (driver range, Container
  Toolkit, `nvidia-ctk cdi generate`, Linux-only) + kill-switch reconfigure toggle (AC7, AC10).
- `SandboxDockerfile` (modify) — COPY+chmod `container-bin/aisandbox-gpu` (KB; `devtools.d/gpu/`
  rides existing `COPY devtools.d/`). **No CUDA packages** (AC4).
- `entrypoint.sh` (modify, minimal) — generic `provision_capability` + `load_devtool_env` already
  pick up `gpu`/`env.sh`; confirm best-effort nested wiring stays order-independent (minor 4).
- `README.md` (modify) + `docs/gpu.md` (new) — prereqs, prominent trust-boundary tradeoff
  (host-wide exposure, AC10 rationale), multi-session sharing/contention (shared/time-sliced; VRAM
  not enforced; MIG/caps out of scope v1 — AC8), kill-switch usage, and the **manual GPU-host
  verification runbook for AC1/AC2/AC3**.

**Test (qa):**
- `server/src/test/java/com/aisandbox/server/sessions/HostScriptGpuPassthroughTest.java` (new) —
  **primary CI gate** (auto-run under `:server:test`, mirrors `HostScriptComposeEnvTest`). Fake
  `nvidia-smi`/CDI-spec/`docker` shims: assert `spawn.sh` layers `docker-compose.gpu.yml`+env when
  host-GPU present & switch off (AC1 wiring); no override + byte-identical docker argv when no GPU
  (AC5) and when kill switch on (AC10); Linux-gated (AC9). **[minor 2]** add a
  two-back-to-back-spawn case (fake GPU present) asserting each layers the override independently →
  AC8 concurrency (no new cross-session shared mutable state/lock; safety inherited from today's
  independent-spawn model).
- `server/src/test/e2e/uc101-gpu-degradation-unit.sh` (new) — plain-bash complement (mirrors
  `uc27-devtools-selector-unit.sh`): manifest well-formed (catalog has `gpu`, version-bearing LABEL,
  `ARCH=amd64` gate, WARNING present), `host_gpu_available`/`host_gpu_disabled` logic,
  `aisandbox-gpu doctor` degradation message.
- **[minor 1]** AC4 gate asserts **absence of CUDA packages/layers** in the built image (text/layer
  approach like `SandboxDockerfileContractTest`), NOT byte-identical size — state explicitly so QA
  doesn't chase an impossible assertion. Wire into an actually-run `:server:test`/image job.
- `docker compose config` validity check for `docker-compose.gpu.yml`.

⚠️ **Note for QA:** `server/src/test/e2e/*.sh` harnesses (uc26/27/30/95) are **not auto-wired into
CI** — they're standalone complements. Primary CI-verifiable coverage MUST go through the
`:server:test` Java fake-shim path.

## AC → verification map

| AC | Satisfied by | Label |
|----|---|---|
| 1 nvidia-smi host-wide | CDI driver injection, no opt-in; wiring shim-tested | wiring **CI**; live **owner-GPU** |
| 2 CUDA framework in shell | `aisandbox-gpu` installs CUDA userspace | **owner-GPU** (install logic CI-testable) |
| 3 nested DinD `--gpus` | CDI into inner rootless dockerd (enablement seam) | **owner-GPU** — NOT claimed done |
| 4 userspace absent until selected / image unchanged | cache-provisioned; no CUDA in Dockerfile | **CI** (absence assertion) |
| 5 graceful no-GPU degrade + actionable error | no override (byte-identical) + `doctor` error + no-CDI info line | **CI** |
| 6 rootless/subuid compat | CDI declarative, no host-root; documented caveats | reasoning+docs **CI**; live **owner** |
| 7 host prereqs documented | setup.sh notes + docs/gpu.md + no-CDI hint | **CI** |
| 8 multi-session contention + no deadlock | no new shared state (concurrency inherited) + docs; 2-spawn test | **CI** |
| 9 Linux-only gate | `uname -s` gate in injector | **CI** |
| 10 host kill switch | `AI_SANDBOX_GPU_DISABLED` + persisted sentinel; spawn gate | **CI** |

## Risks
- **AC3 unprovable here** — ship as enablement + manual runbook; keep labeled owner-GPU-host (don't drift to "done").
- **AC4 intent** — absence-of-CUDA assertion, not byte-identical size (minor 1).
- **CDI prereq** — operator must run `nvidia-ctk cdi generate`; detection keys on CDI spec; the no-CDI info line (minor 3) prevents a silent no-op.
- **Nested wiring order** (minor 4) — must be idempotent/order-independent since gpu↔dind have no resolver ordering.
- **Trust-boundary widening (AC10 pitfall)** — host-wide exposure reaches every session; documented prominently + `WARNING` field; kill switch is the mitigation.

## Challenger verdict
**APPROVE** (no Critical/Major; 5 minors folded in). Verified against actual code: compose-override
machinery (`_aisb_append_compose_override`/`ai_sandbox_compose`), the KVM host-detect precedent
(`host_kvm_gid` + `docker-compose.kvm.yml`), and the two CI test patterns leaned on
(`HostScriptComposeEnvTest` fake-docker-shim argv test, `SandboxDockerfileContractTest` text-parse
contract). Honest CI-vs-owner-GPU verification split confirmed; no over-claiming.
