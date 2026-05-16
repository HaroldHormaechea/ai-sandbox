package com.aisandbox.server.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code aisandboxctl client revoke <nameOrFingerprint>} — deletes the
 * matching allowlist file. The server's watcher picks up the change and
 * tears down any in-flight connection from that cert within ≤ 1s.
 */
@Command(name = "revoke", description = "Delete a client cert from the allowlist.")
public class ClientRevokeCommand implements Runnable {

    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    @Command(name = "revoke", description = "Delete a client cert from the allowlist.")
    public static class Revoke implements Callable<Integer> {

        @Parameters(arity = "1", description = "Client name or fingerprint (SHA-256 hex).")
        String target;

        @Option(
                names = "--clients-dir",
                description = "Server allowlist directory (default /etc/ai-sandbox-server/clients).")
        Path clientsDir = Path.of("/etc/ai-sandbox-server/clients");

        @Override
        public Integer call() throws Exception {
            Path direct = clientsDir.resolve(target + ".crt");
            if (Files.exists(direct)) {
                Files.delete(direct);
                System.out.println("Revoked " + direct);
                return 0;
            }
            // Best-effort fingerprint match: scan and parse each cert.
            try (var stream = Files.newDirectoryStream(clientsDir, "*.crt")) {
                for (Path p : stream) {
                    String pem = Files.readString(p);
                    java.security.cert.X509Certificate cert = com.aisandbox.server.pki.PemUtils.parseCertificate(pem);
                    String fp = com.aisandbox.server.pki.PemUtils.fingerprintHex(cert);
                    if (fp.equalsIgnoreCase(target)) {
                        Files.delete(p);
                        System.out.println("Revoked " + p + " (fp=" + fp + ")");
                        return 0;
                    }
                }
            }
            System.err.println("No matching cert for: " + target);
            return 1;
        }
    }
}
