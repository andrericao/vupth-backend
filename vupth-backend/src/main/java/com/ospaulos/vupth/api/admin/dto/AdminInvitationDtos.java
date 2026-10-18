package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.admin.model.AdminPermission;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Os corpos e respostas do convite e da gestao dos usuarios do painel (convite-com-permissoes). */
public final class AdminInvitationDtos {

    private AdminInvitationDtos() {
    }

    public record InviteRequest(
            @JsonProperty("email") @NotBlank @Email @Size(max = 150) String email,
            @JsonProperty("permissions") @NotNull List<AdminPermission> permissions) {}

    public record AcceptRequest(
            @JsonProperty("token") @NotBlank @Size(max = 200) String token,
            @JsonProperty("password") @NotBlank @Size(max = 200) String password) {}

    public record PermissionsRequest(@JsonProperty("permissions") @NotNull List<AdminPermission> permissions) {}

    public record InvitationResponse(
            @JsonProperty("id") UUID id,
            @JsonProperty("email") String email,
            @JsonProperty("permissions") List<AdminPermission> permissions,
            @JsonProperty("expires_at") OffsetDateTime expiresAt) {}

    public record AcceptResponse(
            @JsonProperty("email") String email,
            @JsonProperty("next") String next) {}

    public record AdminMember(
            @JsonProperty("id") UUID id,
            @JsonProperty("name") String name,
            @JsonProperty("email") String email,
            @JsonProperty("owner") boolean owner,
            @JsonProperty("permissions") List<AdminPermission> permissions,
            @JsonProperty("totp_enabled") boolean totpEnabled) {}
}
