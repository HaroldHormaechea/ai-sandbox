package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-101 § AC1,AC5,AC8,AC9,AC10 — wire-level assertions for the bundled
 * {@code lib.sh} → {@code inject_host_gpu_passthrough()} host GPU
 * passthrough injector and its downstream effect on the {@code docker
 * compose} argv that {@code spawn.sh} runs.
 *
 * <p>Strategy mirrors {@link HostScriptComposeEnvTest}: stage {@code
 * lib.sh} + the real {@code docker-compose.yml} / {@code
 * docker-compose.gpu.yml} on a temp dir, drop a fake {@code docker} shim
 * that echoes its argv, then source {@code lib.sh}, call {@code
 * inject_host_gpu_passthrough}, and invoke {@code ai_sandbox_compose} so
 * the fake docker captures the argv the injector produced. The GPU host
 * is SIMULATED entirely through the injector's documented test knobs —
 * no real NVIDIA hardware, driver, or CDI runtime is required:
 *
 * <ul>
 *   <li>a fake {@code nvidia-smi} on PATH → {@code host_gpu_driver_present}
 *       true;</li>
 *   <li>{@code AISB_GPU_CDI_DIRS}=&lt;temp dir with a fake {@code
 *       nvidia.com/gpu} CDI spec&gt; → {@code host_gpu_cdi_spec_present}
 *       true;</li>
 *   <li>{@code AISB_GPU_KILL_SWITCH_FILE}=&lt;temp sentinel&gt; and
 *       {@code AI_SANDBOX_GPU_DISABLED=1} → the operator kill switch
 *       (AC10);</li>
 *   <li>a fake {@code uname} on PATH printing {@code Darwin} → the
 *       non-Linux gate (AC9).</li>
 * </ul>
 *
 * <p><b>Scope note (owner-GPU-host):</b> AC1 (live {@code nvidia-smi}),
 * AC2 (CUDA compute), and AC3 (nested {@code docker run --gpus}) require
 * a physical NVIDIA GPU + driver + CDI and are NOT verifiable in CI (this
 * environment has no GPU). This test asserts the host-side <i>wiring</i>
 * that satisfies AC1's "no per-session opt-in" layering; the live GPU
 * behaviour is an owner-verify-on-a-GPU-host step (see {@code docs/gpu.md}
 * runbook). The {@link EnabledOnOs} gate (Linux / macOS only — POSIX
 * permissions + {@code /bin/sh} script invocation are mandatory) matches
 * the sibling host-script tests.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class HostScriptGpuPassthroughTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("user.dir")).getParent();

    private static final String GPU_OVERRIDE = "docker-compose.gpu.yml";

    // ── AC1 wiring — GPU present, switch off → override layered + env set ──────

    /**
     * AC1 wiring — on a (simulated) Linux GPU host with the kill switch OFF,
     * {@code inject_host_gpu_passthrough} MUST export {@code AI_SANDBOX_GPU=1},
     * set {@code AI_SANDBOX_GPU_STATUS=on} + the resolved CDI dir, and layer
     * {@code docker-compose.gpu.yml} so the downstream {@code ai_sandbox_compose}
     * argv carries it as a {@code -f} override — with NO per-session opt-in step.
     */
    @Test
    void gpu_present_and_switch_off_layers_override_and_exports_env(@TempDir Path tmp) throws Exception {
        Stage s = stage(tmp);
        Path cdiDir = fakeCdiDir(tmp);

        Map<String, String> env = baseEnv(s);
        installFakeNvidiaSmi(s.bin);
        env.put("AISB_GPU_CDI_DIRS", cdiDir.toString());
        // Kill switch sentinel points at a path that does NOT exist → switch off.
        env.put("AISB_GPU_KILL_SWITCH_FILE", tmp.resolve("no-such-kill-switch").toString());

        Result r = run(s, env, /* callInjector= */ true);
        assertThat(r.rc).isZero();

        assertThat(r.probe)
                .as("AC1/AC10 — GPU present + switch off MUST signal passthrough ON")
                .contains("GPU=1")
                .contains("STATUS=on")
                .contains("CDIDIR=" + cdiDir);

        boolean gpuOverridePresent = r.argv.stream().anyMatch(a -> a.endsWith(GPU_OVERRIDE));
        assertThat(gpuOverridePresent)
                .as(
                        "AC1 — ai_sandbox_compose MUST layer docker-compose.gpu.yml as a -f override (host-wide, no opt-in)")
                .isTrue();
        // The override is a `-f` value (preceded by a -f flag somewhere in argv).
        assertThat(r.argv).as("argv carries a -f flag for the override").contains("-f");
    }

    /**
     * AC8 (concurrency) — two back-to-back spawns on the same simulated GPU host
     * each layer the override independently and export the same env, proving the
     * injector holds no cross-spawn mutable state / lock (safety inherited from
     * today's independent-spawn model). Run as two separate shell invocations
     * (each = one spawn) against identical inputs; both MUST produce identical
     * GPU wiring.
     */
    @Test
    void two_back_to_back_gpu_spawns_each_layer_the_override_independently(@TempDir Path tmp) throws Exception {
        Stage s = stage(tmp);
        Path cdiDir = fakeCdiDir(tmp);

        Map<String, String> env = baseEnv(s);
        installFakeNvidiaSmi(s.bin);
        env.put("AISB_GPU_CDI_DIRS", cdiDir.toString());
        env.put("AISB_GPU_KILL_SWITCH_FILE", tmp.resolve("no-such-kill-switch").toString());

        Result first = run(s, env, true);
        Result second = run(s, env, true);

        assertThat(first.rc).isZero();
        assertThat(second.rc).isZero();
        assertThat(first.probe).as("spawn #1 GPU on").contains("GPU=1").contains("STATUS=on");
        assertThat(second.probe).as("spawn #2 GPU on").contains("GPU=1").contains("STATUS=on");
        assertThat(first.argv.stream().anyMatch(a -> a.endsWith(GPU_OVERRIDE)))
                .as("spawn #1 layers the override")
                .isTrue();
        assertThat(second.argv.stream().anyMatch(a -> a.endsWith(GPU_OVERRIDE)))
                .as("spawn #2 layers the override independently (no shared state)")
                .isTrue();
        assertThat(second.argv)
                .as("AC8 — each spawn produces identical GPU argv (no cross-spawn drift)")
                .isEqualTo(first.argv);
    }

    // ── AC5 — no GPU → byte-identical to today ────────────────────────────────

    /**
     * AC5 — on a host with no NVIDIA GPU (no driver, no CDI), the injector is a
     * strict no-op: {@code AI_SANDBOX_GPU} stays unset, status is {@code none},
     * NO override is layered, and the resulting {@code docker compose} argv is
     * BYTE-IDENTICAL to the same invocation WITHOUT the injector (today's
     * behaviour). Verified by capturing a baseline argv (injector not called)
     * and asserting equality.
     */
    @Test
    void no_gpu_is_a_byte_identical_no_op_vs_today(@TempDir Path tmp) throws Exception {
        Stage s = stage(tmp);

        Map<String, String> env = baseEnv(s);
        // No fake nvidia-smi, CDI dirs point at an empty dir → no driver, no CDI.
        env.put("AISB_GPU_CDI_DIRS", tmp.resolve("empty-cdi").toString());
        env.put("AISB_GPU_KILL_SWITCH_FILE", tmp.resolve("no-such-kill-switch").toString());

        Result baseline = run(s, env, /* callInjector= */ false);
        Result injected = run(s, env, /* callInjector= */ true);

        assertThat(baseline.rc).isZero();
        assertThat(injected.rc).isZero();

        assertThat(injected.probe)
                .as("AC5 — no GPU MUST leave AI_SANDBOX_GPU unset and status none")
                .contains("GPU=\n")
                .contains("STATUS=none");
        assertThat(injected.argv.stream().noneMatch(a -> a.endsWith(GPU_OVERRIDE)))
                .as("AC5 — no override layered when no GPU")
                .isTrue();
        assertThat(injected.argv)
                .as("AC5 — docker argv byte-identical to today (injector is a strict no-op with no GPU)")
                .isEqualTo(baseline.argv);
    }

    /**
     * AC5/AC7 — driver present but NO CDI spec is the one state that MUST NOT be
     * a silent no-op: the injector sets {@code AI_SANDBOX_GPU_STATUS=driver-no-cdi}
     * (spawn.sh turns this into the actionable {@code nvidia-ctk cdi generate}
     * hint) yet STILL lays down NO override and leaves the argv byte-identical to
     * today (the GPU is genuinely not passable without a spec).
     */
    @Test
    void driver_present_but_no_cdi_signals_actionable_status_without_layering(@TempDir Path tmp) throws Exception {
        Stage s = stage(tmp);

        Map<String, String> env = baseEnv(s);
        installFakeNvidiaSmi(s.bin); // driver "present"
        // CDI dir exists but holds no nvidia.com/gpu spec → host_gpu_cdi_spec_present false.
        Path emptyCdi = tmp.resolve("cdi-empty");
        Files.createDirectories(emptyCdi);
        env.put("AISB_GPU_CDI_DIRS", emptyCdi.toString());
        env.put("AISB_GPU_KILL_SWITCH_FILE", tmp.resolve("no-such-kill-switch").toString());

        Result baseline = run(s, env, false);
        Result injected = run(s, env, true);

        assertThat(injected.rc).isZero();
        assertThat(injected.probe)
                .as("AC5/AC7 — driver-present-but-no-CDI MUST surface the distinct driver-no-cdi status")
                .contains("STATUS=driver-no-cdi")
                .contains("GPU=\n");
        assertThat(injected.argv.stream().noneMatch(a -> a.endsWith(GPU_OVERRIDE)))
                .as("no override layered without a CDI spec")
                .isTrue();
        assertThat(injected.argv)
                .as("AC5 — argv still byte-identical to today (no GPU actually passed through)")
                .isEqualTo(baseline.argv);
    }

    // ── AC10 — kill switch ────────────────────────────────────────────────────

    /**
     * AC10 — the operator env kill switch ({@code AI_SANDBOX_GPU_DISABLED=1})
     * wins even on a fully GPU-capable host: status {@code disabled}, no env, no
     * override, byte-identical argv.
     */
    @Test
    void kill_switch_env_disables_even_on_a_gpu_host(@TempDir Path tmp) throws Exception {
        Stage s = stage(tmp);
        Path cdiDir = fakeCdiDir(tmp);

        Map<String, String> envOff = baseEnv(s);
        envOff.put("AISB_GPU_CDI_DIRS", cdiDir.toString());
        envOff.put(
                "AISB_GPU_KILL_SWITCH_FILE", tmp.resolve("no-such-kill-switch").toString());
        // Baseline = no injector, same env → today's argv.
        Result baseline = run(s, envOff, false);

        Map<String, String> env = baseEnv(s);
        installFakeNvidiaSmi(s.bin); // GPU genuinely present …
        env.put("AISB_GPU_CDI_DIRS", cdiDir.toString());
        env.put("AISB_GPU_KILL_SWITCH_FILE", tmp.resolve("no-such-kill-switch").toString());
        env.put("AI_SANDBOX_GPU_DISABLED", "1"); // … but the operator disabled it.

        Result injected = run(s, env, true);
        assertThat(injected.rc).isZero();
        assertThat(injected.probe)
                .as("AC10 — env kill switch MUST disable GPU even with hardware present")
                .contains("STATUS=disabled")
                .contains("GPU=\n");
        assertThat(injected.argv.stream().noneMatch(a -> a.endsWith(GPU_OVERRIDE)))
                .as("AC10 — kill switch suppresses the override")
                .isTrue();
        assertThat(injected.argv)
                .as("AC10 — argv byte-identical to a no-GPU session when switch engaged")
                .isEqualTo(baseline.argv);
    }

    /**
     * AC10 — the persisted sentinel-file kill switch has the same effect as the
     * env override: its mere existence (pointed at by {@code
     * AISB_GPU_KILL_SWITCH_FILE}) disables passthrough on a GPU-capable host.
     */
    @Test
    void kill_switch_sentinel_file_disables_even_on_a_gpu_host(@TempDir Path tmp) throws Exception {
        Stage s = stage(tmp);
        Path cdiDir = fakeCdiDir(tmp);
        Path sentinel = tmp.resolve(".ai-sandbox-gpu-disabled");
        Files.writeString(sentinel, ""); // existence alone engages the switch.

        Map<String, String> env = baseEnv(s);
        installFakeNvidiaSmi(s.bin);
        env.put("AISB_GPU_CDI_DIRS", cdiDir.toString());
        env.put("AISB_GPU_KILL_SWITCH_FILE", sentinel.toString());

        Result injected = run(s, env, true);
        assertThat(injected.rc).isZero();
        assertThat(injected.probe)
                .as("AC10 — sentinel-file kill switch MUST disable GPU even with hardware present")
                .contains("STATUS=disabled")
                .contains("GPU=\n");
        assertThat(injected.argv.stream().noneMatch(a -> a.endsWith(GPU_OVERRIDE)))
                .as("AC10 — sentinel kill switch suppresses the override")
                .isTrue();
    }

    // ── AC9 — Linux-only gate ─────────────────────────────────────────────────

    /**
     * AC9 — the feature is gated to Linux hosts. Simulated by a fake {@code
     * uname} on PATH printing {@code Darwin}: the injector's first-line gate
     * takes the early-return, status {@code none}, and NO override is layered —
     * macOS/Windows hosts are unaffected (byte-identical to today).
     */
    @Test
    void non_linux_host_takes_the_early_return_no_override(@TempDir Path tmp) throws Exception {
        Stage s = stage(tmp);
        Path cdiDir = fakeCdiDir(tmp);

        // Fake uname → Darwin, so the Linux gate fails even though a fake
        // nvidia-smi + CDI spec are present. Baseline uses the SAME fake uname so
        // the comparison isolates the injector's effect, not uname itself.
        installFakeUname(s.bin, "Darwin");
        installFakeNvidiaSmi(s.bin);

        Map<String, String> env = baseEnv(s);
        env.put("AISB_GPU_CDI_DIRS", cdiDir.toString());
        env.put("AISB_GPU_KILL_SWITCH_FILE", tmp.resolve("no-such-kill-switch").toString());

        Result baseline = run(s, env, false);
        Result injected = run(s, env, true);

        assertThat(injected.rc).isZero();
        assertThat(injected.probe)
                .as("AC9 — non-Linux host MUST early-return with status none")
                .contains("STATUS=none")
                .contains("GPU=\n");
        assertThat(injected.argv.stream().noneMatch(a -> a.endsWith(GPU_OVERRIDE)))
                .as("AC9 — no GPU override on a non-Linux host")
                .isTrue();
        assertThat(injected.argv)
                .as("AC9 — argv byte-identical to today on non-Linux (feature fully gated off)")
                .isEqualTo(baseline.argv);
    }

    // ── harness ───────────────────────────────────────────────────────────────

    /** Staged repo slice: {@code bin/} (PATH shims) + a dir holding lib.sh + compose files. */
    private record Stage(Path dir, Path bin, Path log) {}

    private record Result(int rc, List<String> argv, String probe) {}

    /**
     * Stage {@code lib.sh}, {@code docker-compose.yml}, and {@code
     * docker-compose.gpu.yml} (verbatim repo copies) into a temp dir, plus a
     * fake {@code docker} shim on a {@code bin/} PATH entry. The gpu override
     * must sit beside the base compose file so {@code _aisb_append_compose_override}
     * resolves it as {@code $(dirname AI_SANDBOX_COMPOSE_FILE)/docker-compose.gpu.yml}.
     */
    private static Stage stage(Path tmp) throws IOException {
        Path dir = tmp.resolve("stage");
        Files.createDirectories(dir);
        copyExec(REPO_ROOT.resolve("lib.sh"), dir.resolve("lib.sh"));
        Files.copy(
                REPO_ROOT.resolve("docker-compose.yml"),
                dir.resolve("docker-compose.yml"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(REPO_ROOT.resolve(GPU_OVERRIDE), dir.resolve(GPU_OVERRIDE), StandardCopyOption.REPLACE_EXISTING);

        Path bin = tmp.resolve("bin");
        Files.createDirectories(bin);
        Path log = tmp.resolve("docker.log");
        installFakeDocker(bin, log);
        return new Stage(dir, bin, log);
    }

    /**
     * Base env: a tight PATH (fake {@code bin} first, then {@code /usr/bin:/bin}
     * for the core utilities lib.sh needs) so NO host {@code nvidia-smi} can leak
     * in and flip detection true — the GPU host is simulated ONLY through the
     * explicit shims/knobs each test installs. {@code AI_SANDBOX_COMPOSE_FILE}
     * points at the staged base compose file (install-mode resolution).
     *
     * <p>Defense-in-depth against a CI hang: {@code DOCKER_HOST} is pointed at an
     * UNREACHABLE socket. This test drives only {@code docker compose config}
     * (daemon-free) through a fake shim, so the daemon is never needed; but if a
     * real {@code docker} ever leaked onto PATH on a runner with a LIVE daemon, an
     * unreachable {@code DOCKER_HOST} makes it fail FAST ("cannot connect")
     * instead of talking to the live daemon and blocking the whole suite.
     */
    private static Map<String, String> baseEnv(Stage s) {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", s.bin + ":/usr/bin:/bin");
        env.put("AI_SANDBOX_COMPOSE_FILE", s.dir.resolve("docker-compose.yml").toString());
        env.put("DOCKER_HOST", "unix://" + s.dir.resolve("no-such-docker.sock"));
        return env;
    }

    /** A temp CDI dir holding a fake {@code nvidia.com/gpu} spec (what detection greps for). */
    private static Path fakeCdiDir(Path tmp) throws IOException {
        Path cdi = tmp.resolve("cdi");
        Files.createDirectories(cdi);
        Files.writeString(
                cdi.resolve("nvidia.yaml"),
                "cdiVersion: \"0.6.0\"\nkind: \"nvidia.com/gpu\"\ndevices:\n  - name: all\n");
        return cdi;
    }

    /**
     * Run {@code bash -c 'source lib.sh; [inject_host_gpu_passthrough;] printf
     * probe; ai_sandbox_compose -p ai-sandbox-1 config'} with the supplied env and
     * working dir = the stage. Captures the fake-docker argv and a probe of the
     * GPU env vars the injector set.
     *
     * <p><b>Why {@code config}, not {@code up}:</b> this test only needs the
     * composed {@code docker compose} ARGV ({@code -f} override layering + flags),
     * which {@code ai_sandbox_compose} builds identically regardless of the
     * subcommand. {@code config} is DAEMON-FREE — it merely parses/validates the
     * compose files — so even if the fake shim were somehow bypassed on a CI
     * runner with a LIVE daemon, there is no {@code up}/build/readiness-poll that
     * could block the suite (the 15-min-CI-hang failure mode). The whole call is
     * additionally wrapped in a preemptive 25s timeout so any unexpected block
     * becomes a FAST, clearly-labeled failure rather than a suite hang.
     */
    private static Result run(Stage s, Map<String, String> env, boolean callInjector) throws Exception {
        // Fresh docker log per run so back-to-back spawns don't accumulate.
        Files.deleteIfExists(s.log);
        Path probe = Files.createTempFile(s.dir, "probe", ".env");
        String inject = callInjector ? "inject_host_gpu_passthrough && " : "";
        String cmd = "source './lib.sh' && " + inject
                + "printf 'GPU=%s\\nSTATUS=%s\\nCDIDIR=%s\\n' "
                + "\"${AI_SANDBOX_GPU:-}\" \"${AI_SANDBOX_GPU_STATUS:-}\" \"${AI_SANDBOX_GPU_CDI_DIR:-}\" > '"
                + probe + "' && ai_sandbox_compose -p ai-sandbox-1 config";
        int rc = assertTimeoutPreemptively(
                Duration.ofSeconds(25),
                () -> runShell(s.dir, env, cmd),
                "GPU passthrough shell invocation blocked >25s (possible real-docker leak / daemon reach)");
        List<String> argv = Files.exists(s.log) ? Files.readAllLines(s.log) : List.of();
        return new Result(rc, argv, Files.readString(probe));
    }

    private static void installFakeDocker(Path bin, Path log) throws IOException {
        String body = "#!/bin/sh\n" + "for a in \"$@\"; do printf '%s\\n' \"$a\" >> '" + log + "'; done\n" + "exit 0\n";
        Path docker = bin.resolve("docker");
        Files.writeString(docker, body);
        chmod0755(docker);
    }

    /** Fake {@code nvidia-smi} → exit 0, flipping {@code host_gpu_driver_present} true. */
    private static void installFakeNvidiaSmi(Path bin) throws IOException {
        Path smi = bin.resolve("nvidia-smi");
        Files.writeString(smi, "#!/bin/sh\nexit 0\n");
        chmod0755(smi);
    }

    /**
     * Fake {@code uname} that honours {@code -s} (printing the supplied kernel
     * name) and otherwise delegates to the real {@code uname}, so only the
     * OS-gate branch is affected.
     */
    private static void installFakeUname(Path bin, String kernel) throws IOException {
        String body = "#!/bin/sh\n"
                + "if [ \"$1\" = \"-s\" ]; then printf '%s\\n' '" + kernel + "'; exit 0; fi\n"
                + "exec /usr/bin/uname \"$@\"\n";
        Path uname = bin.resolve("uname");
        Files.writeString(uname, body);
        chmod0755(uname);
    }

    private static void copyExec(Path src, Path dst) throws IOException {
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        chmod0755(dst);
    }

    private static void chmod0755(Path p) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException ignored) {
            // Test is gated to POSIX OSes; this should never trip.
        }
    }

    private static int runShell(Path cwd, Map<String, String> env, String cmd) throws Exception {
        List<String> argv = new ArrayList<>(List.of("/bin/bash", "-c", cmd));
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(false);
        Map<String, String> procEnv = pb.environment();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                procEnv.remove(e.getKey());
            } else {
                procEnv.put(e.getKey(), e.getValue());
            }
        }
        procEnv.remove("TERM");
        // Drop BASH_ENV so a non-interactive `bash -c` does NOT source an
        // environment profile that re-prepends real tool dirs (e.g. a dind
        // `docker`) onto PATH and shadows the fake shim this test installs. In
        // CI BASH_ENV is unset, so this is a no-op there; in a dev sandbox that
        // exports BASH_ENV it keeps the test's PATH authoritative and the fake
        // docker argv assertions deterministic.
        procEnv.remove("BASH_ENV");
        Process p = pb.start();
        Thread out = new Thread(() -> drain(p.getInputStream(), System.out));
        Thread err = new Thread(() -> drain(p.getErrorStream(), System.err));
        out.setDaemon(true);
        err.setDaemon(true);
        out.start();
        err.start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("shell command timed out: " + cmd);
        }
        out.join(1000);
        err.join(1000);
        return p.exitValue();
    }

    private static void drain(java.io.InputStream in, java.io.PrintStream sink) {
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                sink.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // best-effort drain
        }
    }
}
