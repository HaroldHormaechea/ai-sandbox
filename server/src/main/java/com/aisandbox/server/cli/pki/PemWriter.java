package com.aisandbox.server.cli.pki;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.EnumSet;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

/**
 * Writes PEM-encoded certs and private keys with appropriate POSIX
 * permissions (0644 for certs, 0600 for keys).
 */
public final class PemWriter {

    private PemWriter() {}

    public static void writeCert(Path path, X509Certificate cert) throws IOException {
        Files.writeString(path, certPem(cert));
        setPosix(path, "rw-r--r--");
    }

    /**
     * PEM-encode a certificate to a {@link String}. Used by the UC04
     * enrollment cert-mint service to feed
     * {@code ClientAllowlistFacade.addClient(name, pem)} without taking a
     * round-trip through disk.
     */
    public static String certPem(X509Certificate cert) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pw = new JcaPEMWriter(sw)) {
            pw.writeObject(cert);
        }
        return sw.toString();
    }

    public static void writePrivateKey(Path path, PrivateKey key) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pw = new JcaPEMWriter(sw)) {
            pw.writeObject(key);
        }
        Files.writeString(path, sw.toString());
        setPosix(path, "rw-------");
    }

    private static void setPosix(Path p, String mode) {
        try {
            EnumSet<PosixFilePermission> perms = EnumSet.copyOf(PosixFilePermissions.fromString(mode));
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX perms unsupported (Windows) — leave the file as-is; the
            // README warns that aisandboxctl should be run on the same host
            // as the server (Linux).
        }
    }
}
