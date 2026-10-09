package com.ospaulos.vupth.api.user.dto.request.auth;

import jakarta.validation.constraints.NotBlank;

/** Corpo do {@code POST /auth/account-deletion/confirm}: o token do link e a senha da conta. */
public record AccountDeletionConfirmRequest(
        @NotBlank String token,
        @NotBlank String password
) {
}
