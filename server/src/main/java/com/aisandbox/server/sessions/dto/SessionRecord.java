package com.aisandbox.server.sessions.dto;

import java.time.Instant;

/**
 * Internal projection of one ai-sandbox session — what the {@code GET /v1/sessions}
 * list returns, modulo API-DTO mapping.
 *
 * <p>UC04 AC37 widened the {@code state} value set from
 * {@code running | exited} to {@code running | starting | stopped};
 * UC-27 added {@code provisioning} (a Docker-running container still
 * installing its spawn-time toolchains, before {@code /tmp/aisandbox-ready}
 * exists); UC-28 added {@code terminating} and UC-46 added {@code paused}, so
 * the full set is now
 * {@code running | starting | provisioning | terminating | paused | stopped}.
 * The Java type stays {@link String} so the wire format and the mapper
 * ({@code ApiMappers.toSummary}) need no DTO churn; only the meaning
 * widens. {@code DockerEnumerationService.mapState} owns the
 * Docker-status translation and {@code DockerEnumerationService.enumerate}
 * layers {@code provisioning} / {@code terminating} on via the readiness
 * probe + in-flight-delete registry.
 *
 * @param n            session number
 * @param label        free-form label from {@code com.ai-sandbox.label}, may be empty
 * @param tmuxTitle    tmux window title, normalised; {@code (idle)} / {@code (unavailable)}
 * @param state        {@code running} | {@code starting} | {@code provisioning} | {@code terminating} | {@code paused} | {@code stopped}
 * @param uptimeSec    seconds since the container started, or 0 when unknown
 * @param activeStreams currently-attached WebSocket count
 * @param startedAt    container start time, or epoch when unknown
 */
public record SessionRecord(
        int n, String label, String tmuxTitle, String state, long uptimeSec, int activeStreams, Instant startedAt) {}
