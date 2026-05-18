package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC06 § AC2 + parts of AC3 — SSH-key step coverage. Pinned behaviours:
 *
 * <ul>
 *   <li>Flag-driven {@code --git-key PATH} copies the unencrypted key
 *       byte-for-byte into {@code <secrets-dir>/git-key} with mode 0600.</li>
 *   <li>Flag-driven encrypted-key route hands off to
 *       {@link EncryptedKeyDecryptor}; the operator's host key is
 *       untouched.</li>
 *   <li>Interactive candidate enumeration mirrors
 *       {@code setup.sh:list_ssh_keys} — excludes {@code *.pub},
 *       {@code known_hosts*}, {@code config},
 *       {@code authorized_keys}, {@code environment}, {@code *.bak}.</li>
 *   <li>Interactive "pick by number", "pick by path", and "generate"
 *       all land at {@code <secrets-dir>/git-key}.</li>
 *   <li>Generate runs {@code ssh-keygen -t ed25519 -f <out> -N ""
 *       -C "ai-sandbox-server"}.</li>
 * </ul>
 */
class SshKeyStepTest {

    // Opaque fixture bytes — content is irrelevant because every
    // ssh-keygen invocation is intercepted by FakeProcessRunner; the
    // step only ever performs byte-identical copies. We deliberately
    // do NOT use the real PEM `BEGIN OPENSSH PRIVATE KEY` header
    // string here (the dashed marker matches the gitleaks
    // `private-key` rule). Real keys are never checked in — operators
    // bring their own.
    private static final byte[] PRIVATE_KEY_BYTES = "<ssh-key-fixture-bytes-opaque-to-step>\n".getBytes();

    /** Build a step with the standard collaborators wired against {@code runner}. */
    private static SshKeyStep step(FakeProcessRunner runner, FakeConsoleIO io, Path sshDir) {
        EncryptedKeyDecryptor decryptor = new EncryptedKeyDecryptor(runner);
        return new SshKeyStep(runner, io, decryptor, sshDir);
    }

    @Test
    void flag_driven_copies_unencrypted_key_to_output_with_mode_0600(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src-key");
        Files.write(src, PRIVATE_KEY_BYTES);
        Path out = tmp.resolve("secrets/git-key");
        Files.createDirectories(out.getParent());

        FakeProcessRunner runner = new FakeProcessRunner();
        // ssh-keygen -y -P "" -f <key> → exit 0 means unencrypted.
        runner.captureResponse = argv -> new ProcessRunner.Result(0, "");
        FakeConsoleIO io = new FakeConsoleIO();

        step(runner, io, tmp.resolve("ssh-dir"))
                .run(src, /* passFile */ null, /* passEnv */ null, out, /* ownership */ null);

        // Byte-identical copy.
        assertThat(Files.readAllBytes(out)).isEqualTo(PRIVATE_KEY_BYTES);
        // Mode 0600.
        assertThat(Files.getPosixFilePermissions(out))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        // Only the encryption probe ran; no ssh-keygen -p call.
        assertThat(runner.captureCalls).hasSize(1);
        assertThat(runner.captureCalls.get(0)).containsSubsequence("ssh-keygen", "-y", "-P", "", "-f", src.toString());
    }

    @Test
    void flag_driven_encrypted_key_routes_through_decryptor_without_mutating_source(@TempDir Path tmp)
            throws Exception {
        Path src = tmp.resolve("src-encrypted-key");
        Files.write(src, PRIVATE_KEY_BYTES);
        byte[] sourceBytesBefore = Files.readAllBytes(src);
        Path passFile = tmp.resolve("pass");
        Files.writeString(passFile, "hunter2\n");

        Path out = tmp.resolve("secrets/git-key");
        Files.createDirectories(out.getParent());

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> {
            // ssh-keygen -y -P "" -f <key>: exit 1 → encrypted.
            if (argv.size() >= 2 && "ssh-keygen".equals(argv.get(0)) && "-y".equals(argv.get(1))) {
                return new ProcessRunner.Result(1, "Load key: incorrect passphrase");
            }
            // ssh-keygen -p -P <pass> -N "" -f <temp>: exit 0 → success.
            // (Our fake doesn't actually rewrite the temp; the test
            // doesn't depend on the bytes since the contract is
            // "operator's host key untouched".)
            return new ProcessRunner.Result(0, "");
        };
        FakeConsoleIO io = new FakeConsoleIO();

        step(runner, io, tmp.resolve("ssh-dir")).run(src, passFile, null, out, null);

        // AC3 — operator's host key not mutated.
        assertThat(Files.readAllBytes(src))
                .as("source key MUST NOT be touched (AC3 § operator's host key untouched)")
                .isEqualTo(sourceBytesBefore);

        // Output exists and is mode 0600.
        assertThat(out).exists();
        assertThat(Files.getPosixFilePermissions(out))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        // Both shell-outs fired: -y probe and -p strip.
        assertThat(runner.captureCalls).hasSize(2);
        assertThat(runner.captureCalls.get(0)).contains("-y");
        List<String> stripCall = runner.captureCalls.get(1);
        assertThat(stripCall).contains("-p").contains("-P", "hunter2").contains("-N", "");
    }

    @Test
    void flag_driven_missing_source_raises_ioexception(@TempDir Path tmp) {
        Path missing = tmp.resolve("not-there");
        Path out = tmp.resolve("git-key");
        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        assertThatThrownBy(() -> step(runner, io, tmp.resolve("ssh-dir")).run(missing, null, null, out, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("SSH key not found");
    }

    @Test
    void interactive_lists_candidates_filtering_pub_known_hosts_config_authorized_bak(@TempDir Path tmp)
            throws Exception {
        Path sshDir = tmp.resolve(".ssh");
        Files.createDirectories(sshDir);
        Files.write(sshDir.resolve("id_ed25519"), PRIVATE_KEY_BYTES);
        Files.write(sshDir.resolve("id_ed25519.pub"), "ssh-ed25519 AAAA bob\n".getBytes());
        Files.write(sshDir.resolve("id_rsa"), PRIVATE_KEY_BYTES);
        Files.write(sshDir.resolve("id_rsa.pub"), "ssh-rsa AAAA bob\n".getBytes());
        Files.write(sshDir.resolve("known_hosts"), new byte[] {});
        Files.write(sshDir.resolve("known_hosts.old"), new byte[] {});
        Files.write(sshDir.resolve("config"), new byte[] {});
        Files.write(sshDir.resolve("authorized_keys"), new byte[] {});
        Files.write(sshDir.resolve("environment"), new byte[] {});
        Files.write(sshDir.resolve("backup-key.bak"), new byte[] {});

        Path out = tmp.resolve("secrets/git-key");
        Files.createDirectories(out.getParent());

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(0, ""); // unencrypted
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.inputLines.add("1"); // pick the first candidate (id_ed25519 — sorted)

        step(runner, io, sshDir).run(null, null, null, out, null);

        String printed = io.allOutput();
        // Only id_ed25519 and id_rsa survive the filter.
        assertThat(printed).contains("id_ed25519").contains("id_rsa");
        // Excluded entries MUST NOT appear.
        assertThat(printed)
                .doesNotContain("id_ed25519.pub")
                .doesNotContain("id_rsa.pub")
                .doesNotContain("known_hosts")
                .doesNotContain("config\n")
                .doesNotContain("authorized_keys")
                .doesNotContain("environment")
                .doesNotContain(".bak");

        // Choice "1" picks id_ed25519 (alphabetical first); output
        // matches the chosen key's bytes.
        assertThat(Files.readAllBytes(out)).isEqualTo(PRIVATE_KEY_BYTES);
    }

    @Test
    void interactive_pick_by_full_path_copies_the_typed_file(@TempDir Path tmp) throws Exception {
        Path sshDir = tmp.resolve(".ssh"); // empty
        Files.createDirectories(sshDir);
        Path elsewhere = tmp.resolve("elsewhere/my-key");
        Files.createDirectories(elsewhere.getParent());
        Files.write(elsewhere, PRIVATE_KEY_BYTES);
        Path out = tmp.resolve("secrets/git-key");
        Files.createDirectories(out.getParent());

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(0, ""); // unencrypted
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.inputLines.add(elsewhere.toString());

        step(runner, io, sshDir).run(null, null, null, out, null);

        assertThat(Files.readAllBytes(out)).isEqualTo(PRIVATE_KEY_BYTES);
    }

    @Test
    void interactive_reprompts_on_unknown_input_until_match(@TempDir Path tmp) throws Exception {
        Path sshDir = tmp.resolve(".ssh");
        Files.createDirectories(sshDir);
        Files.write(sshDir.resolve("id_ed25519"), PRIVATE_KEY_BYTES);
        Path out = tmp.resolve("secrets/git-key");
        Files.createDirectories(out.getParent());

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(0, "");
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.inputLines.add("garbage"); // not a number, not a path, not "g"
        io.inputLines.add("99"); // out-of-range index
        io.inputLines.add("1"); // valid

        step(runner, io, sshDir).run(null, null, null, out, null);

        // Two "Not found:" lines emitted before the successful match.
        long notFoundCount =
                io.printed.stream().filter(s -> s.contains("Not found:")).count();
        assertThat(notFoundCount).isEqualTo(2);
        assertThat(Files.readAllBytes(out)).isEqualTo(PRIVATE_KEY_BYTES);
    }

    @Test
    void interactive_generate_invokes_ssh_keygen_with_documented_argv(@TempDir Path tmp) throws Exception {
        Path sshDir = tmp.resolve(".ssh"); // empty
        Files.createDirectories(sshDir);
        Path out = tmp.resolve("secrets/git-key");
        Files.createDirectories(out.getParent());

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> {
            if (argv.contains("-t") && argv.contains("ed25519")) {
                // Real ssh-keygen would write both <out> and <out>.pub.
                // Fake it: just write the private key target so the
                // downstream setPosixFilePermissions call succeeds.
                int fIdx = argv.indexOf("-f") + 1;
                Path target = Path.of(argv.get(fIdx));
                try {
                    Files.write(target, PRIVATE_KEY_BYTES);
                    Files.writeString(Path.of(target + ".pub"), "ssh-ed25519 AAAA gen\n");
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
                return new ProcessRunner.Result(0, "");
            }
            return new ProcessRunner.Result(0, "");
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.inputLines.add("g");

        step(runner, io, sshDir).run(null, null, null, out, null);

        // AC2 — verbatim argv: ssh-keygen -t ed25519 -f <out> -N "" -C "ai-sandbox-server"
        List<String> argv = runner.captureCalls.get(0);
        assertThat(argv).containsSequence("ssh-keygen", "-t", "ed25519");
        assertThat(argv).containsSequence("-f", out.toString());
        assertThat(argv).containsSequence("-N", "");
        assertThat(argv).containsSequence("-C", "ai-sandbox-server");

        // Result file exists with mode 0600 + the public-key sibling.
        assertThat(out).exists();
        assertThat(Files.getPosixFilePermissions(out))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Path.of(out + ".pub")).exists();
    }

    @Test
    void interactive_generate_pre_deletes_stale_target_and_pub_files(@TempDir Path tmp) throws Exception {
        Path sshDir = tmp.resolve(".ssh");
        Files.createDirectories(sshDir);
        Path out = tmp.resolve("secrets/git-key");
        Files.createDirectories(out.getParent());
        // Stale files from a prior --force re-run: ssh-keygen refuses
        // to overwrite without prompting, so the step pre-deletes.
        Files.writeString(out, "stale-private\n");
        Files.writeString(Path.of(out + ".pub"), "stale-public\n");

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> {
            if (argv.contains("-t") && argv.contains("ed25519")) {
                // By the time ssh-keygen is invoked, the step MUST have
                // already removed both files (so ssh-keygen has a clean
                // canvas).
                int fIdx = argv.indexOf("-f") + 1;
                Path target = Path.of(argv.get(fIdx));
                assertThat(Files.exists(target))
                        .as("stale <out> file must be removed before ssh-keygen runs")
                        .isFalse();
                assertThat(Files.exists(Path.of(target + ".pub")))
                        .as("stale <out>.pub file must be removed before ssh-keygen runs")
                        .isFalse();
                try {
                    Files.write(target, PRIVATE_KEY_BYTES);
                    Files.writeString(Path.of(target + ".pub"), "fresh\n");
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
                return new ProcessRunner.Result(0, "");
            }
            return new ProcessRunner.Result(0, "");
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.inputLines.add("g");

        step(runner, io, sshDir).run(null, null, null, out, null);

        assertThat(Files.readAllBytes(out)).isEqualTo(PRIVATE_KEY_BYTES);
    }

    @Test
    void interactive_generate_surfaces_ssh_keygen_failure(@TempDir Path tmp) throws Exception {
        Path sshDir = tmp.resolve(".ssh");
        Files.createDirectories(sshDir);
        Path out = tmp.resolve("secrets/git-key");
        Files.createDirectories(out.getParent());

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(1, "ssh-keygen: command not found");
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.inputLines.add("g");

        assertThatThrownBy(() -> step(runner, io, sshDir).run(null, null, null, out, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ssh-keygen -t ed25519 failed");
    }

    @Test
    void interactive_eof_on_stdin_raises_ioexception(@TempDir Path tmp) throws Exception {
        Path sshDir = tmp.resolve(".ssh");
        Files.createDirectories(sshDir);
        Path out = tmp.resolve("secrets/git-key");
        Files.createDirectories(out.getParent());

        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        // inputLines empty → readLine() returns null → step gives up.

        assertThatThrownBy(() -> step(runner, io, sshDir).run(null, null, null, out, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("EOF on stdin");
    }
}
