package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

/**
 * AC25 — DELETE / stream-open per-N mutex behaviour.
 */
class PerSessionMutexRegistryTest {

    @Test
    void get_returns_the_same_lock_for_the_same_n() {
        PerSessionMutexRegistry reg = new PerSessionMutexRegistry();
        ReentrantLock a = reg.get(3);
        ReentrantLock b = reg.get(3);
        assertThat(a).isSameAs(b);
    }

    @Test
    void get_returns_independent_locks_for_distinct_n() {
        PerSessionMutexRegistry reg = new PerSessionMutexRegistry();
        ReentrantLock a = reg.get(1);
        ReentrantLock b = reg.get(2);
        assertThat(a).isNotSameAs(b);
    }

    @Test
    void evict_removes_idle_locks() {
        PerSessionMutexRegistry reg = new PerSessionMutexRegistry();
        ReentrantLock lock = reg.get(4);
        reg.evict(4);
        // Same N issued again must produce a fresh lock since the prior was evicted.
        ReentrantLock again = reg.get(4);
        assertThat(again).isNotSameAs(lock);
    }

    @Test
    void evict_keeps_active_locks() {
        PerSessionMutexRegistry reg = new PerSessionMutexRegistry();
        ReentrantLock lock = reg.get(5);
        lock.lock();
        try {
            reg.evict(5);
            // While held, the lock must persist.
            assertThat(reg.get(5)).isSameAs(lock);
        } finally {
            lock.unlock();
        }
    }
}
