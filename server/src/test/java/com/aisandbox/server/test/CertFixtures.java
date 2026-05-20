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
import java.util.List;
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
 * any real provider API key; the mock contract forbids embedding any
 * Anthropic-style API-key prefix (enforced by AuditNoSecretsTest).
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

    /**
     * UC10 § AC7 — generate a server cert with the supplied SAN entries.
     * Each {@code sanEntries} value follows the {@code DNS:&lt;value&gt;} /
     * {@code IP:&lt;value&gt;} convention defined by
     * {@link SelfSignedServerCertGenerator#generate(String, java.util.List)}.
     * An empty list mints a cert with NO SAN extension (legacy CN-only
     * identity), exercising the {@code extractSanEntries} empty-list path
     * that UC10's {@code ClientInviteCommand} refusal must handle.
     */
    public static ServerMaterial newServer(String cn, List<String> sanEntries) throws Exception {
        SelfSignedServerCertGenerator.Material mat = new SelfSignedServerCertGenerator().generate(cn, sanEntries);
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

    /**
     * UC10 § AC7 — write server.crt + server.key with the supplied SAN
     * entries. The default
     * {@link #writeServerMaterialTo(Path, String)} overload (no SAN) is
     * kept verbatim so existing tests don't churn; new UC10-era tests
     * that need to exercise the SAN-vs-URL refusal call this overload
     * to mint a cert whose SAN list is deterministic and controllable.
     *
     * @param sanEntries ordered list of {@code DNS:<value>} / {@code IP:<value>}
     *                   entries; may be empty (mints a SAN-less cert).
     */
    public static ServerMaterial writeServerMaterialTo(Path pkiDir, String cn, List<String> sanEntries)
            throws Exception {
        Files.createDirectories(pkiDir);
        ServerMaterial m = newServer(cn, sanEntries);
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
