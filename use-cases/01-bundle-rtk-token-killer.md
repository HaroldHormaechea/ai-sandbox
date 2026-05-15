# Use Case 01: Bundle RTK (Rust Token Killer) and auto-enable for Claude Code

## Summary

Bundle the **RTK (Rust Token Killer)** CLI proxy into the `ai-sandbox` container image and auto-enable its Claude Code hook so every Bash tool call Claude makes inside the sandbox is transparently rewritten to its `rtk <cmd>` token-compressed equivalent. `SandboxDockerfile` fetches the latest RTK release from `github.com/rtk-ai/rtk/releases/latest` at build time (rolling, matching the `@anthropic-ai/claude-code` policy, not the pinned gitleaks pattern), branches on `TARGETARCH` to install `rtk-x86_64-unknown-linux-musl.tar.gz` natively on amd64 and `rtk-aarch64-unknown-linux-gnu.tar.gz` plus `apk add gcompat` on arm64, and lands the binary at `/usr/local/bin/rtk`. `entrypoint.sh` runs `rtk init -g` on every container start (idempotent) *after* the `claude-config/` bind mount is in place, so the hook config (`~/.claude/settings.json` + RTK's emitted `CLAUDE.md` / `RTK.md` augmentation) persists across `docker compose down/up`. The container's `~/.claude/CLAUDE.md` gains a directive instructing Claude to prefer Bash equivalents (`cat`, `rg`, `find`) over the built-in `Read` / `Grep` / `Glob` tools, since those bypass the Bash hook and therefore bypass RTK. Touches `SandboxDockerfile`, `entrypoint.sh`, `README.md`, `claude-config/`'s seed content, and `PROJECT_BRIEF.md`. No operator-facing script changes (`setup.sh` / `attach.sh` / `clean.sh` stay untouched).

## Acceptance Criteria

1. After a fresh `setup.sh` / `setup.ps1`, `docker compose exec claude-sandbox rtk --version` exits 0 and prints a version string.
2. `docker compose exec claude-sandbox rtk gain` exits 0 and prints token-savings stats — confirming the correct package, not the unrelated `reachingforthejack/rtk` (Rust Type Kit) name-collision.
3. The image builds and runs on both `linux/amd64` and `linux/arm64` Docker platforms; both produce a working `rtk` binary callable from `claude`-user shells.
4. On `linux/arm64`, `apk info gcompat` shows the shim is installed; on `linux/amd64`, it is not.
5. The build logs the resolved RTK version (e.g. `rtk --version` echoed during build) so operators can verify what they got from a rolling-latest fetch.
6. Inside the running `tmux main` session, Claude's Bash tool calls for at least `git status`, `cat`, `grep`, `find`, `cargo test`, `npm test` are auto-rewritten to `rtk <cmd>` via the hook installed in `~/.claude/`.
7. `entrypoint.sh` invokes `rtk init -g` *after* `claude-config/` is bind-mounted, on every container start, idempotently — repeated `docker compose down && up` cycles do not duplicate or corrupt hook entries.
8. `~/.claude/CLAUDE.md` (or RTK's emitted `RTK.md`, whichever lands first) inside the container contains a directive telling Claude to prefer Bash equivalents (`cat`, `rg`/`grep`, `find`) over the built-in `Read` / `Grep` / `Glob` tools, with a one-line rationale.
9. `README.md` documents that RTK is bundled, links to `rtk gain` for analytics, and explicitly warns that Claude Code built-in tools (`Read`, `Grep`, `Glob`) bypass the Bash hook and therefore bypass RTK — even with the CLAUDE.md nudge, the agent may still call them.
10. `PROJECT_BRIEF.md` is updated: frontmatter `stack.versions.rtk: "latest-at-build"` (matching the existing `claude_code: "latest-at-build"` convention); prose Technologies + Architecture sections list RTK as a bundled in-container tool with rolling-latest pinning.
11. `clean.sh` / `clean.ps1` still wipes and rebuilds successfully — no new state requires bespoke cleanup beyond what already exists.
12. Image-size delta from the RTK binary (and `gcompat` on arm64 only) is recorded in the PR description for the reviewer's awareness; no hard budget enforced.

## Potential Pitfalls & Open Questions

- **Open** — aarch64 strategy still needs a thumbs-up. Going with `apk add gcompat` + the glibc aarch64 binary because the user said "support all hosts" but didn't pick among the four arch-specific options. Alternative if perf matters: `cargo install` from source in a separate build stage (heavier image, native musl binary). Confirm or override during implementation.
- **Assumption** — `rtk init -g` is idempotent. Upstream README implies it but does not document it explicitly. QA should verify by running it twice in a row in a smoke test and diffing `~/.claude/settings.json`.
- **Assumption** — License is Apache-2.0 per GitHub repo metadata. The upstream README badge says MIT (likely outdated). Both are compatible with ai-sandbox's MIT redistribution; the attribution line in our README should cite whichever turns out to be authoritative at build time. Verify once during implementation.
- **Risk** — Rolling-latest reduces reproducibility. Two operators building a week apart get different rtk versions; an upstream regression hits without warning. Accepted as a conscious choice (matches `@anthropic-ai/claude-code` policy). AC #5 (log the resolved version during build) is the mitigation.
- **Risk** — Supply-chain surface widens. Build-time network fetch from GitHub Releases adds RTK to the trust set alongside gitleaks, alpine apk, and npm. No checksum verification is currently done for gitleaks either, so this is consistent but worth noting in the threat-model paragraph of the README.
- **Edge case** — Built-in-tool bypass remains a real leak even with the CLAUDE.md directive. The directive sets a preference; it does not enforce. AC #9 makes the limitation explicit in user docs.
- **Edge case** — Bind-mount masking still applies, just folded into the design. Anything `rtk init -g` writes during `docker build` would be hidden by the `claude-config/` bind mount at runtime, which is why install happens in `entrypoint.sh` post-mount (AC #7). Calling out so a future change to the image-build flow doesn't accidentally move it back.
- **Assumption resolved** — Single-layer (container-only) install. Rationale: RTK only matters where an LLM consumes terminal output; the host has no LLM. Single source of truth for RTK lives in the Dockerfile. Not asked in the clarification round to save a slot.

## Original Description

I want to add rtk (rustk token killer) as part of this tool, so it is automatically loaded/used

Subsequent clarification from the user: "rust token killer" — confirming RTK = github.com/rtk-ai/rtk.

## Clarifications

- Q: Architecture support — upstream RTK only ships a musl Linux binary for x86_64, not aarch64. Alpine on Apple Silicon hosts won't run the aarch64 release tarball (glibc-only) without help. What's the strategy?
  A: "We need to support windows, linux and macOs" — interpreted as "all hosts including Apple Silicon". Implementation pick: `apk add gcompat` + the glibc aarch64 binary on `linux/arm64`; native musl binary on `linux/amd64`. To be confirmed during implementation.
- Q: Version pinning policy for RTK — match gitleaks (hard pin in a single build arg) or match @anthropic-ai/claude-code (rolling latest at build time)?
  A: Rolling latest at build time.
- Q: Should Claude Code be nudged (via CLAUDE.md / RTK.md inside the container) to prefer Bash equivalents (cat, rg, find) over its built-in Read, Grep, Glob tools — which bypass the Bash hook and therefore bypass RTK?
  A: Yes, add a directive in the container's CLAUDE.md.
- Q: When should `rtk init -g` run inside the container?
  A: On every container start, idempotently.
