package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * {@link Ownership#chownTree(java.nio.file.Path)} are additionally
 * exercised end-to-end through {@code aisandboxctl pki init} by
 * {@code PkiInitCommandTest}, and their real-host behaviour as root is
 * covered by the {@code release-install-smoke} CI job.
 *
 * <p>The {@code chownTree_*} / {@code chown_*} tests below are the
 * <b>hermetic</b> regression for the {@code pki init} dangling-symlink
 * crash (a dangling symlink inside a per-session rootless-DinD overlay2
 * layer, e.g. {@code .../usr/local/bin/nodejs}, pointing at a missing
 * target). They run without root by constructing an {@link Ownership}
 * from the <em>current</em> user/group of a probe file, so every chown
 * is a permitted self-chown no-op. They pin two behaviours the fix
 * introduced:
 *
 * <ul>
 *   <li><b>lchown semantics</b> — {@code chown}/{@code chownTree} must
 *       NOT dereference symlinks. Pre-fix the attribute view was resolved
 *       without {@link java.nio.file.LinkOption#NOFOLLOW_LINKS}, so
 *       {@code setOwner} followed a dangling link to its missing target
 *       and raised {@link java.nio.file.NoSuchFileException}, aborting the
 *       whole install.</li>
 *   <li><b>skip-and-continue</b> — {@code chownTree} (now
 *       {@code walkFileTree}-based) must skip an entry it cannot chown or
 *       read and keep processing siblings, emitting exactly ONE aggregate
 *       warning per root. Pre-fix {@code Files.walk} + a throwing per-entry
 *       chown aborted the whole pass on the first failure.</li>
 * </ul>
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

    // ── chownTree / chown dangling-symlink + skip-and-continue regression ──

    @Test
    void chownTree_over_tree_with_a_dangling_symlink_does_not_throw(@TempDir Path tmp) throws Exception {
        assumePosix(tmp);
        Ownership self = selfOwnership(tmp);

        // Mirror the production trigger: a directory tree that contains a
        // dangling symlink (points at a target that does not exist), just
        // like the overlay2 `.../usr/local/bin/nodejs` link inside a
        // per-session rootless-DinD layer.
        Path root = Files.createDirectories(tmp.resolve("tree"));
        Files.writeString(root.resolve("regular.txt"), "x");
        Path dangling = root.resolve("nodejs");
        assumeSymlink(dangling, root.resolve("does-not-exist-target"));

        // Pre-fix this threw NoSuchFileException: Files.walk visited the
        // link and chown (without NOFOLLOW_LINKS) followed it to the
        // missing target. Post-fix the link itself is lchown'd in place.
        assertThatCode(() -> self.chownTree(root)).doesNotThrowAnyException();
    }

    @Test
    void chown_on_a_dangling_symlink_does_not_throw(@TempDir Path tmp) throws Exception {
        assumePosix(tmp);
        Ownership self = selfOwnership(tmp);

        Path dangling = tmp.resolve("dangling-link");
        assumeSymlink(dangling, tmp.resolve("missing-target"));

        // lchown: operate on the link, never dereference to the (absent)
        // target. Pre-fix (no NOFOLLOW_LINKS) this raised NoSuchFileException.
        assertThatCode(() -> self.chown(dangling)).doesNotThrowAnyException();
    }

    @Test
    void chownTree_skips_an_unprocessable_entry_processes_siblings_and_warns_once(@TempDir Path tmp) throws Exception {
        assumePosix(tmp);
        Ownership self = selfOwnership(tmp);

        Path root = Files.createDirectories(tmp.resolve("tree"));
        // A sibling that CAN be chowned — proves the walk continues past
        // the failure rather than aborting.
        Path good = root.resolve("good.txt");
        Files.writeString(good, "x");
        // A subdirectory we make unreadable so walkFileTree cannot open it
        // → visitFileFailed fires and the entry is skipped. (The dir itself
        // is still chowned in preVisitDirectory; its contents can't be read.)
        Path locked = Files.createDirectories(root.resolve("locked"));
        Files.writeString(locked.resolve("hidden.txt"), "y");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            // Running as root (or any uid that can bypass the permission
            // bits) makes this un-enforceable → the skip never happens and
            // the assertion would be vacuous. Skip in that case.
            Assumptions.assumeTrue(isUnreadable(locked), "cannot make a dir unreadable (root?) — skip");

            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            PrintStream origErr = System.err;
            System.setErr(new PrintStream(errBuf, true));
            try {
                assertThatCode(() -> self.chownTree(root)).doesNotThrowAnyException();
            } finally {
                System.setErr(origErr);
            }

            String stderr = errBuf.toString();
            // Exactly one aggregate warning per root — no per-entry spam.
            assertThat(countOccurrences(stderr, "aisandboxctl: chownTree("))
                    .as("aggregate chownTree warning must be emitted exactly once per root")
                    .isEqualTo(1);
            // Singular "1 entry" proves EXACTLY one entry was skipped — the
            // locked dir — and that `good.txt` (and root) were processed
            // successfully rather than also failing.
            assertThat(stderr).contains("chownTree(" + root + ")");
            assertThat(stderr).contains("skipped 1 entry");
            assertThat(stderr).contains("Continuing.");
        } finally {
            // Restore perms so @TempDir cleanup can delete the tree.
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Build an {@link Ownership} from the CURRENT owner/group of {@code probe}
     * (any real path on the test FS). Chowning anything to the current
     * user/group is a permitted no-op, so the walk exercises the real
     * {@code setOwner}/{@code setGroup} path without needing root.
     */
    private static Ownership selfOwnership(Path probe) throws IOException {
        PosixFileAttributes attrs = Files.readAttributes(probe, PosixFileAttributes.class);
        return new Ownership(attrs.owner(), attrs.group());
    }

    private static void assumePosix(Path tmp) {
        Assumptions.assumeTrue(
                tmp.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "non-POSIX filesystem — chown semantics not applicable");
    }

    /** Create {@code link} → {@code target}; skip the test if symlinks are unsupported. */
    private static void assumeSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symlinks unsupported on this FS: " + e.getMessage());
        }
        assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .as("the symlink itself must exist (its target is intentionally absent)")
                .isTrue();
        assertThat(Files.exists(link))
                .as("the symlink must be dangling (target does not exist)")
                .isFalse();
    }

    private static boolean isUnreadable(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.iterator();
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
