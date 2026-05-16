package com.aisandbox.server.sessions.facade.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Per-session {@link ReentrantLock} registry. Serialises operations that
 * mutate a single session (DELETE, stream open). Identity is the session
 * number {@code N}; entries are created on demand.
 *
 * <p>The map is unbounded but the population is bounded by the live session
 * count (typically &lt; 100); cleanup is delegated to a future enhancement.
 */
@Component
public class PerSessionMutexRegistry {

    private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock get(int n) {
        return locks.computeIfAbsent(n, k -> new ReentrantLock(true));
    }

    public void evict(int n) {
        ReentrantLock l = locks.get(n);
        if (l != null && !l.isLocked() && !l.hasQueuedThreads()) {
            locks.remove(n, l);
        }
    }
}
