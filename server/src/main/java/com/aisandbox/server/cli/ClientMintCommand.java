package com.aisandbox.server.cli;

import com.aisandbox.server.cli.pki.ClientCertGenerator;
import com.aisandbox.server.cli.pki.PemWriter;
import com.aisandbox.server.cli.pki.Pkcs12Writer;
import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code aisandboxctl client mint <name>} — generates a fresh client
 * cert + key, packages them as a PKCS#12 bundle (default) or PEM trio
 * ({@code --pem}), writes a tiny README for the recipient, and copies
 * the public cert into the server allowlist folder for immediate
 * pickup by the watcher.
 */
@Command(
        name = "client",
        description = "Client mint/revoke/list.",
        subcommands = {ClientMintCommand.Mint.class, ClientRevokeCommand.Revoke.class, ClientListCommand.List.class})
public class ClientMintCommand implements Runnable {

    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    @Command(name = "mint", description = "Generate a new client cert + bundle.")
    public static class Mint implements Callable<Integer> {

        @Parameters(arity = "1", description = "Client name (used as filename stem + CN).")
        String name;

        @Option(names = "--out", description = "Output directory for the bundle (default ./)")
        Path outDir = Path.of(".");

        @Option(
                names = "--clients-dir",
                description = "Server allowlist directory (default /etc/ai-sandbox-server/clients)")
        Path clientsDir = Path.of("/etc/ai-sandbox-server/clients");

        @Option(
                names = "--pki-dir",
                description = "Server PKI dir (where server.crt lives) (default /etc/ai-sandbox-server/pki)")
        Path pkiDir = Path.of("/etc/ai-sandbox-server/pki");

        @Option(names = "--pem", description = "Emit a PEM trio instead of a PKCS#12 bundle.")
        boolean pem;

        @Override
        public Integer call() throws Exception {
            if (!name.matches("[A-Za-z0-9._-]+")) {
                System.err.println("Client name must match [A-Za-z0-9._-]+");
                return 2;
            }
            if (!Files.isDirectory(outDir)) {
                Files.createDirectories(outDir);
            }
            if (!Files.isDirectory(clientsDir)) {
                Files.createDirectories(clientsDir);
            }

            var material = new ClientCertGenerator().generate(name);

            // Always write the public cert into the allowlist folder.
            PemWriter.writeCert(clientsDir.resolve(name + ".crt"), material.certificate());

            // Always copy the server cert next to the bundle so the
            // client can pin it.
            Path serverCrt = pkiDir.resolve("server.crt");
            if (Files.isRegularFile(serverCrt)) {
                Files.copy(serverCrt, outDir.resolve("server.crt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            if (pem) {
                PemWriter.writeCert(outDir.resolve(name + ".crt"), material.certificate());
                PemWriter.writePrivateKey(
                        outDir.resolve(name + ".key"), material.keyPair().getPrivate());
            } else {
                char[] pass = readPassphrase();
                try {
                    Pkcs12Writer.write(
                            outDir.resolve(name + ".p12"),
                            name,
                            material.keyPair().getPrivate(),
                            material.certificate(),
                            pass);
                } finally {
                    Arrays.fill(pass, '\0');
                }
            }

            writeReadme(outDir, name, pem);

            System.out.println("Client minted: " + name);
            System.out.println("  bundle    : " + outDir + (pem ? " (.crt + .key)" : " (.p12)"));
            System.out.println("  server cp : " + outDir.resolve("server.crt"));
            System.out.println("  allowlist : " + clientsDir.resolve(name + ".crt"));
            return 0;
        }

        private static char[] readPassphrase() {
            Console c = System.console();
            if (c == null) {
                System.err.println("No console available; emit a PKCS#12 with --pem fallback?");
                throw new IllegalStateException("Cannot read passphrase without a TTY");
            }
            char[] p = c.readPassword("Passphrase for the .p12 bundle: ");
            char[] p2 = c.readPassword("Confirm passphrase: ");
            if (!Arrays.equals(p, p2)) {
                Arrays.fill(p, '\0');
                Arrays.fill(p2, '\0');
                throw new IllegalStateException("Passphrases do not match");
            }
            Arrays.fill(p2, '\0');
            return p;
        }

        private static void writeReadme(Path outDir, String name, boolean pem) throws IOException {
            String body;
            if (pem) {
                body =
                        """
                        Drop %s.crt + %s.key + server.crt into your client.

                        Curl example:
                          curl --cert %s.crt --key %s.key --cacert server.crt \\
                               https://your-host:12410/v1/sessions
                        """
                                .formatted(name, name, name, name);
            } else {
                body =
                        """
                        Use %s.p12 (PKCS#12 bundle) with your client (the passphrase you
                        chose during mint unlocks it). server.crt is the server's public
                        cert; pin it to verify the server.

                        Curl example:
                          curl --cert-type P12 --cert %s.p12:<passphrase> --cacert server.crt \\
                               https://your-host:12410/v1/sessions
                        """
                                .formatted(name, name);
            }
            Files.writeString(outDir.resolve("README.txt"), body);
        }
    }
}
