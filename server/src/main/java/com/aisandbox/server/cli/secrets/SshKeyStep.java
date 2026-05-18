package com.aisandbox.server.cli.secrets;

import com.aisandbox.server.cli.Ownership;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * UC06 § AC2 / AC3 — step (a) of {@code aisandboxctl secrets seed}:
 * place an unencrypted SSH private key at
 * {@code <secrets-dir>/git-key} (mode 0600, owned by the
 * {@code ai-sandbox-server} system user).
 *
 * <p>Three flow shapes:
 *
 * <ul>
 *   <li><b>Flag-driven</b> — {@code --git-key PATH} given: byte-copy
 *       that file. If encrypted, also pass
 *       {@code --git-key-passphrase-file} or
 *       {@code --git-key-passphrase-env}; the {@link
 *       EncryptedKeyDecryptor} strips the passphrase into a private
 *       temp before the final copy.</li>
 *   <li><b>Interactive — pick</b> — list candidates in
 *       {@code <sshDir>/} (per {@code setup.sh}'s
 *       {@code list_ssh_keys}); operator types a number, a path, or
 *       {@code g} to generate. Encrypted picks fall through the same
 *       decryptor with an on-TTY passphrase prompt.</li>
 *   <li><b>Interactive — generate</b> — runs
 *       {@code ssh-keygen -t ed25519 -f <secrets-dir>/git-key -N ""}
 *       so the secrets dir receives a freshly minted key.</li>
 * </ul>
 *
 * <p>The operator's host key is never mutated: encrypted keys are
 * decrypted into a temp file and the temp is what gets copied to the
 * output. AC3 § "Operator's host key untouched" assertion.
 */
public final class SshKeyStep {

    private final ProcessRunner runner;
    private final ConsoleIO io;
    private final EncryptedKeyDecryptor decryptor;
    private final Path sshDir;

    public SshKeyStep(ProcessRunner runner, ConsoleIO io, EncryptedKeyDecryptor decryptor, Path sshDir) {
        this.runner = runner;
        this.io = io;
        this.decryptor = decryptor;
        this.sshDir = sshDir;
    }

    /**
     * Run the SSH-key step.
     *
     * @param gitKeyFlag value of {@code --git-key} (operator's source
     *     path) or {@code null} for interactive.
     * @param passphraseFile value of {@code --git-key-passphrase-file}
     *     or {@code null}.
     * @param passphraseEnv value of {@code --git-key-passphrase-env}
     *     or {@code null}.
     * @param outputPath destination ({@code <secrets-dir>/git-key}).
     * @param ownership pre-resolved owner/group to chown the result
     *     to, or {@code null} on hosts where the lookup failed.
     */
    public void run(Path gitKeyFlag, Path passphraseFile, String passphraseEnv, Path outputPath, Ownership ownership)
            throws IOException, InterruptedException {

        if (gitKeyFlag != null) {
            copyOrDecrypt(gitKeyFlag, passphraseFile, passphraseEnv, outputPath);
        } else {
            interactivePickOrGenerate(passphraseFile, passphraseEnv, outputPath);
        }

        Files.setPosixFilePermissions(outputPath, PosixFilePermissions.fromString("rw-------"));
        if (ownership != null) {
            ownership.chown(outputPath);
        }
    }

    // ── flag-driven path ────────────────────────────────────────────

    private void copyOrDecrypt(Path source, Path passphraseFile, String passphraseEnv, Path outputPath)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("SSH key not found at " + source + " (--git-key)");
        }
        if (isEncrypted(source)) {
            Path decrypted = decryptor.decrypt(source, passphraseFile, passphraseEnv, io);
            try {
                Files.copy(decrypted, outputPath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try {
                    Files.deleteIfExists(decrypted);
                } catch (IOException ignored) {
                    // mode-0600 temp ages out via the OS tmp reaper.
                }
            }
        } else {
            Files.copy(source, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ── interactive path ────────────────────────────────────────────

    private void interactivePickOrGenerate(Path passphraseFile, String passphraseEnv, Path outputPath)
            throws IOException, InterruptedException {
        List<String> keys = listSshKeys();
        io.println("");
        io.println("  step 1/4 — SSH key");
        if (!keys.isEmpty()) {
            io.println("  Candidate keys in " + sshDir + ":");
            for (int i = 0; i < keys.size(); i++) {
                io.println("    " + (i + 1) + ") " + keys.get(i));
            }
        } else {
            io.println("  No candidate keys found in " + sshDir + ".");
        }
        io.println("");

        while (true) {
            io.print("  Type a number, 'g' to generate ed25519, or a path to a key: ");
            String choice = io.readLine();
            if (choice == null) {
                throw new IOException("EOF on stdin before SSH key was selected");
            }
            choice = choice.trim();

            if (choice.equalsIgnoreCase("g")) {
                generateEd25519(outputPath);
                return;
            }
            Path picked = null;
            if (choice.matches("\\d+")) {
                int idx = Integer.parseInt(choice);
                if (idx >= 1 && idx <= keys.size()) {
                    picked = sshDir.resolve(keys.get(idx - 1));
                }
            }
            if (picked == null) {
                Path asPath = Path.of(choice);
                if (Files.isRegularFile(asPath)) {
                    picked = asPath;
                }
            }
            if (picked != null) {
                copyOrDecrypt(picked, passphraseFile, passphraseEnv, outputPath);
                return;
            }
            io.println("  Not found: " + choice);
        }
    }

    private void generateEd25519(Path target) throws IOException, InterruptedException {
        // ssh-keygen refuses to overwrite without prompting, so clear
        // both the private + public files first. AC13's --force path
        // already cleared conflicts at the orchestrator level, but a
        // belt-and-braces delete keeps the generate path robust in
        // re-run scenarios.
        Files.deleteIfExists(target);
        Path pub = Path.of(target.toString() + ".pub");
        Files.deleteIfExists(pub);

        ProcessRunner.Result res = runner.runAndCapture(List.of(
                "ssh-keygen", "-t", "ed25519", "-f", target.toString(), "-N", "", "-C", "ai-sandbox-server"));
        if (res.exitCode() != 0) {
            throw new IOException("ssh-keygen -t ed25519 failed (exit=" + res.exitCode() + "): "
                    + res.output().trim());
        }
        // ssh-keygen also writes a <target>.pub next to the private
        // key. We leave it under the secrets dir for the operator's
        // convenience (helpful when registering the key with the git
        // remote); only the private key is consumed by the container.
    }

    // ── helpers ─────────────────────────────────────────────────────

    /**
     * Probe encryption status via {@code ssh-keygen -y -P "" -f
     * <key>}: exit 0 ↔ unencrypted (the empty passphrase worked); any
     * non-zero exit ↔ encrypted (or unreadable). Same heuristic as
     * {@code setup.sh} uses inline.
     */
    private boolean isEncrypted(Path key) throws IOException, InterruptedException {
        ProcessRunner.Result res =
                runner.runAndCapture(List.of("ssh-keygen", "-y", "-P", "", "-f", key.toString()));
        return res.exitCode() != 0;
    }

    /**
     * Enumerate keys in {@link #sshDir}, mirroring {@code setup.sh}'s
     * {@code list_ssh_keys} filter: exclude {@code *.pub},
     * {@code known_hosts*}, {@code config}, {@code authorized_keys},
     * {@code environment}, and {@code *.bak}.
     */
    private List<String> listSshKeys() {
        if (!Files.isDirectory(sshDir)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(sshDir)) {
            for (var it = s.iterator(); it.hasNext(); ) {
                Path p = it.next();
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (name.endsWith(".pub")) continue;
                if (name.startsWith("known_hosts")) continue;
                if (name.equals("config")) continue;
                if (name.equals("authorized_keys")) continue;
                if (name.equals("environment")) continue;
                if (name.endsWith(".bak")) continue;
                out.add(name);
            }
        } catch (IOException ioe) {
            return List.of();
        }
        out.sort(String::compareTo);
        return out;
    }
}
