package com.aisandbox.server.clients.dto;

import java.math.BigInteger;
import java.time.Instant;

/**
 * Internal DTO describing one allowlisted client cert. Lives strictly
 * inside the service / facade layers; the API layer has its own
 * {@code ClientListResponseDto} and a mapper at the controller boundary.
 *
 * @param name           filename stem ({@code <name>.crt} in the allowlist folder)
 * @param cn             certificate Common Name
 * @param fingerprintHex SHA-256 over the DER, lowercase hex
 * @param serial         certificate serial number
 * @param addedAt        filesystem mtime of the cert file
 */
public record AllowedClient(String name, String cn, String fingerprintHex, BigInteger serial, Instant addedAt) {}
