package com.aisandbox.server.stream.facade;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.sessions.SpawnPromptInjector;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.InputInjectionService;
import com.aisandbox.server.stream.service.InputInjectionService.InjectTarget;
import com.aisandbox.server.stream.service.InputInjectionService.SpawnInjectOutcome;
import com.aisandbox.server.stream.service.SwarmEnumerationService;
import com.aisandbox.server.stream.service.TranscriptTailService;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * UC-98 — {@link ConversationFacade} implements the {@code sessions}-domain
 * {@link SpawnPromptInjector} port (the dependency-inversion seam that lets the
 * session facade pre-seed a prompt without a {@code sessions → stream} cycle).
 * These tests pin that the port implementation:
 *
 * <ul>
 *   <li>drives the spawn-scoped {@link InputInjectionService#injectSpawnPrompt}
 *       (Bug 1 — gate → type → confirm → exactly-once Enter) against the
 *       session's <b>main</b> pane, NOT the shared {@code injectComposer} (which
 *       fires Enter back-to-back and loses it on the pre-prompt spawn path);</li>
 *   <li>records a <b>server-actor</b> audit line (no client fingerprint) whose
 *       {@code outcome} reflects the inject result — {@code ok} when submitted,
 *       {@code prompt-not-ready} / {@code type-not-confirmed} on either graceful
 *       degrade — so the injection is distinguishable from a client-driven
 *       composer submission AND its result is auditable.</li>
 * </ul>
 */
class ConversationFacadeSpawnInjectTest {

    private InputInjectionService injection;
    private AuditLogger audit;
    private ConversationFacade facade;

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

    @BeforeEach
    void setUp() {
        StreamFacade streamFacade = mock(StreamFacade.class);
        SwarmEnumerationService swarm = mock(SwarmEnumerationService.class);
        TranscriptTailService tail = mock(TranscriptTailService.class);
        injection = mock(InputInjectionService.class);
        ConversationEventMapper mapper = mock(ConversationEventMapper.class);
        audit = mock(AuditLogger.class);
        facade = new ConversationFacade(streamFacade, swarm, tail, injection, mapper, audit, props());
    }

    @Test
    void inject_drives_the_spawn_scoped_variant_and_audits_ok_when_submitted() throws Exception {
        String text = "We will work in the project cool-folder.";
        when(injection.injectSpawnPrompt(eq(5), eq(InjectTarget.main()), eq(text)))
                .thenReturn(SpawnInjectOutcome.SUBMITTED);

        facade.inject(5, text);

        // Bug 1 — the spawn path uses the gated injectSpawnPrompt, NOT the shared injectComposer.
        InOrder order = inOrder(injection, audit);
        order.verify(injection).injectSpawnPrompt(eq(5), eq(InjectTarget.main()), eq(text));
        // Server-actor audit line (actor=server, targetId=main), outcome ok, ordered after inject.
        order.verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_INPUT),
                        eq("ok"),
                        eq("n"),
                        eq(5),
                        eq("targetId"),
                        eq("main"),
                        eq("actor"),
                        eq("server"));
        // The verified-good conversation composer is NEVER used on the spawn path.
        verify(injection, never()).injectComposer(eq(5), eq(InjectTarget.main()), eq(text));
    }

    @Test
    void inject_audits_prompt_not_ready_when_the_gate_times_out() throws Exception {
        String text = "We will work in the project cool-folder.";
        when(injection.injectSpawnPrompt(eq(5), eq(InjectTarget.main()), eq(text)))
                .thenReturn(SpawnInjectOutcome.PROMPT_NOT_READY);

        facade.inject(5, text);

        // Graceful degrade — the outcome is surfaced in the audit line, not swallowed.
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_INPUT),
                        eq("prompt-not-ready"),
                        eq("n"),
                        eq(5),
                        eq("targetId"),
                        eq("main"),
                        eq("actor"),
                        eq("server"));
    }

    @Test
    void inject_audits_type_not_confirmed_when_the_literal_never_echoes() throws Exception {
        String text = "We will work in the project cool-folder.";
        when(injection.injectSpawnPrompt(eq(5), eq(InjectTarget.main()), eq(text)))
                .thenReturn(SpawnInjectOutcome.TYPE_NOT_CONFIRMED);

        facade.inject(5, text);

        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_INPUT),
                        eq("type-not-confirmed"),
                        eq("n"),
                        eq(5),
                        eq("targetId"),
                        eq("main"),
                        eq("actor"),
                        eq("server"));
    }
}
