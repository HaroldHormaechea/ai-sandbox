package com.aisandbox.server.serverupdate.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.cli.secrets.ServerVersion;
import com.aisandbox.server.serverupdate.dto.ApplyResult;
import com.aisandbox.server.serverupdate.dto.ReleaseInfo;
import com.aisandbox.server.serverupdate.dto.UpdateStatus;
import com.aisandbox.server.serverupdate.service.GitHubReleaseService;
import com.aisandbox.server.serverupdate.service.ServerUpdateException;
import com.aisandbox.server.serverupdate.service.UpdateTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * UC-84 — use-case boundary for server self-update (AC4-AC16).
 *
 * <p>Same-domain facade: it composes the two {@code serverupdate} services
 * directly (profile-java-server-architecture rule 6 — a single-domain
 * operation needs no intermediate facade). No DB ⇒ no {@code @Transactional}.
 * Both operations emit an audit line (AC). The privileged install + restart is
 * performed entirely by the independent root {@code ai-sandbox-updater} unit —
 * this facade only (a) reads GitHub unauthenticated and (b) emits the
 * parameter-free trigger; it never runs {@code dpkg}/{@code apt}/{@code systemctl}
 * and forwards no data to the updater (AC8/AC11).
 */
@Component
public class ServerUpdateFacade {

    private static final Logger LOG = LoggerFactory.getLogger(ServerUpdateFacade.class);

    private final GitHubReleaseService github;
    private final UpdateTriggerService trigger;
    private final AuditLogger audit;

    public ServerUpdateFacade(GitHubReleaseService github, UpdateTriggerService trigger, AuditLogger audit) {
        this.github = github;
        this.trigger = trigger;
        this.audit = audit;
    }

    /**
     * Check GitHub for a newer {@code server-v*} release and compare it with the
     * running version. Degrades to {@code updateAvailable=false} (never throws)
     * when the server runs outside a packaged jar ({@code ServerVersion.current()
     * == "dev"}) — that path does not even contact GitHub.
     */
    public UpdateStatus check() {
        String current = ServerVersion.current();
        if (ServerVersion.DEV_FALLBACK.equals(current)) {
            // Dev / test runtime: no manifest version to compare, so there is
            // nothing to offer. Never throw (risk note).
            audit.logEvent(
                    AuditAction.SERVER_UPDATE_CHECK, "ok", "current", current, "latest", "n/a", "updateAvailable", false);
            return new UpdateStatus(current, null, false, null, null);
        }
        try {
            ReleaseInfo latest = github.latestServerRelease();
            if (latest == null) {
                audit.logEvent(
                        AuditAction.SERVER_UPDATE_CHECK,
                        "ok",
                        "current",
                        current,
                        "latest",
                        "none",
                        "updateAvailable",
                        false);
                return new UpdateStatus(current, current, false, null, null);
            }
            boolean updateAvailable = GitHubReleaseService.compareSemver(latest.version(), current) > 0;
            if (updateAvailable && latest.debAssetUrl() == null) {
                // A newer release exists but ships no amd64 .deb — surfaced as a
                // clear error rather than offering an un-installable update (AC14).
                throw new ServerUpdateException.NoAsset(
                        "server-v" + latest.version() + " has no " + GitHubReleaseService.DEB_ASSET_SUFFIX + " asset");
            }
            audit.logEvent(
                    AuditAction.SERVER_UPDATE_CHECK,
                    "ok",
                    "current",
                    current,
                    "latest",
                    latest.version(),
                    "updateAvailable",
                    updateAvailable);
            return new UpdateStatus(
                    current, latest.version(), updateAvailable, latest.htmlUrl(), latest.debAssetUrl());
        } catch (ServerUpdateException e) {
            audit.logEvent(AuditAction.SERVER_UPDATE_CHECK, "fail", "current", current, "error", e.getClass()
                    .getSimpleName());
            throw e;
        }
    }

    /**
     * Emit the parameter-free update trigger. The marker write is the critical,
     * privileged action and runs FIRST so the response does not depend on GitHub
     * reachability; the target version is then resolved best-effort purely for
     * the client's "updating…" copy (informational — the updater self-determines
     * its real target). Responds promptly (AC9): it never waits for the install
     * or the restart.
     */
    public ApplyResult apply() {
        trigger.requestUpdate();
        String target = resolveTargetBestEffort();
        audit.logEvent(AuditAction.SERVER_UPDATE_APPLY, "ok", "target", target == null ? "latest" : target);
        return new ApplyResult(true, target);
    }

    private String resolveTargetBestEffort() {
        String current = ServerVersion.current();
        if (ServerVersion.DEV_FALLBACK.equals(current)) {
            return null;
        }
        try {
            ReleaseInfo latest = github.latestServerRelease();
            return latest == null ? null : latest.version();
        } catch (RuntimeException e) {
            // Never let a GitHub hiccup turn a successful trigger into a failure
            // (the trigger is already written; the updater needs nothing from us).
            LOG.info("apply(): best-effort target lookup failed ({}); reporting null target", e.toString());
            return null;
        }
    }
}
