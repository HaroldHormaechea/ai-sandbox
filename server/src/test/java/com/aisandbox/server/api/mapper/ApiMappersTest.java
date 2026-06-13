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
}
