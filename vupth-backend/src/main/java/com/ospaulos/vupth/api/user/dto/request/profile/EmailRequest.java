package com.ospaulos.vupth.api.user.dto.request.profile;

import jakarta.validation.constraints.Email;

public record EmailRequest(
        @Email String email
) {
}
