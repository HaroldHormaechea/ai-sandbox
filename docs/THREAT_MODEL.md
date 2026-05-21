# Threat model — UC03 mTLS management server

Scope: the Java management server's network and on-disk surface only.
The UC02 host-script + Docker kit has its own threats covered elsewhere.

## Assets

| Asset                          | Value to attacker                                    |
|--------------------------------|------------------------------------------------------|
| Docker socket (`/var/run/docker.sock`) | Full container lifecycle; trivial host root via container escape. |
| Server private key             | Impersonate the server (MITM future TLS sessions).   |
| Client private keys (operator-side) | Authenticate as that operator.                  |
| Audit log                      | Forensic value; tampering hides attacks.             |
| Operator workstation P12 bundle | Same as client private key.                         |
| Live tmux sessions             | Interactive shell inside a Claude container; can write to host-mounted workspace. |

## Trust boundaries

```
[ Hostile internet ]  ──> port 12410/TCP ──> [ mTLS handshake ]
                                              │
                                              ▼ (allowlist match)
                                          [ ai-sandbox-server JVM ]
                                              │
                                              ├─> exec UC02 host scripts
                                              ├─> docker compose / docker exec
                                              └─> /etc/ai-sandbox-server/*
```

The mTLS handshake is the **only** authentication gate. Anything past it
runs with full admin authority over the kit (AC17 — flat authz in this
MVP).

## Threats and mitigations

### T1 — Internet exposure of port 12410

Default bind is `0.0.0.0`/`::`. A misconfigured firewall exposes the port
to the public internet. Without mTLS this would be catastrophic; with
mTLS the attack surface narrows to:

- TLS 1.3 implementation bugs in OpenJDK / Netty.
- Allowlist-management bugs (cert leak, weak parse).
- Allowlist-cert leakage by an authorised operator.

Mitigation: host-level firewalling is recommended in the README. TLS
cipher allowlist is explicit (`TLS_AES_256_GCM_SHA384`,
`TLS_CHACHA20_POLY1305_SHA256`, `TLS_AES_128_GCM_SHA256`); no
plaintext fallback path exists.

### T2 — Docker socket = host root

Anyone with a valid client cert can `docker run --privileged` against the
host indirectly (by spawning a session). The `ai-sandbox-server` user is
intentionally in the `docker` group; this is the privilege boundary the
mTLS gate protects.

Mitigation: the watcher tears down active connections from a revoked cert
within ≤ 1 s. Operators should revoke immediately on suspected key
compromise.

### T3 — Server private key at rest (plain PEM, mode 0600)

The server's RSA private key lives at `/etc/ai-sandbox-server/pki/server.key`
as a plain PEM at mode 0600. A host-level intruder with root can read it.

**Accepted for MVP; documented upgrade path:** wrap the key with a
passphrase, store the passphrase in a systemd `EnvironmentFile=` that the
unit reads at boot, and unlock the key in
`ReloadableSslContextHolder.rebuild`. The change is purely additive on
the server side; the operator workflow gains `aisandboxctl pki init` flag
`--passphrase`.

### T4 — Shell-out injection through the host scripts

`spawn.sh` and `clean.sh` are called via `ProcessBuilder` with argument
arrays only. There is no shell interpolation anywhere. The few
caller-controlled inputs (label, mode flags, session number) are
validated against a strict regex / enum before exec.

The label regex is `[A-Za-z0-9._:/+\\- ]{1,64}`; this is permissive
enough for human-friendly labels but does not allow shell metacharacters.
A future tightening to ban whitespace is feasible.

### T5 — tmux multi-attach footprint

Per-client tmux sessions (`tmux new-session -t main`) create a new tmux
client per stream. Under heavy multi-attach (10 per cert × 10 certs)
that's 100 simultaneous tmux clients inside one container, each holding
a PTY. `/dev/pts` exhaustion is a real risk on heavily-loaded hosts.

The systemd unit does not set `LimitNOFILE=` explicitly; if this bites
in practice, raise it in a follow-up.

### T6 — Audit-log tampering

The audit log is JSON Lines, daily-rotated, on a directory writable only
by `ai-sandbox-server`. A host-level intruder with root can edit or
delete history. Off-host shipping (journald → remote syslog, or a
filebeat shipper) is recommended but out of scope for UC03.

### T7 — Allowlist folder trust

The allowlist folder is consulted on every TLS handshake. A host-level
intruder with write access to that folder can mint themselves a cert and
authenticate. The folder is owned by `ai-sandbox-server` at mode 0750;
the systemd unit mounts it read-only into the process namespace.

### T8 — Sketch: bypass via host filesystem mount

A successful `docker run -v /etc/ai-sandbox-server:/host-pki:ro` against
the kit's docker socket exposes the server PKI to a container the
attacker controls. Treat any attacker with valid mTLS as having read
access to the server cert + allowlist as well — the mTLS gate is not a
defence against itself.

## Out of scope for UC03

- Per-cert authorisation (read-only vs admin). Tracked as a follow-up;
  every valid cert is admin today.
- Resumable streams across network drops.
- Off-host log shipping.
- Container-side hardening (those decisions belong to UC02's threat
  model).
- DDoS / SYN flood protections beyond the per-IP rate limit.

## Enrollment trust boundary (UC04)

UC04 adds an Android client whose initial bootstrap necessarily happens
**without** an existing client certificate — the whole point is to
deliver one. To make that possible the server's
`io.netty.handler.ssl.ClientAuth` was flipped from `REQUIRE` to
`OPTIONAL`, and a new HTTP-level filter
(`com.aisandbox.server.api.MtlsEnforcementFilter`) re-imposes the same
gate at L7. The single mTLS-exempt path is:

```
POST /v1/enrollment   (body: {"token": "<single-use opaque token>"})
```

Every other path returns `401 mtls_required` when the connecting client
has not presented (and the trust manager has not approved) a real cert.
This section enumerates the new threats, the mitigations, and the
residual risk that comes with widening the trust surface.

### TLS-layer change — REQUIRE → OPTIONAL

**Threat.** Before UC04, a TCP connection without a client cert was
torn down during the TLS handshake; the application never saw the
attempt. After UC04 the handshake completes and the request reaches
the WebFilter chain. A bug in `MtlsEnforcementFilter`'s bypass logic
(e.g. a path-traversal that resolves to `/v1/enrollment/../sessions`)
would expose the entire authenticated API anonymously.

**Mitigations.**
- The bypass list is exactly two strings: `/v1/enrollment` and
  `/v1/enrollment/` (trailing-slash normalisation matching
  `RequestSizeLimitFilter`). Sub-paths under `/v1/enrollment/foo` are
  NOT bypassed.
- The filter runs at `Ordered.HIGHEST_PRECEDENCE + 10`, ahead of any
  routing — there is no path-rewrite that the filter would miss.
- `ClientIdentityExtractor` (HIGHEST_PRECEDENCE) is the canonical
  ATTR-writer; it reads the identity off the Netty channel id, never
  off a header that could be forged.
- Any path widening to the bypass list MUST update this section.

### Single-use token semantics

**Threat.** A leaked token (operator's QR screenshot, SD card image of
a phone, etc.) lets an attacker fetch a client cert for someone else's
device — a silent take-over of the operator's intended enrollment.

**Mitigations.**
- Tokens carry ≥256 bits of entropy (32 bytes / 64 hex chars from
  `SecureRandom`) — brute-forcing the value space is computationally
  infeasible.
- Default lifetime 10 minutes, configurable via
  `ai-sandbox.server.enrollment.default-ttl-minutes`. Operator workflow
  is "run `aisandboxctl client invite`, scan the QR within minutes,
  done"; the window is small enough that a leaked token typically
  expires before reuse.
- Single-use: redemption deletes the token file. A second redemption
  attempt — by the legitimate device that recovered from a network
  blip, or by an attacker who acquired the QR — returns
  `enrollment_token_redeemed` (401).
- The token is never logged. The audit log records the lowercase reject
  reason (`token-invalid`, `token-expired`, `token-redeemed`,
  `rate-limited`) and the source IP, never the token itself.

### Atomic file-delete serialization

**Threat.** Two simultaneous redemption attempts on the same token race
to mint two certs. If both win, the operator has shipped a cert to an
attacker without realising it.

**Mitigation.** `EnrollmentTokenStore.redeem(token, clock)` wraps the
read-verify-delete critical section in a `synchronized` block — only
one caller inside the JVM at a time. The actual atomicity comes from
`Files.deleteIfExists` (POSIX-atomic on Linux); the synchronized block
is the in-process serialization point. The losing caller sees
`Files.deleteIfExists → false` and is mapped to `ALREADY_REDEEMED`
through a bounded LRU tombstone set (1024 recent tokens).

### Per-IP rate limit

**Threat.** A network-level attacker who has tapped one Android QR
flow can replay-attempt the token at line rate while the legitimate
device is still walking to the operator's machine. The race favours
whichever side hits the server first; per-IP rate-limiting tilts that
race back to the operator's network.

**Mitigation.** `EnrollmentRateLimiterService` enforces 1 redemption per
60 s per source IP by default
(`ai-sandbox.server.enrollment.rate-limit-{per-window,window-seconds}`).
Independent counter from the pre-TLS `PerIpRateLimiter` so a noisy
neighbour at the TCP layer can't consume an enrollment slot. Trip
returns `429 enrollment_rate_limited`.

The 256-byte hard cap on the request body (`RequestSizeLimitFilter`
specialization for `/v1/enrollment`) prevents amplification attempts
that pad the JSON to extract differential timing.

### PKCS#12 transport-passphrase — fixed sentinel (UC14)

**Threat.** A PKCS#12 file wrapped with a publicly-known passphrase
looks like a mishandled secret — naive log scanners see the constant
string in the source code and flag it as a credentials leak.

**Mitigation by design.** The sentinel passphrase
`ai-sandbox-enrollment` is intentional and not a secret. The bundle is
delivered over the same TLS connection that authenticated the
single-use token, consumed entirely in-memory by the Android client
(no `Files.write`), and the imported key lives in the Android KeyStore
as non-exportable. The wire envelope (TLS) is the secret, not the P12
passphrase. The response carries
`X-AI-Sandbox-P12-Passphrase: ai-sandbox-enrollment` as an
informational header so the client can sanity-check without
hard-coding the convention. Operators MUST NOT redirect the response
body to disk.

**Why not empty?** The JDK 21 default PKCS#12 emitter wraps the
private-key bag with PBES2 (PBKDF2-HMAC-SHA256 + AES-256) regardless
of whether the passphrase is empty. The Android-side parser is
BouncyCastle 1.79 (bundled by UC13 because Android's stock providers
don't register `SecretKeyFactory` under the bare PBKDF2 OID
`1.2.840.113549.1.5.12`). BouncyCastle 1.79 hard-rejects an empty
`char[]` during PBKDF2 key derivation —
`IllegalArgumentException("password empty")`. No system property
disables that check. The fixed sentinel is the smallest coordinated
change that gets the two sides to agree.

### Operator hygiene for `/var/lib/ai-sandbox-server/enrollment/`

**Threat.** The token store lives at
`/var/lib/ai-sandbox-server/enrollment/` by default (operator-owned, mode
0700). A host-level intruder with read access there can lift unredeemed
tokens and pre-empt the legitimate enrollment.

**Mitigation.**
- `EnrollmentTokenStore.ensureDir()` creates the directory at mode
  0700 on first use; the systemd unit's `ReadWritePaths=` is the
  authoritative narrowing.
- Each token file is mode 0600 via tmp + `ATOMIC_MOVE`.
- A scheduled `purgeExpired()` job (60 s `fixedDelay`) keeps the
  on-disk footprint bounded so operator audit is feasible.
- Operators should NOT keep the directory backed up off-host — token
  files are short-lived, the backup would be stale by the time it
  could be exploited.

### Non-goal: re-keying existing clients

`POST /v1/enrollment` is for **bootstrap only**. The proposal explicitly
excludes using the endpoint to roll a compromised cert: revocation
remains a folder-delete operation against `/etc/ai-sandbox-server/clients/`
followed by a fresh `aisandboxctl client invite`. Treating enrollment
as a re-key path would let an attacker who stole a current cert mint
themselves a replacement under the same name without the operator's
involvement.

### Residual risk

Even with every mitigation above, a network-adjacent attacker who
catches a QR scan in flight (e.g. a malicious agent in the lobby of the
operator's office) can race the legitimate device on the redemption,
and rate limiting cannot break a near-tie. The acceptable mitigation
today is operator vigilance: keep the QR off camera, redeem promptly,
verify the cert CN on the device matches what was invited.

A future revision should consider deriving enrollment authority from a
separate operator-only mTLS-gated bootstrap channel (operator
authenticates over the existing mTLS port, mints a one-shot enrollment
URL with a short-lived nonce, the Android device scans that). That
removes the mTLS-exempt path entirely. The operator burden — one extra
step per device — is the reason the simpler shape was chosen for UC04.

### Anonymous-identity sentinel

For accounting symmetry, a TLS connection that completes the handshake
without a client cert is attached to `ClientIdentity.ANONYMOUS` via
`ActiveConnectionRegistry.attachAnonymous`. The
`MtlsEnforcementFilter` treats anonymous identically to a null
identity: every non-enrollment path is rejected. The sentinel exists
so log lines and the audit record can distinguish "TLS layer never
ran" (registry miss) from "TLS layer ran without auth" (anonymous) —
useful when triaging filter ordering bugs.

### Graceful WS close on revocation

When the allowlist watcher detects a removed cert, the
`ActiveConnectionRegistry.revoke(Set<String>)` orchestration now
graceful-closes every active WebSocket session for that fingerprint
(close code `4401`, reason `revoked`) before tearing down the
underlying TCP channel — the Android client AC26 cert-revoked dialog
fires on that close frame. The 100 ms timeout keeps a non-responsive
client from delaying the TCP-layer tear-down beyond AC13's 1-second
budget.
