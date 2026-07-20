package com.ospaulos.vupth.api.user.dto.request.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Corpo do {@code PATCH /profile/update-password/me}.
 *
 * <p>{@code currentPassword} (JSON {@code current_password}) e OBRIGATORIO: trocar a senha exige
 * provar a senha atual, nao basta o token de acesso (Client #13). Se nao conferir, o endpoint
 * responde {@code 401 CURRENT_PASSWORD_INVALID} e nada e alterado.</p>
 */
public record PasswordRequest(
        @JsonProperty("current_password")
        @NotBlank String currentPassword,

        @NotBlank String password
) {
}
