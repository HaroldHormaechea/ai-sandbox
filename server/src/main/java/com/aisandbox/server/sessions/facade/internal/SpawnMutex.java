package com.aisandbox.server.sessions.facade.internal;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * Server-wide single-occupant lock that serialises {@code POST /v1/sessions}.
 * AC25 — concurrent spawn requests execute one at a time.
 */
@Component
public class SpawnMutex {

    private final Semaphore lock = new Semaphore(1, true);

    public void acquire() throws InterruptedException {
        lock.acquire();
    }

    public boolean tryAcquire(long millis) throws InterruptedException {
        return lock.tryAcquire(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void release() {
        lock.release();
    }
}
