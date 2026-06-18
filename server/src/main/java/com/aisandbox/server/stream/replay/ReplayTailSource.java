package com.aisandbox.server.stream.replay;

import com.aisandbox.server.stream.service.TranscriptTailService.Tail;
import com.aisandbox.server.stream.service.TranscriptTailService.TailSource;
import com.aisandbox.server.stream.service.TranscriptTailService.TailTarget;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * UC-85 — the {@code replay}-profile {@link TailSource}. Instead of spawning the docker
 * helper it returns a {@link Tail} backed by a {@link ReplayEnvelopeReader} over the
 * scenario's committed fixture, so the pump → {@code ConversationEventMapper} → WS-emit path
 * is byte-identical to production while the transcript SOURCE is a recorded file.
 *
 * <p>Active only under {@code replay} ({@code @Profile("replay")}); {@link DockerTailSource}
 * is {@code @Profile("!replay")}, so exactly one {@code TailSource} bean is ever wired.
 */
@Component
@Profile("replay")
public class ReplayTailSource implements TailSource {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayTailSource.class);

    private final ReplaySessionCatalog catalog;
    private final ReplayAnswerSink answerSink;
    private final ReplayProperties props;

    public ReplayTailSource(ReplaySessionCatalog catalog, ReplayAnswerSink answerSink, ReplayProperties props) {
        this.catalog = catalog;
        this.answerSink = answerSink;
        this.props = props;
    }

    @Override
    public Tail open(int n, TailTarget target, int backfillLines) throws IOException {
        Optional<ReplaySessionCatalog.Scenario> scenario = catalog.byN(n);
        if (scenario.isEmpty()) {
            throw new IOException("replay: no fixture scenario for session n=" + n);
        }
        LOG.info(
                "replay tail opening for n={} scenario={} ({} lines, backfill={})",
                n,
                scenario.get().target(),
                scenario.get().lines().size(),
                backfillLines);
        ReplayEnvelopeReader reader =
                new ReplayEnvelopeReader(scenario.get().lines(), n, answerSink, props.answerTimeoutMs());
        return new Tail(reader);
    }
}
