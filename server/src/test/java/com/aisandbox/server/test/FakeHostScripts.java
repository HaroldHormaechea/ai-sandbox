package com.aisandbox.server.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Drops fake {@code spawn.sh} / {@code clean.sh} / {@code attach.sh} into
 * a temp directory so {@code HostScriptLocator} and the startup checks
 * find executable files at the configured repo-root.
 *
 * <p>The scripts are minimal POSIX-shell shims: each prints a marker line
 * to stdout, optionally echoes the assigned session number, and exits with
 * the requested code. Tests that need a particular spawn N or exit code
 * pick from the variants below.
 */
public final class FakeHostScripts {

    private FakeHostScripts() {}

    public static Path installAt(Path repoRoot) throws IOException {
        Files.createDirectories(repoRoot);
        writeScript(repoRoot.resolve("spawn.sh"), defaultSpawn());
        writeScript(repoRoot.resolve("clean.sh"), defaultClean());
        writeScript(repoRoot.resolve("attach.sh"), defaultAttach());
        return repoRoot;
    }

    public static void replaceSpawn(Path repoRoot, String body) throws IOException {
        writeScript(repoRoot.resolve("spawn.sh"), body);
    }

    public static void replaceClean(Path repoRoot, String body) throws IOException {
        writeScript(repoRoot.resolve("clean.sh"), body);
    }

    public static String defaultSpawn() {
        return """
                #!/bin/sh
                # Fake spawn.sh: emits an ai-sandbox-N line so the facade can parse N.
                echo "ai-sandbox-7 ready"
                exit 0
                """;
    }

    public static String spawnEmittingN(int n, int exit) {
        return "#!/bin/sh\necho ai-sandbox-" + n + " ready\nexit " + exit + "\n";
    }

    public static String spawnFailing(int exit, String stderr) {
        // shell-safe: stderr lines are emitted via printf to avoid backslash games
        return "#!/bin/sh\nprintf '%s\\n' '" + stderr.replace("'", "'\\''") + "' 1>&2\nexit " + exit + "\n";
    }

    public static String spawnHanging() {
        return "#!/bin/sh\nsleep 30\n";
    }

    public static String defaultClean() {
        return """
                #!/bin/sh
                echo "cleaning $@"
                exit 0
                """;
    }

    public static String cleanFailing(int exit) {
        return "#!/bin/sh\necho clean-failed 1>&2\nexit " + exit + "\n";
    }

    public static String defaultAttach() {
        return """
                #!/bin/sh
                exit 0
                """;
    }

    private static void writeScript(Path p, String body) throws IOException {
        Files.writeString(p, body);
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows test run — POSIX perms unsupported, executable bit not required there.
        }
    }
}
