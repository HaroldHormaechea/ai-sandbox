package com.aisandbox.server.stream.replay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UC-85 — a {@link BufferedReader} that replays a recorded fixture's envelope lines to the
 * conversation tail pump, line by line, exactly as the docker helper's stdout would. It is
 * the {@code Reader} half of a fixture-backed {@link
 * com.aisandbox.server.stream.service.TranscriptTailService.Tail}.
 *
 * <p>Two replay-only behaviours, both invisible to the pump:
 *
 * <ul>
 *   <li>{@code __replay__\tawait-answer} directives are <b>consumed here</b> (never returned to
 *       the pump): the reader parks on {@link ReplayAnswerSink#awaitAnswer} until the device's
 *       answer is recorded for this session (or the gate times out / the stream closes), then
 *       continues with the recorded post-answer frames. This is what makes the
 *       question→answer→resume ordering deterministic and faithful (the resume frames replay
 *       AFTER the answer, like a real Claude turn).</li>
 *   <li>At end of fixture {@link #readLine()} returns {@code null} (EOF), which the pump treats
 *       as normal teardown.</li>
 * </ul>
 *
 * <p>{@link #close()} (called by the {@code Tail} on stream close) flips the closed flag and
 * releases any in-flight answer wait so the pump thread unparks promptly.
 */
public final class ReplayEnvelopeReader extends BufferedReader {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayEnvelopeReader.class);

    private final List<String> lines;
    private final int n;
    private final ReplayAnswerSink sink;
    private final long answerTimeoutMs;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private int idx = 0;

    public ReplayEnvelopeReader(List<String> lines, int n, ReplayAnswerSink sink, long answerTimeoutMs) {
        // The base reader is unused — every read goes through the overridden readLine().
        super(new StringReader(""));
        this.lines = lines;
        this.n = n;
        this.sink = sink;
        this.answerTimeoutMs = answerTimeoutMs;
    }

    @Override
    public String readLine() throws IOException {
        while (true) {
            if (closed.get()) {
                return null;
            }
            if (idx >= lines.size()) {
                return null; // EOF — fixture exhausted
            }
            String line = lines.get(idx++);
            if (line == null || line.isEmpty()) {
                continue; // skip blank separator lines
            }
            int tab = line.indexOf('\t');
            String source = tab < 0 ? line : line.substring(0, tab);
            if (ReplayFixtureValidator.REPLAY_DIRECTIVE_SOURCE.equals(source)) {
                String payload = tab < 0 ? "" : line.substring(tab + 1);
                String directive = payload.indexOf('\t') < 0 ? payload : payload.substring(0, payload.indexOf('\t'));
                if (ReplayFixtureValidator.DIRECTIVE_AWAIT_ANSWER.equals(directive.trim())) {
                    LOG.info("replay tail n={} parked at await-answer gate", n);
                    boolean answered = sink.awaitAnswer(n, answerTimeoutMs);
                    LOG.info("replay tail n={} resuming (answered={} closed={})", n, answered, closed.get());
                }
                continue; // never hand a replay directive to the pump
            }
            return line; // a real envelope line (__ctrl__ control or <source>\t<json>)
        }
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            sink.release(n); // unpark a pump thread waiting at the answer gate
        }
        super.close();
    }
}
