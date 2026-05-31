package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-27 — read/write coverage for {@link DevToolsConfig}, the thin Java reader
 * for the {@code .ai-sandbox-devtools} ledger.
 *
 * <p>UC-27 moved the capability catalog entirely into the SHELL (auto-discovered
 * manifests under {@code devtools.d/}); the Java side holds <b>no</b> catalog,
 * {@code apply_at} mapping, or id validation (AC#4 — shell is the single source
 * of truth). What remains is:
 *
 * <ul>
 *   <li>{@link DevToolsConfig#readEnabled(Path)} — a LENIENT reader that returns
 *       every non-empty first column in file order. Because there is no Java
 *       catalog to validate against, an id this binary does not recognise is
 *       PRESERVED, not dropped (the opposite of the UC-26 behaviour). This is
 *       what lets the {@code reconfigure --doctor} path and the shell selector
 *       agree on the enabled set regardless of which capabilities ship (AC#2,
 *       AC#7).</li>
 *   <li>{@link DevToolsConfig#writeEnabled(Path, Set)} — a TEST-ONLY seam (no
 *       production path writes via Java post-UC-27; the shell selector persists
 *       the ledger itself). Retained {@code public} so tests in sibling packages
 *       can seed a ledger without shelling out. Emits {@code <id>\tsession-spawn}
 *       lines in iteration order.</li>
 * </ul>
 */
class DevToolsConfigTest {

    // ── Read tolerance ───────────────────────────────────────────────

    @Test
    void readEnabled_on_missing_file_returns_empty_set(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        assertThat(ledger).doesNotExist();
        assertThat(DevToolsConfig.readEnabled(ledger))
                .as("AC#7 — a missing ledger is the default state (no devtools enabled)")
                .isEmpty();
    }

    @Test
    void readEnabled_tolerates_comments_blank_lines_and_extra_whitespace(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        // Hand-editable on purpose — comments + blank lines let an operator
        // annotate the file without breaking the parser.
        String contents = String.join(
                "\n",
                "# UC-27 ledger — opt-in development tools",
                "",
                "   ",
                "# rootless Docker-in-Docker",
                "dind\tsession-spawn",
                "   # trailing comment, ignored",
                "",
                "");
        Files.writeString(ledger, contents, StandardCharsets.UTF_8);

        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");
    }

    @Test
    void readEnabled_is_lenient_and_preserves_ids_not_known_to_this_binary(@TempDir Path tmp) throws IOException {
        // AC#4 — the SHELL owns the catalog; the Java reader holds no list to
        // validate against, so it must PRESERVE every id (java, android, dind,
        // and even a hypothetical future capability the binary predates). This
        // is the UC-27 inversion of the UC-26 "drop unknown ids" behaviour.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Files.writeString(
                ledger,
                "dind\tsession-spawn\njava\tsession-spawn\nandroid\tsession-spawn\nfuturecap\tsession-spawn\n",
                StandardCharsets.UTF_8);

        assertThat(DevToolsConfig.readEnabled(ledger))
                .as("AC#4 — Java is not authoritative; it preserves whatever the shell wrote")
                .containsExactly("dind", "java", "android", "futurecap");
    }

    @Test
    void readEnabled_uses_only_the_first_column_and_preserves_file_order(@TempDir Path tmp) throws IOException {
        // The second column (apply_at) is informational on read; only the id
        // (first whitespace-delimited token) is returned, and the set keeps
        // file order so a deterministic ledger round-trips deterministically.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Files.writeString(
                ledger,
                "java   session-spawn\ndind   image-build\nandroid  whatever-second-col\n",
                StandardCharsets.UTF_8);

        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("java", "dind", "android");
    }

    // ── Round-trip + write shape (test seam) ────────────────────────

    @Test
    void write_then_read_round_trip_preserves_the_enabled_set_in_order(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Set<String> input = new LinkedHashSet<>();
        input.add("java");
        input.add("android");
        input.add("dind");

        DevToolsConfig.writeEnabled(ledger, input);
        assertThat(ledger).exists();
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("java", "android", "dind");

        // Re-writing the same set is byte-identical (deterministic output).
        byte[] first = Files.readAllBytes(ledger);
        DevToolsConfig.writeEnabled(ledger, input);
        assertThat(Files.readAllBytes(ledger)).isEqualTo(first);
    }

    @Test
    void writeEnabled_emits_id_tab_session_spawn_lines_in_iteration_order(@TempDir Path tmp) throws IOException {
        // lib.sh's reader consumes `<id>\t<apply_at>` per line. The Java seam
        // emits the same shape so a ledger seeded by a test is indistinguishable
        // from one the shell selector wrote.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Set<String> enabled = new LinkedHashSet<>();
        enabled.add("java");
        enabled.add("dind");
        DevToolsConfig.writeEnabled(ledger, enabled);

        assertThat(Files.readString(ledger, StandardCharsets.UTF_8))
                .isEqualTo("java\tsession-spawn\ndind\tsession-spawn\n");
    }

    @Test
    void writeEnabled_of_empty_set_truncates_to_zero_bytes(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Files.writeString(ledger, "dind\tsession-spawn\n", StandardCharsets.UTF_8);
        assertThat(Files.size(ledger)).isPositive();

        // AC#6/AC#12 — turning everything off yields a ledger readEnabled treats
        // as "no devtools enabled", so spawn behaves byte-identically to today.
        DevToolsConfig.writeEnabled(ledger, new LinkedHashSet<>());
        assertThat(ledger).exists();
        assertThat(Files.size(ledger)).isZero();
        assertThat(DevToolsConfig.readEnabled(ledger)).isEmpty();
    }

    @Test
    void writeEnabled_skips_null_and_blank_ids(@TempDir Path tmp) throws IOException {
        // Defence — a blank id would produce a malformed line; the writer drops
        // it rather than persisting a record the shell reader would choke on.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Set<String> input = new LinkedHashSet<>();
        input.add("dind");
        input.add("   "); // blank
        DevToolsConfig.writeEnabled(ledger, input);

        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");
        assertThat(Files.readString(ledger, StandardCharsets.UTF_8)).isEqualTo("dind\tsession-spawn\n");
    }

    @Test
    void writeEnabled_creates_parent_directories_as_needed(@TempDir Path tmp) throws IOException {
        // The seam's caller may point at a not-yet-created sessions dir on a
        // fresh install; writeEnabled mkdir -p's the parent.
        Path nested =
                tmp.resolve("var").resolve("lib").resolve("ai-sandbox-server").resolve("sessions");
        Path ledger = nested.resolve(".ai-sandbox-devtools");
        assertThat(nested).doesNotExist();

        Set<String> enabled = new LinkedHashSet<>();
        enabled.add("dind");
        DevToolsConfig.writeEnabled(ledger, enabled);

        assertThat(ledger).exists();
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");
    }
}
