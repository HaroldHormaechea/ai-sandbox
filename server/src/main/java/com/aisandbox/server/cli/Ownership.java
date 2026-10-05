package com.aisandbox.server.cli;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
public record Ownership(UserPrincipal owner, GroupPrincipal group) implements Chowner {

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
        UserPrincipalLookupService lookup = FileSystems.getDefault().getUserPrincipalLookupService();
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

    /**
     * Chown a single file or directory to {@code <user>:<user>}.
     *
     * <p>Resolves the attribute view with {@link LinkOption#NOFOLLOW_LINKS}
     * (lchown semantics): when {@code p} is a symlink, the link itself is
     * chowned rather than its target. This is deliberate — following a
     * symlink to a missing target (common inside a per-session rootless-DinD
     * overlay2 layer, e.g. a dangling {@code .../usr/local/bin/nodejs}) would
     * raise {@link java.nio.file.NoSuchFileException} and abort the whole
     * install. A tree walk never wants to dereference links anyway; it owns
     * the entries it enumerates, not their targets.
     */
    @Override
    public void chown(Path p) throws IOException {
        PosixFileAttributeView view =
                Files.getFileAttributeView(p, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        view.setOwner(owner);
        view.setGroup(group);
    }

    /**
     * Recursive chown of every entry under {@code root} (root itself included).
     *
     * <p>Walks with {@link Files#walkFileTree} and is resilient to per-entry
     * failures: an entry that cannot be chowned — because it vanished between
     * enumeration and the chown (a live DinD store mutates under us), is
     * unreadable, or otherwise rejects the operation — is skipped and the walk
     * continues rather than aborting the pass. Symlinks are not followed (the
     * default for {@code walkFileTree}), so dangling links are visited as plain
     * entries and chowned in place via {@link #chown(Path)}'s NOFOLLOW_LINKS
     * semantics. Skipped entries are summarized in a single aggregate warning
     * per {@code root} — no per-entry log spam.
     */
    @Override
    public void chownTree(Path root) throws IOException {
        int[] skipped = {0};
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        tryChown(dir, skipped);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        tryChown(file, skipped);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // Entry disappeared or became unreadable mid-walk; skip and continue.
                        skipped[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                });
        if (skipped[0] > 0) {
            System.err.println("aisandboxctl: chownTree(" + root + ") skipped " + skipped[0] + " entr"
                    + (skipped[0] == 1 ? "y" : "ies")
                    + " that could not be chowned (vanished mid-walk, unreadable, or rejected). Continuing.");
        }
    }

    /** Chown {@code p}, counting (not propagating) a per-entry failure so the walk can continue. */
    private void tryChown(Path p, int[] skipped) {
        try {
            chown(p);
        } catch (IOException e) {
            skipped[0]++;
        }
    }
}
