package com.aisandbox.server.cli.secrets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Recording fake for {@link ProcessRunner} used across the UC06
 * {@code secrets seed} test suite. Captures every argv vector handed
 * to either {@link #runAndCapture(List)} or {@link #runInheritIO(List)}
 * and, on demand, runs scripted side-effects in lieu of an actual
 * subprocess.
 *
 * <p>Two response hooks let each test tailor behaviour:
 *
 * <ul>
 *   <li>{@link #captureResponse} — called for every
 *       {@link #runAndCapture(List)} invocation. Defaults to
 *       <code>exit=0, output=""</code>. Tests override to script
 *       different outcomes (e.g. {@code ssh-keygen -y} returning
 *       non-zero to signal an encrypted key) or to emit file-system
 *       side-effects (e.g. {@code ssh-keygen -t ed25519} materialising
 *       the target file so the orchestrator's downstream chmod
 *       succeeds).</li>
 *   <li>{@link #inheritResponse} — same shape but for the
 *       inherit-IO variant. Defaults to <code>exit=0</code>. Used by
 *       interactive-flow tests that need to fake a successful docker
 *       run (gh auth, Claude pre-init) plus the file-system side-effect
 *       a real container would have produced.</li>
 * </ul>
 *
 * <p>{@code IOException}s thrown from response lambdas are wrapped in
 * a {@link RuntimeException} so callers can use the
 * <code>Function</code> shape without sprinkling <code>throws</code>
 * declarations everywhere.
 */
public final class FakeProcessRunner implements ProcessRunner {

    public final List<List<String>> captureCalls = new ArrayList<>();
    public final List<List<String>> inheritCalls = new ArrayList<>();

    /** Set by tests to script {@link #runAndCapture(List)} outcomes. */
    public Function<List<String>, Result> captureResponse = argv -> new Result(0, "");

    /** Set by tests to script {@link #runInheritIO(List)} outcomes. */
    public Function<List<String>, Integer> inheritResponse = argv -> 0;

    @Override
    public Result runAndCapture(List<String> argv) throws IOException {
        captureCalls.add(List.copyOf(argv));
        try {
            return captureResponse.apply(argv);
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw re;
        }
    }

    @Override
    public int runInheritIO(List<String> argv) throws IOException {
        inheritCalls.add(List.copyOf(argv));
        try {
            return inheritResponse.apply(argv);
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw re;
        }
    }

    /**
     * Convenience — return the most-recent {@link #runAndCapture(List)}
     * argv whose first token equals {@code executable}, or {@code null}
     * if none matched. Useful for assertions that don't care about
     * earlier calls.
     */
    public List<String> lastCaptureArgvWith(String executable) {
        for (int i = captureCalls.size() - 1; i >= 0; i--) {
            List<String> call = captureCalls.get(i);
            if (!call.isEmpty() && executable.equals(call.get(0))) {
                return call;
            }
        }
        return null;
    }

    /**
     * Convenience — return the most-recent {@link #runInheritIO(List)}
     * argv whose first token equals {@code executable}.
     */
    public List<String> lastInheritArgvWith(String executable) {
        for (int i = inheritCalls.size() - 1; i >= 0; i--) {
            List<String> call = inheritCalls.get(i);
            if (!call.isEmpty() && executable.equals(call.get(0))) {
                return call;
            }
        }
        return null;
    }
}
