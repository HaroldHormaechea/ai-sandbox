package com.aisandbox.server.serverupdate.service;

/**
 * UC-84 — typed failures from the server-self-update domain (check + apply).
 *
 * <p>These deliberately carry NO reference to {@code api.error.ErrorCode} or
 * any {@code api.*} type. The {@code api} package depends on this package (the
 * controller imports the facade), so importing {@code ErrorCode} here would
 * form an {@code api ↔ serverupdate} package cycle — which
 * {@code LayeringTest.no_cycles_between_top_level_feature_packages()} forbids
 * (same constraint that keeps {@code MtlsEnforcementFilter} in {@code api}
 * rather than {@code identity}). The mapping from each subtype to its HTTP
 * status + {@link com.aisandbox.server.api.error.ErrorCode} lives in
 * {@code api.error.ProblemDetailsAdvice}, which is allowed to depend on this
 * package.
 *
 * <p>Every subtype maps to a clean Problem-Details response while the server
 * keeps running on its current version (AC14).
 */
public abstract sealed class ServerUpdateException extends RuntimeException
        permits ServerUpdateException.GitHubUnreachable,
                ServerUpdateException.RateLimited,
                ServerUpdateException.NoAsset,
                ServerUpdateException.CheckFailed,
                ServerUpdateException.TriggerFailed {

    protected ServerUpdateException(String message) {
        super(message);
    }

    protected ServerUpdateException(String message, Throwable cause) {
        super(message, cause);
    }

    /** GitHub Releases API unreachable (DNS/connect/read failure or timeout). → 502. */
    public static final class GitHubUnreachable extends ServerUpdateException {
        public GitHubUnreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Unauthenticated GitHub API rate limit hit (HTTP 403, budget exhausted). → 429. No token fallback (AC13). */
    public static final class RateLimited extends ServerUpdateException {
        public RateLimited(String message) {
            super(message);
        }
    }

    /** A newer release exists but ships no matching {@code *_amd64.deb} asset (wrong arch). → 502. */
    public static final class NoAsset extends ServerUpdateException {
        public NoAsset(String message) {
            super(message);
        }
    }

    /** GitHub responded but the result could not be understood (bad status, unparseable body). → 502. */
    public static final class CheckFailed extends ServerUpdateException {
        public CheckFailed(String message) {
            super(message);
        }

        public CheckFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The parameter-free update trigger marker could not be written (I/O failure). → 500. */
    public static final class TriggerFailed extends ServerUpdateException {
        public TriggerFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
