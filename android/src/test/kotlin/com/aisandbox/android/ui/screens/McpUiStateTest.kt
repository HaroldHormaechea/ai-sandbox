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

    // ── UC-82 — buildMcpAddRequest pure builder (the Add-dialog → wire seam) ───

    @Test
    fun build_stdio_keepsCommandArgsEnv_andOmitsUrlHeaders() {
        val req = buildMcpAddRequest(
            name = "call-graph",
            transport = "stdio",
            command = "npx",
            argsText = "-y  some-package   extra",
            url = "ignored.example",
            envText = "TOKEN=abc\nKEY = v2 \n\nMALFORMED_NO_EQ",
            headersText = "ignored: header",
        )

        assertThat(req.transport).isEqualTo("stdio")
        assertThat(req.command).isEqualTo("npx")
        // Args split on runs of whitespace, blanks dropped.
        assertThat(req.args).containsExactly("-y", "some-package", "extra")
        // Env: K=V lines parsed, key/value trimmed, blank / '='-less lines dropped.
        assertThat(req.env).containsEntry("TOKEN", "abc").containsEntry("KEY", "v2")
        assertThat(req.env).doesNotContainKey("MALFORMED_NO_EQ")
        // stdio omits the http/sse fields entirely.
        assertThat(req.url).isNull()
        assertThat(req.headers).isNull()
    }

    @Test
    fun build_stdio_foldsBlankOptionalsToNull() {
        val req = buildMcpAddRequest(
            name = " local ",
            transport = "stdio",
            command = " mcp-bin ",
            argsText = "   ",
            url = "",
            envText = "\n  \n",
            headersText = "",
        )

        assertThat(req.name).isEqualTo("local") // trimmed
        assertThat(req.command).isEqualTo("mcp-bin") // trimmed
        assertThat(req.args).isNull() // all-blank → null (omitted on the wire)
        assertThat(req.env).isNull()
    }

    @Test
    fun build_http_keepsUrlAndHeaders_andOmitsStdioFields() {
        val req = buildMcpAddRequest(
            name = "atlassian",
            transport = "SSE", // mixed case …
            command = "ignored",
            argsText = "ignored args",
            url = " https://mcp.atlassian.com/v1/sse ",
            envText = "IGNORED=1",
            headersText = "Authorization: Bearer t\n\nX-Trace: 1\n   ",
        )

        assertThat(req.transport).isEqualTo("sse") // … normalised to lower-case
        assertThat(req.url).isEqualTo("https://mcp.atlassian.com/v1/sse") // trimmed
        assertThat(req.headers).containsExactly("Authorization: Bearer t", "X-Trace: 1")
        // http/sse omits the stdio fields entirely.
        assertThat(req.command).isNull()
        assertThat(req.args).isNull()
        assertThat(req.env).isNull()
    }

    @Test
    fun build_doesNotShellEscapeOrAlterHostileValues_soTheyTravelAsInertData() {
        // AC4 (client side): the builder must NOT try to quote/escape — the value travels
        // verbatim and lands as a discrete argv element server-side, where it is inert.
        val hostile = "x; touch /tmp/uc82_pwned && \$(ls /)"
        val req = buildMcpAddRequest(
            name = "evil",
            transport = "stdio",
            command = hostile,
            argsText = "`whoami`",
            url = "",
            envText = "E=v;rm -rf /",
            headersText = "",
        )

        assertThat(req.command).isEqualTo(hostile) // verbatim, not escaped/quoted
        assertThat(req.args).containsExactly("`whoami`")
        assertThat(req.env).containsEntry("E", "v;rm -rf /")
    }

    @Test
    fun build_envValueMayContainEqualsSigns() {
        // Only the FIRST '=' splits key from value, so a base64/padded value survives.
        val req = buildMcpAddRequest(
            name = "n",
            transport = "stdio",
            command = "c",
            argsText = "",
            url = "",
            envText = "B64=YWJjZA==",
            headersText = "",
        )

        assertThat(req.env).containsEntry("B64", "YWJjZA==")
    }
}
