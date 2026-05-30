package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.cli.secrets.DevToolsConfig.ApplyAt;
import com.aisandbox.server.cli.secrets.DevToolsConfig.Capability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-26 — pure read/write coverage for {@link DevToolsConfig}, the helper
 * that owns the {@code .ai-sandbox-devtools} ledger format and the v1
 * capability catalog.
 *
 * <p>The ledger is the persisted form of the "Select the development tools
 * you want to install" wizard step (AC#7). It must:
 *
 * <ul>
 *   <li>Survive a write-then-read round-trip without losing or reordering
 *       enabled ids (AC#2 — default OFF, AC#7 — persistence is the single
 *       source of truth for spawn-time injection).</li>
 *   <li>Tolerate comments and blank lines so an operator can hand-edit the
 *       file without breaking machine-readability.</li>
 *   <li>Skip unknown ids silently on read (forward-compat — newer ledger,
 *       older binary) and on write (defence — the wizard step is the
 *       front-line filter, but {@code writeEnabled} should not crash on a
 *       bad input).</li>
 *   <li>Expose the per-capability {@code apply_at} via {@link
 *       DevToolsConfig#applyAt(String)} so {@code lib.sh}'s
 *       {@code inject_devtool_spawn_env} and the Java parallel can route
 *       on the same axis (AC#8 — generic catalog shape, not DinD-specific).</li>
 * </ul>
 *
 * <p>The ledger file's POSIX mode (0644 per the proposal) is set by
 * {@link DevToolsStep#run} after delegating to
 * {@link DevToolsConfig#writeEnabled}, not by {@code writeEnabled} itself
 * (which respects the JVM umask). That assertion therefore lives in
 * {@link DevToolsStepTest}, which exercises the full wizard-step path.
 */
class DevToolsConfigTest {

    // ── Catalog shape (AC#2, AC#8) ──────────────────────────────────

    @Test
    void v1_catalog_has_exactly_one_entry_named_dind_at_session_spawn() {
        // AC#2 — "for v1 the checklist has exactly one entry"; AC#8 — the
        // catalog is the data shape (id|apply_at|label|warning) that lets
        // a future capability slot in without restructuring anything.
        List<Capability> catalog = DevToolsConfig.CATALOG;
        assertThat(catalog).hasSize(1);
        Capability dind = catalog.get(0);
        assertThat(dind.id()).isEqualTo("dind");
        assertThat(dind.applyAt()).isEqualTo(ApplyAt.SESSION_SPAWN);
        // AC#2 — the v1 label must mention "Docker-in-Docker" and
        // "rootless" so the wizard renders a self-explanatory checklist
        // line (the operator should be able to grok what the entry does
        // without external docs).
        assertThat(dind.label()).contains("Docker-in-Docker").contains("rootless");
        // AC#3 — selecting DinD must surface an inline trust-boundary
        // warning; the catalog row carries the canonical wording. Pin the
        // signal phrases so a future edit can't silently drop the explicit
        // trust-boundary framing.
        assertThat(dind.warning())
                .as("AC#3 — inline trust-boundary warning text MUST mention the boundary expansion explicitly")
                .contains("trust boundary")
                .contains("rootless")
                .contains("session");
    }

    @Test
    void apply_at_wire_strings_match_lib_sh_catalog() {
        // The Bash side (lib.sh '_aisb_devtool_catalog') uses the literal
        // strings "image-build" and "session-spawn" in the second `|`-field.
        // Both sides MUST agree byte-for-byte; otherwise inject_devtool_spawn_env
        // (bash) and DevToolsConfig (Java) drift and a future capability that
        // shares an apply_at gets routed differently on the two paths.
        assertThat(ApplyAt.IMAGE_BUILD.wire()).isEqualTo("image-build");
        assertThat(ApplyAt.SESSION_SPAWN.wire()).isEqualTo("session-spawn");

        assertThat(ApplyAt.fromWire("image-build")).contains(ApplyAt.IMAGE_BUILD);
        assertThat(ApplyAt.fromWire("session-spawn")).contains(ApplyAt.SESSION_SPAWN);
        // Forward-compat — null and unknown values resolve to empty.
        assertThat(ApplyAt.fromWire(null)).isEmpty();
        assertThat(ApplyAt.fromWire("not-a-flavour")).isEmpty();
    }

    // ── apply_at lookup ─────────────────────────────────────────────

    @Test
    void applyAt_lookup_returns_session_spawn_for_dind_empty_for_unknown_ids() {
        assertThat(DevToolsConfig.applyAt("dind")).contains(ApplyAt.SESSION_SPAWN);
        // Defence — unknown ids are tolerated.
        assertThat(DevToolsConfig.applyAt("rust")).isEmpty();
        assertThat(DevToolsConfig.applyAt("")).isEmpty();
        assertThat(DevToolsConfig.applyAt("  dind  ")).isEmpty(); // exact-id match — no trim
    }

    @Test
    void find_returns_the_capability_record_for_a_known_id() {
        Optional<Capability> dind = DevToolsConfig.find("dind");
        assertThat(dind).isPresent();
        assertThat(dind.get().applyAt()).isEqualTo(ApplyAt.SESSION_SPAWN);
        assertThat(DevToolsConfig.find("nope")).isEmpty();
    }

    // ── Read tolerance ───────────────────────────────────────────────

    @Test
    void readEnabled_on_missing_file_returns_empty_set(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        // Sanity — file must not exist (TempDir is created empty).
        assertThat(ledger).doesNotExist();
        assertThat(DevToolsConfig.readEnabled(ledger))
                .as("AC#7 — a missing ledger is the default state (no devtools enabled)")
                .isEmpty();
    }

    @Test
    void readEnabled_tolerates_comments_blank_lines_and_extra_whitespace(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        // Hand-editable on purpose — comments + blank lines exist to let an
        // operator annotate the file without breaking the parser.
        String contents = String.join(
                "\n",
                "# UC-26 ledger — opt-in development tools",
                "",
                "   ",
                "# v1 only entry: rootless Docker-in-Docker",
                "dind\tsession-spawn",
                "   # trailing comment, ignored",
                "",
                "");
        Files.writeString(ledger, contents, StandardCharsets.UTF_8);

        Set<String> enabled = DevToolsConfig.readEnabled(ledger);
        assertThat(enabled).containsExactly("dind");
    }

    @Test
    void readEnabled_drops_unknown_ids_silently_for_forward_compat(@TempDir Path tmp) throws IOException {
        // AC#8 — adding a future capability is structural, not a wizard
        // restructure. An older binary that doesn't yet know about
        // `rust` / `python` MUST still read a newer ledger without
        // erroring; it just sees the subset of capabilities it knows.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Files.writeString(ledger, "dind\tsession-spawn\nrust\timage-build\npython\timage-build\n", StandardCharsets.UTF_8);

        Set<String> enabled = DevToolsConfig.readEnabled(ledger);
        assertThat(enabled).containsExactly("dind");
    }

    @Test
    void readEnabled_uses_only_the_id_column_apply_at_is_re_resolved_from_the_catalog(@TempDir Path tmp)
            throws IOException {
        // The ledger's second column is informational — readEnabled returns
        // only the ids and the apply_at is re-resolved at use time. This
        // prevents a stale persisted apply_at (e.g. "image-build" recorded
        // when dind was hypothetically image-baked) from out-voting the
        // current catalog (where dind is session-spawn).
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Files.writeString(ledger, "dind\timage-build\n", StandardCharsets.UTF_8);

        Set<String> enabled = DevToolsConfig.readEnabled(ledger);
        assertThat(enabled).containsExactly("dind");
        // And the live apply_at is what the catalog says, NOT what the
        // ledger says.
        assertThat(DevToolsConfig.applyAt("dind")).contains(ApplyAt.SESSION_SPAWN);
    }

    // ── Round-trip (AC#7 — persistence is single source of truth) ───

    @Test
    void write_then_read_round_trip_is_deterministic(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Set<String> input = new LinkedHashSet<>();
        input.add("dind");

        DevToolsConfig.writeEnabled(ledger, input);
        assertThat(ledger).exists();

        // Round-trip preserves the enabled set.
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");

        // Second write of the same set is byte-identical (deterministic
        // output — the writer iterates the static catalog, not the input
        // set, so insertion order can't perturb the file).
        byte[] first = Files.readAllBytes(ledger);
        DevToolsConfig.writeEnabled(ledger, input);
        byte[] second = Files.readAllBytes(ledger);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void writeEnabled_of_empty_set_truncates_to_zero_bytes(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        // Pre-populate.
        Files.writeString(ledger, "dind\tsession-spawn\n", StandardCharsets.UTF_8);
        assertThat(Files.size(ledger)).isPositive();

        // AC#6 — turning everything off must produce a ledger that
        // readEnabled treats as "no devtools enabled" (which then makes
        // inject_devtool_spawn_env a no-op and sessions byte-identical
        // to today's behaviour).
        DevToolsConfig.writeEnabled(ledger, new LinkedHashSet<>());
        assertThat(ledger).exists();
        assertThat(Files.size(ledger)).isZero();
        assertThat(DevToolsConfig.readEnabled(ledger)).isEmpty();
    }

    @Test
    void writeEnabled_emits_id_tab_apply_at_lines(@TempDir Path tmp) throws IOException {
        // lib.sh's read_enabled_devtools reads `<id>\t<apply_at>` per line;
        // the wizard's Java parallel must emit the same shape so the two
        // paths can co-exist (an operator who runs setup.sh once and
        // `aisandboxctl reconfigure` later should see the same file format).
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Set<String> enabled = new LinkedHashSet<>();
        enabled.add("dind");
        DevToolsConfig.writeEnabled(ledger, enabled);

        String body = Files.readString(ledger, StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("dind\tsession-spawn\n");
    }

    @Test
    void writeEnabled_silently_skips_unknown_ids(@TempDir Path tmp) throws IOException {
        // Defence in depth — the wizard step is the front-line filter,
        // but writeEnabled also drops any id that isn't in the catalog
        // rather than persisting garbage. A round-trip then yields only
        // the known ids.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Set<String> input = new LinkedHashSet<>();
        input.add("dind");
        input.add("rust"); // not in v1 catalog
        DevToolsConfig.writeEnabled(ledger, input);

        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");
        assertThat(Files.readString(ledger, StandardCharsets.UTF_8)).isEqualTo("dind\tsession-spawn\n");
    }

    @Test
    void writeEnabled_creates_parent_directories_as_needed(@TempDir Path tmp) throws IOException {
        // The wizard's caller (reconfigure / onboard) may point at a
        // not-yet-created sessions dir on a fresh install; writeEnabled
        // mkdir -p's the parent to keep the write atomic from the
        // caller's POV.
        Path nested = tmp.resolve("var").resolve("lib").resolve("ai-sandbox-server").resolve("sessions");
        Path ledger = nested.resolve(".ai-sandbox-devtools");
        assertThat(nested).doesNotExist();

        Set<String> enabled = new LinkedHashSet<>();
        enabled.add("dind");
        DevToolsConfig.writeEnabled(ledger, enabled);

        assertThat(ledger).exists();
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");
    }
}
