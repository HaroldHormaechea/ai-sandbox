# Use Case 08: aisandboxctl CLI wrapper

## Summary

The `.deb` and zip packages tell operators to invoke management commands as `aisandboxctl <subcommand>` (server README, `debian/control` Description, postinst "Next steps", CI smoke commentary all use the shorthand), but no `aisandboxctl` wrapper actually ships — only the underlying jar at `/opt/ai-sandbox-server/lib/aisandboxctl.jar`. v0.0.9 adds a thin POSIX shell wrapper so every operator-executable command is directly invokable. The `.deb` installs `/usr/bin/aisandboxctl`; the zip release ships `bin/aisandboxctl` at the bundle root with explicit README guidance to symlink it onto PATH. Both wrappers hardcode `/usr/bin/java` (matching the systemd unit and immune to `sudo` env-pruning). CI's `release-install-smoke` exercises the wrapper end-to-end on both `.deb` and `.zip` paths. The wrapper preserves exit codes, argv passthrough, and stdout/stderr streaming byte-for-byte vs. the long form, works under `sudo` and as the `ai-sandbox-server` service user, and leaves the underlying jar's CLI surface untouched. All operator-facing docs are audited and reconciled. **No Windows-host wrapper** in v0.0.9 — the operator surface is Linux-only today.

## Acceptance Criteria

1. `.deb` installs `/usr/bin/aisandboxctl` (0755, root:root) — a POSIX shell wrapper whose body is `exec /usr/bin/java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar "$@"` (or equivalent with a `set -e` header). Verified by `dpkg -L ai-sandbox-server | grep -x /usr/bin/aisandboxctl` in CI.

2. Zip release bundle includes `bin/aisandboxctl` at the bundle root (extracts to `/opt/ai-sandbox-server/bin/aisandboxctl`, mode 0755). Same wrapper body as the `.deb`. `ReleaseBundleTest` (or a new sibling test) asserts the file's presence, mode, and byte-equality with the `.deb`'s wrapper.

3. The zip-install Quick-install README section gains an explicit symlink line: `sudo ln -s /opt/ai-sandbox-server/bin/aisandboxctl /usr/local/bin/aisandboxctl` — positioned after the unzip step and before the first `aisandboxctl pki init` call.

4. Both wrappers hardcode `/usr/bin/java`. No `$JAVA_HOME` lookup, no PATH-based java resolution. Rationale captured in an inline comment in the wrapper source file (matches systemd unit + immune to sudo env-pruning).

5. Exit code passthrough — wrapper returns the same exit code as direct-jar invocation. CI smoke test asserts equality across at least one success path (`pki init`) and one failure path (`client mint <existing>` or similar known-non-zero).

6. Stream passthrough — stdout and stderr are byte-equivalent between wrapper and direct-jar across the `--json` matrix from UC-07 § AC5. No buffering, no shell-level transformation.

7. Works under `sudo` (operator-typical) AND as `ai-sandbox-server` service user (`runuser -u ai-sandbox-server -- aisandboxctl …`). `sudo`'s default env-pruning does NOT break the wrapper because `/usr/bin/java` is absolute.

8. CI `release-install-smoke` flips `pki init`, `secrets seed`, `client mint`, `client invite` invocations to the wrapper form on both `.deb` and `.zip` install paths. UC-07's H1.1 and H2 enrollment round-trips continue to pass via the wrapper.

9. `server/README.md` reconciled — zip-install Quick-install section's `java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar …` examples flip to `aisandboxctl …` (post-symlink-instruction). `.deb`-install section already uses shorthand and is unchanged.

10. Underlying jar `/opt/ai-sandbox-server/lib/aisandboxctl.jar` and its CLI surface are NOT touched — wrapper-only change. Pre-existing scripts using the long form continue to work (regression guard).

11. Documentation audit — every operator-facing surface (`server/README.md`, `debian/control`, postinst "Next steps", `docs/THREAT_MODEL.md`, OpenAPI spec examples, `.github/workflows/server-ci.yml` inline comments referencing operator commands) — each surface either uses the shorthand or has explicit retain-rationale captured in the implementation commit body.

12. Full `:server:test` green, `:server:spotlessCheck` clean, CI workflows green on build + integration + both `release-install-smoke` phases. Release workflow attaches v0.0.9 `.zip` and `.deb`.

## Potential Pitfalls & Open Questions

- **Edge case** — Pre-installed conflicts. If an operator manually symlinked `/usr/local/bin/aisandboxctl` (current README workaround for v0.0.8) and then upgrades to v0.0.9 via `apt install`, both `/usr/local/bin/aisandboxctl` (operator's symlink) and `/usr/bin/aisandboxctl` (.deb-installed wrapper) coexist. PATH order on most distros puts `/usr/local/bin` first, so the operator's symlink wins — both resolve to the same jar so behaviour is identical, but worth a one-line release-note callout suggesting `sudo rm /usr/local/bin/aisandboxctl` if previously self-symlinked.

- **Assumption** — Single-file shell script is the wrapper body (`exec /usr/bin/java -jar … "$@"` with no fancy preamble). Confirmed during clarifications.

- **Edge case** — `ai-sandbox-server` service user shell. The user is created with `--shell /usr/sbin/nologin` (per postinst). `runuser -u ai-sandbox-server -- aisandboxctl …` should still work because `runuser` doesn't use the target user's shell for `--`-style argv invocations. Worth one CI assertion to prove the contract.

## Original Description

The `.deb` and zip packages reference `aisandboxctl <subcommand>` throughout their docs (server/README.md), in the .deb control file's Description, in the postinst "Next steps" block, and in the CI smoke test's plain-language assertions — but no `/usr/bin/aisandboxctl` wrapper actually ships. Operators have to run `sudo java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar <subcommand> …` which is what CI does empirically, but the operator-facing docs say `aisandboxctl …`. Fix in v0.0.9: ship a proper CLI wrapper so every operator-executable command is directly invokable.

Scope:
- `.deb` package installs a `/usr/bin/aisandboxctl` POSIX shell wrapper that `exec`s `java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar "$@"`.
- The zip release bundle ships a sibling `aisandboxctl` wrapper at a place the operator can drop into PATH (probably `/opt/ai-sandbox-server/bin/aisandboxctl` with install-time guidance to symlink to `/usr/local/bin/`).
- A `.cmd` / `.ps1` Windows-host counterpart for the zip's bundled host scripts (if there's any operator surface on Windows that needs invoking — needs scoping during the use case).
- The README's install flow and the postinst "Next steps" both flip to the shorthand form everywhere (currently the body of the long form `java -jar …` is in the zip-install README section but the .deb description and postinst already use the shorthand — making the gap a real install-time stumble).
- CI smoke jobs in `.github/workflows/server-ci.yml` should call the wrapper to prove it works end-to-end (both `.deb` and `.zip` paths).
- Audit every operator-facing surface (README, postinst output, manpage if any, OpenAPI examples that reference CLI examples) and reconcile to the shorthand form once the wrapper exists.
- Out of scope today: full bash/zsh completion scripts (separate ticket if desired). Out of scope: replacing `java -jar` with a graalvm native-image build (much bigger project).

Constraints:
- The wrapper must preserve exit codes and argv passthrough exactly so existing scripts that invoke `java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar` see no behavioural difference if/when they switch to the wrapper.
- The wrapper must NOT silently swallow stderr or buffer output (operator-facing CLI; immediate-feedback contract).
- The wrapper must work under `sudo` (no env-pruning surprises) and as the `ai-sandbox-server` service user.

## Clarifications

- Q: Should v0.0.9 ship a Windows-host wrapper alongside the Linux POSIX shell wrapper?
  A: Skip — Linux-only operator surface. The CLI is JVM-based but the only real operator surface is the .deb on Linux and the .zip unzipped on Linux. Can revisit when a Windows operator surface materializes.

- Q: How should the wrapper resolve the `java` binary?
  A: Hardcode `/usr/bin/java`. Matches the systemd unit's `ExecStart=/usr/bin/java`, immune to sudo env-pruning. The .deb already depends on `openjdk-21-jre-headless | openjdk-21-jdk-headless` so `/usr/bin/java` is guaranteed to exist.

- Q: What's the zip-install PATH-enable UX?
  A: README instructs manual symlink. Add `sudo ln -s /opt/ai-sandbox-server/bin/aisandboxctl /usr/local/bin/aisandboxctl` to the existing Quick-install block. Transparent — operator sees exactly what's being installed. Matches the zip's no-magic philosophy.
