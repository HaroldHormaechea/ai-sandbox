package com.aisandbox.server.clients.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.pki.PemUtils;
import com.aisandbox.server.test.CertFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC12 — the in-memory allowlist mirrors the folder contents after each
 * rebuild, and {@code rebuild()} returns the set of revoked fingerprints
 * (so the watcher / facade can tear down active sessions).
 */
class ClientAllowlistServiceTest {

    private ClientAllowlistService service(Path dir) {
        return new ClientAllowlistService(new AllowlistDirectory(dir), new ClientCertParser());
    }

    @Test
    void rebuild_picks_up_all_crt_files(@TempDir Path dir) throws Exception {
        CertFixtures.writeClientPemTo(dir, "alice");
        CertFixtures.writeClientPemTo(dir, "bob");

        ClientAllowlistService svc = service(dir);
        Set<String> revoked = svc.rebuild();

        assertThat(svc.list()).hasSize(2);
        assertThat(svc.list()).extracting(c -> c.name()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(revoked).isEmpty();
    }

    @Test
    void rebuild_returns_fingerprints_removed_since_last_snapshot(@TempDir Path dir) throws Exception {
        Path aliceCrt = CertFixtures.writeClientPemTo(dir, "alice");
        Path bobCrt = CertFixtures.writeClientPemTo(dir, "bob");

        ClientAllowlistService svc = service(dir);
        svc.rebuild();

        String bobFp = PemUtils.fingerprintHex(PemUtils.parseCertificate(Files.readString(bobCrt)));

        // Revoke bob by deleting the file, then rebuild.
        Files.delete(bobCrt);
        Set<String> revoked = svc.rebuild();

        assertThat(revoked).containsExactly(bobFp);
        assertThat(svc.list()).extracting(c -> c.name()).containsExactly("alice");
        assertThat(aliceCrt).exists();
    }

    @Test
    void isAllowed_keys_on_fingerprint(@TempDir Path dir) throws Exception {
        Path crt = CertFixtures.writeClientPemTo(dir, "alice");
        String fp = PemUtils.fingerprintHex(PemUtils.parseCertificate(Files.readString(crt)));

        ClientAllowlistService svc = service(dir);
        svc.rebuild();

        assertThat(svc.isAllowed(fp)).isTrue();
        assertThat(svc.isAllowed("0".repeat(64))).isFalse();
    }

    @Test
    void rebuild_soft_fails_on_unparseable_entry(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("good.crt"), CertFixtures.newClient("good").pem());
        Files.writeString(dir.resolve("bad.crt"), "this is not pem at all");

        ClientAllowlistService svc = service(dir);
        svc.rebuild();

        // The good entry survives; the bad one is logged and dropped.
        assertThat(svc.list()).extracting(c -> c.name()).containsExactly("good");
    }

    @Test
    void empty_directory_yields_empty_snapshot(@TempDir Path dir) throws Exception {
        ClientAllowlistService svc = service(dir);
        svc.rebuild();
        assertThat(svc.list()).isEmpty();
    }

    @Test
    void snapshot_is_immutable(@TempDir Path dir) throws Exception {
        CertFixtures.writeClientPemTo(dir, "alice");
        ClientAllowlistService svc = service(dir);
        svc.rebuild();
        // Must not allow external mutation.
        assertThatThrowsOnMutation(svc);
    }

    private void assertThatThrowsOnMutation(ClientAllowlistService svc) {
        try {
            svc.snapshot().clear();
            org.assertj.core.api.Assertions.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException ok) {
            // expected — the snapshot map is immutable (Map.copyOf).
        }
    }
}
