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
}
