# Use Case 101: Host NVIDIA GPU (CUDA) passthrough into sandboxes

## Summary

Enable ai-sandbox per-session sandboxes to use the host's **NVIDIA** GPU for CUDA-accelerated workloads (e.g. ML/AI model training) inside a session — including inside the **nested rootless Docker-in-Docker (DinD)** containers a user launches within the session. On a Linux host (UC-27) that has the NVIDIA driver and the NVIDIA Container Toolkit installed, the GPU is exposed to **all sessions by default (host-wide)**: GPU devices pass through the per-session Compose container *and* into the session's nested DinD, so both `nvidia-smi`/CUDA in the session shell and `docker run --gpus …` inside the session work against the host GPU. The **CUDA userspace (toolkit/libraries) is not baked into the base image** (`node:20-bookworm-slim`); it is an **opt-in `aisandbox-gpu` dev-tools capability** (new `devtools.d/` entry + `container-bin/aisandbox-gpu` helper, mirroring the existing `aisandbox-{dind,java,android,emulator}` capabilities) installed on demand, so non-GPU users pay no image-size cost. The feature must degrade cleanly on hosts without a GPU/driver/toolkit, document host prerequisites, address GPU sharing/contention across concurrent host-wide sessions, and remain compatible with the rootless-DinD subuid model (UC-30). It is Linux-only (UC-27).

## Acceptance Criteria

1. On a host with a supported NVIDIA driver + NVIDIA Container Toolkit, `nvidia-smi` run inside a session container succeeds and lists the host GPU(s) — with no per-session opt-in step (host-wide exposure).
2. After the `aisandbox-gpu` dev-tools capability is selected for a session, a CUDA framework (e.g. `python -c "import torch; assert torch.cuda.is_available()"` or a CUDA sample) executes a trivial GPU compute (e.g. a tensor op) on the host GPU from the session shell.
3. Inside the session's nested rootless DinD, `docker run --gpus all <cuda-image> nvidia-smi` succeeds and accesses the host GPU (the depth the workload actually runs at).
4. The CUDA userspace is absent from a session until the `aisandbox-gpu` capability is selected; selecting it installs the toolkit/libraries, and the base sandbox image size for non-GPU users is unchanged (verifiable via image inspection / size assertion in CI).
5. On a host with no NVIDIA GPU, driver, or Container Toolkit, sessions spawn and run exactly as today (no GPU, no regression), and any attempt to use the GPU produces a clear, actionable error naming the missing prerequisite — never a confusing crash or a silently broken session.
6. GPU exposure is compatible with rootless DinD and the subuid mapping (UC-30): enabling it does not require running the session container as root on the host and does not break rootless nesting — or, where a specific rootless limitation exists, it is explicitly documented with the rationale.
7. Host prerequisites are documented for the operator: supported NVIDIA driver version range, NVIDIA Container Toolkit install + configuration (including CDI / Container Device Interface if used), and the Linux-only constraint. `setup.sh` / operator docs state what the host must provide.
8. Behavior with multiple concurrent sessions sharing one physical GPU is defined and documented (shared/time-sliced access; expectations around VRAM contention); the management server does not deadlock or crash under concurrent GPU use.
9. The Linux-only guarantee (UC-27) is preserved: the GPU feature is gated to Linux hosts; macOS/Windows hosts are unaffected (they simply have no GPU path).
10. A host-level switch exists to disable GPU exposure entirely even on a GPU-capable host (operator override of the host-wide default), for operators who do not want the widened trust boundary on every session.

## Potential Pitfalls & Open Questions

- **Risk** — Nested rootless-DinD GPU is the hardest part of this use case. The NVIDIA Container Toolkit's device injection and cgroup device rules can conflict with rootless Docker and the additional nested DinD layer; a working path likely requires CDI (Container Device Interface) generation on the host and specific toolkit/daemon configuration, and may be partially infeasible while staying fully rootless. **Recommendation: the first implementation step should be a feasibility spike on a single reference GPU host** to prove the nested path before committing to the full feature.
- **Risk** — Host-wide exposure widens the "container is the trust boundary" surface for *every* session: the GPU device nodes and driver ioctls become reachable from all sessions, not only those that need the GPU, and GPU contention becomes automatic across concurrent sessions. Accepted per the host-wide decision; mitigated by AC-10's host-level kill switch, but the tradeoff must be documented prominently.
- **Edge case** — All concurrent sessions share one physical GPU (VRAM exhaustion, time-slicing stalls). MIG/partitioning and per-session VRAM caps are out of scope for v1 (documented, not enforced).
- **Assumption** — The `aisandbox-gpu` capability's CUDA userspace version must be compatible with the host's installed NVIDIA driver (the CUDA/driver compatibility matrix). The capability must target a CUDA version compatible with the documented supported-driver range, or detect/adapt to the host driver.
- **Open question (Missing input)** — Eventual implementation maturity: whether the first delivered increment is a **feasibility spike / enablement** (prove the nested-DinD GPU path works on one reference host, plus graceful no-GPU degradation) or a **full production feature**. Flagged for the dev-team at implementation time; it does not block this definition, but given the nesting risk a spike-first increment is advised.
- **Environment note** — The development/CI environment has no NVIDIA GPU, so AC-1/2/3 (live `nvidia-smi` + CUDA + nested `docker run --gpus`) cannot be verified automatically; they are **on-host manual-verification** criteria. CI can still verify no-GPU graceful degradation (AC-5), the capability's presence/installation wiring (AC-4), config/script validity, and documentation (AC-6/7/9).

## Original Description

Allow ai-sandbox sandboxes to use the HOST's GPU (graphics card) — e.g. NVIDIA CUDA — for GPU-accelerated workloads such as AI/ML model training inside a session's container. The sandboxes are rootless Docker-in-Docker (DinD) per-session environments (see UC-30). The use case should cover exposing the host GPU into the per-session sandbox containers (NVIDIA Container Toolkit / --gpus, device passthrough), making CUDA usable inside a session, and the operational/security considerations (opt-in per session or host-wide, driver/toolkit prerequisites on the host, resource sharing/contention between concurrent sessions, rootless-DinD compatibility).

## Clarifications

- Q: Which GPU vendors must this use case support?
  A: NVIDIA / CUDA only.
- Q: At what depth must the GPU be usable (session container vs nested rootless DinD)?
  A: Both — the session container AND containers launched inside the session's nested DinD.
- Q: How should GPU access be enabled (per-session, host-wide, config)?
  A: Host-wide default (every session on a GPU host gets the GPU), with a host-level disable switch.
- Q: Where should the CUDA userspace live (base image vs opt-in capability vs user-managed)?
  A: An opt-in `aisandbox-gpu` dev-tools capability, installed on demand; not baked into the base image.
