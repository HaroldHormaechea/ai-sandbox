package com.aisandbox.server.test;

import com.aisandbox.server.cli.pki.ClientCertGenerator;
import com.aisandbox.server.cli.pki.PemWriter;
import com.aisandbox.server.cli.pki.SelfSignedServerCertGenerator;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

/**
 * Shared test fixture: generates disposable self-signed certificates so
 * the TLS-layer tests don't need committed PEM fixtures. Reuses the
 * production cert-builders in {@code com.aisandbox.server.cli.pki} —
 * exercising those builders is itself coverage of the PKI provisioning
 * path (AC7, AC38).
 *
 * <p>Every test method that needs material instantiates a fresh fixture
 * to avoid cross-test contamination. None of the material here resembles
 * real Anthropic API keys; per the mock contract no {@code sk-ant-} strings
 * may appear in any test file or fixture.
 */
public final class CertFixtures {

    private CertFixtures() {}

    /** A freshly generated client cert + key pair. */
    public record ClientMaterial(X509Certificate certificate, KeyPair keyPair, String pem) {}

    /** A freshly generated server cert + key pair. */
    public record ServerMaterial(X509Certificate certificate, KeyPair keyPair) {}

    public static ClientMaterial newClient(String name) throws Exception {
        ClientCertGenerator.Material mat = new ClientCertGenerator().generate(name);
        return new ClientMaterial(mat.certificate(), mat.keyPair(), pemOf(mat.certificate()));
    }

    public static ServerMaterial newServer(String cn) throws Exception {
        SelfSignedServerCertGenerator.Material mat = new SelfSignedServerCertGenerator().generate(cn);
        return new ServerMaterial(mat.certificate(), mat.keyPair());
    }

    /** Write a fresh client PEM into the given allowlist directory. */
    public static Path writeClientPemTo(Path allowlistDir, String name) throws Exception {
        Files.createDirectories(allowlistDir);
        ClientMaterial m = newClient(name);
        Path target = allowlistDir.resolve(name + ".crt");
        Files.writeString(target, m.pem());
        return target;
    }

    /** Write server.crt + server.key into the given PKI directory. */
    public static ServerMaterial writeServerMaterialTo(Path pkiDir, String cn) throws Exception {
        Files.createDirectories(pkiDir);
        ServerMaterial m = newServer(cn);
        PemWriter.writeCert(pkiDir.resolve("server.crt"), m.certificate());
        PemWriter.writePrivateKey(pkiDir.resolve("server.key"), m.keyPair().getPrivate());
        return m;
    }

    public static String pemOf(X509Certificate cert) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(cert);
        }
        return sw.toString();
    }
}
