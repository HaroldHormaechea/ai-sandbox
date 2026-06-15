package com.aisandbox.server.serverupdate.dto;

/**
 * UC-84 — internal result of an apply request (profile-java-server-architecture
 * rule 5). {@code ApiMappers} maps it to {@code ApiDtos.UpdateApplyResponse}.
 *
 * @param accepted      always true when returned (a failure throws instead).
 * @param targetVersion best-effort latest {@code server-v*} version for the
 *                      client's "updating…" copy, or {@code null} when it could
 *                      not be resolved. Informational only: the privileged
 *                      updater self-determines its real target (AC8/AC11) — the
 *                      server forwards nothing to it.
 */
public record ApplyResult(boolean accepted, String targetVersion) {}
