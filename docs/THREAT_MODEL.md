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
