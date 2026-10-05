# Host GPU (NVIDIA CUDA) passthrough — operator guide (UC-101)

This document is the operator reference for exposing the **host's NVIDIA GPU** to
ai-sandbox sessions for CUDA workloads (ML/AI training, etc.), including inside
the session's **nested rootless Docker-in-Docker (DinD)**. It covers host
prerequisites, the trust-boundary tradeoff, the sharing/contention model, the
kill switch, and the **on-GPU-host verification runbook** for the criteria that
cannot be verified in CI.

> **Scope.** NVIDIA / CUDA only. **Linux hosts only** (UC-27) — macOS/Windows
> hosts have no GPU path and are unaffected.

---

## Architecture — two decoupled layers

GPU support is split into two independent layers. Understanding the split is the
key to the whole feature.

1. **Host-wide device + driver exposure** (not a devtool).
   On a GPU-capable Linux host, `spawn.sh` passes the GPU into **every** session
   — **no per-session opt-in**. This is the host-wide decision captured in the
   use case. It is implemented in `lib.sh` (`inject_host_gpu_passthrough`) +
   `docker-compose.gpu.yml`, driven by `spawn.sh` before `docker compose up`,
   and mirrors the existing KVM precedent (`host_kvm_gid` +
   `docker-compose.kvm.yml`).
   - Uses **CDI (Container Device Interface)**: the NVIDIA Container Toolkit
     generates a spec that the Docker daemon reads to inject the driver
     libraries, `nvidia-smi`, and the GPU device nodes into the container. CDI is
     the path that works with **rootless** Docker and the subuid model (UC-30) —
     it is declarative device injection, needing no privileged host-root runtime.
   - It is a **strict no-op** on any host that is not Linux, has no driver, has
     no CDI spec, or has the kill switch engaged. In those cases the `docker
     compose up` command line is **byte-identical** to a pre-UC-101 spawn.

2. **CUDA userspace** — the opt-in **`gpu`** devtool capability.
   The CUDA toolkit/libraries are **not baked into the base image**
   (`node:20-bookworm-slim`). They are provisioned on demand into the persisted
   `/workspace/environment-utilities/gpu/` cache by `aisandbox-gpu install` when
   the `gpu` capability is selected (via `./setup.sh --reconfigure`). Non-GPU
   users pay **no image-size cost** — the base image is unchanged.

Layer 1 makes the **device** reachable; layer 2 makes **CUDA code** able to use
it. You usually want both: host-wide exposure on the host, and the `gpu`
capability selected for sessions that run CUDA.

---

## Host prerequisites (operator)

On the GPU host, install and verify, in order:

1. **A supported NVIDIA driver.** Confirm with `nvidia-smi` on the host — it must
   list your GPU(s). Use a driver whose CUDA compatibility covers the userspace
   version the `gpu` capability installs (see "CUDA / driver compatibility"
   below).
2. **The NVIDIA Container Toolkit.** Install per NVIDIA's documentation
   (`nvidia-container-toolkit` package).
3. **Generate the CDI spec.** This is the step most people miss:
   ```bash
   sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml
   sudo nvidia-ctk cdi list        # should list nvidia.com/gpu=... devices
   ```
   ai-sandbox detects a GPU host by finding a CDI spec declaring
   `nvidia.com/gpu` under `/etc/cdi` or `/var/run/cdi`. **`/etc/cdi` is the
   expected location** — it is what `docker-compose.gpu.yml` bind-mounts into the
   session for the nested-DinD path. Regenerate the spec after a driver upgrade.

Check host readiness at any time:
```bash
./setup.sh --gpu-status
```
This reports the driver, the CDI spec location, the kill-switch state, and
whether sessions will get a GPU.

If the **driver is present but no CDI spec is found**, `spawn.sh` prints an
explicit warning naming the one missing step (it does **not** silently pass no
GPU):
```
gpu : NVIDIA driver present but no CDI spec found — GPU NOT passed through.
      Generate one on the host: sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml
```

---

## Trust-boundary tradeoff (read this)

Host-wide exposure is a **deliberate widening** of the project's "the container
is the trust boundary" policy:

- The NVIDIA **device nodes and driver ioctls become reachable from _every_
  session**, not only the sessions that need the GPU.
- **GPU contention becomes automatic** across all concurrent sessions on the
  host.

This is the accepted host-wide decision from the use case. The mitigation is the
**host kill switch** (below): an operator who does not want the widened boundary
on every session can disable GPU exposure entirely, even on a GPU-capable host.
Selecting the `gpu` *capability* does **not** widen exposure further — it only
adds the CUDA userspace so a session can *use* the already-exposed device.

---

## Multiple sessions / contention

All concurrent sessions on a host **share the one physical GPU**:

- Access is **shared / time-sliced** by the driver. Multiple sessions can submit
  work simultaneously; the GPU scheduler interleaves it.
- **VRAM is not partitioned or capped.** One session can exhaust GPU memory and
  cause CUDA out-of-memory errors in others. There is no per-session VRAM quota.
- **MIG (Multi-Instance GPU) and per-session VRAM caps are out of scope for v1.**
  They are documented here as a known limitation, not enforced.
- The management server does **not** serialize or lock GPU access — sessions
  spawn independently (each layers the passthrough override independently; there
  is no shared mutable GPU state), so there is no GPU-related deadlock path. Heavy
  concurrent GPU use will contend for the device, not hang the server.

If you need hard isolation between tenants' GPU workloads, run them on separate
hosts or separate physical GPUs — v1 does not provide in-host GPU partitioning.

---

## Kill switch (host-level disable)

Disable GPU exposure entirely, even on a GPU-capable host:

```bash
./setup.sh --gpu-disable    # persist a sentinel; NEW sessions get no GPU
./setup.sh --gpu-enable     # remove the sentinel; re-enable passthrough
./setup.sh --gpu-status     # show current state
```

- `--gpu-disable` writes a per-machine sentinel (`.ai-sandbox-gpu-disabled` in
  developer mode, or beside the per-install state under the management server).
  It is gitignored and never committed.
- A one-off / CI override is also available via the environment:
  `AI_SANDBOX_GPU_DISABLED=1 ./spawn.sh …`. The env override is independent of,
  and higher-priority than, the sentinel.
- The switch takes effect for **new** sessions only; existing sessions keep the
  GPU they were spawned with until respawned.

When the switch is engaged, the spawn path is a strict no-op — sessions are
byte/behaviour-identical to a non-GPU host.

---

## CUDA / driver compatibility

The `gpu` capability installs a pinned CUDA **userspace** version
(`AISB_CUDA_VERSION`, default `12.4`, in `devtools.d/lib/versions.sh`). It must
be compatible with the host's installed **driver** per NVIDIA's CUDA/driver
compatibility matrix. If your host driver is older than the default CUDA
userspace requires, either upgrade the driver or pin a compatible CUDA version:

```bash
# e.g. on the host before building/spawning
export AISB_CUDA_VERSION=12.2
```

The install uses the CUDA pip wheels (`nvidia-cuda-runtime-cuXX`,
`nvidia-cuda-nvcc-cuXX`, …), installed rootless into the session cache — override
the exact set with `AISB_CUDA_WHEELS` if your matrix needs it. Frameworks such as
PyTorch ship their own bundled CUDA runtime; the capability provides a resolvable
`CUDA_HOME` + `nvcc` + the core CUDA libraries on `LD_LIBRARY_PATH`.

---

## Rootless-DinD compatibility (UC-30)

GPU exposure is compatible with the rootless-DinD subuid model:

- The session container does **not** run as host-root — CDI injects devices
  declaratively, so no privileged runtime is required on the host daemon.
- For the **nested** path (a `docker run` inside the session's rootless DinD),
  `docker-compose.gpu.yml` bind-mounts the host CDI spec (`/etc/cdi`, read-only)
  into the session, and `aisandbox-dind` points the inner rootless `dockerd` at
  it on start (`--cdi-spec-dir /etc/cdi`) when `AI_SANDBOX_GPU=1`. The wiring is
  **order-independent** (the `AI_SANDBOX_GPU` env is set at container creation,
  before any devtool provisioning) and **idempotent** (applied as a fresh dockerd
  start argument; a running daemon is never mutated).
- Known limitation: the nested path uses CDI (`docker run --device
  nvidia.com/gpu=all …`), **not** the `--gpus` flag — `--gpus` needs the
  `nvidia-container-runtime` shim, which is not installed in the rootless inner
  daemon. Use `--device nvidia.com/gpu=all` for nested GPU containers.

---

## Verification

### What CI verifies (this environment has no GPU)

The dev/CI environment has **no NVIDIA GPU**, so the live GPU criteria cannot be
verified automatically. CI **does** verify:

- **Graceful no-GPU degradation (AC5):** no override layered + byte-identical
  docker argv on a host with no GPU; `aisandbox-gpu doctor` names the missing
  prerequisite.
- **Kill switch (AC10):** env + sentinel both disable passthrough; no override.
- **Linux-only gate (AC9).**
- **Capability absence (AC4):** no CUDA packages/layers in the base image.
- **Host prerequisites documented (AC7):** this file + `setup.sh`.
- Config/script validity (`docker compose config`, `bash -n`).

### Manual on-GPU-host runbook (AC1 / AC2 / AC3)

Run these on a **real NVIDIA GPU host** after completing the prerequisites above.
These are the **owner-verified** criteria.

**Setup (once):**
```bash
# On the GPU host:
nvidia-smi                                                   # host sees the GPU
sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml   # generate CDI spec
./setup.sh --gpu-status                                      # expect: driver present, CDI found, switch OFF
./setup.sh --reconfigure                                    # select the `gpu` capability
./spawn.sh                                                   # spawn a session; log should read "passthrough ON"
./attach.sh                                                  # attach to the session
```

**AC1 — host-wide `nvidia-smi` (no per-session opt-in).**
Inside the session shell (the `gpu` capability need NOT be selected for this —
AC1 is the host-wide device layer):
```bash
nvidia-smi          # must succeed and list the host GPU(s)
```

**AC2 — CUDA framework executes GPU compute.**
Inside a session with the `gpu` capability selected:
```bash
aisandbox-gpu doctor   # expect all ✓ (GPU usable)
# Example with a CUDA framework (install your framework of choice first):
python3 -c "import torch; assert torch.cuda.is_available(); \
            x=torch.rand(1000,1000,device='cuda'); print((x@x).sum().item())"
# …or any CUDA sample / tensor op that runs on the device.
```

**AC3 — nested rootless DinD GPU.**
Inside the session, with **both** the `dind` and `gpu` capabilities selected:
```bash
aisandbox-dind doctor                 # daemon up; log should note CDI spec dir /etc/cdi
docker run --rm --device nvidia.com/gpu=all nvidia/cuda:12.4.0-base-ubuntu22.04 nvidia-smi
# must succeed and list the host GPU from inside the nested container.
```
> Note: the nested path uses `--device nvidia.com/gpu=all` (CDI), not `--gpus`
> (see "Rootless-DinD compatibility" above).

**Teardown:**
```bash
exit                       # leave the session
./clean.sh                 # or remove the session via the management API/app
./setup.sh --gpu-disable   # optional: disable host-wide exposure afterwards
```

If any step fails, run `aisandbox-gpu doctor` (session shell) and
`./setup.sh --gpu-status` (host) — between them they name the exact missing
prerequisite.
