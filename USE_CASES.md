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
| 12 | [use-cases/12-webflux-exception-advice-routing-fix.md](use-cases/12-webflux-exception-advice-routing-fix.md) | Fix UC-11 regression — WebFlux exception chain bypasses EnrollmentWebExceptionHandler | done | 2026-05-21 |
| 13 | [use-cases/13-android-bouncycastle-pkcs12-import-fix.md](use-cases/13-android-bouncycastle-pkcs12-import-fix.md) | Android client adopts BouncyCastle for PKCS#12 enrollment-cert import | done | 2026-05-21 |
| 14 | [use-cases/14-pkcs12-empty-password-sentinel.md](use-cases/14-pkcs12-empty-password-sentinel.md) | Fix UC-13 regression — sentinel passphrase for enrollment PKCS#12 (BouncyCastle 1.79 rejects empty char[]) | done | 2026-05-21 |
| 15 | [use-cases/15-server-sessions-enumeration-and-post-response-error-noise.md](use-cases/15-server-sessions-enumeration-and-post-response-error-noise.md) | Server `/v1/sessions` enumeration broken — `docker compose ls --all` rejected, `.docker/config.json` unreadable, downstream `handleAny` log noise | done | 2026-05-21 |
| 16 | [use-cases/16-android-resume-to-sessions-instead-of-onboarding.md](use-cases/16-android-resume-to-sessions-instead-of-onboarding.md) | Android client — cold-start after enrollment should resume to the sessions list, not re-prompt for QR | done | 2026-05-21 |
| 17 | [use-cases/17-server-onboarding-uid-alignment.md](use-cases/17-server-onboarding-uid-alignment.md) | Out-of-box server onboarding + uid-aligned session permissions | done | 2026-05-22 |
| 18 | [use-cases/18-android-sessions-cards-untappable.md](use-cases/18-android-sessions-cards-untappable.md) | Android sessions-screen cards do not respond to taps | pending | 2026-05-22 |

