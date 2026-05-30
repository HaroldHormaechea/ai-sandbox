package com.aisandbox.server.cli.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * UC-26 — pure read/write helper for the {@code .ai-sandbox-devtools}
 * ledger and its companion catalog.
 *
 * <p>This is the Java parallel of {@code lib.sh}'s devtool helpers. It
 * has no Spring annotations, no Picocli annotations, and no I/O outside
 * of reading and writing one text file. Wired into the install-time CLI
 * by {@link DevToolsStep}; tested directly without Spring boot.
 *
 * <h2>File format</h2>
 *
 * One whitespace-separated record per line:
 *
 * <pre>{@code
 *   dind   session-spawn
 *   # comments and blank lines are tolerated
 * }</pre>
 *
 * <p>The first column is the capability id; the second is its
 * {@code apply_at} flavour. {@code apply_at} is purely informational on
 * disk — at read time we re-resolve from the {@link #CATALOG} so a
 * future change to a capability's {@code apply_at} does not strand
 * already-persisted records.
 *
 * <h2>Catalog</h2>
 *
 * The catalog mirrors {@code lib.sh}'s {@code _aisb_devtool_catalog}.
 * Keep the two in sync — both shells (shell + Java) read this list.
 *
 * <p>UC06 § AC25 install-time CLI exemption applies: this class lives
 * under {@code cli/secrets/} and is consumed only by the {@code
 * aisandboxctl reconfigure} / {@code aisandboxctl onboard} install-time
 * subcommands, not by any server runtime path.
 */
public final class DevToolsConfig {

    /** Where each capability applies — at image-build time or per-spawn. */
    public enum ApplyAt {
        IMAGE_BUILD("image-build"),
        SESSION_SPAWN("session-spawn");

        private final String wire;

        ApplyAt(String wire) {
            this.wire = wire;
        }

        /** On-disk / on-wire form. Matches the bash strings in {@code lib.sh}. */
        public String wire() {
            return wire;
        }

        /** Parse a {@code wire()}-shaped string back to the enum. {@code null}-tolerant. */
        public static Optional<ApplyAt> fromWire(String s) {
            if (s == null) {
                return Optional.empty();
            }
            for (ApplyAt v : values()) {
                if (v.wire.equals(s)) {
                    return Optional.of(v);
                }
            }
            return Optional.empty();
        }
    }

    /** A single capability the wizard step can offer. */
    public record Capability(String id, ApplyAt applyAt, String label, String warning) {}

    /**
     * Catalog of opt-in development-tool capabilities. v1 has one entry:
     * rootless Docker-in-Docker. Add a row here (and a matching row to
     * {@code _aisb_devtool_catalog} in {@code lib.sh}) to offer a new
     * capability in the wizard step.
     */
    public static final List<Capability> CATALOG = List.of(new Capability(
            "dind",
            ApplyAt.SESSION_SPAWN,
            "Enable Docker-in-Docker (rootless; lets sessions run docker / docker compose"
                    + " inside their sandbox container)",
            "Enabling Docker-in-Docker (rootless) lets code running inside a session start"
                    + " its own docker / docker compose commands. The rootless daemon runs as the"
                    + " non-root session user with no host-socket bind, so it does NOT widen the host"
                    + " trust boundary — but it DOES widen what code inside a session can reach (the"
                    + " session can now launch and inspect containers). Project policy is \"the container"
                    + " is the trust boundary\"; enabling this is a deliberate, opt-in expansion of that"
                    + " boundary."));

    private DevToolsConfig() {
        // Static utility.
    }

    /** Look up a capability by id; {@link Optional#empty()} if unknown. */
    public static Optional<Capability> find(String id) {
        for (Capability c : CATALOG) {
            if (c.id().equals(id)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** Look up {@code apply_at} by id; {@link Optional#empty()} if unknown. */
    public static Optional<ApplyAt> applyAt(String id) {
        return find(id).map(Capability::applyAt);
    }

    /**
     * Read the persisted ledger at {@code path} and return the set of
     * enabled ids in file order. Tolerates comments + blank lines; an
     * unknown id is dropped with no error (forward-compat — newer
     * ledgers can be read by older binaries).
     *
     * <p>Returns an empty set when the file does not exist.
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
            if (id.isEmpty()) {
                continue;
            }
            if (find(id).isEmpty()) {
                // Skip silently — keeps newer-ledger forward-compat.
                continue;
            }
            enabled.add(id);
        }
        return enabled;
    }

    /**
     * Write the enabled set to {@code path}, replacing any prior
     * contents. Each line is {@code <id>\t<apply_at>}; {@code apply_at}
     * is resolved from the catalog. Unknown ids in {@code enabled} are
     * skipped (the caller should validate first). The output is
     * deterministic in catalog order so two runs that enable the same
     * set produce byte-identical files.
     *
     * <p>An empty {@code enabled} set still writes a (zero-byte) file,
     * mirroring {@code write_enabled_toolchains}.
     */
    public static void writeEnabled(Path path, Set<String> enabled) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Capability c : CATALOG) {
            if (enabled.contains(c.id())) {
                lines.add(c.id() + "\t" + c.applyAt().wire());
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
