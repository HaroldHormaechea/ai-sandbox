package com.aisandbox.server.cli;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Abstraction over the POSIX user-administration calls invoked by
 * {@link PkiInitCommand} — wraps {@code getent passwd <name>} and
 * {@code useradd ...} so unit tests can substitute a fake without
 * shelling out.
 *
 * <p>Used only by {@code aisandboxctl pki init} (UC05 § AC13). The
 * default implementation invokes the host's {@code getent} +
 * {@code useradd} binaries via {@link ProcessBuilder}; behaviour is
 * POSIX-only, the command is a no-op on non-POSIX hosts (where
 * {@code pki init} already short-circuits at the root-check step).
 */
public interface SystemUserAdmin {

    /** @return {@code true} when a POSIX user with this name already exists. */
    boolean userExists(String name) throws IOException, InterruptedException;

    /**
     * Creates a POSIX system user with the policy fixed by UC05 § AC13:
     * {@code useradd --system --no-create-home --shell /usr/sbin/nologin
     * --user-group --groups docker <name>}. Throws if the user already
     * exists; callers should gate with {@link #userExists(String)}.
     */
    void createSystemUser(String name) throws IOException, InterruptedException;

    /** Production implementation — shells out to {@code getent} + {@code useradd}. */
    final class Default implements SystemUserAdmin {

        /**
         * Canonical {@code useradd} argv prefix used by {@link #createSystemUser(String)}.
         * Lifted out as a {@code public static final} list so the Debian
         * package's {@code postinst} script can be cross-referenced against
         * it — every flag here MUST match the equivalent flags in
         * {@code server/debian/postinst} (and vice versa). Drift would
         * produce a different uid / shell / group set depending on whether
         * the user was created by {@code aisandboxctl pki init} or by the
         * .deb install hook, breaking the install-flow's idempotency promise.
         *
         * <p>The trailing {@code <name>} argument is NOT in this list — callers
         * append it at invocation time.
         */
        public static final List<String> USERADD_ARGV_PREFIX = List.of(
                "useradd",
                "--system",
                "--no-create-home",
                "--shell",
                "/usr/sbin/nologin",
                "--user-group",
                "--groups",
                "docker");

        @Override
        public boolean userExists(String name) throws IOException, InterruptedException {
            Process p = new ProcessBuilder("getent", "passwd", name)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("getent passwd " + name + " timed out");
            }
            // getent passwd <name> exits 0 if found, 2 if not found.
            return p.exitValue() == 0;
        }

        @Override
        public void createSystemUser(String name) throws IOException, InterruptedException {
            java.util.ArrayList<String> argv = new java.util.ArrayList<>(USERADD_ARGV_PREFIX);
            argv.add(name);
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("useradd " + name + " timed out");
            }
            int rc = p.exitValue();
            if (rc != 0) {
                throw new IOException("useradd " + name + " failed (exit=" + rc + "): " + new String(out));
            }
        }
    }
}
