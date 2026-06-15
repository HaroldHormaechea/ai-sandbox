package com.aisandbox.server.serverupdate.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * UC-84 — the server's ENTIRE privileged capability, distilled to a single,
 * parameter-free action: create one fixed-name file in one fixed directory
 * (AC8/AC11/AC12).
 *
 * <h2>Security core — DO NOT WEAKEN</h2>
 *
 * <ul>
 *   <li>The trigger directory is a single HARDCODED literal
 *       ({@link #TRIGGER_DIR}). The same literal is asserted by the QA path-pin
 *       test, by the {@code postinst} {@code install -d}, and by the
 *       {@code ai-sandbox-updater.path} unit's {@code PathExistsGlob} — a CI
 *       grep cross-check proves all three agree, so none can drift.</li>
 *   <li>The marker filename is a fixed literal ({@link #MARKER_FILENAME}); the
 *       server consumes NO request input for it.</li>
 *   <li>No file content carries meaning. The updater clears this directory
 *       blindly and never reads any name or byte from it — it self-determines
 *       the latest {@code server-v*} target. So even a fully compromised server
 *       can at most request "update to the latest published release", nothing
 *       else. No shell, no {@code ProcessExecutor}, no argv, no env is involved
 *       on this path.</li>
 * </ul>
 *
 * <p>The systemd unit grants the service write access to
 * {@code /var/lib/ai-sandbox-server} (its {@code ReadWritePaths}), and the
 * {@code postinst} pre-creates {@link #TRIGGER_DIR} owned by
 * {@code ai-sandbox-server} mode 0750, so this NIO write succeeds without any
 * unit edit. The script {@code /opt/ai-sandbox-server/updater/} that the root
 * unit runs is NOT in {@code HostScriptLocator}, so the server has no exec path
 * to it.
 */
@Service
public class UpdateTriggerService {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateTriggerService.class);

    /**
     * THE canonical trigger directory — single source of truth. MUST stay
     * byte-identical to the {@code postinst} {@code install -d} target and the
     * {@code ai-sandbox-updater.path} unit's watched path (CI cross-checks all
     * three against this exact string).
     */
    public static final String TRIGGER_DIR = "/var/lib/ai-sandbox-server/update-trigger";

    /** Fixed marker filename — the updater ignores it; it only matters that the dir becomes non-empty. */
    public static final String MARKER_FILENAME = "update.requested";

    private final Path triggerDir;

    public UpdateTriggerService(@Value("${ai-sandbox.update.trigger-dir:" + TRIGGER_DIR + "}") String triggerDir) {
        this.triggerDir = Path.of(triggerDir);
    }

    /**
     * Emit the parameter-free update trigger: ensure the trigger dir exists and
     * (over)write the fixed-name marker into it. Pure {@code java.nio} — no
     * shell, no process, no arguments.
     *
     * @throws ServerUpdateException.TriggerFailed on any I/O failure (the
     *     controller maps it to {@code 500 update_trigger_failed} and the
     *     server keeps running — AC14).
     */
    public void requestUpdate() {
        Path marker = triggerDir.resolve(MARKER_FILENAME);
        try {
            Files.createDirectories(triggerDir);
            // Content is irrelevant to the updater (it never reads it); write a
            // tiny fixed payload so the file is non-empty and the .path unit's
            // empty→non-empty transition fires reliably.
            Files.write(
                    marker,
                    "update-requested\n".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            LOG.info("Wrote update trigger marker at {}", marker);
        } catch (IOException e) {
            throw new ServerUpdateException.TriggerFailed("Could not write update trigger marker at " + marker, e);
        }
    }
}
