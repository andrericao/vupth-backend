package com.ospaulos.vupth.api.user.dto.request.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Corpo do {@code DELETE /profile/delete/me}.
 *
 * <p>{@code currentPassword} (JSON {@code current_password}) e OBRIGATORIO pelo mesmo motivo do
 * {@code PATCH /profile/update-password/me}: o app fica logado por muito tempo, e apagar a conta
 * e irreversivel — o token de acesso sozinho nao prova que quem pediu e o titular. Senha errada
 * responde {@code 401 CURRENT_PASSWORD_INVALID} e nada e alterado.</p>
 */
public record DeleteAccountRequest(
        @JsonProperty("current_password")
        @NotBlank String currentPassword
) {
}
