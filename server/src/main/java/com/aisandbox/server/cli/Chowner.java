package com.aisandbox.server.cli;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Minimal chown contract used by install-time CLI commands
 * ({@code pki init}, {@code secrets seed}) so they can call through an
 * interface rather than the concrete {@link Ownership} record.
 *
 * <p>Production uses {@link Ownership} (which implements this); the
 * resolved chown behavior is unchanged — this interface only names the two
 * operations callers invoke. Its reason for existing is testability:
 * {@link Ownership} is a {@code record} (implicitly {@code final}) and so
 * cannot be subclassed into a recording spy. By having the command's
 * ownership-resolver seam yield a {@code Chowner}, a test can inject a spy
 * that records which paths {@link #chown(Path)} / {@link #chownTree(Path)}
 * were invoked on (needed to prove {@code sessions/} itself is chowned but
 * its per-session contents are NOT recursively re-owned).
 */
public interface Chowner {

    /** Chown a single file or directory (symlinks chowned in place, not followed). */
    void chown(Path p) throws IOException;

    /** Recursive chown of every entry under {@code root} (root itself included), skip-and-continue. */
    void chownTree(Path root) throws IOException;
}
