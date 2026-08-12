package com.ospaulos.vupth.api.user.dto.request.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.user.model.SocialProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * O app envia o id-token que recebeu do provedor; quem valida e emite os nossos
 * tokens e o backend. O app nunca usa o token do provedor para falar com a API.
 */
public record SocialLoginRequest(
        @NotNull SocialProvider provider,
        @JsonProperty("id_token")
        @NotBlank String idToken
) {
}
