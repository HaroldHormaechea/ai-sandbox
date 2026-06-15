package com.aisandbox.server.stream.replay;

import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.service.SyntheticSessionSource;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * UC-85 — exposes the {@code replay} fixture catalog's synthetic sessions to {@link
 * com.aisandbox.server.sessions.service.SessionRegistryService}. Active only under the
 * {@code replay} profile; reports {@link #exclusive()} as {@code true} so the registry returns
 * these deterministic records without running {@code docker} (there is none in a replay run).
 */
@Component
@Profile("replay")
public class ReplaySyntheticSessions implements SyntheticSessionSource {

    private final ReplaySessionCatalog catalog;

    public ReplaySyntheticSessions(ReplaySessionCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<SessionRecord> records() {
        return catalog.syntheticRecords();
    }

    @Override
    public boolean exclusive() {
        return true;
    }
}
