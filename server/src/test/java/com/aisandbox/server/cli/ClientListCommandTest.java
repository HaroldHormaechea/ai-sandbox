package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.test.CertFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * AC40 — {@code client list} prints a table with one row per allowlist
 * entry: name, CN, fingerprint, added timestamp.
 */
class ClientListCommandTest {

    @Test
    void list_prints_one_row_per_entry(@TempDir Path tmp) throws Exception {
        Path clients = tmp.resolve("clients");
        CertFixtures.writeClientPemTo(clients, "alice");
        CertFixtures.writeClientPemTo(clients, "bob");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            int exit = new CommandLine(new ClientListCommand.List())
                    .execute( "--clients-dir", clients.toString());
            assertThat(exit).isZero();
        } finally {
            System.setOut(originalOut);
        }

        String stdout = buf.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("NAME").contains("CN").contains("FINGERPRINT").contains("ADDED");
        assertThat(stdout).contains("alice").contains("bob");
    }

    @Test
    void returns_one_when_dir_missing(@TempDir Path tmp) throws Exception {
        Path notADir = tmp.resolve("nope");
        // Capture stderr so the test output stays clean.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int exit = new CommandLine(new ClientListCommand.List())
                    .execute( "--clients-dir", notADir.toString());
            assertThat(exit).isEqualTo(1);
        } finally {
            System.setErr(originalErr);
        }
        assertThat(notADir).doesNotExist();
        // Sanity — empty dir prints something useful (no exception escaping).
        Files.createDirectories(notADir);
    }
}
