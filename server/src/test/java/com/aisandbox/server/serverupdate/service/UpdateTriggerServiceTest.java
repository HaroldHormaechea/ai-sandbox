package com.aisandbox.server.serverupdate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-84 — unit coverage for {@link UpdateTriggerService}, the server's ENTIRE
 * privileged capability: write one fixed-name, payload-free marker into one
 * fixed directory (AC8/AC11/AC12).
 *
 * <h2>AC → method map</h2>
 *
 * <ul>
 *   <li>AC8/AC11 — {@link #request_writes_a_single_fixed_name_marker()}: a
 *       fixed filename, never derived from any input.</li>
 *   <li>AC11/AC12 — {@link #marker_content_is_fixed_and_carries_no_input()}:
 *       the bytes are a constant; the service consumes no caller data.</li>
 *   <li>AC8 — {@link #request_creates_the_trigger_dir_when_missing()},
 *       {@link #request_is_idempotent_overwrite()}.</li>
 *   <li>AC14 — {@link #io_failure_maps_to_trigger_failed()}: a non-writable
 *       trigger dir surfaces {@link ServerUpdateException.TriggerFailed}.</li>
 *   <li>Trigger-path pin — {@link #trigger_dir_constant_is_the_canonical_literal()}.</li>
 * </ul>
 */
class UpdateTriggerServiceTest {

    @Test
    void request_writes_a_single_fixed_name_marker(@TempDir Path dir) throws IOException {
        Path triggerDir = dir.resolve("update-trigger");
        new UpdateTriggerService(triggerDir.toString()).requestUpdate();

        Path marker = triggerDir.resolve(UpdateTriggerService.MARKER_FILENAME);
        assertThat(marker).exists();
        // Exactly ONE entry, and its name is the fixed literal — never request-derived.
        try (var stream = Files.list(triggerDir)) {
            List<Path> entries = stream.toList();
            assertThat(entries).singleElement().isEqualTo(marker);
        }
        assertThat(UpdateTriggerService.MARKER_FILENAME).isEqualTo("update.requested");
    }

    @Test
    void marker_content_is_fixed_and_carries_no_input(@TempDir Path dir) throws IOException {
        Path triggerDir = dir.resolve("update-trigger");
        new UpdateTriggerService(triggerDir.toString()).requestUpdate();

        String content =
                Files.readString(triggerDir.resolve(UpdateTriggerService.MARKER_FILENAME), StandardCharsets.UTF_8);
        // A constant payload — the updater never reads it; it only matters that the dir became non-empty.
        assertThat(content).isEqualTo("update-requested\n");
    }

    @Test
    void request_creates_the_trigger_dir_when_missing(@TempDir Path dir) {
        Path triggerDir = dir.resolve("nested").resolve("update-trigger");
        assertThat(triggerDir).doesNotExist();

        new UpdateTriggerService(triggerDir.toString()).requestUpdate();

        assertThat(triggerDir).isDirectory();
        assertThat(triggerDir.resolve(UpdateTriggerService.MARKER_FILENAME)).exists();
    }

    @Test
    void request_is_idempotent_overwrite(@TempDir Path dir) throws IOException {
        Path triggerDir = dir.resolve("update-trigger");
        UpdateTriggerService svc = new UpdateTriggerService(triggerDir.toString());

        svc.requestUpdate();
        svc.requestUpdate();

        // Re-emitting truncates/overwrites the same single marker — never accumulates files.
        try (var stream = Files.list(triggerDir)) {
            assertThat(stream.toList()).hasSize(1);
        }
        assertThat(Files.readString(triggerDir.resolve(UpdateTriggerService.MARKER_FILENAME)))
                .isEqualTo("update-requested\n");
    }

    @Test
    void io_failure_maps_to_trigger_failed(@TempDir Path dir) throws IOException {
        // Make the trigger path a regular FILE so createDirectories() fails -> TriggerFailed.
        Path clash = dir.resolve("update-trigger");
        Files.writeString(clash, "i am a file, not a dir");
        // POSIX-only guard: on a FS where this somehow succeeds, skip rather than false-fail.
        assumeTrue(Files.isRegularFile(clash));

        UpdateTriggerService svc =
                new UpdateTriggerService(clash.resolve("child").toString());

        assertThatThrownBy(svc::requestUpdate).isInstanceOf(ServerUpdateException.TriggerFailed.class);
        // The server keeps running on its current version (AC14) — the exception is typed, not fatal.
    }

    @Test
    void trigger_dir_constant_is_the_canonical_literal() {
        // The single source of truth the postinst + .path unit + CI cross-check all pin.
        assertThat(UpdateTriggerService.TRIGGER_DIR).isEqualTo("/var/lib/ai-sandbox-server/update-trigger");
    }
}
