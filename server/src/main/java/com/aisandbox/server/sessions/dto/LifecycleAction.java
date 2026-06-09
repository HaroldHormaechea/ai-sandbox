package com.aisandbox.server.sessions.dto;

/**
 * UC-46 — the four Docker-lifecycle operations the management server can
 * drive on a single session container, beyond spawn (create) and clean
 * (remove). Modelled on Docker's container lifecycle:
 *
 * <ul>
 *   <li>{@code STOP} — {@code docker compose stop}: send SIGTERM, preserve
 *       the container + volumes so the session can be {@code START}ed
 *       again later. Distinct from {@code clean.sh} (remove), which tears
 *       the container down with {@code down -v}.</li>
 *   <li>{@code START} — {@code docker compose start}: bring a previously
 *       stopped container back up. The container's {@code entrypoint.sh}
 *       re-runs (idempotently — it clears + rewrites
 *       {@code /tmp/aisandbox-ready} and recreates the tmux {@code main}
 *       session on every boot), so the session contract is re-established.</li>
 *   <li>{@code PAUSE} — {@code docker compose pause}: SIGSTOP via the cgroup
 *       freezer; suspends every process (tmux + Claude) without tearing
 *       anything down.</li>
 *   <li>{@code UNPAUSE} — {@code docker compose unpause}: resume a paused
 *       container.</li>
 * </ul>
 *
 * <p>Internal DTO (per {@code profile-java-server-architecture} rule 5 it
 * never carries API-shaping annotations). {@link #flag()} is the token
 * passed to {@code lifecycle.sh --action}; {@link #isValidFrom(String)}
 * encodes the canonical state→action transition matrix, mirrored verbatim
 * by the Android client so the menu greys out invalid actions before any
 * request is made:
 *
 * <table border="1">
 *   <caption>Canonical transition matrix</caption>
 *   <tr><th>Action</th><th>Valid from state</th></tr>
 *   <tr><td>{@code START}</td><td>{@code stopped}</td></tr>
 *   <tr><td>{@code STOP}</td><td>{@code running}, {@code provisioning}, {@code paused}</td></tr>
 *   <tr><td>{@code PAUSE}</td><td>{@code running}</td></tr>
 *   <tr><td>{@code UNPAUSE}</td><td>{@code paused}</td></tr>
 * </table>
 */
public enum LifecycleAction {
    STOP("stop"),
    START("start"),
    PAUSE("pause"),
    UNPAUSE("unpause");

    private final String flag;

    LifecycleAction(String flag) {
        this.flag = flag;
    }

    /** The {@code --action} token handed to {@code lifecycle.sh}. */
    public String flag() {
        return flag;
    }

    /**
     * Parse a wire path token ({@code stop|start|pause|unpause}, case
     * insensitive) into the matching action.
     *
     * @throws IllegalArgumentException for any unknown token (mapped to a
     *     400 {@code bad_request} by {@code ProblemDetailsAdvice})
     */
    public static LifecycleAction fromToken(String token) {
        if (token != null) {
            for (LifecycleAction a : values()) {
                if (a.flag.equalsIgnoreCase(token)) {
                    return a;
                }
            }
        }
        throw new IllegalArgumentException(
                "unknown lifecycle action: '" + token + "' (expected stop|start|pause|unpause)");
    }

    /**
     * Whether this action is a legal transition from the given server-reported
     * wire state. The matrix is authoritative and is mirrored exactly by the
     * Android client ({@code SessionsApi.LifecycleAction}).
     *
     * <p>An {@code action.isValidFrom(state) == false} request is rejected by
     * the facade with a 409 {@code session_state_conflict} — the server is the
     * final arbiter even though the client greys out the same actions.
     */
    public boolean isValidFrom(String state) {
        if (state == null) {
            return false;
        }
        return switch (this) {
            case START -> state.equals("stopped");
            case STOP -> state.equals("running") || state.equals("provisioning") || state.equals("paused");
            case PAUSE -> state.equals("running");
            case UNPAUSE -> state.equals("paused");
        };
    }
}
