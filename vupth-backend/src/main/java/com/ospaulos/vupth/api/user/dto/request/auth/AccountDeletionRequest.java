package com.ospaulos.vupth.api.user.dto.request.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Corpo do {@code POST /auth/account-deletion/request}: o e-mail da conta a excluir. */
public record AccountDeletionRequest(
        @NotBlank @Email String email
) {
    public AccountDeletionRequest {
        email = email == null ? null : email.trim();
    }
}
