package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aisandbox.server.stream.service.StreamRegistryService.StreamId;
import com.aisandbox.server.stream.service.TmuxBridgeService.Bridge;
import org.junit.jupiter.api.Test;

/**
 * UC-74 (AC4) — the new {@link StreamBridgeRegistry} is the deterministic
 * handle {@code GracefulShutdownHandler.stop()} uses to destroy any live PTY
 * bridge (pty4j {@code PtyProcess} + per-client tmux session) still open at
 * shutdown. These tests pin its register/unregister/snapshot/count contract and
 * the UC-21 mid-stream re-bridge replacement semantics (a fresh bridge under the
 * same {@link StreamId} replaces the now-closed old one, so the backstop always
 * tracks the live bridge).
 */
class StreamBridgeRegistryTest {

    @Test
    void register_then_snapshot_exposes_the_live_bridge() {
        StreamBridgeRegistry reg = new StreamBridgeRegistry();
        Bridge bridge = mock(Bridge.class);

        reg.register(new StreamId("s1"), bridge);

        assertThat(reg.count()).isEqualTo(1);
        assertThat(reg.snapshot()).containsExactly(bridge);
    }

    @Test
    void unregister_drops_the_bridge_from_the_teardown_set() {
        StreamBridgeRegistry reg = new StreamBridgeRegistry();
        StreamId id = new StreamId("s1");
        reg.register(id, mock(Bridge.class));

        reg.unregister(id);

        assertThat(reg.count()).isZero();
        assertThat(reg.snapshot()).isEmpty();
    }

    @Test
    void re_register_under_same_id_replaces_the_old_bridge() {
        // UC-21 re-bridge swap — the registry must track the LIVE bridge so the
        // shutdown backstop never closes a stale handle while leaving the new one.
        StreamBridgeRegistry reg = new StreamBridgeRegistry();
        StreamId id = new StreamId("s1");
        Bridge oldBridge = mock(Bridge.class);
        Bridge newBridge = mock(Bridge.class);

        reg.register(id, oldBridge);
        reg.register(id, newBridge);

        assertThat(reg.count()).isEqualTo(1);
        assertThat(reg.snapshot()).containsExactly(newBridge).doesNotContain(oldBridge);
    }

    @Test
    void null_arguments_are_ignored_and_never_throw() {
        StreamBridgeRegistry reg = new StreamBridgeRegistry();

        reg.register(null, mock(Bridge.class));
        reg.register(new StreamId("s1"), null);
        reg.unregister(null);

        assertThat(reg.count()).isZero();
        assertThat(reg.snapshot()).isEmpty();
    }

    @Test
    void snapshot_is_an_immutable_copy_decoupled_from_later_mutation() {
        StreamBridgeRegistry reg = new StreamBridgeRegistry();
        reg.register(new StreamId("s1"), mock(Bridge.class));

        var snap = reg.snapshot();
        reg.register(new StreamId("s2"), mock(Bridge.class));

        // The snapshot taken before the second register is unaffected (copy semantics),
        // so iterating it during shutdown can't be disturbed by concurrent teardown.
        assertThat(snap).hasSize(1);
        assertThat(reg.count()).isEqualTo(2);
    }
}
