package com.aisandbox.server.models.dto;

/**
 * UC-66 — internal DTO for one advertised Claude model. Lives strictly
 * inside the service / facade layers; the API layer has its own
 * {@code ApiDtos.ModelSummary} and a mapper at the controller boundary
 * (per {@code profile-java-server-architecture} rule 5). No API-shaping
 * annotations belong here.
 *
 * @param id    model id/alias sent to Claude Code via {@code /model <id>}
 * @param label human-readable name shown in the client's model picker
 */
public record ModelDescriptor(String id, String label) {}
