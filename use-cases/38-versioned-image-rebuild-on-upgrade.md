# Use Case 38: Versioned `ai-context` image with explicit and upgrade-triggered rebuild

## Summary
Today the session image is published under a single floating tag `ai-context:latest` (set in `docker-compose.yml`'s `image:` field and `EnsureSandboxImage.IMAGE_TAG`), built from `SandboxDockerfile` via `docker compose ... build claude-sandbox`. `EnsureSandboxImage` only builds when the tag is *absent* (`docker image inspect ai-context:latest`) — it has no notion of an image being *stale*, so a `.deb` upgrade that ships a changed image definition leaves the old image in place. This use case gives the image a version identity equal to the **server package version** (`ai_sandbox_server_version` / `server-v*` tag), stamped onto the image as an OCI **`LABEL`** and read back via `docker image inspect`; the runtime tag stays `ai-context:latest` (no compose contract change). It adds an `aisandboxctl onboard` parameter (e.g. `--rebuild-image`) that **forces a rebuild of the image only**, without re-gathering the other onboarding components. And it makes the `.deb` upgrade path rebuild **synchronously during `postinst configure`** when the installed image's labelled version **differs** from the version the new package bundles — guarded so the rebuild can never fail or hang the package install. Spawn-time staleness enforcement is explicitly out of scope.

## Acceptance Criteria
1. The built image carries an OCI `LABEL` (e.g. `com.ai-sandbox.image-version=<server-version>`) set at build time to the server package version, readable via `docker image inspect` without running a container.
2. The server package version (`ai_sandbox_server_version`, overridable by the `server-v*` tag build, exactly as the jar already is) is the single source of truth for the image version; the build stamps the label from it.
3. The runtime tag remains `ai-context:latest`; `EnsureSandboxImage.IMAGE_TAG` and `docker-compose.yml`'s `image:` field are unchanged. Staleness is determined solely from the label, not the tag.
4. `EnsureSandboxImage` (or its caller) gains a staleness check that classifies the local image as: absent, present-and-current (label == package version), or present-but-stale (label **differs from** package version — any non-equal value, including a missing label, counts as stale).
5. `aisandboxctl onboard` accepts a parameter (e.g. `--rebuild-image`) that **forces** a rebuild of `ai-context` even when a current image is already present, and performs **only** the image rebuild — it does not re-gather PKI/SSH/git/gh/Claude/devtools components.
6. `--rebuild-image` and `--no-image-build` are mutually exclusive; supplying both yields a clear error and a non-zero exit.
7. On a `.deb` upgrade (`postinst configure` with a non-empty previous version → `IS_UPGRADE=1`), when the installed `ai-context` image is absent or its labelled version **differs** from the version the new package bundles, the image is rebuilt **synchronously within `postinst`**; when the image is already current, no rebuild occurs.
8. The synchronous upgrade rebuild is fully guarded: if Docker is unavailable/not running, or the build fails for any reason, `postinst` still exits 0 (the install/upgrade succeeds) and prints a clear deferred message (e.g. "rebuild later with `sudo aisandboxctl onboard --rebuild-image`"), consistent with the existing deferral style. It must not hang `dpkg`/`apt` (bounded like the existing tty/read guards).
9. A fresh install (no previous version) preserves today's behaviour: no eager image build in a non-interactive `postinst`; the lazy interactive build path is unchanged.
10. Re-running `onboard --rebuild-image` when the image is already current still rebuilds on demand (explicit override, distinct from the staleness-gated upgrade path).
11. The new flag appears in `onboard --help`, and `postinst` "Next steps"/deferral messaging mentions the image-rebuild path where relevant.
12. Documentation (server README and/or `PROJECT_BRIEF.md` where the image build is described) reflects the label-based versioning scheme, the new flag, and the synchronous upgrade-rebuild behaviour.
13. Test coverage with the existing `ProcessRunner`/`ConsoleIO` seams (no real Docker daemon): the staleness classification (absent / current / differs / missing-label), the `--rebuild-image` flag wiring, the mutually-exclusive guard against `--no-image-build`, and the `postinst` upgrade branch's rebuild-and-guard behaviour.

## Potential Pitfalls & Open Questions
- **Risk** — *Synchronous rebuild blocks apt.* By design the rebuild runs inside `postinst configure`, so an upgrade where Docker is up will block `apt`/`dpkg` for the multi-minute build duration. AC8 keeps it from *failing/hanging indefinitely*, but the operator-visible blocking time on upgrade is accepted as part of this use case.
- **Edge case** — *Build context availability at upgrade time.* The synchronous rebuild needs the bundled compose context (`<install-dir>/host/docker-compose.yml` + `SandboxDockerfile`) to already be in place when `postinst` runs. dpkg unpacks files before `postinst configure`, so this should hold — but the rebuild must use the same `--project-directory`/`-f` invocation `EnsureSandboxImage` already uses, against the *newly unpacked* context.
- **Edge case** — *`-SNAPSHOT` churn.* With a not-equal test and the package version as identity, two different builds of the same `-SNAPSHOT` version label identically, so a changed Dockerfile under an unchanged `-SNAPSHOT` version will *not* be seen as stale. Accepted: release builds carry distinct `server-v*` versions, so production upgrades always differ when intended.
- **Edge case** — *Which Docker daemon at upgrade.* `postinst` runs as root; the rebuild targets root's Docker (the same daemon the service uses via `DOCKER_CONFIG=/var/lib/ai-sandbox-server/docker-config`). The rootless DinD daemon (UC-30) is per-session and not the build target — confirm the rebuild uses the root/system daemon, matching where `ai-context:latest` already lives.
- **Assumption** — `--rebuild-image` performs a normal `docker compose build` (relying on layer caching; a changed `SandboxDockerfile` busts it); it does **not** imply `--no-cache` unless a future flag adds that.

## Original Description
new use case for ai-context. Offer a parameter to the onboard command to rebuild the image only. That should be done automatically on upgrade if the image is outdated. so time to versión it too

## Clarifications
- Q: How should the image version be derived (the value stamped as a label and compared to detect "outdated")?
  A: The server package version (`ai_sandbox_server_version` / `server-v*` tag).
- Q: How should "outdated" be decided when comparing the installed image's label to the package version?
  A: Differs (not-equal) — any mismatch, including a missing label, counts as stale.
- Q: How should the versioned image be exposed — runtime tag vs. how staleness is detected?
  A: Keep `ai-context:latest` as the runtime tag; detect staleness via an OCI LABEL.
- Q: On a `.deb` upgrade with an outdated image, when/how should the automatic rebuild run?
  A: Synchronously in `postinst` (guarded so it never fails/hangs the install).
- Q: Should session spawn also enforce image staleness, or is that out of scope?
  A: Out of scope for this use case.
