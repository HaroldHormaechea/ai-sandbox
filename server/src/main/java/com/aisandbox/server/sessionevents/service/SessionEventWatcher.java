package com.aisandbox.server.sessionevents.service;

import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Row;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * UC-32 — the source of truth for "the server observed a state change". A
 * scheduled job that polls the authoritative session snapshot, diffs it against
 * the previous tick, and broadcasts one coalesced {@link SessionEventMessage.Delta}
 * to every live {@code /v1/sessions/events} subscriber.
 *
 * <p><b>Why a periodic reconcile-and-diff rather than a docker-events stream</b>
 * (the use case's "Where the server learns of state changes" decision): the
 * REST list already enumerates ALL transitions the UI cares about —
 * client-initiated spawn/delete AND out-of-band ones (a container exiting on its
 * own, OOM, host reboot, provisioning→running readiness). Diffing that same
 * enumeration guarantees the push feed is never staler than a manual refresh,
 * with no second watch mechanism to keep in sync.
 *
 * <p><b>Subscriber-gated</b> (the "avoiding a thundering reconcile" pitfall):
 * when no one is listening the tick returns immediately and resets its baseline,
 * so there is zero enumeration churn on an idle server. When subscribers drop to
 * zero the baseline is cleared; the next subscriber gets a fresh full
 * {@code Snapshot} from the handler, and the watcher re-baselines on its first
 * tick (emitting no spurious delta).
 *
 * <p>Layering: this is a Job ({@code profile-java-server-architecture} rule 6).
 * It calls the {@link SessionEventFacade} (facade) for its read and the
 * {@link SessionEventBroadcaster} directly — the broadcaster is an infra sink,
 * not a domain service, so the job→facade rule is not crossed (the same
 * exception the stream handler relies on for {@code ActiveStreamRegistry}). No
 * {@code @Transactional}: there is nothing to persist.
 */
@Component
@Profile("!docs-only")
public class SessionEventWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SessionEventWatcher.class);

    private final SessionEventFacade facade;
    private final SessionEventBroadcaster broadcaster;

    /**
     * The last broadcast baseline, keyed by session {@code n}. {@code null}
     * means "no baseline" — either the server just started or subscribers
     * dropped to zero. Accessed only from the single-threaded scheduler, so it
     * needs no synchronization.
     */
    private Map<Integer, Row> previous = null;

    public SessionEventWatcher(SessionEventFacade facade, SessionEventBroadcaster broadcaster) {
        this.facade = facade;
        this.broadcaster = broadcaster;
    }

    /**
     * Poll → diff → broadcast. {@code fixedDelay} (not {@code fixedRate}) so a
     * slow enumeration can never overlap itself. Combined with the sessions
     * registry's ~1 s read cache this gives a worst-case observe→push latency
     * of roughly two seconds (AC1).
     */
    @Scheduled(fixedDelay = 1000L)
    public void tick() {
        if (broadcaster.subscriberCount() == 0) {
            // Nobody listening — do not enumerate, and drop the baseline so the
            // next subscriber re-syncs cleanly (no stale delta on resubscribe).
            previous = null;
            return;
        }

        // facade.snapshot() swallows enumeration IOExceptions (returns empty),
        // so this never throws into the scheduler thread.
        List<Row> current = facade.snapshot().sessions();
        Map<Integer, Row> currentByN = new LinkedHashMap<>();
        for (Row r : current) {
            currentByN.put(r.n(), r);
        }

        if (previous == null) {
            // First tick after (re)gaining subscribers: the handler already sent
            // each of them an authoritative Snapshot, so just establish the
            // baseline without emitting a redundant Delta.
            previous = currentByN;
            return;
        }

        List<Row> upserts = new ArrayList<>();
        for (Row r : current) {
            Row prevRow = previous.get(r.n());
            // Record equality compares every field, so an unchanged row is
            // skipped and a state/label/uptime/etc. change is an upsert.
            if (!r.equals(prevRow)) {
                upserts.add(r);
            }
        }
        List<Integer> removed = new ArrayList<>();
        for (Integer n : previous.keySet()) {
            if (!currentByN.containsKey(n)) {
                removed.add(n);
            }
        }

        previous = currentByN;

        if (upserts.isEmpty() && removed.isEmpty()) {
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("sessionevents delta: {} upsert(s), {} removal(s)", upserts.size(), removed.size());
        }
        broadcaster.broadcast(new SessionEventMessage.Delta(upserts, removed));
    }
}
