package com.aisandbox.server.sessions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.cli.secrets.EnsureSandboxImage;
import com.aisandbox.server.cli.secrets.ServerVersion;
import com.aisandbox.server.config.ServerProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * UC-77 — the server-side warm-up of the {@code ai-context:latest} sandbox
 * image. Pins the contract the spawn gate relies on (AC1/AC3/AC5):
 *
 * <ul>
 *   <li>An <b>absent</b> image is built — through the SAME static
 *       {@link EnsureSandboxImage} argv (so the warm-built image is
 *       label-identical and {@code classify} later reports CURRENT, no double
 *       build) — and the resulting state is {@link SandboxImageState#READY}.
 *       The build argv carries {@code --build-arg IMAGE_VERSION=<version>}.</li>
 *   <li>A <b>current / stale</b> image is left in place: the warm path runs no
 *       {@code docker compose build} at all.</li>
 *   <li>The build is bounded by {@link
 *       ServerProperties.Limits#imageBuildTimeoutSeconds()} (30&nbsp;min), NOT
 *       the tight per-request {@code spawn-timeout-seconds}.</li>
 *   <li>Concurrency: an {@link java.util.concurrent.atomic.AtomicReference}
 *       CAS into {@link SandboxImageState#BUILDING} means exactly one build
 *       runs no matter how many callers race {@link
 *       SandboxImageService#warm()}.</li>
 *   <li>A build failure (non-zero compose exit, post-build image still absent,
 *       or an {@link ProcessExecutor.ExecTimeoutException}) lands the state in
 *       {@link SandboxImageState#FAILED}.</li>
 *   <li>AC3 — a successful warm build emits a distinct {@code
 *       SANDBOX_IMAGE_WARM / ok} audit line; a failed one emits {@code
 *       SANDBOX_IMAGE_WARM / fail} (never a {@code spawn_failed}), so an
 *       operator can tell "warming up" from "broken".</li>
 * </ul>
 */
class SandboxImageServiceTest {

    /** ServerVersion.current() returns the DEV_FALLBACK ("dev") outside a packaged jar (unit tests). */
    private static final String VERSION = ServerVersion.current();

    private static ServerProperties props() {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15));
    }

    private static HostScriptLocator locatorAt(Path repoRoot) {
        HostScriptLocator locator = mock(HostScriptLocator.class);
        when(locator.repoRoot()).thenReturn(repoRoot);
        return locator;
    }

    /** True for the label-reading classify inspect (the only argv carrying {@code --format}). */
    private static boolean isClassifyInspect(List<String> argv) {
        return argv.contains("--format");
    }

    /** True for the {@code docker compose ... build} argv. */
    private static boolean isBuild(List<String> argv) {
        return argv.contains("build");
    }

    // ── AC1/AC5 — absent ⇒ build ⇒ READY, with the version-stamped argv ──

    @Test
    void absent_image_is_built_and_becomes_ready_stamping_the_server_version() throws Exception {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        AuditLogger audit = mock(AuditLogger.class);
        AtomicReference<List<String>> buildArgv = new AtomicReference<>();
        AtomicReference<Duration> buildTimeout = new AtomicReference<>();

        when(executor.run(anyList(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            Duration to = inv.getArgument(2);
            if (isClassifyInspect(argv)) {
                return new ProcessExecutor.Result(125, "Error: no such image", ""); // ⇒ ABSENT
            }
            if (isBuild(argv)) {
                buildArgv.set(argv);
                buildTimeout.set(to);
                return new ProcessExecutor.Result(0, "", ""); // build OK
            }
            return new ProcessExecutor.Result(0, "", ""); // post-build present probe OK
        });

        SandboxImageService svc = new SandboxImageService(executor, locatorAt(Path.of("/repo")), props(), audit);

        assertThat(svc.warm()).isEqualTo(SandboxImageState.READY);
        assertThat(svc.state()).isEqualTo(SandboxImageState.READY);

        // Built through the SAME static argv builder the onboard path uses, and
        // it stamps the running server version via --build-arg IMAGE_VERSION=<v>.
        assertThat(buildArgv.get())
                .containsSequence("docker", "compose")
                .containsSequence("--build-arg", EnsureSandboxImage.BUILD_ARG + "=" + VERSION)
                .endsWith(
                        "build",
                        "--build-arg",
                        EnsureSandboxImage.BUILD_ARG + "=" + VERSION,
                        EnsureSandboxImage.COMPOSE_SERVICE);

        // AC1 — the heavy build is bounded by imageBuildTimeoutSeconds (30 min),
        // NOT the tight per-request spawn timeout (5s in this fixture).
        assertThat(buildTimeout.get())
                .as("warm build must use imageBuildTimeoutSeconds, not spawn-timeout-seconds")
                .isEqualTo(Duration.ofSeconds(ServerProperties.IMAGE_BUILD_TIMEOUT_SECONDS_DEFAULT));

        // AC3 — a distinct SANDBOX_IMAGE_WARM/ok audit line (NOT spawn_failed).
        verify(audit)
                .logEvent(
                        eq(AuditAction.SANDBOX_IMAGE_WARM),
                        eq("ok"),
                        eq("image"),
                        eq(EnsureSandboxImage.IMAGE_TAG),
                        eq("durationMs"),
                        any());
    }

    // ── AC5 — present (current) ⇒ no build ──────────────────────────────

    @Test
    void current_image_skips_the_build_and_is_ready() throws Exception {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        AuditLogger audit = mock(AuditLogger.class);
        AtomicInteger buildCount = new AtomicInteger();

        when(executor.run(anyList(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isClassifyInspect(argv)) {
                return new ProcessExecutor.Result(0, VERSION, ""); // label == version ⇒ CURRENT
            }
            if (isBuild(argv)) {
                buildCount.incrementAndGet();
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        SandboxImageService svc = new SandboxImageService(executor, locatorAt(Path.of("/repo")), props(), audit);

        assertThat(svc.warm()).isEqualTo(SandboxImageState.READY);
        assertThat(buildCount.get())
                .as("a CURRENT image must not be rebuilt on the warm path")
                .isZero();
        // No warm-build audit line on the no-op path. (4 args pins the Object...
        // overload, disambiguating it from the Map<String,?> overload.)
        verify(audit, never()).logEvent(eq(AuditAction.SANDBOX_IMAGE_WARM), any(), any(), any());
    }

    @Test
    void stale_image_is_left_in_place_no_build_on_warm_path() throws Exception {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        AtomicInteger buildCount = new AtomicInteger();

        when(executor.run(anyList(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isClassifyInspect(argv)) {
                return new ProcessExecutor.Result(0, "server-v0.0.1", ""); // != version ⇒ STALE
            }
            if (isBuild(argv)) {
                buildCount.incrementAndGet();
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        SandboxImageService svc =
                new SandboxImageService(executor, locatorAt(Path.of("/repo")), props(), mock(AuditLogger.class));

        assertThat(svc.warm()).isEqualTo(SandboxImageState.READY);
        assertThat(buildCount.get())
                .as("a STALE image is the upgrade path's job to refresh, not the warm path's")
                .isZero();
    }

    // ── AC5 — CAS: exactly one build under concurrent warm() callers ─────

    @Test
    void concurrent_warm_callers_build_at_most_once() throws Exception {
        int threads = 8;
        ProcessExecutor executor = mock(ProcessExecutor.class);
        AtomicInteger buildCount = new AtomicInteger();
        CountDownLatch buildEntered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);

        when(executor.run(anyList(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isClassifyInspect(argv)) {
                return new ProcessExecutor.Result(125, "", ""); // ABSENT
            }
            if (isBuild(argv)) {
                buildCount.incrementAndGet();
                buildEntered.countDown();
                // Hold the (single) winner inside the build so the other callers
                // genuinely race the CAS while BUILDING is observable.
                proceed.await(5, TimeUnit.SECONDS);
                return new ProcessExecutor.Result(0, "", "");
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        SandboxImageService svc =
                new SandboxImageService(executor, locatorAt(Path.of("/repo")), props(), mock(AuditLogger.class));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier startLine = new CyclicBarrier(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    startLine.await();
                    return svc.warm();
                });
            }
            assertThat(buildEntered.await(5, TimeUnit.SECONDS))
                    .as("the winning caller should reach the build")
                    .isTrue();
        } finally {
            proceed.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(buildCount.get())
                .as("the AtomicReference CAS must serialise warm() to a single build")
                .isEqualTo(1);
        assertThat(svc.state()).isEqualTo(SandboxImageState.READY);
    }

    // ── AC1/AC3 — failure modes land FAILED + emit a fail audit line ─────

    @Test
    void non_zero_compose_build_lands_failed_and_audits_fail() throws Exception {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(executor.run(anyList(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isClassifyInspect(argv)) {
                return new ProcessExecutor.Result(125, "", ""); // ABSENT
            }
            if (isBuild(argv)) {
                return new ProcessExecutor.Result(2, "", "compose build blew up"); // build FAILS
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        SandboxImageService svc = new SandboxImageService(executor, locatorAt(Path.of("/repo")), props(), audit);

        assertThat(svc.warm()).isEqualTo(SandboxImageState.FAILED);
        assertThat(svc.state()).isEqualTo(SandboxImageState.FAILED);
        verify(audit)
                .logEvent(
                        eq(AuditAction.SANDBOX_IMAGE_WARM),
                        eq("fail"),
                        eq("image"),
                        eq(EnsureSandboxImage.IMAGE_TAG),
                        eq("durationMs"),
                        any(),
                        eq("error"),
                        any());
    }

    @Test
    void build_success_but_image_still_absent_lands_failed() throws Exception {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(executor.run(anyList(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isClassifyInspect(argv)) {
                return new ProcessExecutor.Result(125, "", ""); // ABSENT
            }
            if (isBuild(argv)) {
                return new ProcessExecutor.Result(0, "", ""); // build CLAIMS success
            }
            return new ProcessExecutor.Result(1, "", "no such image"); // post-build probe: still absent
        });

        SandboxImageService svc = new SandboxImageService(executor, locatorAt(Path.of("/repo")), props(), audit);

        assertThat(svc.warm()).isEqualTo(SandboxImageState.FAILED);
        verify(audit)
                .logEvent(
                        eq(AuditAction.SANDBOX_IMAGE_WARM), eq("fail"), eq("image"), any(), any(), any(), any(), any());
    }

    @Test
    void exec_timeout_during_build_lands_failed() throws Exception {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(executor.run(anyList(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isClassifyInspect(argv)) {
                return new ProcessExecutor.Result(125, "", ""); // ABSENT
            }
            if (isBuild(argv)) {
                throw new ProcessExecutor.ExecTimeoutException("exec timeout: docker");
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        SandboxImageService svc = new SandboxImageService(executor, locatorAt(Path.of("/repo")), props(), audit);

        assertThat(svc.warm())
                .as("an ExecTimeoutException (an IOException subtype) during the build lands FAILED, not a crash")
                .isEqualTo(SandboxImageState.FAILED);
        verify(audit)
                .logEvent(
                        eq(AuditAction.SANDBOX_IMAGE_WARM), eq("fail"), eq("image"), any(), any(), any(), any(), any());
    }

    /**
     * Regression guard (UC-77) — a RuntimeException from the executor (e.g. it
     * returns {@code null}, NPEing the classify step, as a {@code @Primary}
     * Mockito mock does in the full-context integration test) MUST land the
     * state in {@link SandboxImageState#FAILED}, NOT leave it stuck at
     * {@link SandboxImageState#BUILDING}. A wedged BUILDING is unrecoverable —
     * {@code warm()} early-returns on BUILDING and {@code warmAsync()} no-ops on
     * it — so the spawn gate would 503 every session for the life of the JVM.
     * FAILED is recoverable (the gate re-kicks {@code warmAsync} on
     * ABSENT/FAILED).
     */
    @Test
    void runtime_exception_from_executor_lands_failed_not_stuck_building() throws Exception {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        AuditLogger audit = mock(AuditLogger.class);

        // The classify inspect returns null (exactly what an un-stubbed Mockito
        // mock does) → classifyImage NPEs. A non-IOException must not wedge.
        when(executor.run(anyList(), any(), any(Duration.class))).thenReturn(null);

        SandboxImageService svc = new SandboxImageService(executor, locatorAt(Path.of("/repo")), props(), audit);

        SandboxImageState result = svc.warm();
        assertThat(result)
                .as("a RuntimeException during classify must not leave the warm path wedged at BUILDING")
                .isNotEqualTo(SandboxImageState.BUILDING);
        assertThat(svc.state())
                .as("state must be a terminal, recoverable FAILED — never a permanent BUILDING wedge")
                .isEqualTo(SandboxImageState.FAILED);
    }

    // ── warmAsync — kicks the build off-thread, idempotent when settled ──

    @Test
    void warm_async_kicks_a_build_and_eventually_becomes_ready() throws Exception {
        ProcessExecutor executor = mock(ProcessExecutor.class);

        when(executor.run(anyList(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isClassifyInspect(argv)) {
                return new ProcessExecutor.Result(125, "", ""); // ABSENT
            }
            return new ProcessExecutor.Result(0, "", ""); // build + present probe OK
        });

        SandboxImageService svc =
                new SandboxImageService(executor, locatorAt(Path.of("/repo")), props(), mock(AuditLogger.class));

        svc.warmAsync();

        // The daemon thread should drive the state to READY shortly.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (svc.state() != SandboxImageState.READY && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(svc.state()).isEqualTo(SandboxImageState.READY);
    }

    @Test
    void warm_async_is_a_no_op_when_already_ready() throws Exception {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        when(executor.run(anyList(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isClassifyInspect(argv)) {
                return new ProcessExecutor.Result(0, VERSION, ""); // CURRENT
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        SandboxImageService svc =
                new SandboxImageService(executor, locatorAt(Path.of("/repo")), props(), mock(AuditLogger.class));

        // Settle to READY first.
        assertThat(svc.warm()).isEqualTo(SandboxImageState.READY);

        // A redundant async kick when READY must not re-classify or rebuild.
        svc.warmAsync();
        Thread.sleep(100);
        // Only the single warm() classify ran; warmAsync short-circuited.
        verify(executor, times(1)).run(anyList(), any(), any(Duration.class));
    }
}
