package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC07 Feature E — drift gate between {@code aisandboxctl pki init}'s
 * Java-side {@code useradd} invocation and the Debian package's
 * {@code postinst} hook. Both paths MUST produce a system user with
 * identical {@code uid}, {@code shell}, primary group, and secondary
 * group ({@code docker}). Drift here is the foot-gun the developer's
 * commit `3150192` Javadoc on
 * {@link SystemUserAdmin.Default#USERADD_ARGV_PREFIX} explicitly warned
 * about — installing via {@code apt} then re-running {@code pki init}
 * would either no-op (idempotent, good) or produce a second user with
 * a different identity (broken, bad).
 *
 * <p>This test reads {@code server/debian/postinst} from the repo,
 * extracts the {@code useradd} argv list, and asserts byte-equality
 * against {@link SystemUserAdmin.Default#USERADD_ARGV_PREFIX} modulo
 * the final {@code <name>} argument (which the shell script passes
 * literally as {@code ai-sandbox-server}; the Java side appends it at
 * invocation time).
 *
 * <p>Skipped when the postinst is not on disk (e.g. a clone without
 * {@code server/debian/} for some reason). The CI release-install-smoke
 * job always has it; local-dev hosts always have it; this is a
 * portability belt-and-braces.
 */
class SystemUserAdminPostInstParityTest {

    /** Test JVM runs in {@code server/} → repo root is one up. */
    private static final Path PROJECT_DIR = Path.of(System.getProperty("user.dir"));

    private static final Path POSTINST = PROJECT_DIR.resolve("debian").resolve("postinst");

    @Test
    void postinst_useradd_argv_matches_java_useradd_argv_prefix() throws Exception {
        assumeTrue(
                Files.isRegularFile(POSTINST),
                "server/debian/postinst not on disk — skipping parity test (CI always has it)");

        String shell = Files.readString(POSTINST);
        List<String> postinstArgv = extractUseraddArgv(shell);

        // Two invariants. (1) The shell script's argv must contain the
        // exact Java prefix flags, in order; (2) the only addition over
        // the prefix is the literal "ai-sandbox-server" trailing arg.
        List<String> expectedFull = new ArrayList<>(SystemUserAdmin.Default.USERADD_ARGV_PREFIX);
        expectedFull.add("ai-sandbox-server");

        assertThat(postinstArgv)
                .as(
                        "Debian postinst useradd argv MUST match SystemUserAdmin.Default.USERADD_ARGV_PREFIX + literal 'ai-sandbox-server' — drift breaks install-flow idempotency. See the Javadoc on USERADD_ARGV_PREFIX for the cross-reference contract.")
                .containsExactlyElementsOf(expectedFull);
    }

    /**
     * Parse the {@code useradd ... <name>} invocation out of the
     * postinst shell script. The script uses POSIX line-continuations
     * ({@code \\\n}) so we flatten on whitespace after stripping the
     * continuations, then split on whitespace and capture from
     * {@code useradd} through the trailing name.
     *
     * <p>Tolerant of the comments + control flow around the call: we
     * find the {@code useradd} token and walk forward until the
     * statement terminator (newline outside a continuation).
     */
    private static List<String> extractUseraddArgv(String shellScript) {
        // Strip line continuations: backslash-newline → single space.
        String flattened = shellScript.replace("\\\n", " ");
        // Locate the useradd invocation. There's only one in postinst.
        int idx = flattened.indexOf("useradd");
        assertThat(idx).as("postinst MUST contain a `useradd` invocation").isGreaterThanOrEqualTo(0);
        int eol = flattened.indexOf('\n', idx);
        if (eol < 0) {
            eol = flattened.length();
        }
        String statement = flattened.substring(idx, eol).trim();
        // Split on any whitespace run; strip empties (multiple spaces
        // from the flattened continuations).
        List<String> tokens = new ArrayList<>();
        for (String t : statement.split("\\s+")) {
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }
}
