package com.aisandbox.server.cli.secrets;

import com.aisandbox.server.cli.Ownership;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.regex.Pattern;

/**
 * UC06 § AC4 — step (b) of {@code aisandboxctl secrets seed}: write the
 * git author identity at {@code <secrets-dir>/gitconfig} (mode 0600,
 * owned by {@code ai-sandbox-server}).
 *
 * <p>Flag-driven: {@code --git-name "..." --git-email "..."}. Both
 * required for non-interactive runs (TTY-less missing-flag detection
 * is handled by the orchestrator's AC12 check, not here). Interactive
 * mode prompts for each field, defaulting to whatever
 * {@code git config --global} reports (one scope only — UC06 doesn't
 * need {@code setup.sh}'s full system/global/unscoped cascade).
 *
 * <p>Validation mirrors {@code setup.sh}:
 *
 * <ul>
 *   <li>name: non-empty after whitespace trim;</li>
 *   <li>email: {@code ^[^@\s]+@[^@\s]+\.[^@\s]+$}.</li>
 * </ul>
 *
 * <p>Output format (matches the existing {@code secrets/gitconfig}
 * shape consumed by {@code entrypoint.sh} via {@code git config
 * --global include.path}):
 *
 * <pre>
 * [user]
 *     name = Alice Example
 *     email = alice@example.com
 * </pre>
 */
final class GitIdentityStep {

    /** Mirror of {@code setup.sh:validate_email}. */
    static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ProcessRunner runner;
    private final ConsoleIO io;

    GitIdentityStep(ProcessRunner runner, ConsoleIO io) {
        this.runner = runner;
        this.io = io;
    }

    /**
     * Run the git-identity step.
     *
     * @param nameFlag value of {@code --git-name} or {@code null}.
     * @param emailFlag value of {@code --git-email} or {@code null}.
     * @param outputPath destination ({@code <secrets-dir>/gitconfig}).
     * @param ownership pre-resolved owner/group or {@code null}.
     */
    void run(String nameFlag, String emailFlag, Path outputPath, Ownership ownership)
            throws IOException, InterruptedException {

        String name = (nameFlag != null) ? nameFlag.trim() : promptName();
        String email = (emailFlag != null) ? emailFlag.trim() : promptEmail();

        if (name.isEmpty()) {
            throw new IOException("git author name is empty (--git-name required when non-interactive)");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IOException("git author email '" + email + "' is not RFC-5322-ish (--git-email)");
        }

        String contents = "[user]\n\tname = " + name + "\n\temail = " + email + "\n";
        Files.writeString(outputPath, contents, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(outputPath, PosixFilePermissions.fromString("rw-------"));
        if (ownership != null) {
            ownership.chown(outputPath);
        }
    }

    // ── interactive prompts ─────────────────────────────────────────

    private String promptName() throws IOException, InterruptedException {
        String def = readGitGlobal("user.name");
        while (true) {
            io.print("  git author name" + suffixDefault(def) + ": ");
            String v = io.readLine();
            if (v == null) {
                throw new IOException("EOF on stdin before git author name was supplied");
            }
            v = v.trim();
            if (v.isEmpty() && !def.isEmpty()) {
                return def;
            }
            if (!v.isEmpty()) {
                return v;
            }
            io.println("  Name must not be empty.");
        }
    }

    private String promptEmail() throws IOException, InterruptedException {
        String def = readGitGlobal("user.email");
        while (true) {
            io.print("  git author email" + suffixDefault(def) + ": ");
            String v = io.readLine();
            if (v == null) {
                throw new IOException("EOF on stdin before git author email was supplied");
            }
            v = v.trim();
            String candidate = v.isEmpty() ? def : v;
            if (EMAIL_PATTERN.matcher(candidate).matches()) {
                return candidate;
            }
            io.println("  Email must look like name@host.tld.");
        }
    }

    private static String suffixDefault(String def) {
        return def.isEmpty() ? "" : " [" + def + "]";
    }

    private String readGitGlobal(String key) throws IOException, InterruptedException {
        ProcessRunner.Result res = runner.runAndCapture(List.of("git", "config", "--global", "--get", key));
        if (res.exitCode() != 0) {
            return "";
        }
        return res.output().stripTrailing();
    }
}
