---
plan_for: use-cases/100-single-multiplexed-websocket.md
work_branch: feat/uc-100-single-multiplexed-websocket
team: ai-sandbox-uc-100
approved: 2026-07-25
---

# UC-100 — Single multiplexed WebSocket — FINAL APPROVED PROPOSAL

Challenger-approved (Round 2, after the R1 revision resolving 4 Majors + 2 Minors). `TARGET_DIR` = `/workspace/ai-sandbox-uc-100-single-multiplexed-websocket`. Prose only — the developer writes the code. All 11 ACs mapped; no locked decision disturbed; no `profile-java-server-architecture` violation.

---

## Analysis

Today the Android client holds up to **four** long-lived realtime WebSockets to the management server:
- per-session terminal `stream` — `android/src/main/kotlin/com/aisandbox/android/net/StreamClient.kt` (subprotocol `ai-sandbox.v1`; binary = PTY stdio, text = `ControlMessage`/`StreamServerMessage`);
- per-session `conversation` — `android/src/main/kotlin/com/aisandbox/android/net/ConversationClient.kt` (subprotocol `ai-sandbox.conv.v1`; text-only `ConversationServerMessage`/`ConversationClientMessage`);
- the global `sessions/events` feed — `android/src/main/kotlin/com/aisandbox/android/net/SessionEventsClient.kt` (subprotocol `ai-sandbox.v1`; one-way `SessionEventMessage` snapshot/delta) — with **two independent consumers**: `SessionsViewModel` (foreground) and the `PendingQuestionService` dataSync foreground service.

Each client owns its own OkHttp socket **and its own `ReconnectController`** (`android/.../net/ReconnectController.kt`), driven by independent controllers: `TerminalStreamController`, `ConversationController`, `SessionEventsController`. On a delete-session→create-session flow all live sockets tear down and their uncoordinated back-off loops re-dial inside the same per-IP window; the TLS-layer `PerIpRateLimiter` (`server/.../tls/PerIpRateLimiter.java` + `RateLimitingChannelHandler.java`) — 10 new conns/10 s, 10 concurrent, keyed on source IP *before* TLS — trips, and the storm self-sustains (the 1102 `Rate-limit reject … identity=harold-phone` log confirms it).

Server side, three reactive `WebSocketHandler`s are wired by two configs:
- `server/.../stream/config/WebSocketConfiguration.java` maps `/v1/sessions/*/stream` → `SessionStreamHandler` and `/v1/sessions/*/conversation` → `SessionConversationHandler`;
- `server/.../sessionevents/config/SessionEventsWebSocketConfiguration.java` maps `/v1/sessions/events` → `SessionEventWebSocketHandler`.

Identity is resolved once per socket (Netty channel-id → `ActiveConnectionRegistry`; anonymous → close 1008) and the fingerprint is indexed on `ActiveStreamRegistry` for the UC04 4401-revoke path. The dormant handshake-interceptor beans confirm Reactor-Netty exposes **no** working pre-upgrade filter — today's subprotocol/cap gates run handler-side post-101 and signal via WS close codes.

The UC-85 deterministic replay gate plugs in at the `TailSource` seam: `server/.../stream/replay/ReplayTailSource.java` (+ `ReplayEnvelopeReader`, `ReplaySessionCatalog`, `ReplayAnswerSink`) substitutes recorded fixtures **below** `ConversationEventMapper` and **below** the WebSocket transport. Its await-answer gate (`ReplayEnvelopeReader` parks on `ReplayAnswerSink.awaitAnswer`, released by `recordAnswer`/`recordAnswerBatch` from the conversation handler's answer path, ordered *after* the UC-96 answer-echo). Fixtures are `<source>\t<json>` envelope lines validated by `schemaVersion`. None of this touches the socket/handshake layer, so a **transport-only** multiplex leaves the replay source path byte-identical — provided the conversation channel's frame production/consumption and the echo→inject ordering are lifted faithfully.

---

## Proposed Solution

Collapse all realtime traffic onto **one** process-wide multiplexed WebSocket at a new versioned path `/v1/mux` (subprotocol `ai-sandbox.mux.v1`). All business logic — `StreamFacade`, `ConversationFacade`, `SessionEventFacade`, `TmuxBridgeService`, `ConversationEventMapper`, `TranscriptTailService`/`TailSource`+replay, `SessionEventBroadcaster`, `InputInjectionService`, and the `PerIpRateLimiter` — is **unchanged**; only the transport/handshake/routing layer is new.

### Envelope `{channel, sessionId, type, seq, payload}`
- `channel`: `control` | `stream` | `conversation` | `events`.
- `sessionId`: the session number `n` for per-session channels; omitted/null for `events` and `control`.
- `type`: the discriminator *within* the channel — for `control`: `hello`, `welcome`, `subscribe`, `unsubscribe`, `subscribed`, `unsubscribed`, `sub-error`, `error`; for the data channels the **existing** discriminators unchanged (`resize`, `mouse`, `targets`, `snapshot`, `delta`, `composer-input`, `answer`, `answer-batch`, …).
- `seq`: **per-subscription** monotonic counter. Carried-but-advisory *within* a channel (a single ordered TCP stream cannot reorder); its real job is **cross-reconnect gap detection** — on reconnect the client compares the last-seen per-channel `seq` and, on a gap, triggers that channel's authoritative resync (conversation backfill / events snapshot) rather than trusting deltas. Resolves the UC's open question in favor of per-channel.
- `payload`: the existing typed models nested verbatim — `ControlMessage`, `StreamServerMessage`, `ConversationServerMessage`/`ConversationClientMessage`, `SessionEventMessage` (locked decision 2).
- **Encoding.** JSON envelope for `control` and all text frames; the `stream` channel's PTY stdout/stdin use a **compact binary envelope** = fixed header `[channel:1B][sessionId:varint][seq:8B]` + raw bytes, sent as a WS binary frame — no base64 on the hot path. PTY output is an order-preserving, boundary-less byte stream, so chunking a large frame across envelopes is transparent (no client-side reassembly). One codec on each side.

### Server — new `mux` package (base dir `server/src/main/java/com/aisandbox/server/mux/`)
- `dto/Envelope.java` + `dto/MuxControlMessage.java` — envelope record with `@JsonTypeInfo` and the control sub-union (`hello`/`welcome`/`subscribe`/`unsubscribe`/`subscribed`/`unsubscribed`/`sub-error`/`error`). Lives in `mux/dto` because these are WS frames, not REST bodies — the same precedent as `stream/dto`, so the API-DTO-separation rule of `profile-java-server-architecture` is satisfied.
- `config/MultiplexWebSocketConfiguration.java` — a single `SimpleUrlHandlerMapping` for `/v1/mux` → `MultiplexWebSocketHandler`. **Removes** the stream, conversation, and events mappings from the two existing configs.
- `handler/MultiplexWebSocketHandler.java` — the sole WS entrypoint. Resolves identity once (channel-id → `ActiveConnectionRegistry`; anonymous → close 1008); runs the `hello`/`welcome` handshake; demuxes inbound frames by `(channel,sessionId)` to the right channel-session; owns the `subscribe`/`unsubscribe` lifecycle and the single fair outbound writer. Registers the one session on `ActiveStreamRegistry` (fingerprint→session, for the 4401 revoke) **and** registers a connection-level keepalive entry in `StreamRegistryService`.
- **`hello`/`welcome` handshake + per-channel caps (AC3).** First control frame from the client is `hello{protocol:"mux.v1", caps:{stream:…, conversation:…, events:…}}` advertising the client protocol version and requested per-channel caps. Server replies `welcome{protocol:"mux.v1", caps:{…}}` with the **negotiated** per-channel caps (defaults sourced from `ServerProperties.Streams` — max binary/text frame bytes, output-ring bytes, per-client/global stream caps). On a version mismatch the server replies `control/error{code:"upgrade_required"}` and closes with code **4426** (defense-in-depth for the new-client↔old-server direction; the primary signal is the capabilities probe + the legacy 426 route below).
- `channel/MuxChannelSession.java` (interface) + `channel/StreamChannelSession.java`, `channel/ConversationChannelSession.java`, `channel/EventsChannelSession.java` — the per-`(channel,sessionId)` business logic **extracted from** the three existing handlers, re-expressed against an abstract typed `FrameSink` + inbound callback instead of a raw `WebSocketSession`. Each emits typed payloads (with a per-subscription `seq`) and consumes typed inbound payloads; the handler wraps/unwraps envelopes. Delegation to `StreamFacade`/`ConversationFacade`/`SessionEventFacade` is preserved intact (Controller→Facade chain). The three existing handlers (`SessionStreamHandler`, `SessionConversationHandler`, `SessionEventWebSocketHandler`) retire as `WebSocketHandler`s; their internals move here.
- `service/MuxOutboundWriter.java` — the fairness + ordering core (AC7 + the AC11 replay-gate invariant):
  - **Per-channel bounded FIFO queues.** Each `(channel,sessionId)` subscription owns its own bounded queue; the writer round-robins *across* queues but preserves **strict FIFO within** each. Enqueue is the single serialization point (a concurrent-safe bounded queue), so the tail-pump thread and the boundedElastic inbound handlers enqueue **without a lock** — this replaces `SessionConversationHandler`'s `outboundLock`. There is no shared terminal token for a late frame to lose to, so the `FAIL_NON_SERIALIZED`/`FAIL_TERMINATED` hazard is *structurally removed*, not merely re-guarded.
  - **`ChannelComplete` sentinel (flush-before-teardown).** A `ChannelComplete` sentinel is enqueued as the **last** item of a channel's queue *only* on true unsubscribe/teardown. The writer guarantees every pre-sentinel frame is flushed to `session.send(...)` **before** the channel is removed and its `unsubscribed`/`sub-error` is emitted — the mux analogue of Reactor unicast's "terminal-after-data". This is what keeps a trailing `AnswerEcho` from being dropped.
  - **Echo-before-inject preserved literally.** `ConversationChannelSession` keeps the current code order: enqueue `AnswerEcho`(s) (single = one; batch = N in tab order) → **then** call `injectAnswer`/`injectAnswerBatch` (which, under the `replay` profile, offers the gate token → unparks the tail pump → the pump races to channel EOF). Because enqueue is synchronous + FIFO and the pump's EOF only enqueues the `ChannelComplete` sentinel (itself the last item), the echo is durably ahead of the sentinel — the FAIL_TERMINATED window cannot exist.
  - **switchTarget (UC-21) invariant preserved.** Closing the old tail during a target switch must **not** enqueue `ChannelComplete` (the channel stays live). The sentinel is gated by the same generation token the pump uses today: an old tail's EOF under a bumped generation is swallowed (continue), never a sentinel. Start-new → swap+bump → close-old ordering is unchanged.
  - **Connection-scoped completion.** The handler's single `session.send(mergedOutbound)` sees `onComplete` only when the **connection** closes (all channels gone / socket teardown), never on a per-channel EOF — structurally eliminating the "dropped terminal complete → silent hang" that `outboundLock` guarded.
  - **Chunking + backpressure.** Large `stream` payloads are chunked (≤ ~32 KiB per envelope, within the 256 KiB cap) so a terminal burst yields between chunks and cannot stall `conversation`/`events`. A per-channel queue overflow closes **that channel's subscription** with a `sub-error` (mirrors today's per-stream `stream_overflow`), never the whole connection — bounding server-side buffering.
- `service/MuxProtocol.java` — protocol-version constant + negotiated per-channel caps (defaults from `ServerProperties.Streams`).
- **Per-subscribe authorization taxonomy (preserves AC2/AC4).** A `subscribe(stream,n)` / `subscribe(conversation,n)` runs `StreamFacade.authorizeOpen(n, identity)` **at subscribe-time** (both channels authorize today), mapping the four result types to distinct control-channel `sub-error` codes carrying the existing `ErrorCode` vocabulary: `SessionNotFound` → `sub-error{code:session_not_found}` (was HTTP 404); `NotRunning` → `sub-error{code:session_not_running, state}` (409); `CapExceeded` → `sub-error{code:stream_cap_exceeded, scope}` (503); `Draining` → `sub-error{code:draining}` (503); `Allowed` → `subscribed{channel,sessionId}` ack. The client maps each `sub-error` code to the same UI state the HTTP status drove.
- **Stream-cap accounting (per-subscription `ActiveStream`).** The per-client/global cap is already per-`ActiveStream` (per PTY stream), not per-socket: `StreamFacade.authorizeOpen` reads `streamRegistry.globalCount()` + `streamRegistry.countFor(identity.fingerprintHex())`, and `StreamFacade.openStream` registers one `ActiveStream(id,n,fingerprint,session)` per open. Preserve the invariant: each `subscribe(stream,n)` → `openStream` registers its own `ActiveStream` (sharing the one mux `session`); `unsubscribe`/teardown → `closeStream(id)` unregisters. `countFor(fingerprint)` then counts concurrent stream **subscriptions** per identity = today's per-client cap exactly; `globalCount()` unchanged. Nothing socket-count-based exists to defeat.
- **Keepalive (connection-level entry).** `WebSocketKeepalive` sweeps `StreamRegistryService.snapshot()` and pings each `ActiveStream.session` on `lastIo` staleness. Under mux, stream ActiveStreams share one session (redundant pings — de-dup by session), and — critically — a client with **only** conversation/events subscriptions has no `ActiveStream` and would never be pinged (idle socket dies). Fix: `MultiplexWebSocketHandler` registers a **connection-level keepalive entry** for the mux session in `StreamRegistryService`, `touch`ed on **any** channel I/O, independent of stream subscriptions. Registry membership: `StreamRegistryService` = per-stream `ActiveStream`s (cap + idle-touch) **plus** the connection keepalive entry; `ActiveStreamRegistry` = the one mux session per fingerprint (4401 revoke path).
- **Legacy hard-cut signal (AC8).** `LegacyEndpointGoneHandler` is a **plain HTTP route** (a `RouterFunction` / `@RestController`, **NOT** a reactive `WebSocketHandler` — a WS handler runs post-101 and cannot emit a pre-upgrade status) mapped on the three legacy paths `/v1/sessions/{n}/stream`, `/v1/sessions/{n}/conversation`, `/v1/sessions/events`, returning **HTTP 426 Upgrade Required** + problem-details `client_upgrade_required`, and never attempting a WS upgrade. Since the mux mapping now claims only `/v1/mux`, no `HandlerMapping` claims the old paths for an upgrade, so an old client's `Upgrade: websocket` falls through to this HTTP route → 426 (non-101) → okhttp `onFailure` (ProtocolException) → fast, explicit failure, never a silent hang. Documented fallback: if 426 is ever unreachable in some environment, an unmapped path still yields a non-101 upgrade failure, which the *new* client treats as `upgrade_required`; the explicit 426 route is preferred for the old-client direction. A new REST probe `GET /v1/capabilities` → `{ws_protocol:"mux.v1"}` (an **API-layer DTO**, not a mux DTO) lets a new client detect an old server pre-connect and route to the update screen.
- **Untouched (AC9/AC10):** `PerIpRateLimiter`, `RateLimitingChannelHandler`, and `application.yaml` `ai-sandbox.server.limits.*` are not modified; REST list/spawn/delete/refresh stay on HTTP. Docs: new `server/MUX_PROTOCOL.md`; `STREAM_PROTOCOL.md`/`CONVERSATION_PROTOCOL.md`/`SESSIONS_EVENTS_PROTOCOL.md` marked superseded.

### Android — shared connection (base dir `android/src/main/kotlin/com/aisandbox/android/`)
- `net/MuxConnection.kt` — the single OkHttp WebSocket: envelope encode/decode (incl. the binary header), `subscribe(channel,n)`/`unsubscribe(channel,n)`, per-`(channel,sessionId)` inbound `SharedFlow`s, and **exactly one** `ReconnectController`. On reconnect it **re-subscribes to precisely the subscription set active before the drop, deduped** (AC6). Maps close **4401 → `NetworkEvent.CertRevoked`** (now the single mapping site) and **4426 → the update-required screen** — disjoint codes, explicitly disambiguated.
- `net/MuxConnectionManager.kt` — app-scoped (held by `AppContainer`); owns the connection and the authoritative subscription set; coordinates connect/back-off/re-subscribe.
- `net/MuxEnvelope.kt` — envelope DTO + codecs.
- `net/StreamClient.kt`, `net/ConversationClient.kt`, `net/SessionEventsClient.kt` — become thin channel adapters over `MuxConnection` (public `incoming`/`state`/`send*` surfaces preserved to minimize controller churn); they no longer own sockets or reconnect loops.
- `terminal/TerminalStreamController.kt`, `conversation/ConversationController.kt`, `ui/screens/SessionEventsController.kt` — `attach`/`connect` → `subscribe`; `close`/`disconnect` → `unsubscribe`; connection state derives from the shared `MuxConnection`; their own `ReconnectController`s are removed. `ui/screens/SessionsViewModel.kt` and `notifications/PendingQuestionService.kt` both subscribe to the `events` channel over the one connection (two feeds → two cheap subscriptions on one socket).
- Session open → `subscribe`, delete/close → `unsubscribe` — **no TCP open/close** (AC1/AC4). The update-required UI reuses the existing `ui/screens/AppUpdate*` / `ui/screens/ServerUpdate*` coordinator+screen machinery, triggered by the capabilities probe or the 4426 close.

---

## Files Affected

### Production code (developer)
**Server — new** (under `server/src/main/java/com/aisandbox/server/`):
- `mux/dto/Envelope.java`
- `mux/dto/MuxControlMessage.java`
- `mux/config/MultiplexWebSocketConfiguration.java`
- `mux/handler/MultiplexWebSocketHandler.java`
- `mux/channel/MuxChannelSession.java`
- `mux/channel/StreamChannelSession.java`
- `mux/channel/ConversationChannelSession.java`
- `mux/channel/EventsChannelSession.java`
- `mux/service/MuxOutboundWriter.java`
- `mux/service/MuxProtocol.java`
- `mux/handler/LegacyEndpointGoneHandler.java` (plain HTTP route → 426) **or** an equivalent `RouterFunction` config; plus a `/v1/capabilities` endpoint + API-layer response DTO under the existing REST controller package.

**Server — modified** (under `server/src/main/java/com/aisandbox/server/`):
- `stream/config/WebSocketConfiguration.java` (remove stream + conversation mappings)
- `sessionevents/config/SessionEventsWebSocketConfiguration.java` (remove events mapping)
- `stream/handler/SessionStreamHandler.java` (internals → `StreamChannelSession`)
- `stream/handler/SessionConversationHandler.java` (internals → `ConversationChannelSession`; echo-before-inject + switchTarget generation gating preserved)
- `sessionevents/handler/SessionEventWebSocketHandler.java` (internals → `EventsChannelSession`)
- `stream/service/WebSocketKeepalive.java` (connection-level keepalive entry)
- `ActiveStreamRegistry` attach/detach sites (one mux session per fingerprint)
- Docs: new `server/MUX_PROTOCOL.md`; mark `server/STREAM_PROTOCOL.md`, `server/CONVERSATION_PROTOCOL.md`, `server/SESSIONS_EVENTS_PROTOCOL.md` superseded.
- **Untouched (assert-only):** `server/.../tls/PerIpRateLimiter.java`, `.../tls/RateLimitingChannelHandler.java`, `server/src/main/resources/application.yaml` `limits.*`.

**Android — new** (under `android/src/main/kotlin/com/aisandbox/android/`):
- `net/MuxConnection.kt`
- `net/MuxConnectionManager.kt`
- `net/MuxEnvelope.kt`

**Android — modified** (under `android/src/main/kotlin/com/aisandbox/android/`):
- `net/StreamClient.kt`, `net/ConversationClient.kt`, `net/SessionEventsClient.kt` (thin channel adapters)
- `terminal/TerminalStreamController.kt`, `conversation/ConversationController.kt`, `ui/screens/SessionEventsController.kt` (subscribe/unsubscribe; own reconnect removed)
- `AppContainer.kt` (own + expose `MuxConnectionManager`)
- `ui/screens/SessionsViewModel.kt`, `notifications/PendingQuestionService.kt` (events over the shared connection)
- update-required screen wiring under `ui/screens/` (reuse `AppUpdate*`/`ServerUpdate*`)

### Test code (qa)
Under `server/src/test/**`, `android/src/test/**`, `android/src/androidTest/**`:
- Envelope (de)serialization incl. the binary header.
- Version negotiation: `hello`/`welcome`, `upgrade_required`, close 4426.
- Multiplex inbound routing by `(channel,sessionId)`.
- Subscribe/unsubscribe lifecycle incl. **unsubscribe tears down stream + conversation tails, no leaked tails** (AC5).
- Reconnect re-subscription with no duplicate subscriptions (AC6).
- **Per-channel fairness (AC7):** a large stream burst interleaves so conversation/events frames land within a bounded window; per-channel FIFO for both stream chunks and conversation echoes; per-channel ordering preserved.
- Per-subscribe `authorizeOpen` → `sub-error` taxonomy (the four codes).
- Legacy paths → HTTP 426 `client_upgrade_required` (AC8).
- Delete→create opens **no** new socket + **zero** `Rate-limit reject` entries (AC1).
- Rate-limiter config unchanged (AC9); REST list/spawn/delete unchanged (AC10).
- Adapt existing `StreamClientTest` / `ConversationControllerTest` / `SessionEvents*` tests to the shared connection.
- **UC-85 replay gate (AC11)** via `android/gate.sh` (or the `android-gate` CI job) against the `replay`-profile server. Fixtures need **no** re-capture (they live below the mapper), but the gate MUST run and must specifically exercise the **multi-question `answer-batch`** path over the mux connection (the UC-96 echo-before-inject differential — single-question passes while multi-question would regress if the trailing echoes are dropped).

---

## Risks & Considerations
- **TCP-level HOL is irreducible.** One TCP/TLS connection serializes bytes; application-layer fairness (chunk interleaving) satisfies AC7's "busy terminal doesn't starve conversation," but cannot remove byte-level serialization. QA asserts the app-layer property (bounded interleave window + per-channel FIFO), not sub-frame preemption.
- **Old-client↔new-server legibility** is bounded by what a pre-shipped client can do: the server guarantees fast-fail (HTTP 426 + audit log), not a retrofitted dialog. The UC locks coordinated `android-vX.Y.Z` + `server-vX.Y.Z` release and accepts old-client breakage; AC8's "explicit, never silent hang" is met at the handshake, not inside the legacy binary.
- **Reconnect races (AC5/AC6).** A subscribe/unsubscribe in flight at drop: the client's authoritative subscription set is re-asserted on reconnect; the server makes subscribe/unsubscribe **idempotent** (re-subscribe to a live channel dedupes; unsubscribe of an absent channel is a no-op), so no leaked/missing subscription survives.
- **Backpressure.** Per-channel bounded queues + credit; a queue overflow closes that one channel with `sub-error`, never the socket — no unbounded server buffering.
- **Binary/JSON dual codec** is a deliberate perf call on the terminal hot path (challenger-endorsed) — the PTY channel stays raw binary; everything else is JSON.
- **`seq` scope** is per-subscription, carried-but-advisory within a channel, consumed for cross-reconnect gap detection.
- **`profile-java-server-architecture`.** Mux DTOs are WS frames kept out of `..api`; the handler delegates to facades; transactions stay facade-owned. `/v1/capabilities` uses an API-layer DTO. No brief conflict.
- **UC-74 graceful shutdown / UC04 4401 revoke.** Bridge-teardown and the 4401 close now operate through the single connection's channel sessions; the existing registries (`StreamRegistryService`, `ActiveStreamRegistry`, `StreamBridgeRegistry`) are re-pointed, not removed.

---

## AC coverage map
AC1 shared connection + unsubscribe-not-close · AC2 payloads nested unchanged · AC3 fresh framing + `hello`/`welcome` caps · AC4 subscribe/unsubscribe on open/delete · AC5 unsubscribe → channel-session tail teardown · AC6 one `ReconnectController` + deduped re-subscribe · AC7 fair writer + chunk interleave + per-channel FIFO · AC8 remove mappings + HTTP 426 + `/v1/capabilities` + 4426 · AC9 limiter untouched · AC10 REST untouched · AC11 replay seam below transport + gate run exercising `answer-batch`.

## Challenger verdict
**APPROVED** (Round 2, after one revision round resolving 4 Majors + 2 Minors). All 11 acceptance criteria mapped, all pitfalls addressed, no profile violation, no scope creep.
