package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/**
 * UC06 extracted-class regression. {@link Ownership} was lifted out of
 * {@code PkiInitCommand} so {@link SecretsSeedCommand} and any future
 * install-time CLI can reuse the same {@code <user>:<user>} chown
 * contract.
 *
 * <p>The interesting behavioural surface is {@link Ownership#resolve}:
 * on hosts where the looked-up user is missing it MUST return
 * {@code null} (callers skip every chown when they see null) and emit
 * a warning labelled with the supplied {@code commandLabel} so
 * operators can trace which CLI step asked.
 *
 * <p>{@link Ownership#chown(java.nio.file.Path)} /
 * {@link Ownership#chownTree(java.nio.file.Path)} are covered
 * end-to-end by {@code PkiInitCommandTest} (the path that runs without
 * the system user present skips chown entirely; the real-chown path
 * lives in the {@code release-install-smoke} CI job). Re-asserting
 * that here would just duplicate {@code PkiInitCommandTest}.
 */
class OwnershipTest {

    /** A user name that almost certainly isn't on any dev host. */
    private static final String MISSING_USER = "ai-sandbox-server-test-missing-user-xyz9k";

    @Test
    void resolve_returns_null_when_user_is_not_on_host() {
        // No fixture setup — the user just doesn't exist.
        Ownership o = Ownership.resolve(MISSING_USER, "ownership-test");
        assertThat(o)
                .as(
                        "Ownership.resolve MUST return null when the lookup fails (callers skip chown). Real-host chown is covered by release-install-smoke CI.")
                .isNull();
    }

    @Test
    void resolve_warns_with_user_name_and_command_label_when_lookup_fails() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(errBuf, true));
        try {
            Ownership.resolve(MISSING_USER, "secrets seed");
        } finally {
            System.setErr(origErr);
        }
        String stderr = errBuf.toString();
        // The warning surfaces the command label (so operators know
        // which install-time step asked) and the failed user name.
        assertThat(stderr).contains("aisandboxctl secrets seed: skipping chown");
        assertThat(stderr).contains(MISSING_USER);
    }
}
