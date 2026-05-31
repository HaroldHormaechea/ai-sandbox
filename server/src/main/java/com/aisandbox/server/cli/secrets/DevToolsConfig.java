package com.aisandbox.server.cli.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * UC-27 — pure reader for the {@code .ai-sandbox-devtools} ledger.
 *
 * <p>UC-27 made the SHELL the single source of truth for the devtools catalog
 * (auto-discovered manifests under {@code devtools.d/}). The Java install-time
 * CLI no longer holds any capability list, {@code apply_at} mapping, or
 * validation — it just reads the persisted ledger (for {@code reconfigure
 * --doctor}) and delegates the interactive picker to the shared shell selector
 * ({@code devtools-select.sh}) via {@link DevToolsStep}. With no Java-side
 * mirror there is no drift to guard against (AC#4).
 *
 * <p>This is a thin, annotation-free helper with no I/O beyond reading (and, for
 * tests, writing) one text file. UC06 § AC25 install-time CLI exemption applies.
 *
 * <h2>File format</h2>
 *
 * One whitespace-separated record per line; the first column is the capability
 * id (the second, {@code apply_at}, is ignored on read):
 *
 * <pre>{@code
 *   dind   session-spawn
 *   java   session-spawn
 *   # comments and blank lines are tolerated
 * }</pre>
 */
public final class DevToolsConfig {

    private DevToolsConfig() {
        // Static utility.
    }

    /**
     * Read the persisted ledger at {@code path} and return the set of enabled
     * ids in file order. LENIENT (UC-27): every non-empty first column is
     * accepted — there is no catalog to validate against, and an id this binary
     * does not recognise is simply preserved (the shell is authoritative).
     * Tolerates comments + blank lines; never throws on content (only genuine
     * I/O errors propagate). Returns an empty set when the file does not exist.
     */
    public static Set<String> readEnabled(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return new LinkedHashSet<>();
        }
        Set<String> enabled = new LinkedHashSet<>();
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            String id = parts[0];
            if (!id.isEmpty()) {
                enabled.add(id);
            }
        }
        return enabled;
    }

    /**
     * Write the enabled set to {@code path} as {@code <id>\tsession-spawn} lines,
     * in iteration order, replacing any prior contents.
     *
     * <p><b>Not used by any production path.</b> Post-UC-27 the shell
     * ({@code write_enabled_devtools} in {@code lib.sh}) is the sole ledger
     * writer in production — the Java picker delegates to the shell selector,
     * which persists the result itself. This method is retained only as a test
     * seam so unit tests can seed a ledger without shelling out (kept
     * {@code public} because {@code ReconfigureCommandTest} lives in a different
     * package). The {@code session-spawn} column is a fixed placeholder
     * (read-time ignores it; every UC-27 capability is spawn-time anyway).
     */
    public static void writeEnabled(Path path, Set<String> enabled) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String id : enabled) {
            if (id != null && !id.isBlank()) {
                lines.add(id + "\t" + "session-spawn");
            }
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(
                path,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }
}
