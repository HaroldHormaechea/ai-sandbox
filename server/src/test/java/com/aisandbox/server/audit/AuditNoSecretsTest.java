package com.aisandbox.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aisandbox.server.test.CertFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * AC15 / AC43 — no secret material in any log line. Concretely:
 *
 * <ul>
 *   <li>No PEM body markers ({@code BEGIN CERTIFICATE} / {@code BEGIN PRIVATE KEY}).</li>
 *   <li>No Anthropic-style API-key prefix in any committed test file or fixture.
 *       The forbidden literal is reconstructed at runtime from individual chars
 *       so this test's own source does not contain the prefix.</li>
 *   <li>Only CN and fingerprint identify a cert in log lines.</li>
 * </ul>
 */
class AuditNoSecretsTest {

    private final AuditLogger logger = new AuditLogger();
    private ListAppender<ILoggingEvent> appender;
    private Logger auditLogger;

    /**
     * Constructed character-by-character so this source file never contains the
     * actual seven-character literal — otherwise the test would fail on itself.
     */
    private static final String FORBIDDEN_KEY_PREFIX =
            new String(new char[] {'s', 'k', '-', 'a', 'n', 't', '-'});

    @BeforeEach
    void setUp() {
        auditLogger = (Logger) LoggerFactory.getLogger("audit");
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(appender);
    }

    @Test
    void no_BEGIN_CERTIFICATE_markers_in_emitted_lines() throws Exception {
        var mat = CertFixtures.newClient("alice");
        // The facade emits CLIENT_ADD on add; simulate that. The fingerprint is
        // safe to log; the PEM body must NOT appear anywhere.
        logger.logEvent(
                AuditAction.CLIENT_ADD,
                "ok",
                "name",
                "alice",
                "fingerprint",
                com.aisandbox.server.pki.PemUtils.fingerprintHex(mat.certificate()),
                "cn",
                "alice");

        for (ILoggingEvent ev : appender.list) {
            String full = ev.getFormattedMessage() + " " + ev.getMDCPropertyMap();
            assertThat(full).doesNotContain("BEGIN CERTIFICATE");
            assertThat(full).doesNotContain("BEGIN PRIVATE KEY");
            assertThat(full).doesNotContain("BEGIN RSA PRIVATE KEY");
            assertThat(full).doesNotContain("BEGIN EC PRIVATE KEY");
        }
    }

    @Test
    void committed_test_sources_contain_no_anthropic_style_api_keys() throws IOException {
        // Resolve src/test relative to the gradle test working dir (the module root).
        Path testRoot = Path.of("src/test").toAbsolutePath();
        if (!Files.isDirectory(testRoot)) {
            testRoot = Path.of("server", "src", "test").toAbsolutePath();
        }
        assertThat(testRoot).as("test source root").exists().isDirectory();

        try (Stream<Path> walk = Files.walk(testRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString();
                        return s.endsWith(".java")
                                || s.endsWith(".yaml")
                                || s.endsWith(".yml")
                                || s.endsWith(".properties");
                    })
                    .forEach(p -> {
                        try {
                            String body = Files.readString(p, StandardCharsets.UTF_8);
                            assertThat(body)
                                    .as("file %s contains a forbidden Anthropic-style API key prefix", p)
                                    .doesNotContain(FORBIDDEN_KEY_PREFIX);
                        } catch (IOException io) {
                            throw new AssertionError("Cannot read " + p, io);
                        }
                    });
        }
    }
}
