package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.serverupdate.dto.ApplyResult;
import com.aisandbox.server.serverupdate.dto.UpdateStatus;
import com.aisandbox.server.serverupdate.facade.ServerUpdateFacade;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * UC-84 — the {@link ServerUpdateController} HTTP-mapping seam (AC4, AC6, AC9).
 * The controller is a thin facade-only layer (profile-java-server-architecture
 * rule 6); these tests pin that it maps the internal {@code serverupdate.dto}
 * results to the {@code api.dto} response records field-for-field and returns
 * {@code 200 OK}.
 */
class ServerUpdateControllerTest {

    private final ServerUpdateFacade facade = mock(ServerUpdateFacade.class);
    private final ServerUpdateController controller = new ServerUpdateController(facade);

    @Test
    void check_maps_update_status_to_api_response() {
        when(facade.check())
                .thenReturn(new UpdateStatus(
                        "0.0.9", "0.0.10", true, "https://gh/server-v0.0.10", "https://gh/d/0.0.10_amd64.deb"));

        ResponseEntity<?> response = controller.check();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(ApiDtos.UpdateCheckResponse.class);
        ApiDtos.UpdateCheckResponse body = (ApiDtos.UpdateCheckResponse) response.getBody();
        assertThat(body.currentVersion()).isEqualTo("0.0.9");
        assertThat(body.latestVersion()).isEqualTo("0.0.10");
        assertThat(body.updateAvailable()).isTrue();
        // AC6 — the Changelog external-browser link is the release HTML URL.
        assertThat(body.releaseHtmlUrl()).isEqualTo("https://gh/server-v0.0.10");
        assertThat(body.debAssetUrl()).isEqualTo("https://gh/d/0.0.10_amd64.deb");
    }

    @Test
    void check_up_to_date_maps_without_update_button_fields() {
        when(facade.check()).thenReturn(new UpdateStatus("0.0.10", "0.0.10", false, null, null));

        ResponseEntity<?> response = controller.check();

        ApiDtos.UpdateCheckResponse body = (ApiDtos.UpdateCheckResponse) response.getBody();
        assertThat(body.updateAvailable()).isFalse();
        assertThat(body.releaseHtmlUrl()).isNull();
    }

    @Test
    void apply_maps_apply_result_to_api_response() {
        when(facade.apply()).thenReturn(new ApplyResult(true, "0.0.10"));

        ResponseEntity<?> response = controller.apply();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(ApiDtos.UpdateApplyResponse.class);
        ApiDtos.UpdateApplyResponse body = (ApiDtos.UpdateApplyResponse) response.getBody();
        // AC9 — prompt ack with accepted=true + best-effort target version.
        assertThat(body.accepted()).isTrue();
        assertThat(body.targetVersion()).isEqualTo("0.0.10");
    }
}
