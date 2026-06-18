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

    /**
     * UC-85 — sessions not backed by a running Docker container. Late-bound via a setter (the
     * same pattern {@link com.aisandbox.server.stream.facade.StreamFacade} uses for the swarm
     * enumerator) so existing unit constructions of this service compile unchanged. Defaults to
     * {@link SyntheticSessionSource#NONE}; under the {@code replay} profile the
     * {@code ReplaySyntheticSessions} bean is injected and reports {@code exclusive()=true}, so
     * {@link #list()} returns the deterministic fixture sessions without running {@code docker}.
     */
    private volatile SyntheticSessionSource synthetic = SyntheticSessionSource.NONE;

    public SessionRegistryService(DockerEnumerationService enumeration, HostShellSessionService hostShell) {
        this.enumeration = enumeration;
        this.hostShell = hostShell;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSyntheticSessionSource(SyntheticSessionSource synthetic) {
        if (synthetic != null) {
            this.synthetic = synthetic;
        }
    }

    public List<SessionRecord> list() throws IOException {
        // UC-85 — under the replay profile the synthetic catalog is authoritative: return its
        // deterministic records and never shell out to docker (there is none in a replay run).
        SyntheticSessionSource syn = this.synthetic;
        if (syn.exclusive()) {
            return List.copyOf(syn.records());
        }
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
