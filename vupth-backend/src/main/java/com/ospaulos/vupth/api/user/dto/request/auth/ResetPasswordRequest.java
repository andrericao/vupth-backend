package com.ospaulos.vupth.api.user.dto.request.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo do {@code POST /auth/reset-password} (backlog #1). Consome o código de 6 dígitos recebido
 * por e-mail e define a nova senha. Código inválido/expirado -> 400 {@code RESET_CODE_INVALID} /
 * {@code RESET_CODE_EXPIRED}.
 */
public record ResetPasswordRequest(
        @NotBlank @Email String email,

        @NotBlank @Size(min = 6, max = 6) String code,

        @JsonProperty("new_password")
        @NotBlank String newPassword
) {
}
