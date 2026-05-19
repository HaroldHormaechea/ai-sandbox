# Use Case 07: HTTP/2 re-enablement + v0.0.8 tactical bundle

## Summary

HTTP/2 re-enablement for UC03's mTLS management server, paired with a bundle of tactical improvements that close v0.0.7's open ends. The architectural core is propagating `ClientIdentity` across per-stream `Http2StreamChannel` boundaries so `MtlsEnforcementFilter` sees a non-null identity for HTTP/2 requests; the implementation must follow the v0.0.6 cascade lesson of test-first by writing a failing `MtlsDispatchOverH2Test` BEFORE touching propagation code. The release also lands a `--json` flag on `aisandboxctl client invite`, replaces the empty `ServerCertHotReloadIT` stub with a real `@SpringBootTest`, audits and renames every `*IT.java` that doesn't actually need DinD, fixes the `spawn.ps1` CRLF/LF flake (canonicalizing on CRLF), sweeps stale `/etc/.../enrollment` references out of `PROJECT_BRIEF.md` + `docs/THREAT_MODEL.md`, and runs `/revise-brief` against UC03 § AC11 as a post-release follow-up. Customizer pins flip to `.protocol(HttpProtocol.HTTP11, HttpProtocol.H2)` and ALPN list re-adds `"h2"` once the test goes green. v0.0.8 ships only when ALL items are ready — no auto-split.

## Acceptance Criteria

1. A new `MtlsDispatchOverH2Test` (`@SpringBootTest`) exists under `server/src/test/java/com/aisandbox/server/integration/`, uses Java's `HttpClient` configured for `Version.HTTP_2`, presents a real client cert from the test allowlist, GETs `/v1/healthz`, and asserts response status ∈ {200, 503} and body does NOT contain `"mtls_required"`. The test FAILS on the pre-v0.0.8 main (HTTP/1.1 only). QA runs it once on the current branch before any production code in this use case ships and reports the failure trace.

2. After the cert-propagation fix lands, `MtlsDispatchOverH2Test` PASSES. The fix is chosen between (a) a stream-channel observer that copies `IDENTITY_ATTR` onto each new `Http2StreamChannel`, or (b) a `ServerHttpRequest.getSslInfo()` fallback inside `ClientIdentityExtractor`. The analyst chooses based on empirical bytecode verification of which path actually works under Reactor Netty 1.3.x. Rationale is captured in the implementation commit and the inline customizer Javadoc.

3. `NettyServerCustomizer.applyTls` is updated to `.protocol(HttpProtocol.HTTP11, HttpProtocol.H2)`. `ReloadableSslContextHolder.rebuild` re-adds `"h2"` to the ALPN list (`"h2", "http/1.1"` — `h2` first). The existing `SslContextBootOrderTest.alpn_negotiates_http_1_1_over_tls` is renamed back to `alpn_negotiates_h2_over_tls` and re-asserts `"h2"`.

4. CI's `release-install-smoke` job adds a `client invite alice-phone` → `POST /v1/enrollment` round-trip under HTTP/2 (existing v0.0.7 round-trip stays under HTTP/1.1). HTTP/2 round-trip uses `curl --http2` or equivalent. Both phases must pass.

5. `aisandboxctl client invite` gains a `--json` flag. With `--json`: stdout is the JSON object only (single line, no QR, no operator-facing trailer); trailer goes to stderr. When `--json --out <path>` is combined, both work together: JSON appears on stdout AND `--out` writes the same JSON to the file; trailer to stderr regardless. Without `--json`: behaviour is identical to v0.0.7. The CI smoke YAML's `head -n 1` workaround is replaced with `--json` + comment block updated.

6. `ServerCertHotReloadIT` is rewritten as a real `@SpringBootTest` (no DinD dependency). Test writes a new server cert atomically to the watched PKI dir, asserts `ReloadableSslContextHolder.current()` returns a different `SslContext` instance within the debounce window (≤1s default), asserts in-flight TLS sessions retain their original cert per UC03 AC14 (SSLSession-equality check, not handshake re-do, sync'd via `Phaser` / `CountDownLatch` — no `Thread.sleep`). Class renamed `ServerCertHotReloadTest` since DinD is no longer required.

7. Every `*IT.java` file under `server/src/test/` that does NOT need DinD is renamed to `*Test.java`. Maximum-coverage audit: each `*IT` is inspected for actual Docker/DinD usage; non-DinD files are renamed. The audit report (which files renamed, which kept as `*IT`) goes in the implementation commit body. v0.0.8 release notes call out the renames so muscle-memory disruption is visible.

8. `.gitattributes` is updated so `*.ps1` files are normalized to CRLF (`text eol=crlf`). Both the repo-root `spawn.ps1` and the bundled `host/spawn.ps1` ship CRLF; `ReleaseBundleTest > bundled_host_files_are_byte_identical_to_repo_originals` passes for the first time since pre-v0.0.6. The choice tracks UC02's Windows-parity intent.

9. `PROJECT_BRIEF.md` and `docs/THREAT_MODEL.md` are scrubbed of stale `/etc/ai-sandbox-server/enrollment/` references — search-and-replace pass referencing the new `/var/lib/ai-sandbox-server/enrollment/` path. Any other v0.0.6-era documentation obsoleted by v0.0.7 is fixed in the same pass.

10. After v0.0.8 ships (post-release follow-up), `/revise-brief` is run against UC03 to remove the AC11 "deferred" language. `use-cases/03-mtls-java-management-server.md` reflects the now-implemented HTTP/2 reality.

11. v0.0.8 ships only when ALL items (1–10) are ready. No auto-split if HTTP/2 turns out larger than estimated. **Before tagging**: the Android v0.1.0 (or current) APK is manually smoke-tested against a v0.0.8 server on a real or emulated device — enroll via QR, list sessions, open a session, send keystrokes. If the manual smoke fails, v0.0.8 is held. The smoke is documented as a tag-blocker step in the release runbook. The team-lead escalates to the user for an explicit re-bundle/split decision only if the analyst's plan-preview shows H2 work likely needs >1 dev-team run; absent that, the team grinds through normal rounds without re-consult.

12. Full `:server:test` suite green. `:server:spotlessCheck` clean. CI `server-ci` workflow green on build, integration, AND both release-install-smoke phases (zip + deb, each with HTTP/1.1 AND HTTP/2 round-trips). Release workflow attaches v0.0.8 .zip and .deb.

## Potential Pitfalls & Open Questions

- **Ambiguity** — Which propagation mechanism (stream observer vs `getSslInfo()` fallback) is the right choice? Analyst decides empirically via bytecode inspection of Reactor Netty 1.3.x + a focused JUnit reproduction. Both candidates documented; choice flagged in the implementation commit. User pre-commitment: none (let analyst decide).
- **Edge case** — Reactor Netty 1.3.x may not expose a clean `Http2StreamObserver` hook by that name; the actual API surface in 1.3.5 needs bytecode-level verification before option (a) commits. Pattern matches v0.0.6 round 4 (the `NettyPipeline` constant inspection).
- **Risk** — Empirical verification of `ServerHttpRequest.getSslInfo()` under HTTP/2 is non-trivial: the `SslHandler` lives on the parent channel, but `getSslInfo()` may walk the stream channel's pipeline first. If both options fall through, a third path (manual peer-cert capture stashed via stream-channel attribute on creation) becomes the fallback.
- **Risk** — Bundling 7 items with the "always bundle" policy means HTTP/2 estimate risk drags the entire release. Mitigation: team-lead escalates on plan-preview if H2 looks >1 dev-team run; user retains override at that decision point.
- **Edge case** — `ServerCertHotReloadTest`'s "in-flight TLS sessions retain their original cert" assertion needs a way to open a TLS connection, hold it while rotating the cert, and verify the held connection's `SSLSession` is unchanged. Sync via `Phaser` / `CountDownLatch`, not `Thread.sleep`. QA decides the exact orchestration.
- **Risk** — `*IT` → `*Test` mass rename may break external tooling (IDE filters, CI substring matchers, developer aliases). Mitigation: v0.0.8 release notes enumerate the renames; the audit report in the commit body is the single source of truth for the diff.

## Original Description

Use case: HTTP/2 re-enablement for the mTLS management server (UC03), with a bundle of tactical improvements piggybacking on the same release.

### Background

v0.0.6 deferred HTTP/2 because of a structural bug: under HTTP/2, Reactor Netty creates per-stream `Http2StreamChannel` instances that do NOT inherit the parent connection channel's `IDENTITY_ATTR`. `ActiveConnectionRegistry.attach()` (called by `IdentityCapturingHandler` at `SslHandshakeCompletionEvent` time) keys identity by the parent channel's ID. Under HTTP/2, `ClientIdentityExtractor.channelIdOf()` resolves to the stream channel's ID — a different value — and lookup fails. `MtlsEnforcementFilter` sees null identity and 401s every request.

v0.0.7 shipped HTTP/1.1-only as the workaround. The customizer pins `.protocol(HttpProtocol.HTTP11)` and the holder's ALPN list is `"http/1.1"` only. UC03 § AC11 explicitly requires HTTP/2 over TLS for REST, so we have documented drift that needs resolving.

### Primary work — HTTP/2 re-enablement

The architectural decision: how to propagate the parent connection's `ClientIdentity` onto per-stream channels.

Two candidate mechanisms (analyst should weigh both empirically):
1. **Stream-channel observer** — install a Reactor Netty `Http2StreamObserver`-equivalent (or use whatever the framework's per-stream-creation hook is in 1.3.x) that copies the parent's `IDENTITY_ATTR` onto each new `Http2StreamChannel` as it's created. Keeps the existing registry contract intact.
2. **`ServerHttpRequest.getSslInfo()` fallback** — modify `ClientIdentityExtractor.channelIdOf()` (or add a sibling resolution path) to read peer certs from `ServerHttpRequest.getSslInfo().getPeerCertificates()` when channel-ID lookup fails. Spring WebFlux's `getSslInfo()` walks the request channel's pipeline for `SslHandler`, but under HTTP/2 the SslHandler is on the parent channel — empirical verification required that this actually works under H2.

**Test-first discipline is non-negotiable.** The v0.0.6 cascade lesson is "if no test runs the production code path, the bug ships." For this work: QA writes a `MtlsDispatchOverH2Test` FIRST that does a real HTTP/2 mTLS request through the production `NettyServerCustomizer` and asserts the response is NOT 401. The test fails on the current branch (HTTP/1.1 only). Only after that test fails does the developer touch the cert-propagation mechanism. Then the customizer re-adds `.protocol(HttpProtocol.HTTP11, HttpProtocol.H2)` and the holder's ALPN list re-adds `"h2"`. The test turns green.

The existing `MtlsDispatchTest` (added in v0.0.6) covers HTTP/1.1 mTLS dispatch end-to-end — the new `MtlsDispatchOverH2Test` is the H2-tier sibling.

### Tactical improvements bundled into the same release

1. **`aisandboxctl client invite --json` flag.** v0.0.7's CI smoke uses `head -n 1` to extract JSON from `client invite`'s mixed stdout (JSON + operator-facing trailer). The proper fix: a `--json` flag that emits machine-clean JSON on stdout, suppresses the QR + trailer, exits 0 on success. Replaces the workflow hack. Tiny CLI addition.

2. **UC03 § AC11 reconciliation.** Currently UC03 requires HTTP/2 over TLS as a hard AC. This use case fulfills that requirement. After the H2 work lands, UC03 should be updated to remove any "deferred to v0.0.7/v0.0.8" qualifications and reflect the now-implemented reality. This may happen via `/revise-brief` after the v0.0.8 release lands; capture it in the use case's acceptance criteria as a post-release follow-up.

3. **`ServerCertHotReloadIT` real implementation.** Currently a DinD-gated empty stub. Should exercise the real `ServerCertWatcher.loop()` cert-rotation path — write a new cert atomically, assert the holder rebuilds within the debounce window, assert in-flight TLS sessions retain their original cert per the AC14 contract. Doesn't need DinD; can run as a JUnit `@SpringBootTest` similar to `SslContextBootOrderTest`.

4. **`*IT` naming audit.** The repo's `*IT` suffix gates tests on `AI_SANDBOX_DIND=1` (the `integrationTest` Gradle task), but several existing `*IT` files don't actually need DinD. Audit each one and rename to `*Test` where DinD isn't required so they actually run on every PR. The v0.0.6 work renamed `SslContextBootOrderIT` → `SslContextBootOrderTest` for this reason and set the precedent.

5. **Pre-existing `spawn.ps1` CRLF/LF flake.** `ReleaseBundleTest > bundled_host_files_are_byte_identical_to_repo_originals` fails because `host/spawn.ps1` (bundled) is CRLF while `spawn.ps1` (repo root) is LF. Likely a `.gitattributes` / release-zip generation mismatch. One-line fix in `.gitattributes` (or in the gradle `releaseBundle` task to normalize), then the flake disappears.

6. **Stale-doc sweep.** `PROJECT_BRIEF.md` + `docs/THREAT_MODEL.md` still reference `/etc/ai-sandbox-server/enrollment/` as the token store path (it moved to `/var/lib/.../enrollment/` in v0.0.7). Search-and-replace pass. Same opportunity to scrub any other v0.0.6-era documentation that v0.0.7's bugfixes obsoleted.

### Why bundle

Each tactical item is too small to justify its own use case, but together they form a coherent "v0.0.8 housekeeping + the H2 thing we promised" release. The HTTP/2 work is the headline; the rest comes along with it. If H2 turns out to be a much bigger lift than expected, the tactical work can split off into v0.0.9 — but starting as one bundle and splitting later is cheaper than the reverse.

### Out of scope

- Multi-arch (arm64) `.deb` packaging — separate ticket.
- apt repository hosting — operators still download .deb from GitHub Releases.
- RPM / snap / flatpak packaging.
- Web UI for the management server (currently CLI-only).
- Any breaking changes to UC04's Android client enrollment flow.
- HTTP/3 (no Reactor Netty stable support yet).

## Clarifications

- Q: What's the bundle-split trigger? If HTTP/2 turns out larger than expected, when do the tactical items spin off to v0.0.9?
  A: Always bundle. v0.0.8 ships only when both H2 and tactical work are ready. The team-lead escalates to the user only if the analyst's plan-preview shows H2 likely needs >1 dev-team run.

- Q: When `aisandboxctl client invite --json` is combined with `--out <path>`, what's the contract?
  A: Both work together. JSON goes to stdout AND `--out` writes the same JSON to the file. Trailer text goes to stderr regardless.

- Q: For the `spawn.ps1` CRLF/LF byte-identity fix — which line-ending wins?
  A: CRLF everywhere. Both repo-root `spawn.ps1` and bundled `host/spawn.ps1` ship CRLF. `.gitattributes` declares `*.ps1` as `text eol=crlf`. Tracks UC02's Windows-parity intent.

- Q: How aggressive should the `*IT` rename audit be?
  A: Rename all non-DinD ITs. Every `*IT.java` that doesn't actually call into Docker/DinD gets renamed to `*Test.java`. Maximum coverage on every PR.

- Q: Android-side HTTP/2 verification — how does v0.0.8 confirm the existing Android client (UC04) still works after the H2 flip?
  A: Manual smoke on a real or emulated device before tagging v0.0.8 — enroll via QR, list sessions, open a session, send keystrokes. Tag-blocker. Captured in AC11.

- Q: Should the analyst have a preferred propagation mechanism to anchor the investigation, or is the empirical-first directive enough to drive the choice?
  A: Let analyst decide. Pure empirical-first via bytecode inspection of Reactor Netty 1.3.x + focused JUnit reproduction. No user pre-commitment.

- Q: Escalation threshold for the 'always bundle' rule — at what point does the team-lead come back to the user for an explicit re-bundle/split decision?
  A: Plan-preview only. Team-lead escalates if the analyst's plan-preview shows H2 work likely needs >1 dev-team run. Otherwise proceed without re-consult.
