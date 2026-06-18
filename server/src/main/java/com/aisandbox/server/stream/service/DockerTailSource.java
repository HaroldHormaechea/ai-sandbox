package com.aisandbox.server.stream.service;

import com.aisandbox.server.stream.service.TranscriptTailService.Tail;
import com.aisandbox.server.stream.service.TranscriptTailService.TailSource;
import com.aisandbox.server.stream.service.TranscriptTailService.TailTarget;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * UC-85 — the production {@link TailSource}: opens a live transcript tail by spawning the
 * in-container {@code aisandbox-conversation-tail} helper via
 * {@code docker compose -p ai-sandbox-N exec -T claude-sandbox …} and reading its stdout
 * incrementally. This is exactly the behaviour {@link TranscriptTailService#start} had
 * before the seam was introduced; it is now an injectable bean so the deterministic-gate
 * {@code replay} profile can substitute a fixture-backed source instead.
 *
 * <p>Active for every profile EXCEPT {@code replay} ({@code @Profile("!replay")}), so a
 * normal server run is unchanged and a replay run never spawns docker.
 */
@Component
@Profile("!replay")
public class DockerTailSource implements TailSource {

    private static final Logger LOG = LoggerFactory.getLogger(DockerTailSource.class);

    @Override
    public Tail open(int n, TailTarget target, int backfillLines) throws IOException {
        List<String> argv = TranscriptTailService.buildArgv(n, target, backfillLines);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(false);
        // Discard stderr so a chatty helper can never block on a full stderr pipe
        // (we never parse it); helper-side warnings are best-effort diagnostics.
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        LOG.debug("started transcript tail helper for n={} target={} (pid stream)", n, target);
        return new Tail(proc);
    }
}
