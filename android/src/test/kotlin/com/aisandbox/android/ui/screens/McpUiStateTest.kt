package com.aisandbox.android.ui.screens

import com.aisandbox.android.net.McpServerInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-67 — contract of [McpUiState], the sealed state the [McpScreen] renders from.
 * Pure-JVM (no Android), pinning that: [McpUiState.Loaded] carries the server list
 * the screen lists (AC3), [McpUiState.Empty] is the distinct "no MCP servers"
 * terminal state (AC7), and [McpUiState.Error] carries a display message (so a
 * not-enrolled / transport / HTTP failure renders text instead of spinning).
 *
 * <p>NOTE (testability gap, reported to the developer): the screen's stateful
 * holder [McpViewModel] reads `AppContainer` straight off the `Application` and
 * keeps its `MutableStateFlow` private, and the row composable `McpServerRow` /
 * `StateChip` are `private` (unlike UC-66's `internal ModelSelectionDialog`
 * seam). That blocks deterministic unit coverage of the Loading→Loaded/Empty/Error
 * transitions, the operate→re-fetch, busyServer per-row disabling, and the chip /
 * button-enablement rendering. This test covers only the state TYPE; the live AC8
 * from-UI run is the authoritative gate for the rendered behaviour until a
 * stateless `internal` seam + an injectable ViewModel land.
 */
class McpUiStateTest {

    @Test
    fun loaded_carries_the_listed_servers_in_order() {
        val servers = listOf(
            McpServerInfo(name = "call-graph", transport = "stdio", state = "connected", detail = "java -jar d.jar"),
            McpServerInfo(name = "atlassian", transport = "sse", state = "needs_auth", detail = "https://x/sse"),
        )

        val state = McpUiState.Loaded(servers)

        assertThat(state.servers).containsExactlyElementsOf(servers)
        assertThat(state.servers.map { it.state }).containsExactly("connected", "needs_auth")
    }

    @Test
    fun empty_and_loading_are_distinct_singleton_states() {
        // AC7 — Empty is its own state (the "no MCP servers" screen), separate from
        // Loading and from an (empty) Loaded list.
        assertThat(McpUiState.Empty).isSameAs(McpUiState.Empty)
        assertThat(McpUiState.Loading).isSameAs(McpUiState.Loading)
        assertThat(McpUiState.Empty).isNotEqualTo(McpUiState.Loading)
        assertThat(McpUiState.Empty as McpUiState).isNotInstanceOf(McpUiState.Loaded::class.java)
    }

    @Test
    fun error_carries_its_display_message() {
        val state = McpUiState.Error("Not enrolled")
        assertThat(state.message).isEqualTo("Not enrolled")
        // Value-equality so the screen can de-dupe identical error emissions.
        assertThat(state).isEqualTo(McpUiState.Error("Not enrolled"))
        assertThat(state).isNotEqualTo(McpUiState.Error("boom"))
    }
}
