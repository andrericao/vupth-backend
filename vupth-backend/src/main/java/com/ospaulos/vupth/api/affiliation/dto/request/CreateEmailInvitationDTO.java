package com.ospaulos.vupth.api.affiliation.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corpo de {@code POST /establishment/{id}/email-invitations} (convite-sem-conta). */
public record CreateEmailInvitationDTO(
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        @Size(max = 150, message = "email must be at most 150 characters")
        String email
) {
    /** Tira os espacos antes do @Email: " Fulano@X.com " e o mesmo e-mail, nao um invalido. */
    public CreateEmailInvitationDTO {
        email = email == null ? null : email.trim();
    }
}
