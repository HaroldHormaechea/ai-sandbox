package com.aisandbox.server.enrollment.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.enrollment.dto.MintedBundle;
import com.aisandbox.server.enrollment.facade.EnrollmentFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/enrollment} (UC04 AC33) — the only mTLS-exempt path on
 * the server. The Android client POSTs an opaque token (previously issued
 * by {@code aisandboxctl client invite <name>}) and receives a freshly-
 * minted PKCS#12 bundle (private key + cert, empty transport passphrase)
 * as {@code application/octet-stream}.
 *
 * <p>Failure modes (RFC 9457 {@code application/problem+json}, mapped by
 * {@link com.aisandbox.server.api.error.ProblemDetailsAdvice}):
 *
 * <ul>
 *   <li>401 {@code enrollment_token_invalid}  — token unknown.</li>
 *   <li>401 {@code enrollment_token_expired}  — past expiry.</li>
 *   <li>401 {@code enrollment_token_redeemed} — already consumed.</li>
 *   <li>413 {@code payload_too_large}         — request body &gt; 256 B,
 *       caught at {@code RequestSizeLimitFilter}.</li>
 *   <li>429 {@code enrollment_rate_limited}   — per-IP cap tripped.</li>
 * </ul>
 *
 * <p>Per {@code profile-java-server-architecture} rule 6 this controller
 * calls a facade only — never reaches into the cert mint, token store,
 * or allowlist service directly.
 *
 * <p>Disabled under {@code docs-only} so OAS rendering does not need the
 * facade graph live.
 */
@RestController
@RequestMapping("/v1/enrollment")
@Profile("!docs-only")
public class EnrollmentController {

    private final EnrollmentFacade facade;

    public EnrollmentController(EnrollmentFacade facade) {
        this.facade = facade;
    }

    @Operation(
            summary = "Redeem a single-use enrollment token (UC04).",
            description = "The only mTLS-exempt path on the server. Returns a freshly-minted PKCS#12 bundle "
                    + "(private key + cert, empty transport passphrase). The newly-minted public cert is "
                    + "written to the server allowlist before the response is returned, so the client can "
                    + "immediately use the new identity on the next request.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Bundle minted.",
                content =
                        @Content(
                                mediaType = "application/octet-stream",
                                schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(
                responseCode = "401",
                description = "Token invalid, expired, or already redeemed.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
                responseCode = "413",
                description = "Request body too large.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
                responseCode = "429",
                description = "Per-IP rate limit tripped.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> redeem(
            @Valid @RequestBody ApiDtos.EnrollmentRequest req, ServerHttpRequest httpRequest) throws Exception {
        String sourceIp = extractSourceIp(httpRequest);
        MintedBundle bundle = facade.redeem(req.token(), sourceIp);
        String filename = bundle.name() + ".p12";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(bundle.pkcs12().length);
        headers.setContentDispositionFormData("attachment", filename);
        // The PKCS#12 transport passphrase is empty — surface that as an
        // out-of-band header so the client can sanity-check without
        // hard-coding the convention. The header is informational only;
        // protocol semantics are unaffected.
        headers.set("X-AI-Sandbox-P12-Passphrase", "");
        return new ResponseEntity<>(bundle.pkcs12(), headers, HttpStatus.CREATED);
    }

    private static String extractSourceIp(ServerHttpRequest req) {
        if (req == null || req.getRemoteAddress() == null) {
            return null;
        }
        // server.forward-headers-strategy=native makes Spring respect
        // X-Forwarded-For when present; getRemoteAddress() returns the
        // canonical client IP either way.
        return req.getRemoteAddress().getAddress().getHostAddress();
    }
}
