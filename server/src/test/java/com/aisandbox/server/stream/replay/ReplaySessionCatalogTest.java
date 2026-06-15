package com.aisandbox.server.stream.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aisandbox.server.config.SpecialSessions;
import com.aisandbox.server.sessions.dto.SessionRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-85 (AC-2) — the manifest/schema-version half of drift detection, plus the synthetic-session
 * shape the device sees. {@link ReplaySessionCatalog} is what actually aborts boot when the
 * fixtures are stale, so this test pins:
 *
 * <ul>
 *   <li>a manifest whose {@code schemaVersion} differs from {@link ReplayFixtureValidator#SCHEMA_VERSION}
 *       fails LOUD (the headline AC-2 drift case);</li>
 *   <li>a missing manifest, an empty {@code scenarios[]}, a duplicate session number, and a
 *       collision with the reserved server-ssh session all fail loud;</li>
 *   <li>a malformed fixture referenced by an otherwise-valid manifest fails loud (the catalog
 *       runs the validator over every fixture);</li>
 *   <li>the committed fixtures load cleanly and every scenario is exposed as a {@code running}
 *       {@code claude} synthetic session.</li>
 * </ul>
 */
class ReplaySessionCatalogTest {

    private static final Path REPO_FIXTURES = Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("fixtures")
            .resolve("replay");

    private static ReplayProperties propsForDir(Path dir) {
        // Default answer timeout + a production-marker path that is guaranteed absent in the test env.
        return new ReplayProperties(
                dir.toString(), 30_000L, dir.resolve("__never-exists__").toString());
    }

    private static void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** A minimal, validator-passing question fixture. */
    private static String validQuestionFixture() {
        return String.join(
                "\n",
                "__ctrl__\tbackfill-start\t0",
                "main\t{\"type\":\"assistant\",\"uuid\":\"q1\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                        + "\"id\":\"tu\",\"name\":\"AskUserQuestion\",\"input\":{\"questions\":[{\"question\":\"Q\","
                        + "\"header\":\"H\",\"multiSelect\":false,\"options\":[{\"label\":\"A\",\"description\":\"a\"}]}]}}]}}",
                "__ctrl__\tbackfill-end",
                "__replay__\tawait-answer",
                "main\t{\"type\":\"system\",\"subtype\":\"turn_duration\",\"uuid\":\"te\",\"durationMs\":1,"
                        + "\"messageCount\":1}");
    }

    @Test
    void schemaVersionMismatchFailsLoud(@TempDir Path dir) throws IOException {
        int wrong = ReplayFixtureValidator.SCHEMA_VERSION + 1;
        write(dir, "single.tail", validQuestionFixture());
        write(
                dir,
                "manifest.json",
                "{\"schemaVersion\":" + wrong + ",\"scenarios\":[{\"n\":1,\"target\":\"single\","
                        + "\"title\":\"Single\",\"fixture\":\"single.tail\"}]}");

        assertThatThrownBy(() -> new ReplaySessionCatalog(propsForDir(dir), new ReplayFixtureValidator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schemaVersion")
                .hasMessageContaining("stale");
    }

    @Test
    void missingManifestFailsLoud(@TempDir Path dir) {
        assertThatThrownBy(() -> new ReplaySessionCatalog(propsForDir(dir), new ReplayFixtureValidator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest");
    }

    @Test
    void emptyScenariosFailsLoud(@TempDir Path dir) throws IOException {
        write(
                dir,
                "manifest.json",
                "{\"schemaVersion\":" + ReplayFixtureValidator.SCHEMA_VERSION + ",\"scenarios\":[]}");
        assertThatThrownBy(() -> new ReplaySessionCatalog(propsForDir(dir), new ReplayFixtureValidator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scenarios");
    }

    @Test
    void malformedFixtureReferencedByManifestFailsLoud(@TempDir Path dir) throws IOException {
        write(dir, "broken.tail", "main\t{not valid json");
        write(
                dir,
                "manifest.json",
                "{\"schemaVersion\":" + ReplayFixtureValidator.SCHEMA_VERSION + ",\"scenarios\":[{\"n\":1,"
                        + "\"target\":\"broken\",\"title\":\"Broken\",\"fixture\":\"broken.tail\"}]}");
        assertThatThrownBy(() -> new ReplaySessionCatalog(propsForDir(dir), new ReplayFixtureValidator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void duplicateSessionNumberFailsLoud(@TempDir Path dir) throws IOException {
        write(dir, "a.tail", validQuestionFixture());
        write(dir, "b.tail", validQuestionFixture());
        write(
                dir,
                "manifest.json",
                "{\"schemaVersion\":" + ReplayFixtureValidator.SCHEMA_VERSION + ",\"scenarios\":["
                        + "{\"n\":1,\"target\":\"a\",\"title\":\"A\",\"fixture\":\"a.tail\"},"
                        + "{\"n\":1,\"target\":\"b\",\"title\":\"B\",\"fixture\":\"b.tail\"}]}");
        assertThatThrownBy(() -> new ReplaySessionCatalog(propsForDir(dir), new ReplayFixtureValidator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void reservedServerSshSessionNumberFailsLoud(@TempDir Path dir) throws IOException {
        // The reserved server-ssh session number is 0, which also fails the "positive n" guard that
        // runs first — either way a scenario can never claim it. Assert it fails loud.
        write(dir, "a.tail", validQuestionFixture());
        write(
                dir,
                "manifest.json",
                "{\"schemaVersion\":" + ReplayFixtureValidator.SCHEMA_VERSION + ",\"scenarios\":[{\"n\":"
                        + SpecialSessions.SERVER_SSH_N + ",\"target\":\"a\",\"title\":\"A\",\"fixture\":\"a.tail\"}]}");
        assertThatThrownBy(() -> new ReplaySessionCatalog(propsForDir(dir), new ReplayFixtureValidator()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void negativeSessionNumberFailsLoud(@TempDir Path dir) throws IOException {
        write(dir, "a.tail", validQuestionFixture());
        write(
                dir,
                "manifest.json",
                "{\"schemaVersion\":" + ReplayFixtureValidator.SCHEMA_VERSION + ",\"scenarios\":[{\"n\":-3,"
                        + "\"target\":\"a\",\"title\":\"A\",\"fixture\":\"a.tail\"}]}");
        assertThatThrownBy(() -> new ReplaySessionCatalog(propsForDir(dir), new ReplayFixtureValidator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void committedFixturesLoadAndExposeSyntheticRunningSessions() {
        assumeTrue(
                Files.isRegularFile(REPO_FIXTURES.resolve("manifest.json")),
                "committed fixtures not found at " + REPO_FIXTURES + " — test must run with cwd=server/");

        ReplaySessionCatalog catalog =
                assertCatalog(() -> new ReplaySessionCatalog(propsForDir(REPO_FIXTURES), new ReplayFixtureValidator()));

        assertThat(catalog.scenarios()).as("all committed scenarios load").hasSize(5);
        List<SessionRecord> records = catalog.syntheticRecords();
        assertThat(records).as("one synthetic session per scenario").hasSize(5);
        assertThat(records)
                .as("every synthetic session is reported running so its card renders + channel opens")
                .allSatisfy(r -> {
                    assertThat(r.state()).isEqualTo("running");
                    assertThat(r.type()).isEqualTo(SpecialSessions.TYPE_CLAUDE);
                    assertThat(r.n()).isNotEqualTo(SpecialSessions.SERVER_SSH_N);
                });
        // byN resolves each declared scenario number.
        for (SessionRecord r : records) {
            assertThat(catalog.byN(r.n()))
                    .as("byN must resolve scenario " + r.n())
                    .isPresent();
        }
        assertThat(catalog.byN(9999))
                .as("an unknown session number resolves to empty")
                .isEmpty();
    }

    private static ReplaySessionCatalog assertCatalog(java.util.concurrent.Callable<ReplaySessionCatalog> c) {
        try {
            ReplaySessionCatalog cat = c.call();
            assertThatCode(() -> {}).doesNotThrowAnyException();
            return cat;
        } catch (Exception e) {
            throw new AssertionError("committed fixtures must load without error", e);
        }
    }
}
