package com.aisandbox.server.sessions.service;

import com.aisandbox.server.sessions.dto.SessionRecord;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * Thin caching layer in front of {@link DockerEnumerationService}.
 * Enumeration is several {@code docker} calls per project, so we hold the
 * last result for 1 second by default. Cache invalidation is explicit
 * (spawn / clean / stream open).
 *
 * <p>UC-62 — on a cache MISS the always-on server host-shell row (when its
 * tmux exists) is PREPENDED to the Docker enumeration before the combined list
 * is frozen for the TTL. Both the {@code tmux has-session} probe and the
 * resulting row therefore ride the same 1 s cache as Docker enumeration: a
 * cache HIT does no extra subprocess. Re-listing on every (un-cached) call for
 * as long as the tmux exists is exactly the persistence contract of AC7.
 */
@Service
public class SessionRegistryService {

    private static final Duration TTL = Duration.ofMillis(1_000L);

    private final DockerEnumerationService enumeration;
    private final HostShellSessionService hostShell;
    private final AtomicReference<Cached> cache = new AtomicReference<>(new Cached(Instant.EPOCH, List.of()));

    public SessionRegistryService(DockerEnumerationService enumeration, HostShellSessionService hostShell) {
        this.enumeration = enumeration;
        this.hostShell = hostShell;
    }

    public List<SessionRecord> list() throws IOException {
        Cached c = cache.get();
        if (Instant.now().isBefore(c.takenAt().plus(TTL))) {
            return c.records();
        }
        List<SessionRecord> docker = enumeration.enumerate();
        // UC-62 — prepend the host-shell row (pinned first server-side too) when
        // its tmux exists; exists() is a no-op when the feature is disabled.
        List<SessionRecord> fresh;
        if (hostShell.exists()) {
            fresh = new ArrayList<>(docker.size() + 1);
            fresh.add(hostShell.row());
            fresh.addAll(docker);
            fresh = List.copyOf(fresh);
        } else {
            fresh = docker;
        }
        cache.set(new Cached(Instant.now(), fresh));
        return fresh;
    }

    public boolean exists(int n) throws IOException {
        for (SessionRecord r : list()) {
            if (r.n() == n) {
                return true;
            }
        }
        return false;
    }

    public void invalidate() {
        cache.set(new Cached(Instant.EPOCH, List.of()));
    }

    private record Cached(Instant takenAt, List<SessionRecord> records) {}
}
