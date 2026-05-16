package com.aisandbox.server.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.StreamRegistryService.ActiveStream;
import com.aisandbox.server.stream.service.StreamRegistryService.StreamId;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * AC28 — per-stream caps: per-client (default 10), global (default 100).
 * register() returns false when either cap is tripped; unregister()
 * decrements both counters.
 */
class StreamRegistryServiceTest {

    private static ServerProperties props(int perClient, int global) {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, perClient, global, 262144, 16384, 262144, 30, 15));
    }

    private static ActiveStream activeStream(String fingerprint) {
        return new ActiveStream(StreamId.fresh(), 1, fingerprint, Mockito.mock(WebSocketSession.class));
    }

    @Test
    void registers_up_to_per_client_cap() {
        StreamRegistryService reg = new StreamRegistryService(props(2, 10));
        assertThat(reg.register(activeStream("aa"))).isTrue();
        assertThat(reg.register(activeStream("aa"))).isTrue();
        // 3rd from the same client trips per-client cap.
        assertThat(reg.register(activeStream("aa"))).isFalse();
        // A different client is unaffected.
        assertThat(reg.register(activeStream("bb"))).isTrue();
        assertThat(reg.globalCount()).isEqualTo(3);
        assertThat(reg.countFor("aa")).isEqualTo(2);
    }

    @Test
    void registers_up_to_global_cap() {
        StreamRegistryService reg = new StreamRegistryService(props(100, 2));
        assertThat(reg.register(activeStream("a"))).isTrue();
        assertThat(reg.register(activeStream("b"))).isTrue();
        assertThat(reg.register(activeStream("c"))).isFalse();
        assertThat(reg.globalCount()).isEqualTo(2);
    }

    @Test
    void unregister_frees_a_slot() {
        StreamRegistryService reg = new StreamRegistryService(props(1, 5));
        ActiveStream a = activeStream("client");
        assertThat(reg.register(a)).isTrue();
        assertThat(reg.register(activeStream("client"))).isFalse();
        reg.unregister(a.id);
        assertThat(reg.register(activeStream("client"))).isTrue();
    }

    @Test
    void snapshot_iterates_active_streams() {
        StreamRegistryService reg = new StreamRegistryService(props(10, 10));
        ActiveStream a = activeStream("alice");
        ActiveStream b = activeStream("bob");
        reg.register(a);
        reg.register(b);

        long count = 0;
        for (ActiveStream s : reg.snapshot()) {
            count++;
            assertThat(s.fingerprintHex).isIn("alice", "bob");
        }
        assertThat(count).isEqualTo(2);
    }
}
