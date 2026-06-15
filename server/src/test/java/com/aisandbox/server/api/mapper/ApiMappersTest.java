package com.aisandbox.server.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.sessions.dto.SessionRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-48 — the controller-boundary mapper carries the new {@code working} flag
 * from the internal {@link SessionRecord} onto the REST {@link ApiDtos.SessionSummary}
 * (a primitive {@code boolean}, so it is always present in the JSON — the
 * class-level {@code @JsonInclude(NON_NULL)} only omits null fields). This is the
 * server seam that surfaces AC1/AC2/AC7 over {@code GET /v1/sessions}.
 */
class ApiMappersTest {

    private static final Instant STARTED = Instant.parse("2026-06-09T10:00:00Z");

    @Test
    void toSummary_carries_working_true_and_the_other_fields() {
        SessionRecord r =
                new SessionRecord(3, "alpha", "vim", "running", 42L, 1, STARTED, "Refactor the SessionRow", true);

        ApiDtos.SessionSummary s = ApiMappers.toSummary(r);

        assertThat(s.n()).isEqualTo(3);
        assertThat(s.state()).isEqualTo("running");
        assertThat(s.conversationName()).isEqualTo("Refactor the SessionRow");
        assertThat(s.working())
                .as("AC1 — a working session maps working=true onto the REST DTO")
                .isTrue();
    }

    @Test
    void toSummary_carries_working_false() {
        SessionRecord r = new SessionRecord(4, "beta", "(idle)", "running", 0L, 0, STARTED, null, false);

        ApiDtos.SessionSummary s = ApiMappers.toSummary(r);

        assertThat(s.working()).as("AC2 — an idle session maps working=false").isFalse();
    }

    @Test
    void back_compat_record_without_working_defaults_to_false() {
        // The pre-UC-48 8-arg SessionRecord ctor delegates working=false, so a row
        // built through the old shape never shows the spinner.
        SessionRecord r = new SessionRecord(5, "gamma", "(idle)", "running", 0L, 0, STARTED, "name");

        assertThat(ApiMappers.toSummary(r).working()).isFalse();
    }

    @Test
    void toSummaries_maps_each_record_including_working() {
        List<ApiDtos.SessionSummary> out = ApiMappers.toSummaries(List.of(
                new SessionRecord(1, "a", "t", "running", 0L, 0, STARTED, null, true),
                new SessionRecord(2, "b", "t", "running", 0L, 0, STARTED, null, false)));

        assertThat(out).extracting(ApiDtos.SessionSummary::working).containsExactly(true, false);
    }

    // ── UC-49 — pendingQuestion carried onto the REST DTO ─────────────────────

    @Test
    void toSummary_carries_pendingQuestion_true() {
        // A pending session is mutually exclusive with working server-side; the
        // 10-arg record carries pendingQuestion=true, working=false.
        SessionRecord r =
                new SessionRecord(3, "alpha", "vim", "running", 42L, 1, STARTED, "Pick a database", false, true);

        ApiDtos.SessionSummary s = ApiMappers.toSummary(r);

        assertThat(s.pendingQuestion())
                .as("AC1 — a pending session maps pendingQuestion=true onto the REST DTO")
                .isTrue();
        assertThat(s.working())
                .as("AC5 — pending and working are mutually exclusive")
                .isFalse();
    }

    @Test
    void toSummary_carries_pendingQuestion_false() {
        SessionRecord r = new SessionRecord(4, "beta", "(idle)", "running", 0L, 0, STARTED, null, false, false);

        assertThat(ApiMappers.toSummary(r).pendingQuestion()).isFalse();
    }

    @Test
    void back_compat_record_without_pendingQuestion_defaults_to_false() {
        // The pre-UC-49 9-arg SessionRecord ctor delegates pendingQuestion=false, so
        // a row built through the old shape never shows the "?" badge.
        SessionRecord r = new SessionRecord(5, "gamma", "(idle)", "running", 0L, 0, STARTED, "name", false);

        assertThat(ApiMappers.toSummary(r).pendingQuestion()).isFalse();
    }

    @Test
    void toSummaries_maps_each_record_including_pendingQuestion() {
        List<ApiDtos.SessionSummary> out = ApiMappers.toSummaries(List.of(
                new SessionRecord(1, "a", "t", "running", 0L, 0, STARTED, null, false, true),
                new SessionRecord(2, "b", "t", "running", 0L, 0, STARTED, null, false, false)));

        assertThat(out).extracting(ApiDtos.SessionSummary::pendingQuestion).containsExactly(true, false);
    }

    // ── UC-69 — pendingQuestionText carried onto the REST DTO (AC3) ──────────

    @Test
    void toSummary_carries_the_pending_question_text() {
        // The 12-arg record carries the first-question text; the mapper must surface
        // it so the Android client can build the local notification body (AC3).
        SessionRecord r = new SessionRecord(
                3,
                "alpha",
                "vim",
                "running",
                42L,
                1,
                STARTED,
                "Pick a database",
                false,
                true,
                "claude",
                "Which database should we use?");

        ApiDtos.SessionSummary s = ApiMappers.toSummary(r);

        assertThat(s.pendingQuestionText())
                .as("AC3 — the first-question text reaches the REST DTO as the notification body")
                .isEqualTo("Which database should we use?");
        assertThat(s.pendingQuestion()).isTrue();
    }

    @Test
    void back_compat_record_without_pending_question_text_defaults_to_null() {
        // Every pre-UC-69 record shape (≤11 args) delegates pendingQuestionText=null,
        // so a row built through an older shape simply carries no notification body
        // (the server omits the field from JSON via @JsonInclude(NON_NULL)).
        SessionRecord r = new SessionRecord(7, "d", "t", "running", 0L, 0, STARTED, null, false, false, "claude");

        assertThat(ApiMappers.toSummary(r).pendingQuestionText()).isNull();
    }

    @Test
    void toSummaries_maps_each_record_including_pending_question_text() {
        List<ApiDtos.SessionSummary> out = ApiMappers.toSummaries(List.of(
                new SessionRecord(
                        1, "a", "t", "running", 0L, 0, STARTED, null, false, true, "claude", "First question?"),
                new SessionRecord(2, "b", "t", "running", 0L, 0, STARTED, null, false, false, "claude", null)));

        assertThat(out)
                .extracting(ApiDtos.SessionSummary::pendingQuestionText)
                .containsExactly("First question?", null);
    }

    // ── UC-62 — type carried onto the REST DTO (AC6) ─────────────────────────

    @Test
    void toSummary_carries_server_ssh_type() {
        // The 11-arg record carries the discriminator; the mapper must surface it
        // so the Android client can pin/badge/route the row (AC6).
        SessionRecord r =
                new SessionRecord(0, "", "(idle)", "running", 0L, 0, STARTED, null, false, false, "server-ssh");

        assertThat(ApiMappers.toSummary(r).type())
                .as("AC6 — the server-ssh discriminator reaches the REST DTO")
                .isEqualTo("server-ssh");
    }

    @Test
    void back_compat_record_without_type_defaults_to_claude() {
        // Every pre-UC-62 record shape (≤10 args) delegates type=claude, so an
        // ordinary session is always reported as claude.
        SessionRecord r = new SessionRecord(7, "d", "t", "running", 0L, 0, STARTED, null, false, false);

        assertThat(ApiMappers.toSummary(r).type()).isEqualTo("claude");
    }

    @Test
    void toSummaries_maps_each_record_including_type() {
        List<ApiDtos.SessionSummary> out = ApiMappers.toSummaries(List.of(
                new SessionRecord(0, "", "t", "running", 0L, 0, STARTED, null, false, false, "server-ssh"),
                new SessionRecord(1, "a", "t", "running", 0L, 0, STARTED, null, false, false, "claude")));

        assertThat(out).extracting(ApiDtos.SessionSummary::type).containsExactly("server-ssh", "claude");
    }

    // ── UC-66 — model descriptor → API ModelSummary (AC2/AC3) ────────────────

    @Test
    void toModelSummary_carries_id_and_label_onto_the_api_dto() {
        // The controller-boundary mapper translates the internal ModelDescriptor
        // into the REST ModelSummary the Android picker decodes (id + human label).
        com.aisandbox.server.models.dto.ModelDescriptor d =
                new com.aisandbox.server.models.dto.ModelDescriptor("claude-opus-4-8", "Opus 4.8");

        ApiDtos.ModelSummary s = ApiMappers.toModelSummary(d);

        assertThat(s.id()).isEqualTo("claude-opus-4-8");
        assertThat(s.label()).isEqualTo("Opus 4.8");
    }

    // ── UC-67 — MCP internal DTOs → API DTOs, state lowercased (AC3/AC6) ──────

    @Test
    void toMcpServerSummary_carries_fields_and_lowercases_the_state() {
        // The controller-boundary mapper translates the internal McpServerStatus
        // (with the McpState enum) into the REST McpServerSummary the Android screen
        // decodes — the state enum becomes its lowercase wire value (AC3).
        com.aisandbox.server.mcp.dto.McpServerStatus status = new com.aisandbox.server.mcp.dto.McpServerStatus(
                "atlassian",
                "sse",
                com.aisandbox.server.mcp.dto.McpState.NEEDS_AUTH,
                "https://mcp.atlassian.com/v1/sse");

        ApiDtos.McpServerSummary s = ApiMappers.toMcpServerSummary(status);

        assertThat(s.name()).isEqualTo("atlassian");
        assertThat(s.transport()).isEqualTo("sse");
        assertThat(s.state()).isEqualTo("needs_auth");
        assertThat(s.detail()).isEqualTo("https://mcp.atlassian.com/v1/sse");
    }

    @Test
    void toMcpActionResult_carries_name_state_and_message_with_lowercased_state() {
        com.aisandbox.server.mcp.dto.McpActionOutcome outcome = new com.aisandbox.server.mcp.dto.McpActionOutcome(
                "call-graph", com.aisandbox.server.mcp.dto.McpState.CONNECTED, "Re-checked the server's connection.");

        ApiDtos.McpActionResult r = ApiMappers.toMcpActionResult(outcome);

        assertThat(r.name()).isEqualTo("call-graph");
        assertThat(r.state()).isEqualTo("connected");
        assertThat(r.message()).isEqualTo("Re-checked the server's connection.");
    }

    // ── UC-82 — API add request → internal McpAddSpec (rule 5 boundary) ───────

    @Test
    void toMcpAddSpec_carries_all_fields_and_lowercases_the_transport() {
        ApiDtos.McpAddRequest req = new ApiDtos.McpAddRequest(
                "atlassian",
                "SSE", // mixed-case transport from the wire …
                null,
                null,
                "https://mcp.atlassian.com/v1/sse",
                null,
                java.util.List.of("Authorization: Bearer t"));

        com.aisandbox.server.mcp.dto.McpAddSpec spec = ApiMappers.toMcpAddSpec(req);

        assertThat(spec.name()).isEqualTo("atlassian");
        assertThat(spec.transport()).isEqualTo("sse"); // … normalised to lower-case here.
        assertThat(spec.url()).isEqualTo("https://mcp.atlassian.com/v1/sse");
        assertThat(spec.headers()).containsExactly("Authorization: Bearer t");
    }

    @Test
    void toMcpAddSpec_carries_stdio_command_args_and_env() {
        ApiDtos.McpAddRequest req = new ApiDtos.McpAddRequest(
                "local",
                "stdio",
                "npx",
                java.util.List.of("-y", "pkg"),
                null,
                java.util.Map.of("TOKEN", "secret"),
                null);

        com.aisandbox.server.mcp.dto.McpAddSpec spec = ApiMappers.toMcpAddSpec(req);

        assertThat(spec.transport()).isEqualTo("stdio");
        assertThat(spec.command()).isEqualTo("npx");
        assertThat(spec.args()).containsExactly("-y", "pkg");
        assertThat(spec.env()).containsEntry("TOKEN", "secret");
    }

    @Test
    void toMcpAddSpec_is_null_safe_on_a_missing_body() {
        // A missing POST body maps to null; the facade then rejects it with a 400.
        assertThat(ApiMappers.toMcpAddSpec(null)).isNull();
    }

    @Test
    void toMcpAddSpec_tolerates_a_null_transport() {
        ApiDtos.McpAddRequest req = new ApiDtos.McpAddRequest("x", null, "npx", null, null, null, null);

        com.aisandbox.server.mcp.dto.McpAddSpec spec = ApiMappers.toMcpAddSpec(req);

        assertThat(spec.transport()).isNull();
        assertThat(spec.name()).isEqualTo("x");
    }
}
