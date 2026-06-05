package com.aisandbox.android.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * UC-32 — Android mirror of the server's
 * {@code com.aisandbox.server.sessionevents.dto.SessionEventMessage} sealed
 * hierarchy: the server→client text frames on the {@code /v1/sessions/events}
 * WebSocket (the live sessions-list status-push channel).
 *
 * <p>Polymorphic on the {@code "type"} discriminator — kotlinx-serialization's
 * default class discriminator — so {@code {"type":"snapshot",…}} and
 * {@code {"type":"delta",…}} decode to the matching subtype. Decode with a
 * lenient {@link kotlinx.serialization.json.Json} ({@code ignoreUnknownKeys =
 * true}) so a future server field never breaks the client.
 *
 * <p>The row type is the existing [SessionSummary] verbatim: the server's
 * {@code Row} record mirrors {@code ApiDtos.SessionSummary} field-for-field, and
 * [SessionSummary] is already the type the sessions list renders — so the push
 * payload feeds [com.aisandbox.android.ui.screens.SessionsCoordinator] with zero
 * extra mapping (AC2). Reusing it (rather than declaring a parallel `Row`) keeps
 * the two wire DTOs from drifting.
 */
@Serializable
sealed interface SessionEventMessage {

    /**
     * The authoritative full session list. Sent once at subscribe and again on
     * every reconnect; the client applies it as a full resync that replaces its
     * working set (AC5).
     */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val sessions: List<SessionSummary> = emptyList(),
    ) : SessionEventMessage

    /**
     * A coalesced incremental update. [upserts] are rows to insert-or-replace
     * keyed by {@code n}; [removed] are the session numbers that vanished. The
     * client applies both idempotently keyed by {@code n} (AC3, AC4).
     */
    @Serializable
    @SerialName("delta")
    data class Delta(
        val upserts: List<SessionSummary> = emptyList(),
        val removed: List<Int> = emptyList(),
    ) : SessionEventMessage
}
