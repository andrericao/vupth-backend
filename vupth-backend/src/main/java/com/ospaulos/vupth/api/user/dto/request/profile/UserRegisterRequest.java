package com.ospaulos.vupth.api.user.dto.request.profile;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

        @NotNull(message = "Password is required")
        @Size(min = 6, message = "Password must be at least 6 characters")
        String password) {
}
