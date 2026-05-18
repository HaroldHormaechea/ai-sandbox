package com.aisandbox.server.cli.secrets;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test seam over {@link ProcessBuilder} for the install-time
 * {@code aisandboxctl secrets seed} flow. Two distinct shell-out modes:
 *
 * <ul>
 *   <li>{@link #runAndCapture(List)} — non-interactive, captures
 *       combined stdout+stderr, returns exit code + output. Used for
 *       short utility calls ({@code ssh-keygen -y …}, {@code git config
 *       --global …}, {@code docker image inspect …}).</li>
 *   <li>{@link #runInheritIO(List)} — interactive, wires the child's
 *       stdin/stdout/stderr straight to the controlling terminal. Used
 *       for the two {@code docker run -it …} OAuth flows (gh auth +
 *       Claude pre-init) and the {@code docker compose build}
 *       fallback in {@link EnsureSandboxImage}.</li>
 * </ul>
 *
 * <p>Production wiring is the {@link Default} implementation; tests
 * inject fakes that record the argv vectors without actually shelling
 * out.
 */
public interface ProcessRunner {

    /**
     * Run the given argv, capturing combined stdout+stderr.
     *
     * @param argv command + arguments; {@code argv.get(0)} is the
     *     executable. Each element is passed verbatim to
     *     {@link ProcessBuilder} — no shell expansion.
     * @return result holding the captured output (text, no trailing
     *     newline normalisation) and the child's exit status.
     */
    Result runAndCapture(List<String> argv) throws IOException, InterruptedException;

    /**
     * Run the given argv with the child inheriting stdin/stdout/stderr,
     * so an interactive {@code docker run -it} session reaches the
     * operator's terminal directly. Blocks until the child exits.
     *
     * @return the child's exit status.
     */
    int runInheritIO(List<String> argv) throws IOException, InterruptedException;

    /** Outcome of a {@link #runAndCapture(List)} invocation. */
    record Result(int exitCode, String output) {

        /** Trimmed copy of {@link #output()} — convenience for callers that just need the value. */
        public String stdoutTrim() {
            return output == null ? "" : output.trim();
        }
    }

    /**
     * Production implementation — shells out via {@link ProcessBuilder}.
     * Captures combined stdout+stderr and waits with a 10-minute cap
     * for the capture variant (a hung child is unusual but the cap keeps
     * the install from wedging forever). The inherit-IO variant has no
     * timeout — interactive OAuth flows can legitimately take minutes
     * while the operator copy-pastes a device-flow code.
     */
    final class Default implements ProcessRunner {

        private static final long CAPTURE_TIMEOUT_SECONDS = 600L;

        @Override
        public Result runAndCapture(List<String> argv) throws IOException, InterruptedException {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(argv.get(0) + " timed out after " + CAPTURE_TIMEOUT_SECONDS + "s");
            }
            return new Result(p.exitValue(), new String(out));
        }

        @Override
        public int runInheritIO(List<String> argv) throws IOException, InterruptedException {
            Process p = new ProcessBuilder(argv).inheritIO().start();
            p.waitFor();
            return p.exitValue();
        }
    }
}
