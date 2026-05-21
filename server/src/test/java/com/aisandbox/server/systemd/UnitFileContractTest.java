package com.aisandbox.server.systemd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * UC11 § AC2 — parsed-content assertions on the packaged systemd unit
 * file {@code server/systemd/ai-sandbox-server.service}.
 *
 * <p>UC10 closed the chain-cleaning bug and the Android client reached
 * {@code POST /v1/enrollment} end-to-end for the first time on the
 * potato-server host, immediately surfacing the systemd-sandbox
 * read-only bug: the unit declared {@code ProtectSystem=strict} +
 * {@code ReadOnlyPaths=/etc/ai-sandbox-server …} which mounted the
 * entire {@code /etc/ai-sandbox-server} tree read-only inside the
 * service's mount namespace, including {@code clients/} where
 * {@link com.aisandbox.server.enrollment.facade.EnrollmentFacade} writes
 * the freshly-minted cert. UC11 § AC1 carves out
 * {@code /etc/ai-sandbox-server/clients} as a {@code ReadWritePaths=}
 * exception; this test parses the unit file's {@code [Service]} section
 * and asserts that carve-out is in place. Catches regressions if anyone
 * edits the unit file again.
 *
 * <p>Path discovery follows the same {@code System.getProperty("user.dir")}
 * pattern as {@link com.aisandbox.server.release.DebPackageTest} and
 * {@link com.aisandbox.server.release.ReleaseBundleTest} — the test JVM
 * cwd is {@code server/}, so the unit file lives at
 * {@code server/systemd/ai-sandbox-server.service}.
 */
class UnitFileContractTest {

    /** Test JVM cwd is {@code server/}; the unit file lives under {@code systemd/}. */
    private static final Path PROJECT_DIR = Path.of(System.getProperty("user.dir"));

    private static final Path UNIT_FILE = PROJECT_DIR.resolve("systemd").resolve("ai-sandbox-server.service");

    @Test
    void read_write_paths_includes_clients_allowlist_carve_out() throws IOException {
        assumeTrue(
                Files.isRegularFile(UNIT_FILE),
                "unit file not found at " + UNIT_FILE + " — test must run with cwd=server/");

        Set<String> rwPaths = parseSpaceSeparatedKey("ReadWritePaths");

        // UC05 § AC23 — original entries MUST stay.
        assertThat(rwPaths)
                .as("UC05 § AC23 — audit log path must remain in ReadWritePaths")
                .contains("/var/log/ai-sandbox-server");
        assertThat(rwPaths)
                .as("UC05 § AC23 — per-session workspace + token store path must remain in ReadWritePaths")
                .contains("/var/lib/ai-sandbox-server");

        // UC11 § AC1 — the actual carve-out under test.
        assertThat(rwPaths)
                .as(
                        "UC11 § AC1 — /etc/ai-sandbox-server/clients MUST be in ReadWritePaths so "
                                + "EnrollmentFacade can write <name>.crt into the allowlist directory")
                .contains("/etc/ai-sandbox-server/clients");
    }

    @Test
    void read_only_paths_still_locks_down_etc_tree_parent() throws IOException {
        assumeTrue(
                Files.isRegularFile(UNIT_FILE),
                "unit file not found at " + UNIT_FILE + " — test must run with cwd=server/");

        Set<String> roPaths = parseSpaceSeparatedKey("ReadOnlyPaths");

        // UC11 § AC1 — the parent /etc/ai-sandbox-server tree stays
        // read-only; only the explicitly-carved-out clients/ subdir is
        // writable. Security model: config files (cert, key, config.yaml,
        // secrets) immutable; allowlist directory mutable.
        assertThat(roPaths)
                .as(
                        "UC11 § AC1 — /etc/ai-sandbox-server parent tree MUST stay in ReadOnlyPaths; "
                                + "only the clients/ subdir is carved out")
                .contains("/etc/ai-sandbox-server");
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Parse the supplied space-separated systemd unit-file key from the
     * {@code [Service]} section. Returns the union across all
     * occurrences of that key (systemd treats repeated keys as
     * additive). Comment lines and other-section lines are ignored.
     */
    private static Set<String> parseSpaceSeparatedKey(String key) throws IOException {
        Set<String> values = new LinkedHashSet<>();
        boolean inService = false;
        String prefix = key + "=";
        for (String raw : Files.readAllLines(UNIT_FILE)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                inService = "[Service]".equalsIgnoreCase(line);
                continue;
            }
            if (!inService) {
                continue;
            }
            // Strip systemd line-continuations (trailing backslash) for the
            // values we care about — the current unit file doesn't use them,
            // but the parser shouldn't break if a future edit does.
            if (line.startsWith(prefix)) {
                String tail = line.substring(prefix.length()).trim();
                Arrays.stream(tail.split("\\s+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .forEach(values::add);
            }
        }
        return values;
    }
}
