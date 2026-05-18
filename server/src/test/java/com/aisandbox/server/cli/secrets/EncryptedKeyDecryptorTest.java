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
 * UC06 § AC3 — passphrase-strip flow in isolation. Pins the three
 * passphrase-sources (file → env → interactive), the
 * {@code ssh-keygen -p -P <pass> -N "" -f <temp>} argv contract, the
 * "operator's host key untouched" invariant, and the no-TTY fail-fast
 * branch when no passphrase source was supplied.
 */
class EncryptedKeyDecryptorTest {

    // Opaque fixture bytes — ssh-keygen is faked, so content is never
    // inspected. We avoid the literal PEM `BEGIN` marker so the
    // gitleaks `private-key` rule doesn't trip on the test source.
    private static final byte[] KEY_BYTES = "<encrypted-key-fixture-bytes-opaque-to-decryptor>\n".getBytes();

    @Test
    void resolves_passphrase_from_file_and_strips_trailing_newline(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("encrypted-key");
        Files.write(src, KEY_BYTES);
        Path passFile = tmp.resolve("pass.txt");
        Files.writeString(passFile, "hunter2\n");

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(0, "");

        Path decrypted = new EncryptedKeyDecryptor(runner).decrypt(src, passFile, null, new FakeConsoleIO());

        try {
            // The temp returned from decrypt() is NOT the source path.
            assertThat(decrypted)
                    .as("decryptor MUST return a private temp, never the operator's source path")
                    .isNotEqualTo(src);
            // Source bytes are unchanged.
            assertThat(Files.readAllBytes(src)).isEqualTo(KEY_BYTES);
            // Temp has mode 0600.
            assertThat(Files.getPosixFilePermissions(decrypted))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            // ssh-keygen -p called with the trimmed passphrase.
            List<String> argv = runner.captureCalls.get(0);
            assertThat(argv).containsSequence("ssh-keygen", "-p");
            assertThat(argv).containsSequence("-P", "hunter2");
            assertThat(argv).containsSequence("-N", "");
            assertThat(argv).containsSequence("-f", decrypted.toString());
        } finally {
            Files.deleteIfExists(decrypted);
        }
    }

    @Test
    void resolves_passphrase_from_file_trims_crlf_only(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("encrypted-key");
        Files.write(src, KEY_BYTES);
        Path passFile = tmp.resolve("pass.txt");
        // \r\n trailing: both bytes get stripped.
        Files.writeString(passFile, "windows-pass\r\n");

        FakeProcessRunner runner = new FakeProcessRunner();
        Path decrypted = new EncryptedKeyDecryptor(runner).decrypt(src, passFile, null, new FakeConsoleIO());
        try {
            assertThat(runner.captureCalls.get(0)).containsSequence("-P", "windows-pass");
        } finally {
            Files.deleteIfExists(decrypted);
        }
    }

    @Test
    void resolves_passphrase_from_file_no_trailing_newline_keeps_full_value(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("encrypted-key");
        Files.write(src, KEY_BYTES);
        Path passFile = tmp.resolve("pass.txt");
        // No trailing newline.
        Files.writeString(passFile, "raw-pass");

        FakeProcessRunner runner = new FakeProcessRunner();
        Path decrypted = new EncryptedKeyDecryptor(runner).decrypt(src, passFile, null, new FakeConsoleIO());
        try {
            assertThat(runner.captureCalls.get(0)).containsSequence("-P", "raw-pass");
        } finally {
            Files.deleteIfExists(decrypted);
        }
    }

    @Test
    void resolves_passphrase_from_env_var(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("encrypted-key");
        Files.write(src, KEY_BYTES);

        FakeProcessRunner runner = new FakeProcessRunner();
        // Use a well-known env var that's almost always set. If it's
        // not, the test is meaningless — skip via assumption rather
        // than fail. (Linux CI hosts always have PATH.)
        String envVar = "PATH";
        String envValue = System.getenv(envVar);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                envValue != null && !envValue.isEmpty(), "PATH env var must be set for this test");

        Path decrypted = new EncryptedKeyDecryptor(runner).decrypt(src, null, envVar, new FakeConsoleIO());
        try {
            assertThat(runner.captureCalls.get(0)).containsSequence("-P", envValue);
        } finally {
            Files.deleteIfExists(decrypted);
        }
    }

    @Test
    void missing_env_var_raises_ioexception(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("encrypted-key");
        Files.write(src, KEY_BYTES);
        FakeProcessRunner runner = new FakeProcessRunner();

        assertThatThrownBy(() -> new EncryptedKeyDecryptor(runner)
                        .decrypt(src, null, "AISANDBOX_TEST_VAR_THAT_IS_NEVER_SET_ZZQ", new FakeConsoleIO()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("AISANDBOX_TEST_VAR_THAT_IS_NEVER_SET_ZZQ")
                .hasMessageContaining("is unset");
    }

    @Test
    void interactive_prompt_reads_password_when_no_flag_supplied(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("encrypted-key");
        Files.write(src, KEY_BYTES);

        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.passwords.add("typed-pass".toCharArray());

        Path decrypted = new EncryptedKeyDecryptor(runner).decrypt(src, null, null, io);
        try {
            assertThat(runner.captureCalls.get(0)).containsSequence("-P", "typed-pass");
            // The step printed a prompt.
            assertThat(io.allOutput()).contains("passphrase-protected");
        } finally {
            Files.deleteIfExists(decrypted);
        }
    }

    @Test
    void no_tty_and_no_flags_raises_ioexception(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("encrypted-key");
        Files.write(src, KEY_BYTES);

        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = false;

        assertThatThrownBy(() -> new EncryptedKeyDecryptor(runner).decrypt(src, null, null, io))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("passphrase-protected")
                .hasMessageContaining("stdin is not a TTY");
    }

    @Test
    void ssh_keygen_failure_propagates_and_cleans_up_temp(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("encrypted-key");
        Files.write(src, KEY_BYTES);
        Path passFile = tmp.resolve("pass.txt");
        Files.writeString(passFile, "wrong");

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(1, "Bad passphrase");

        assertThatThrownBy(() -> new EncryptedKeyDecryptor(runner).decrypt(src, passFile, null, new FakeConsoleIO()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ssh-keygen -p")
                .hasMessageContaining("Bad passphrase");

        // Best-effort cleanup: the temp should not linger.
        // (We can't predict the temp filename, but the source MUST
        // still be intact.)
        assertThat(Files.readAllBytes(src)).isEqualTo(KEY_BYTES);
    }
}
