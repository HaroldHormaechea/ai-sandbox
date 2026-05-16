package com.aisandbox.server.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.aisandbox.server.clients.service.ClientAllowlistService;
import com.aisandbox.server.test.CertFixtures;
import io.netty.handler.ssl.SslContext;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the AC14 server-cert hot-reload holder. The holder MUST
 * build an {@link SslContext} from PEM cert + key files on disk and
 * atomically swap when {@link ReloadableSslContextHolder#rebuild} is
 * called again with new material.
 */
class ReloadableSslContextHolderTest {

    @Test
    void current_throws_before_first_rebuild() {
        ReloadableSslContextHolder holder = new ReloadableSslContextHolder(mock(ClientAllowlistService.class));
        assertThatThrownBy(holder::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not yet initialised");
    }

    @Test
    void rebuild_produces_a_server_sslcontext(@TempDir Path pki) throws Exception {
        CertFixtures.writeServerMaterialTo(pki, "test-server");
        ReloadableSslContextHolder holder = new ReloadableSslContextHolder(mock(ClientAllowlistService.class));

        holder.rebuild(pki.resolve("server.crt"), pki.resolve("server.key"));

        SslContext ctx = holder.current();
        assertThat(ctx).isNotNull();
        assertThat(ctx.isServer()).isTrue();
        // ALPN config must surface h2 so AC11 (HTTP/2 over TLS) is on.
        assertThat(ctx.applicationProtocolNegotiator().protocols()).contains("h2", "http/1.1");
    }

    @Test
    void rebuild_swaps_the_underlying_context(@TempDir Path pki) throws Exception {
        CertFixtures.writeServerMaterialTo(pki, "cn-first");
        ReloadableSslContextHolder holder = new ReloadableSslContextHolder(mock(ClientAllowlistService.class));
        holder.rebuild(pki.resolve("server.crt"), pki.resolve("server.key"));
        SslContext first = holder.current();

        // Overwrite with a freshly generated server pair.
        CertFixtures.writeServerMaterialTo(pki, "cn-second");
        holder.rebuild(pki.resolve("server.crt"), pki.resolve("server.key"));
        SslContext second = holder.current();

        assertThat(second).isNotSameAs(first);
    }
}
