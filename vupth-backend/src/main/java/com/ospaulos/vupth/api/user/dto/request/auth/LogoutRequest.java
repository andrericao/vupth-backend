package com.ospaulos.vupth.api.user.dto.request.auth;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
        @NotBlank(message = "Refresh Token is required!")
        String refreshToken
) {
}
