package com.aisandbox.server.cli.pki;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Bundles a private key + cert into a password-protected PKCS#12 blob.
 * Default content-encryption is PBKDF2 / AES-256 (JDK default for new
 * P12 keystores on Java 17+).
 */
public final class Pkcs12Writer {

    private Pkcs12Writer() {}

    public static void write(Path out, String alias, PrivateKey key, X509Certificate cert, char[] passphrase)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try {
            ks.load(null, null);
        } catch (java.io.IOException ioe) {
            throw new KeyStoreException("Cannot init empty PKCS12", ioe);
        }
        ks.setKeyEntry(alias, key, passphrase, new Certificate[] {cert});
        try (OutputStream os = Files.newOutputStream(out)) {
            ks.store(os, passphrase);
        }
    }
}
