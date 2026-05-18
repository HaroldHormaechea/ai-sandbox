package com.aisandbox.server.cli.secrets;

import java.io.Console;

/**
 * TTY-aware console wrapper for the {@code aisandboxctl secrets seed}
 * flow. The {@link #hasTty()} probe is the AC11/AC12 hinge:
 *
 * <ul>
 *   <li>flag missing + TTY → prompt only for that step (AC11);</li>
 *   <li>flag missing + no TTY → exit non-zero, list every missing flag
 *       on stderr, no files written (AC12).</li>
 * </ul>
 *
 * <p>The {@link Default} impl wraps {@link System#console()} (returns
 * {@code null} when stdin is not a terminal — exactly the no-TTY
 * signal). Tests inject a fake that pretends to have or not have a
 * TTY and feeds canned input lines.
 *
 * <p>Output methods write to {@code System.err} by design — device-flow
 * URLs / codes and prompt labels must remain visible even when callers
 * redirect stdout (e.g., for tee-into-logs install runs); stdout is
 * reserved for the AC22 audit summary.
 */
public interface ConsoleIO {

    /**
     * @return {@code true} when stdin is wired to a real terminal and
     *     interactive prompting is sane. {@code false} otherwise — the
     *     caller MUST fail fast on any step whose flag wasn't supplied.
     */
    boolean hasTty();

    /**
     * Print a line to the operator-visible stream ({@code System.err} in
     * the Default impl). Used for prompts, step headers, and warnings.
     */
    void println(String line);

    /**
     * Print a line without a trailing newline — for prompts where the
     * operator's response should appear on the same line. Backed by
     * {@code System.err}.
     */
    void print(String fragment);

    /**
     * Read a single line from stdin. Returns {@code null} on EOF. The
     * trailing newline is stripped. Should only be called after
     * {@link #hasTty()} returns {@code true} (or the caller knows it's
     * safe — e.g., scripted input redirected into the CLI).
     */
    String readLine();

    /**
     * Read a password / passphrase without echoing. Returns {@code null}
     * when no console is available. The Default impl delegates to
     * {@link Console#readPassword()}; tests inject the cleartext directly
     * through the fake.
     */
    char[] readPassword();

    /** Production implementation — wraps {@link System#console()} + {@link System#err}. */
    final class Default implements ConsoleIO {

        @Override
        public boolean hasTty() {
            return System.console() != null;
        }

        @Override
        public void println(String line) {
            System.err.println(line);
        }

        @Override
        public void print(String fragment) {
            System.err.print(fragment);
            System.err.flush();
        }

        @Override
        public String readLine() {
            Console c = System.console();
            if (c != null) {
                return c.readLine();
            }
            // Fall through to a scanner over stdin for redirected-input
            // cases (CI smoke runs piping flag-driven input). Returns
            // null at EOF.
            try {
                var br = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                return br.readLine();
            } catch (java.io.IOException ioe) {
                return null;
            }
        }

        @Override
        public char[] readPassword() {
            Console c = System.console();
            return c == null ? null : c.readPassword();
        }
    }
}
