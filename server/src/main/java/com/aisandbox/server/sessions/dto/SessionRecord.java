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
 * @param conversationName UC-47 — the derived Claude conversation name for the
 *     session's MAIN pane (newest {@code summary} line, else the first real user
 *     prompt, codepoint-capped server-side), or {@code null} when none is known
 *     (idle / no active conversation / lookup not yet warmed / non-running). The
 *     Android row prefers it over {@code tmuxTitle} as the primary status line,
 *     falling back to {@code tmuxTitle} when null/blank (AC1, AC3).
 */
public record SessionRecord(
        int n,
        String label,
        String tmuxTitle,
        String state,
        long uptimeSec,
        int activeStreams,
        Instant startedAt,
        String conversationName) {

    /**
     * UC-47 back-compat 7-arg constructor for call sites (and test fixtures)
     * created before this use case appended {@code conversationName}. Delegates
     * with a {@code null} name, so a session reported through the old shape
     * simply carries no conversation name and the row falls back to
     * {@code tmuxTitle} — exactly the pre-UC-47 behaviour.
     */
    public SessionRecord(
            int n, String label, String tmuxTitle, String state, long uptimeSec, int activeStreams, Instant startedAt) {
        this(n, label, tmuxTitle, state, uptimeSec, activeStreams, startedAt, null);
    }
}
