package com.ospaulos.vupth.api.user.dto.request.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Corpo do {@code POST /auth/forgot-password} (backlog #1). Dispara o envio de um código de reset
 * para o e-mail — a resposta é sempre 200, exista o e-mail ou não (anti-enumeração).
 */
public record ForgotPasswordRequest(
        @NotBlank @Email String email
) {
}
