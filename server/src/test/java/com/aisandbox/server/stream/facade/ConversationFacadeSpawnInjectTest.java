package com.aisandbox.server.stream.facade;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.sessions.SpawnPromptInjector;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.InputInjectionService;
import com.aisandbox.server.stream.service.InputInjectionService.InjectTarget;
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
 *   <li>types the text into the session's <b>main</b> pane and submits it via
 *       {@link InputInjectionService#injectComposer} — which presses Enter, so
 *       this is inject + submit (AC4);</li>
 *   <li>records a <b>server-actor</b> audit line (no client fingerprint) so the
 *       injection is distinguishable from a client-driven composer submission.</li>
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
    void inject_types_into_the_main_pane_and_submits_then_audits_as_server_actor() throws Exception {
        String text = "We will work in the project cool-folder.";

        facade.inject(5, text);

        // AC4 — typed into the session's main pane and submitted (injectComposer presses Enter).
        InOrder order = inOrder(injection, audit);
        order.verify(injection).injectComposer(eq(5), eq(InjectTarget.main()), eq(text));
        // Server-actor audit line (actor=server, targetId=main), ordered after the inject.
        order.verify(audit)
                .logEvent(eq(AuditAction.CONVERSATION_INPUT), eq("ok"), eq("n"), eq(5), eq("targetId"), eq("main"),
                        eq("actor"), eq("server"));
    }
}
