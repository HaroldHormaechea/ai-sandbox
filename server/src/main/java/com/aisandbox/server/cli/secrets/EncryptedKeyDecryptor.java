package com.aisandbox.server.cli.secrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * UC06 § AC3 — strip a passphrase from an OpenSSH private key without
 * mutating the operator's host copy.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Copy the source key to a private temp file (mode 0600).</li>
 *   <li>Run {@code ssh-keygen -p -P <passphrase> -N "" -f <temp>} to
 *       rewrite the temp file with no passphrase.</li>
 *   <li>Return the temp path. Caller is responsible for moving it
 *       into the secrets dir and cleaning up the temp afterwards.</li>
 * </ol>
 *
 * <p><b>Security note.</b> The passphrase travels through
 * {@code ssh-keygen -p}'s argv, which makes it visible in
 * {@code /proc/<pid>/cmdline}. That is readable by root only — and
 * {@code aisandboxctl secrets seed} runs as root — so the exposure
 * window is limited to the few-millisecond {@code ssh-keygen}
 * invocation, against a root-only process listing. The decrypted key
 * then lives at-rest under {@code /etc/ai-sandbox-server/secrets/}
 * with mode 0600 and is bind-mounted read-only into every spawned
 * container. Documented in operator notes (README install section).
 */
final class EncryptedKeyDecryptor {

    private final ProcessRunner runner;

    EncryptedKeyDecryptor(ProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Resolve the passphrase from one of three sources (flag-file →
     * env var → interactive prompt), then strip it from a private
     * copy of {@code source}.
     *
     * @param source the operator's private key path. Never mutated.
     * @param passphraseFile path supplied via
     *     {@code --git-key-passphrase-file}, or {@code null}.
     * @param passphraseEnv env-var name supplied via
     *     {@code --git-key-passphrase-env}, or {@code null}.
     * @param io console for the interactive passphrase prompt; used
     *     only when both flags are null.
     * @return path to a fresh temp file holding the decrypted key
     *     (mode 0600). Caller takes ownership and must delete it
     *     after copying to the secrets dir.
     * @throws IOException on any I/O or ssh-keygen failure (including
     *     wrong passphrase — surfaced as a non-zero exit).
     */
    Path decrypt(Path source, Path passphraseFile, String passphraseEnv, ConsoleIO io)
            throws IOException, InterruptedException {
        String passphrase = resolvePassphrase(passphraseFile, passphraseEnv, io);

        // Copy source to a private temp with mode 0600. Doing the chmod
        // BEFORE the copy is intentional — ssh-keygen refuses to read a
        // key that's group/world-readable, and the copy preserves the
        // existing 0600 permission set.
        Path temp = Files.createTempFile("aisandbox-git-key-", "");
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));

        // -p = change passphrase mode, -P <old> -N "" = old → empty.
        ProcessRunner.Result res = runner.runAndCapture(
                List.of("ssh-keygen", "-p", "-P", passphrase, "-N", "", "-f", temp.toString()));
        if (res.exitCode() != 0) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best-effort cleanup; the temp is mode 0600 and will age out via tmp reaping.
            }
            throw new IOException("ssh-keygen -p (passphrase strip) failed (exit=" + res.exitCode() + "): "
                    + res.output().trim());
        }
        return temp;
    }

    private static String resolvePassphrase(Path passphraseFile, String passphraseEnv, ConsoleIO io)
            throws IOException {
        if (passphraseFile != null) {
            // Trim trailing newline only; passphrases CAN contain
            // whitespace, so don't strip leading/embedded whitespace.
            String raw = Files.readString(passphraseFile);
            if (raw.endsWith("\r\n")) {
                return raw.substring(0, raw.length() - 2);
            }
            if (raw.endsWith("\n")) {
                return raw.substring(0, raw.length() - 1);
            }
            return raw;
        }
        if (passphraseEnv != null) {
            String v = System.getenv(passphraseEnv);
            if (v == null) {
                throw new IOException("environment variable " + passphraseEnv + " is unset"
                        + " (referenced via --git-key-passphrase-env)");
            }
            return v;
        }
        if (!io.hasTty()) {
            throw new IOException("SSH key is passphrase-protected but no --git-key-passphrase-file /"
                    + " --git-key-passphrase-env was provided and stdin is not a TTY");
        }
        io.print("  SSH key is passphrase-protected. Enter passphrase: ");
        char[] pw = io.readPassword();
        io.println("");
        if (pw == null) {
            throw new IOException("no console available to read SSH key passphrase");
        }
        return new String(pw);
    }
}
