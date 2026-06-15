package com.aisandbox.server.stream.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.sessions.service.SyntheticSessionSource;
import com.aisandbox.server.stream.service.DockerTailSource;
import com.aisandbox.server.stream.service.TranscriptTailService.TailSource;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * UC-85 (AC-11, layer 1 — production-safety by bean absence). The deterministic-gate seams must
 * exist ONLY under the {@code replay} Spring profile; on every other profile (i.e. a real
 * deployment) the wired beans must be the production ones, so there is no synthetic-session
 * source, no recording answer sink, and therefore no {@code AnswerEcho} path at all.
 *
 * <p>This drives exactly the profile-gated components through a focused {@link
 * ApplicationContextRunner} (no full app boot) and asserts which bean wins on each side of the
 * profile switch:
 *
 * <ul>
 *   <li><b>replay OFF (production):</b> {@link TailSource} = {@link DockerTailSource} (the live
 *       docker tail), {@link ReplayAnswerSink} = {@link NoopReplayAnswerSink} with {@code
 *       enabled()==false} (so the conversation handler never emits {@code AnswerEcho}), and NO
 *       {@link SyntheticSessionSource} bean (session enumeration stays the real docker path).
 *       None of the replay-only beans exist.</li>
 *   <li><b>replay ON (the gate):</b> {@link TailSource} = {@link ReplayTailSource},
 *       {@link ReplayAnswerSink} = {@link RecordingReplayAnswerSink} with {@code enabled()==true},
 *       and a {@link SyntheticSessionSource} that is {@code exclusive()} — the docker beans are
 *       gone.</li>
 * </ul>
 */
class ReplayProfileBeanWiringTest {

    /** Committed fixtures live at {@code <repo>/fixtures/replay}; cwd is {@code server/}. */
    private static final Path REPO_FIXTURES = Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("fixtures")
            .resolve("replay");

    /** All profile-gated components on both sides of the switch — Spring picks the right ones. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    DockerTailSource.class,
                    NoopReplayAnswerSink.class,
                    RecordingReplayAnswerSink.class,
                    ReplayProperties.class,
                    ReplayFixtureValidator.class,
                    ReplaySessionCatalog.class,
                    ReplayTailSource.class,
                    ReplaySyntheticSessions.class,
                    ReplayProfileGuard.class);

    @Test
    void productionProfile_wiresDockerTailSource_noEchoNoSynthetic() {
        runner.run(ctx -> {
            // The live docker tail is the only TailSource; the replay one is absent.
            assertThat(ctx).hasSingleBean(TailSource.class);
            assertThat(ctx.getBean(TailSource.class)).isInstanceOf(DockerTailSource.class);
            assertThat(ctx).doesNotHaveBean(ReplayTailSource.class);

            // The answer sink is the no-op (production) one and is NOT enabled → no AnswerEcho path.
            assertThat(ctx).hasSingleBean(ReplayAnswerSink.class);
            ReplayAnswerSink sink = ctx.getBean(ReplayAnswerSink.class);
            assertThat(sink).isInstanceOf(NoopReplayAnswerSink.class);
            assertThat(sink.enabled())
                    .as("AC-11 — outside replay the answer sink is disabled, so the handler never echoes")
                    .isFalse();
            assertThat(ctx).doesNotHaveBean(RecordingReplayAnswerSink.class);

            // No synthetic session source → SessionRegistryService keeps the real docker enumeration.
            assertThat(ctx).doesNotHaveBean(SyntheticSessionSource.class);
            assertThat(ctx).doesNotHaveBean(ReplaySyntheticSessions.class);

            // None of the replay-only infrastructure beans exist on a production profile.
            assertThat(ctx).doesNotHaveBean(ReplaySessionCatalog.class);
            assertThat(ctx).doesNotHaveBean(ReplayProperties.class);
            assertThat(ctx).doesNotHaveBean(ReplayFixtureValidator.class);
            assertThat(ctx).doesNotHaveBean(ReplayProfileGuard.class);
        });
    }

    @Test
    void replayProfile_wiresReplaySeams_andEnablesEcho() {
        runner.withInitializer(this::activateReplay)
                .withPropertyValues(
                        "ai-sandbox.server.replay.dir=" + REPO_FIXTURES,
                        // A guaranteed-absent marker so ReplayProfileGuard does not abort the test boot.
                        "ai-sandbox.server.replay.production-marker-path=" + REPO_FIXTURES.resolve("__never__"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();

                    // The fixture-backed tail replaces the docker one.
                    assertThat(ctx).hasSingleBean(TailSource.class);
                    assertThat(ctx.getBean(TailSource.class)).isInstanceOf(ReplayTailSource.class);
                    assertThat(ctx).doesNotHaveBean(DockerTailSource.class);

                    // The recording sink is wired and ENABLED → the handler echoes the answer (UC-57/UC-43).
                    assertThat(ctx).hasSingleBean(ReplayAnswerSink.class);
                    ReplayAnswerSink sink = ctx.getBean(ReplayAnswerSink.class);
                    assertThat(sink).isInstanceOf(RecordingReplayAnswerSink.class);
                    assertThat(sink.enabled()).isTrue();
                    assertThat(ctx).doesNotHaveBean(NoopReplayAnswerSink.class);

                    // Synthetic sessions take over enumeration, exclusively (no docker shell-out).
                    assertThat(ctx).hasSingleBean(SyntheticSessionSource.class);
                    SyntheticSessionSource syn = ctx.getBean(SyntheticSessionSource.class);
                    assertThat(syn).isInstanceOf(ReplaySyntheticSessions.class);
                    assertThat(syn.exclusive()).isTrue();
                    assertThat(syn.records())
                            .as("the synthetic catalog is non-empty under replay")
                            .isNotEmpty();
                });
    }

    private void activateReplay(ConfigurableApplicationContext ctx) {
        ctx.getEnvironment().setActiveProfiles("replay");
    }
}
