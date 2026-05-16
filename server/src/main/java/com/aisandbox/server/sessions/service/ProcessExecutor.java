package com.aisandbox.server.sessions.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Argv-array-only process executor with bounded output capture and a
 * timeout-driven kill. Used for every host-script and {@code docker compose}
 * invocation. Never invokes a shell; never interpolates strings.
 *
 * <p>Output buffers are capped at 64 KiB each (stdout, stderr). Output
 * beyond the cap is discarded; a sentinel marker is appended so callers
 * can detect truncation.
 */
@Component
public class ProcessExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessExecutor.class);
    private static final int MAX_CAPTURE_BYTES = 64 * 1024;
    private static final String TRUNC_MARK = "\n…[truncated]";

    public record Result(int exitCode, String stdout, String stderr) {}

    public Result run(List<String> argv, Path workingDir, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(false);
        Process p = pb.start();
        try (var pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ai-sandbox-proc-io");
            t.setDaemon(true);
            return t;
        })) {
            Future<String> outFut = pool.submit(() -> drain(p.getInputStream()));
            Future<String> errFut = pool.submit(() -> drain(p.getErrorStream()));
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroy();
                p.waitFor(2, TimeUnit.SECONDS);
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
                throw new TimeoutException("exec timeout: " + argv.get(0));
            }
            String stdout = outFut.get(2, TimeUnit.SECONDS);
            String stderr = errFut.get(2, TimeUnit.SECONDS);
            return new Result(p.exitValue(), stdout, stderr);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            p.destroy();
            throw new IOException("interrupted while running " + argv.get(0), ie);
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("Process output capture failed for {}: {}", argv.get(0), e.toString());
            throw new IOException(e);
        }
    }

    private static String drain(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        var baos = new java.io.ByteArrayOutputStream();
        int n;
        boolean truncated = false;
        while ((n = in.read(buf)) != -1) {
            int remaining = MAX_CAPTURE_BYTES - baos.size();
            if (remaining <= 0) {
                truncated = true;
                // keep draining so the writer side doesn't block on a full pipe
                continue;
            }
            baos.write(buf, 0, Math.min(n, remaining));
            if (baos.size() >= MAX_CAPTURE_BYTES) {
                truncated = true;
            }
        }
        String s = baos.toString(StandardCharsets.UTF_8);
        return truncated ? s + TRUNC_MARK : s;
    }
}
