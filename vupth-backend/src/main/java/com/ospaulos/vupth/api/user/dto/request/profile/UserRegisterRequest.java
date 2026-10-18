package com.ospaulos.vupth.api.user.dto.request.profile;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Registration request.
 *
 * @Email prevents Jackson coercion of numeric literals (e.g. 12345) from being
 * accepted as a valid email — they pass @NotNull (coerced to "12345") but fail
 * @Email validation, returning 422 VALIDATION_ERROR instead of silently
 * registering "12345" as an email or crashing with 500.
 *
 * @since v5
 * @compat @Email added v8 — register_emailAsNumber test now expects 422
 */
public record UserRegisterRequest(

        @NotNull(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        String email,

        // O minimo e o maximo vem da PasswordPolicy (DEC-79): 422 PASSWORD_TOO_SHORT / PASSWORD_TOO_LONG com min_length e
        // max_length, o mesmo code do cadastro, da troca e da redefinicao.
        @NotNull(message = "Password is required")
        String password) {
}
