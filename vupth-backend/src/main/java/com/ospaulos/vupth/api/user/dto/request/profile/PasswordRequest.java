package com.ospaulos.vupth.api.user.dto.request.profile;

import jakarta.validation.constraints.NotBlank;

public record PasswordRequest(
        @NotBlank String password
) {
}
