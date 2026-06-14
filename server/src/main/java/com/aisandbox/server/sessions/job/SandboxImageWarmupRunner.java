package com.aisandbox.server.sessions.job;

import com.aisandbox.server.sessions.facade.SandboxImageFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * UC-77 — kicks the sandbox-image warm build once the application context is
 * up, so the {@code ai-context:latest} image is (re)built ahead of the first
 * interactive spawn rather than during it. AC5: idempotent (the underlying
 * {@code SandboxImageService.warm()} only builds when the image is absent and
 * degrades gracefully when already present), and it NEVER blocks startup.
 *
 * <p>The warm build runs on a dedicated DAEMON thread: a cold build can take
 * minutes, and an {@link ApplicationStartedEvent} listener runs on the main
 * boot thread — blocking it would stall the server's readiness. A daemon
 * thread also won't keep the JVM alive on shutdown. Any failure is swallowed
 * (logged + recorded as {@code FAILED} by the service); a failed warm must not
 * crash the server, and the spawn gate will re-kick a warm on the next request.
 *
 * <p>Layering: a Job ({@code profile-java-server-architecture} rule 6) — it
 * calls the {@link SandboxImageFacade} only. Excluded from the {@code docs-only}
 * profile (the OAS render boots without Docker and must not shell out), mirroring
 * the other startup components.
 */
@Component
@Profile("!docs-only")
public class SandboxImageWarmupRunner implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxImageWarmupRunner.class);

    private final SandboxImageFacade sandboxImage;

    public SandboxImageWarmupRunner(SandboxImageFacade sandboxImage) {
        this.sandboxImage = sandboxImage;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        Thread t = new Thread(
                () -> {
                    try {
                        LOG.info("Sandbox image warm-up starting (background)");
                        sandboxImage.warm();
                    } catch (RuntimeException e) {
                        // Never let a warm-up failure escape the daemon thread.
                        LOG.warn("Sandbox image warm-up errored: {}", e.toString());
                    }
                },
                "sandbox-image-warmup");
        t.setDaemon(true);
        t.start();
    }
}
