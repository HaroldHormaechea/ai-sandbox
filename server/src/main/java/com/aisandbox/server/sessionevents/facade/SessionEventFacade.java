package com.aisandbox.server.sessionevents.facade;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Row;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.facade.SessionFacade;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * UC-32 — use-case boundary for the live sessions-list push channel
 * ({@code /v1/sessions/events}). Sits between the WebSocket handler / scheduled
 * watcher (entry points) and the data it serves.
 *
 * <p>Layering ({@code profile-java-server-architecture} rule 6): this facade
 * performs a <b>cross-domain read</b> of the sessions domain, so it goes
 * facade→facade — {@link #snapshot()} calls {@link SessionFacade#listSessions()}
 * rather than reaching into the sessions domain's services or repositories. The
 * channel is read-only and stateless per call, so there is no transaction to
 * own — no {@code @Transactional} here.
 *
 * <p>It deliberately has NO source dependency on the {@code stream} package: the
 * events channel is a separate, no-cycle slice that only depends on
 * {@code sessions} (its data source) and {@code identity} (the authenticated
 * principal type).
 */
@Component
public class SessionEventFacade {

    private static final Logger LOG = LoggerFactory.getLogger(SessionEventFacade.class);

    /**
     * Light per-client subscription cap for the events channel. This is NOT the
     * terminal-stream {@code perClientCap} (a heavyweight PTY bridge); a status
     * feed is cheap, but a misbehaving client should still not be able to open
     * an unbounded number of feeds. A constant for now (the use case's optional
     * {@code ServerProperties} knob is deferred).
     */
    static final int MAX_SUBSCRIPTIONS_PER_FINGERPRINT = 4;

    private final SessionFacade sessionFacade;
    private final SessionEventBroadcaster broadcaster;

    /**
     * Honoured by {@link #authorizeSubscribe(ClientIdentity)} so a draining
     * server rejects new subscriptions during graceful shutdown — the
     * events-channel analogue of {@code StreamFacade}'s draining flag. Exposed
     * as a hook ({@link #setDraining(boolean)}) for a shutdown orchestrator to
     * flip; defaults to {@code false}.
     */
    private volatile boolean draining = false;

    public SessionEventFacade(SessionFacade sessionFacade, SessionEventBroadcaster broadcaster) {
        this.sessionFacade = sessionFacade;
        this.broadcaster = broadcaster;
    }

    /** Outcome of the subscribe authorization check. */
    public enum SubscribeDecision {
        /** Subscription permitted. */
        ALLOWED,
        /** Server is draining for shutdown — reject new subscriptions. */
        DRAINING,
        /** This fingerprint already holds the maximum number of event feeds. */
        CAP_EXCEEDED
    }

    /**
     * Authorize a new subscription for {@code identity}. Caller (the handler)
     * has already rejected null / {@link ClientIdentity#isAnonymous() anonymous}
     * identities at the transport layer; this enforces the draining guard and
     * the light per-fingerprint cap.
     */
    public SubscribeDecision authorizeSubscribe(ClientIdentity identity) {
        if (draining) {
            return SubscribeDecision.DRAINING;
        }
        if (broadcaster.countFor(identity.fingerprintHex()) >= MAX_SUBSCRIPTIONS_PER_FINGERPRINT) {
            return SubscribeDecision.CAP_EXCEEDED;
        }
        return SubscribeDecision.ALLOWED;
    }

    /**
     * Build the authoritative current snapshot of all sessions.
     *
     * <p>Reads through {@link SessionFacade#listSessions()} (which is backed by
     * a ~1 s cache) and maps each {@link SessionRecord} to a wire {@link Row}.
     *
     * <p><b>IOException is swallowed, not propagated</b> (the use case's "Where
     * the server learns of state changes" pitfall): an enumeration outage must
     * never escape into the scheduler thread (which would kill the watcher) nor
     * abort a subscribe. On failure we log and return an empty list — the next
     * successful tick re-syncs via a {@code Delta}, and a brand-new subscriber
     * simply gets an empty {@code Snapshot} that the client reconciles against
     * its REST refresh (AC5).
     */
    public SessionEventMessage.Snapshot snapshot() {
        List<Row> rows;
        try {
            rows = sessionFacade.listSessions().stream()
                    .map(SessionEventFacade::toRow)
                    .toList();
        } catch (IOException io) {
            LOG.warn("sessionevents snapshot enumeration failed; serving empty snapshot: {}", io.toString());
            rows = List.of();
        }
        return new SessionEventMessage.Snapshot(rows);
    }

    private static Row toRow(SessionRecord r) {
        return new Row(
                r.n(),
                r.label(),
                r.tmuxTitle(),
                r.state(),
                r.uptimeSec(),
                r.activeStreams(),
                r.startedAt(),
                r.conversationName(),
                r.working());
    }

    /**
     * Graceful-shutdown hook (events-channel analogue of
     * {@code StreamFacade#setDraining(boolean)}). When set, new subscriptions
     * are refused with {@link SubscribeDecision#DRAINING}. Wiring this to the
     * shutdown lifecycle is deferred so this slice stays self-contained.
     */
    public void setDraining(boolean draining) {
        this.draining = draining;
    }

    public boolean isDraining() {
        return draining;
    }
}
