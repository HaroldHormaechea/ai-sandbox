package com.aisandbox.server.sessions.service;

import com.aisandbox.server.sessions.dto.SessionRecord;
import java.util.List;

/**
 * UC-85 — seam for supplying sessions that are NOT backed by a running Docker container.
 *
 * <p>Today the only implementation is the deterministic-gate {@code replay} profile's
 * synthetic catalog ({@code ReplaySyntheticSessions}): it returns a {@code running} record per
 * fixture scenario so the device sees a card to open, and reports {@link #exclusive()} as
 * {@code true} so {@link SessionRegistryService} returns those records WITHOUT shelling out to
 * {@code docker} at all (there is no Docker in a replay run). Outside replay no bean is wired,
 * so {@link SessionRegistryService} uses the {@link #NONE} default and its behaviour is
 * unchanged.
 */
public interface SyntheticSessionSource {

    /** The synthetic session records to expose. */
    List<SessionRecord> records();

    /**
     * When {@code true}, {@link #records()} <b>replaces</b> Docker enumeration entirely (no
     * {@code docker} subprocess is run). When {@code false}, the records would be additive — but
     * today only the exclusive (replay) case exists.
     */
    boolean exclusive();

    /** The default no-op source: no synthetic sessions, never exclusive (production behaviour). */
    SyntheticSessionSource NONE = new SyntheticSessionSource() {
        @Override
        public List<SessionRecord> records() {
            return List.of();
        }

        @Override
        public boolean exclusive() {
            return false;
        }
    };
}
