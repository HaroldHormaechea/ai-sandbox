package com.aisandbox.server.sessions.service;

/**
 * UC-77 — lifecycle state of the {@code ai-context:latest} sandbox image as
 * tracked in-JVM by {@link SandboxImageService}. The spawn path consults this
 * (via {@code SandboxImageFacade}) to decide whether the heavy
 * {@code docker compose build} has already happened, so a cold first build is
 * never run inside the tight per-request spawn timeout.
 */
public enum SandboxImageState {
    /**
     * The image is present and current — spawning may proceed unchanged
     * (warm path, byte-identical to pre-UC-77 behaviour).
     */
    READY,

    /**
     * A warm build is in progress (the {@code docker compose build} is
     * running). A spawn request arriving now is rejected fast with 503
     * {@code sandbox_image_warming} rather than blocking on the build.
     */
    BUILDING,

    /**
     * No local image yet, and no build has been kicked off (or the last
     * classify reported it absent). The initial state before the startup
     * warm-up runs.
     */
    ABSENT,

    /**
     * The last warm build (or its pre-flight classify) failed. A subsequent
     * spawn re-kicks an async warm and is rejected fast with 503; it is never
     * served the heavy build on the request thread.
     */
    FAILED
}
