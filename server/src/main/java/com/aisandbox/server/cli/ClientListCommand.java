package com.aisandbox.server.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code aisandboxctl client list} — prints a table matching the
 * {@code GET /v1/clients} shape (name, CN, fingerprint, serial, added).
 */
@Command(name = "list", description = "List currently allowlisted client certs.")
public class ClientListCommand implements Runnable {

    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    @Command(name = "list", description = "List currently allowlisted client certs.")
    public static class List implements Callable<Integer> {

        @Option(
                names = "--clients-dir",
                description = "Server allowlist directory (default /etc/ai-sandbox-server/clients).")
        Path clientsDir = Path.of("/etc/ai-sandbox-server/clients");

        @Override
        public Integer call() throws Exception {
            if (!Files.isDirectory(clientsDir)) {
                System.err.println("Allowlist directory does not exist: " + clientsDir);
                return 1;
            }
            System.out.printf("%-24s %-30s %-65s %s%n", "NAME", "CN", "FINGERPRINT", "ADDED");
            try (var stream = Files.newDirectoryStream(clientsDir, "*.crt")) {
                for (Path p : stream) {
                    String pem = Files.readString(p);
                    try {
                        var cert = com.aisandbox.server.pki.PemUtils.parseCertificate(pem);
                        String name = stripExt(p.getFileName().toString());
                        String cn = com.aisandbox.server.pki.PemUtils.extractCommonName(cert);
                        String fp = com.aisandbox.server.pki.PemUtils.fingerprintHex(cert);
                        System.out.printf("%-24s %-30s %-65s %s%n", name, cn, fp, Files.getLastModifiedTime(p));
                    } catch (Exception e) {
                        System.out.printf("%-24s %s%n", p.getFileName(), "(parse error: " + e.getMessage() + ")");
                    }
                }
            }
            return 0;
        }

        private static String stripExt(String fname) {
            int dot = fname.lastIndexOf('.');
            return dot < 0 ? fname : fname.substring(0, dot);
        }
    }
}
