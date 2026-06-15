package com.aisandbox.server.sessions.facade;

import com.aisandbox.server.sessions.service.SandboxImageService;
import com.aisandbox.server.sessions.service.SandboxImageState;
import org.springframework.stereotype.Component;

/**
 * UC-77 — use-case boundary for the sandbox-image warm-up domain. A thin
 * same-domain facade over {@link SandboxImageService}: the startup
 * {@code SandboxImageWarmupRunner} (a Job) calls {@link #warm()} here, and
 * {@code SessionFacade} consults {@link #state()} / kicks {@link #warmAsync()}
 * from its spawn gate (facade-to-facade, same {@code sessions} domain).
 *
 * <p>No {@code @Transactional}: there is no data store. Layering follows
 * {@code profile-java-server-architecture} rule 6 — Job/Facade → Facade →
 * Service.
 */
@Component
public class SandboxImageFacade {

    private final SandboxImageService service;

    public SandboxImageFacade(SandboxImageService service) {
        this.service = service;
    }

    /** Synchronously ensure the image is present (build only when absent). */
    public SandboxImageState warm() {
        return service.warm();
    }

    /** Kick a warm build on a daemon thread and return immediately. */
    public void warmAsync() {
        service.warmAsync();
    }

    /** Current sandbox-image state. */
    public SandboxImageState state() {
        return service.state();
    }
}
