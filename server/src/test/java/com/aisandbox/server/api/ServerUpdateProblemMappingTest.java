package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.serverupdate.service.ServerUpdateException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/**
 * UC-84 AC14 — every typed {@link ServerUpdateException} subtype maps to a
 * clean {@code application/problem+json} response with the right HTTP status +
 * stable lowercase {@code code}, while the server keeps running on its current
 * version (the advice only translates; it never rethrows fatally).
 *
 * <p>Pins the sealed-switch in {@link ProblemDetailsAdvice#handleServerUpdate}.
 */
class ServerUpdateProblemMappingTest {

    private final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();

    @Test
    void github_unreachable_maps_to_502_update_github_unreachable() {
        ProblemDetail pd = advice.handleServerUpdate(new ServerUpdateException.GitHubUnreachable("dns down", null));
        assertThat(pd.getStatus()).isEqualTo(502);
        assertThat(pd.getProperties()).containsEntry("code", "update_github_unreachable");
        assertThat(pd.getType()).isEqualTo(URI.create("https://ai-sandbox.dev/problems/update_github_unreachable"));
    }

    @Test
    void rate_limited_maps_to_429_update_rate_limited() {
        ProblemDetail pd = advice.handleServerUpdate(new ServerUpdateException.RateLimited("slow down"));
        assertThat(pd.getStatus()).isEqualTo(429);
        assertThat(pd.getProperties()).containsEntry("code", "update_rate_limited");
    }

    @Test
    void no_asset_maps_to_502_update_no_asset() {
        ProblemDetail pd = advice.handleServerUpdate(new ServerUpdateException.NoAsset("no amd64 deb"));
        assertThat(pd.getStatus()).isEqualTo(502);
        assertThat(pd.getProperties()).containsEntry("code", "update_no_asset");
    }

    @Test
    void trigger_failed_maps_to_500_update_trigger_failed() {
        ProblemDetail pd = advice.handleServerUpdate(new ServerUpdateException.TriggerFailed("io", null));
        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getProperties()).containsEntry("code", "update_trigger_failed");
    }

    @Test
    void check_failed_maps_to_502_update_check_failed() {
        ProblemDetail pd = advice.handleServerUpdate(new ServerUpdateException.CheckFailed("weird response"));
        assertThat(pd.getStatus()).isEqualTo(502);
        assertThat(pd.getProperties()).containsEntry("code", "update_check_failed");
    }
}
