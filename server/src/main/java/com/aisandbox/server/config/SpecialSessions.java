package com.aisandbox.server.config;

/**
 * UC-62 — constants for the single "special" (non-Docker) session: the
 * always-on server host-shell row ("SERVER SSH SESSION").
 *
 * <p>The host-shell session is modelled as a regular {@code SessionRecord}
 * flowing through the existing list / stream / delete plumbing, but it is NOT
 * a Docker container — it is a tmux running a login shell on the management
 * server host itself (see {@code HostShellSessionService}). Two pieces of
 * identity distinguish it from real Claude sessions:
 *
 * <ul>
 *   <li>the {@link #SERVER_SSH_N} reserved id ({@code 0}) — chosen because the
 *       per-session counter ({@code spawn.sh}) seeds {@code 0} and increments
 *       <em>before</em> first use, so real Docker sessions are always
 *       {@code n >= 1}. {@code 0} therefore can never collide, and it sidesteps
 *       the {@code n < 0} guards in {@code ScriptExecutorService} (a negative
 *       sentinel would have slipped past them straight into {@code clean.sh} /
 *       {@code lifecycle.sh}).</li>
 *   <li>the {@code type} field on the session row ({@link #TYPE_CLAUDE} vs
 *       {@link #TYPE_SERVER_SSH}) — the authoritative discriminator in
 *       row-carrying contexts. Id-only contexts (stream attach, delete,
 *       lifecycle guards) key on {@code n == SERVER_SSH_N}.</li>
 * </ul>
 */
public final class SpecialSessions {

    private SpecialSessions() {}

    /** Reserved id for the server host-shell session. See class javadoc. */
    public static final int SERVER_SSH_N = 0;

    /** {@code type} value for an ordinary Claude/Docker session row. */
    public static final String TYPE_CLAUDE = "claude";

    /** {@code type} value for the server host-shell session row. */
    public static final String TYPE_SERVER_SSH = "server-ssh";
}
