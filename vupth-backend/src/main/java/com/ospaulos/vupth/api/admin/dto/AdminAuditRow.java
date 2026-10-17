package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma linha da auditoria do painel (GET /admin/audit). */
public record AdminAuditRow(
        @JsonProperty("id") UUID id,
        @JsonProperty("admin_id") UUID adminId,
        @JsonProperty("admin_email") String adminEmail,
        @JsonProperty("method") String method,
        @JsonProperty("path") String path,
        @JsonProperty("query") String query,
        @JsonProperty("body") String body,
        @JsonProperty("status") int status,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
}
