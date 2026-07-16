package com.aisandbox.server.workspace.dto;

/**
 * UC-98 — internal DTO for one selectable workspace project (a top-level
 * folder under the server's shared workspace root). Lives strictly inside the
 * {@code workspace} service / facade layers; the API layer has its own
 * {@code ApiDtos.WorkspaceProjectSummary} and a mapper at the controller
 * boundary (per {@code profile-java-server-architecture} rule 5). No
 * API-shaping annotations belong here.
 *
 * <p>Today {@code id} and {@code name} are both the folder's simple name: the
 * id is the stable selector carried end-to-end through the spawn request
 * (AC9) and re-validated against the live listing before injection (AC10), and
 * {@code name} is the folder name substituted into the setup prompt (AC5). They
 * are kept as distinct fields so a future listing rule (e.g. only git repos, or
 * a synthesized stable id) can diverge them without an API change (AC1).
 *
 * @param id   stable selector for the project (the folder's simple name today)
 * @param name display / folder name substituted into the setup prompt
 */
public record WorkspaceProject(String id, String name) {}
