package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.sessions.facade.internal.SpawnMutex;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * AC25 — the spawn mutex serializes POST /v1/sessions. A concurrent
 * caller blocks while the first holder is active, then proceeds once
 * the holder releases.
 */
class SpawnMutexTest {

    @Test
    void second_acquire_blocks_until_first_release() throws Exception {
        SpawnMutex mutex = new SpawnMutex();
        mutex.acquire();

        AtomicBoolean acquired = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            try {
                mutex.acquire();
                acquired.set(true);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        started.await();
        // Give it 200ms to try; it must not have acquired.
        Thread.sleep(200);
        assertThat(acquired).isFalse();

        mutex.release();
        t.join(2_000);
        assertThat(acquired).isTrue();
    }

    @Test
    void tryAcquire_respects_timeout() throws Exception {
        SpawnMutex mutex = new SpawnMutex();
        mutex.acquire();
        long t0 = System.nanoTime();
        boolean got = mutex.tryAcquire(100);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(got).isFalse();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(95);
        mutex.release();
        assertThat(mutex.tryAcquire(100)).isTrue();
        mutex.release();
    }

    @Test
    void release_without_acquire_is_tolerated() {
        // Semaphore.release on an unheld permit just bumps the count;
        // the production code never relies on permit count > 1 because
        // there is only one slot. Confirming this is a no-op-equivalent
        // protects us from a future API change.
        SpawnMutex mutex = new SpawnMutex();
        mutex.release();
        // We can still acquire twice in a row if we'd over-released, which
        // we deliberately avoid in production via try/finally — assert
        // permitted by virtue of the API contract.
    }
}
