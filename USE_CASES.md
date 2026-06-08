# Use Cases

Status ledger for use cases under `use-cases/`. Machine-maintained — the `define-use-case` skill appends rows; the dev-team orchestrator updates the `Status` and `Updated` columns as it works. Do not hand-edit those two columns unless you know why; edit the use-case file or re-run the skill instead.

Statuses:
- `pending` — saved but not yet picked up by the dev-team
- `in-progress` — the dev-team has started analysis
- `done` — implementation and tests completed
- `blocked` — the dev-team escalated (6-round cap hit, user abort, or infeasibility)
- `rejected` — reviewed and deliberately not implemented (closed without dispatch)

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
| 18 | [use-cases/18-android-sessions-cards-untappable.md](use-cases/18-android-sessions-cards-untappable.md) | Android sessions-screen cards do not respond to taps | done | 2026-05-23 |
| 19 | [use-cases/19-server-deb-onboarding-claude-preinit.md](use-cases/19-server-deb-onboarding-claude-preinit.md) | Server `.deb` onboarding — auto-run wizard on TTY + capture Claude pre-init state | done | 2026-05-23 |
| 20 | [use-cases/20-android-swipe-to-delete-session.md](use-cases/20-android-swipe-to-delete-session.md) | Android client — swipe-to-delete a session (reveal red + trash, confirm, actually delete) | done | 2026-05-23 |
| 21 | [use-cases/21-android-terminal-emulator-and-agent-switcher.md](use-cases/21-android-terminal-emulator-and-agent-switcher.md) | Android client — usable terminal (real emulator + keyboard) + hamburger delete/disconnect + agent-team switcher | done | 2026-05-25 |
| 22 | [use-cases/22-onboarding-toolchain-android-testing.md](use-cases/22-onboarding-toolchain-android-testing.md) | Onboarding toolchain selection with full Android build/test/emulator image support | done | 2026-05-25 |
| 23 | [use-cases/23-android-terminal-keyboard-insets.md](use-cases/23-android-terminal-keyboard-insets.md) | Android terminal — on-screen keyboard occludes the input (IME insets) | done | 2026-05-25 |
| 24 | [use-cases/24-android-terminal-multi-window-leak-fix.md](use-cases/24-android-terminal-multi-window-leak-fix.md) | Android terminal — fix "all tmux windows shown" regression (only main pane + agent switcher) | done | 2026-06-05 |
| 25 | [use-cases/25-android-terminal-split-pane.md](use-cases/25-android-terminal-split-pane.md) | Android terminal — split-pane support (UC04-3c) [rejected] | rejected | 2026-06-08 |
| 26 | [use-cases/26-server-setup-devtools-step-dind.md](use-cases/26-server-setup-devtools-step-dind.md) | Server setup — "Select the development tools you want to install" step with rootless Docker-in-Docker | done | 2026-05-31 |
| 27 | [use-cases/27-devtools-capability-selector.md](use-cases/27-devtools-capability-selector.md) | Server setup — manifest-driven, launch-adapted dev-tools capability selector (Linux-only) | done | 2026-05-31 |
| 28 | [use-cases/28-android-terminating-state-block-redelete.md](use-cases/28-android-terminating-state-block-redelete.md) | "awaiting termination" feedback state + block re-delete while terminating | done | 2026-06-01 |
| 29 | [use-cases/29-readme-overview-delegate-rewrite.md](use-cases/29-readme-overview-delegate-rewrite.md) | Rewrite the three READMEs into a purpose-first overview with delegated installation | done | 2026-06-04 |
| 30 | [use-cases/30-server-side-capability-install-scripts.md](use-cases/30-server-side-capability-install-scripts.md) | Server-side per-capability install scripts in `setup.sh` (idempotent, hard-gated) + dind subuid provisioning | done | 2026-06-04 |
| 31 | [use-cases/31-dind-selftest-skill.md](use-cases/31-dind-selftest-skill.md) | `dind-selftest` skill (baked) — live Docker-in-Docker verification runbook | pending | 2026-06-05 |
| 32 | [use-cases/32-android-sessions-list-live-status-push.md](use-cases/32-android-sessions-list-live-status-push.md) | Android sessions list — live status updates via server push (WebSocket/SSE), not REST-poll-on-resume | done | 2026-06-05 |
| 33 | [use-cases/33-android-terminal-resplit-on-midstream-subagent-spawn.md](use-cases/33-android-terminal-resplit-on-midstream-subagent-spawn.md) | Android terminal — re-zoom on mid-stream subagent spawn (window paints split until a switcher tile is tapped) | done | 2026-06-05 |
| 34 | [use-cases/34-android-terminal-fgs-background-teardown-crash.md](use-cases/34-android-terminal-fgs-background-teardown-crash.md) | Android terminal — crash tearing down the foreground service from the background on reconnect give-up / cert-revoke | done | 2026-06-05 |
| 35 | [use-cases/35-android-terminal-fgs-background-start-crash-on-reconnect.md](use-cases/35-android-terminal-fgs-background-start-crash-on-reconnect.md) | Android terminal — crash starting the foreground service from the background when a stream reconnects while backgrounded (Android 12+) | done | 2026-06-05 |
| 36 | [use-cases/36-android-terminal-conversational-keyboard-words-autocomplete.md](use-cases/36-android-terminal-conversational-keyboard-words-autocomplete.md) | Android terminal — conversational keyboard (word input + autocompletion), not char-by-char, without dropping commands | done | 2026-06-05 |
