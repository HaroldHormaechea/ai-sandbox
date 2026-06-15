package com.aisandbox.server.sessions.facade;

import com.aisandbox.server.sessions.service.SandboxImageState;

/**
 * UC-77 — thrown by the spawn path when the {@code ai-context:latest} sandbox
 * image is not yet ready (still {@link SandboxImageState#BUILDING building}, or
 * {@link SandboxImageState#ABSENT absent}/{@link SandboxImageState#FAILED
 * failed} so a warm build has just been (re)kicked asynchronously). The request
 * itself NEVER runs the heavy build; it is rejected fast.
 *
 * <p>Mapped to HTTP 503 {@code sandbox_image_warming} by
 * {@code ProblemDetailsAdvice} so the client can surface a transient
 * "preparing image" state and retry, rather than reading it as the destructive
 * hard-failure / re-enroll path.
 */
public final class SandboxImageWarmingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient SandboxImageState state;

    public SandboxImageWarmingException(SandboxImageState state) {
        super("sandbox image not ready (state=" + state + "); the image is being prepared — retry shortly");
        this.state = state;
    }

    public SandboxImageState state() {
        return state;
    }
}
