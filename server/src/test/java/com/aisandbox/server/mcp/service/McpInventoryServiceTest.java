package com.aisandbox.server.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.mcp.dto.McpServerStatus;
import com.aisandbox.server.mcp.dto.McpState;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * UC-67 — {@link McpInventoryService} inventories a session's MCP servers by
 * running {@code claude mcp list} inside the session's {@code claude-sandbox}
 * container. These tests pin two contracts:
 *
 * <ul>
 *   <li><b>argv</b> — the EXACT argv-array is
 *       {@code docker compose -p ai-sandbox-N exec -T claude-sandbox claude mcp list}
 *       (no shell, no string interpolation — a hostile MCP name can never smuggle
 *       an extra command, AC4).</li>
 *   <li><b>defensive parsing + degradation</b> — the upstream {@code claude mcp}
 *       surface is version-volatile, so the parser maps connected / needs-auth /
 *       failed / unrecognised states (AC3), drops banners, and the service degrades
 *       to an EMPTY inventory (AC7) when the container is not running ({@link IOException})
 *       or stdout is empty — never throwing.</li>
 * </ul>
 *
 * <p>Note on the non-zero-exit case: the implementation deliberately STILL parses
 * stdout on a non-zero exit, because {@code claude mcp list} returns non-zero when
 * any server's health check fails yet still prints the server lines — so a FAILED
 * server must surface rather than be hidden behind a blanket "exit≠0 ⇒ empty"
 * (see {@link #nonZeroExit_withServerLines_stillSurfacesThem}). Only an
 * {@link IOException} (container absent) or empty stdout yields an empty list.
 */
class McpInventoryServiceTest {

    private static ProcessExecutor.Result ok(String stdout) {
        return new ProcessExecutor.Result(0, stdout, "");
    }

    // ──────────────────────── argv contract (AC4) ────────────────────────────

    @Test
    void list_runs_the_exact_docker_compose_claude_mcp_list_argv_for_the_session() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenReturn(ok(""));

        new McpInventoryService(exec).list(7);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(exec).run(argv.capture(), eq(null), timeout.capture());

        // EXACT argv — argv-array only, no shell, no interpolation (AC4 / security).
        assertThat(argv.getValue())
                .containsExactly(
                        "docker",
                        "compose",
                        "-p",
                        "ai-sandbox-7",
                        "exec",
                        "-T",
                        "claude-sandbox",
                        "claude",
                        "mcp",
                        "list");
        // A bounded timeout is always supplied (never an unbounded wait).
        assertThat(timeout.getValue()).isNotNull().isPositive();
    }

    @Test
    void list_scopes_the_compose_project_to_the_requested_session_number() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenReturn(ok(""));

        new McpInventoryService(exec).list(42);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(exec).run(argv.capture(), eq(null), any());
        assertThat(argv.getValue()).contains("ai-sandbox-42");
    }

    // ──────────────────────── degradation (AC7) ──────────────────────────────

    @Test
    void list_degrades_to_empty_when_the_container_is_not_running() throws Exception {
        // A non-running / SERVER_SSH session has no claude-sandbox container, so
        // `docker compose exec` raises IOException — the service swallows it (AC7).
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenThrow(new IOException("no such container"));

        assertThat(new McpInventoryService(exec).list(3)).isEmpty();
    }

    @Test
    void list_degrades_to_empty_on_blank_stdout() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenReturn(ok("   \n  \n"));

        assertThat(new McpInventoryService(exec).list(1)).isEmpty();
    }

    @Test
    void nonZeroExit_withServerLines_stillSurfacesThem() throws Exception {
        // `claude mcp list` exits non-zero when ANY server's health check fails,
        // but still prints every server line — so a FAILED server must surface,
        // not be hidden. The service parses stdout regardless of exit code.
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(
                        1, "broken: http://localhost:9999 (HTTP) - ✗ Failed to connect\n", "health check failed"));

        List<McpServerStatus> out = new McpInventoryService(exec).list(5);

        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("broken");
            assertThat(s.state()).isEqualTo(McpState.FAILED);
        });
    }

    @Test
    void nonZeroExit_withEmptyStdout_yieldsEmpty() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenReturn(new ProcessExecutor.Result(1, "", "boom"));

        assertThat(new McpInventoryService(exec).list(9)).isEmpty();
    }

    @Test
    void refresh_is_a_fresh_list_invocation() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any()))
                .thenReturn(ok("atlassian: https://mcp.atlassian.com/v1/sse (SSE) - Needs authentication\n"));

        List<McpServerStatus> out = new McpInventoryService(exec).refresh(11);

        assertThat(out).singleElement().satisfies(s -> assertThat(s.state()).isEqualTo(McpState.NEEDS_AUTH));
        // A refresh re-runs the same command (re-runs the health checks).
        verify(exec).run(any(), eq(null), any());
    }

    // ──────────────────────── parse fixtures (AC3) ───────────────────────────

    @Test
    void parseList_maps_connected_needsAuth_and_failed_states() {
        // A realistic multi-server `claude mcp list` snapshot: the two real MCP
        // servers the use case names (call-graph stdio = connected, Atlassian SSE
        // = needs-auth) plus a failed HTTP server.
        String stdout = String.join(
                "\n",
                "Checking MCP server health…",
                "java-class-call-scanning: java -jar /cache/java-class-call-scanning.jar daemon (STDIO) - ✓ Connected",
                "atlassian: https://mcp.atlassian.com/v1/sse (SSE) - Needs authentication",
                "broken: http://localhost:9999/mcp (HTTP) - ✗ Failed to connect",
                "");

        List<McpServerStatus> out = McpInventoryService.parseList(stdout);

        assertThat(out)
                .extracting(McpServerStatus::name)
                .containsExactly("java-class-call-scanning", "atlassian", "broken");
        assertThat(out)
                .extracting(McpServerStatus::state)
                .containsExactly(McpState.CONNECTED, McpState.NEEDS_AUTH, McpState.FAILED);
        assertThat(out).extracting(McpServerStatus::transport).containsExactly("stdio", "sse", "http");
        // The detail keeps the connection string (display-only), including hyphens.
        assertThat(out.get(0).detail()).contains("java -jar /cache/java-class-call-scanning.jar daemon");
    }

    @Test
    void parseList_handles_the_ACTUAL_upstream_claude_mcp_list_output_verbatim() {
        // QA live-captured this EXACT output from the real `claude mcp list` binary
        // (same version the session container runs), with the call-graph MCP from the
        // project's .mcp.json. The parser is "the single point where reality can
        // diverge", so this fixture is the genuine upstream shape, NOT a hand-built
        // approximation: a banner with a Unicode ellipsis (…), a blank line, and one
        // stdio server whose health text is "⏸ Pending approval (run `claude` to
        // approve)" (U+23F8 PAUSE glyph) — a status none of the other fixtures cover.
        // The command itself contains many "--classpath/--src" double-dashes (no
        // " - "), so the LAST " - " split must isolate only the trailing health text.
        String realStdout = "Checking MCP server health…\n"
                + "\n"
                + "java-class-call-scanning: java -jar /home/claude/.cache/project-builder/"
                + "java-class-call-scanning/v0.2.0/java-class-call-scanning.jar serve "
                + "--classpath /workspace/ai-sandbox/server/build/classes/java/main "
                + "--classpath /workspace/ai-sandbox/server/build/classes/java/test "
                + "--src /workspace/ai-sandbox/server/src/main/java "
                + "--src /workspace/ai-sandbox/server/src/test/java "
                + "- ⏸ Pending approval (run `claude` to approve)\n";

        List<McpServerStatus> out = McpInventoryService.parseList(realStdout);

        // Banner + blank line dropped; exactly one server surfaced.
        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("java-class-call-scanning");
            assertThat(s.transport()).isEqualTo("stdio");
            // "Pending approval" → PENDING (a check still in flight), never UNKNOWN.
            assertThat(s.state()).isEqualTo(McpState.PENDING);
            // The full command (with every --flag) stays in the display-only detail,
            // and the trailing health text is split off the LAST " - ".
            assertThat(s.detail())
                    .startsWith("java -jar /home/claude/.cache/project-builder/")
                    .endsWith("--src /workspace/ai-sandbox/server/src/test/java")
                    .doesNotContain("Pending approval");
        });
    }

    @Test
    void parseList_handles_REAL_atlassian_needsAuth_and_failed_health_lines_from_a_live_session() {
        // QA live-captured this EXACT output by running the server's own command —
        // `docker compose -p ai-sandbox-7 exec -T claude-sandbox claude mcp list` —
        // against a real session container holding a user-scoped Atlassian SSE MCP
        // and a deliberately-broken stdio MCP. The genuine health glyphs upstream
        // emits differ from this suite's other fixtures: needs-auth is "! Needs
        // authentication" (a bare "!", not "⚠") and failed is "✘ Failed to
        // connect" (U+2718 HEAVY BALLOT X, not the "✗" the multi-state fixture
        // uses). The keyword-based parser must still classify both correctly — this
        // pins that the defensive design survives the real glyph variance.
        String realStdout = "Checking MCP server health…\n"
                + "\n"
                + "atlassian: https://mcp.atlassian.com/v1/sse (SSE) - ! Needs authentication\n"
                + "broken-tool: /usr/local/bin/definitely-not-here --serve - ✘ Failed to connect\n";

        List<McpServerStatus> out = McpInventoryService.parseList(realStdout);

        assertThat(out).extracting(McpServerStatus::name).containsExactly("atlassian", "broken-tool");
        // Real Atlassian → NEEDS_AUTH (drives the Login enable-condition); broken stdio → FAILED.
        assertThat(out).extracting(McpServerStatus::state).containsExactly(McpState.NEEDS_AUTH, McpState.FAILED);
        assertThat(out).extracting(McpServerStatus::transport).containsExactly("sse", "stdio");
        // The "--serve" double-dash in the broken command stays in the detail; only
        // the trailing " - <glyph> Failed to connect" is split off as the status.
        assertThat(out.get(1).detail()).isEqualTo("/usr/local/bin/definitely-not-here --serve");
    }

    @Test
    void parseList_returns_empty_for_blank_or_null() {
        assertThat(McpInventoryService.parseList(null)).isEmpty();
        assertThat(McpInventoryService.parseList("")).isEmpty();
        assertThat(McpInventoryService.parseList("   \n\t\n")).isEmpty();
    }

    @Test
    void parseList_skips_banner_lines_without_a_name_colon() {
        // The health banner has no "name:" prefix → skipped, not surfaced as a server.
        List<McpServerStatus> out = McpInventoryService.parseList("Checking MCP server health…\n");
        assertThat(out).isEmpty();
    }

    @Test
    void parseList_maps_an_unrecognised_status_to_UNKNOWN_without_dropping_the_server() {
        // A configured server whose health text the parser doesn't recognise is
        // still surfaced (UNKNOWN), never silently dropped. ("blarg" matches none
        // of the recognised status keywords.)
        List<McpServerStatus> out = McpInventoryService.parseList("weird: some-cmd (STDIO) - blarg\n");

        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("weird");
            assertThat(s.state()).isEqualTo(McpState.UNKNOWN);
        });
    }

    @Test
    void parseList_surfaces_a_name_only_line_with_no_status_tail_as_UNKNOWN() {
        // No trailing " - status" → the server is configured but unhealthy/unknown,
        // surfaced (not dropped) so the screen can show it.
        List<McpServerStatus> out = McpInventoryService.parseList("lonely: /usr/local/bin/foo\n");

        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("lonely");
            assertThat(s.state()).isEqualTo(McpState.UNKNOWN);
            assertThat(s.detail()).isEqualTo("/usr/local/bin/foo");
        });
    }

    @Test
    void parseList_keeps_the_last_separator_so_a_hyphenated_command_stays_in_detail() {
        // The status is split off the LAST " - ", so a command/URL containing " - "
        // is preserved in the detail and only the trailing health text is the status.
        List<McpServerStatus> out = McpInventoryService.parseList("svc: my-cmd --flag a - b (STDIO) - ✓ Connected\n");

        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.state()).isEqualTo(McpState.CONNECTED);
            assertThat(s.detail()).contains("a - b");
        });
    }
}
