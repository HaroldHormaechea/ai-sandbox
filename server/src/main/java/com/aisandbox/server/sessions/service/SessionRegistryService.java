package com.aisandbox.server.sessions.service;

import com.aisandbox.server.sessions.dto.SessionRecord;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * Thin caching layer in front of {@link DockerEnumerationService}.
 * Enumeration is several {@code docker} calls per project, so we hold the
 * last result for 1 second by default. Cache invalidation is explicit
 * (spawn / clean / stream open).
 */
@Service
public class SessionRegistryService {

    private static final Duration TTL = Duration.ofMillis(1_000L);

    private final DockerEnumerationService enumeration;
    private final AtomicReference<Cached> cache = new AtomicReference<>(new Cached(Instant.EPOCH, List.of()));

    public SessionRegistryService(DockerEnumerationService enumeration) {
        this.enumeration = enumeration;
    }

    public List<SessionRecord> list() throws IOException {
        Cached c = cache.get();
        if (Instant.now().isBefore(c.takenAt().plus(TTL))) {
            return c.records();
        }
        List<SessionRecord> fresh = enumeration.enumerate();
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
