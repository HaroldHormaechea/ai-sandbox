package com.aisandbox.server.cli;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;

/**
 * Pre-resolved owner + group for {@code <user>:<user>} POSIX chown
 * operations performed by {@code aisandboxctl} install-time commands
 * ({@code pki init}, {@code secrets seed}).
 *
 * <p>Resolving the principals once at the start of a command run and
 * carrying the captured pair through the file walk avoids a per-file
 * lookup that could surprise us mid-flow (e.g., if the resolver changes
 * answers between calls). The {@link #resolve(String)} factory returns
 * {@code null} when the lookup fails — typical for unit-test hosts where
 * the {@code ai-sandbox-server} user was never created. Callers must
 * skip every chown when they get {@code null} back, just like before
 * the extraction.
 *
 * <p>Behavior-preserving extraction of the inner {@code Ownership}
 * record + helper methods previously embedded in
 * {@code PkiInitCommand.Init}; lifted so {@link SecretsSeedCommand} (and
 * any future install-time CLI step) can reuse the same chown contract.
 */
public record Ownership(UserPrincipal owner, GroupPrincipal group) {

    /**
     * Resolve {@code <user>:<user>} once. Returns {@code null} when the
     * lookup fails (the user isn't on the host — typical for unit-test
     * runs). On {@code null} the caller skips every chown; production
     * environments where the system user was created earlier in the
     * install flow reach this path with a live user and a non-null
     * {@code Ownership}.
     *
     * @param user POSIX user name to resolve; group name is assumed to
     *     match (matches the {@code useradd --user-group} convention).
     * @param commandLabel label used in the stderr warning when the
     *     lookup fails ({@code "pki init"}, {@code "secrets seed"} —
     *     so the message points operators back at the right command).
     */
    public static Ownership resolve(String user, String commandLabel) {
        UserPrincipalLookupService lookup =
                FileSystems.getDefault().getUserPrincipalLookupService();
        try {
            UserPrincipal owner = lookup.lookupPrincipalByName(user);
            GroupPrincipal group = lookup.lookupPrincipalByGroupName(user);
            return new Ownership(owner, group);
        } catch (IOException ioe) {
            System.err.println("aisandboxctl " + commandLabel + ": skipping chown — user '" + user
                    + "' not resolvable on this host (" + ioe.getClass().getSimpleName()
                    + "). Production runs MUST be invoked as root after the system user has been created.");
            return null;
        }
    }

    /** Chown a single file or directory to {@code <user>:<user>}. */
    public void chown(Path p) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(p, PosixFileAttributeView.class);
        view.setOwner(owner);
        view.setGroup(group);
    }

    /** Recursive chown of every entry under {@code root} (root itself included). */
    public void chownTree(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (var it = stream.iterator(); it.hasNext(); ) {
                chown(it.next());
            }
        }
    }
}
