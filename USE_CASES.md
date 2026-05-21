# Use Cases

Status ledger for use cases under `use-cases/`. Machine-maintained — the `define-use-case` skill appends rows; the dev-team orchestrator updates the `Status` and `Updated` columns as it works. Do not hand-edit those two columns unless you know why; edit the use-case file or re-run the skill instead.

Statuses:
- `pending` — saved but not yet picked up by the dev-team
- `in-progress` — the dev-team has started analysis
- `done` — implementation and tests completed
- `blocked` — the dev-team escalated (6-round cap hit, user abort, or infeasibility)

| # | File | Title | Status | Updated |
|---|------|-------|--------|---------|
| 01 | [use-cases/01-bundle-rtk-token-killer.md](use-cases/01-bundle-rtk-token-killer.md) | Bundle RTK (Rust Token Killer) and auto-enable for Claude Code | done | 2026-05-15 |
| 02 | [use-cases/02-multi-session-containers.md](use-cases/02-multi-session-containers.md) | Multi-session ai-sandbox containers | done | 2026-05-16 |
| 03 | [use-cases/03-mtls-java-management-server.md](use-cases/03-mtls-java-management-server.md) | mTLS-secured Java management server | done | 2026-05-16 |
| 04 | [use-cases/04-android-client.md](use-cases/04-android-client.md) | Android client application | done | 2026-05-17 |
| 05 | [use-cases/05-self-contained-server-release.md](use-cases/05-self-contained-server-release.md) | Self-contained server release | done | 2026-05-18 |
| 06 | [use-cases/06-complete-server-install-onboarding.md](use-cases/06-complete-server-install-onboarding.md) | Complete server-install onboarding | done | 2026-05-18 |
| 07 | [use-cases/07-http2-reenablement-v008-bundle.md](use-cases/07-http2-reenablement-v008-bundle.md) | HTTP/2 re-enablement + v0.0.8 tactical bundle | done | 2026-05-19 |
| 08 | [use-cases/08-aisandboxctl-cli-wrapper.md](use-cases/08-aisandboxctl-cli-wrapper.md) | aisandboxctl CLI wrapper | done | 2026-05-19 |
| 09 | [use-cases/09-spki-cert-pin-algorithm.md](use-cases/09-spki-cert-pin-algorithm.md) | SPKI cert-pin algorithm reconciliation | done | 2026-05-19 |
| 10 | [use-cases/10-android-cert-pin-chain-cleaning-fix.md](use-cases/10-android-cert-pin-chain-cleaning-fix.md) | Android cert-pin chain-cleaning fix | done | 2026-05-20 |
| 11 | [use-cases/11-enrollment-runtime-fixes.md](use-cases/11-enrollment-runtime-fixes.md) | Enrollment-flow latent bug fixes — systemd ReadWritePaths + WebFlux exception advice + token rollback | done | 2026-05-21 |

