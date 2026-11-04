package com.ospaulos.vupth.api.feedback.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /feedback (feedback-por-email, DEC-167): o texto curto do usuario logado, a categoria (opcional) e de qual
 * app veio. A versao e o aparelho sao informados pelo app e so entram no e-mail.
 */
public record FeedbackRequest(
        @NotBlank @Size(max = 1000) String text,
        @Pattern(regexp = "SUGESTAO|PROBLEMA|ELOGIO|OUTRO", message = "category deve ser SUGESTAO, PROBLEMA, ELOGIO ou OUTRO")
        String category,
        @NotBlank @Pattern(regexp = "CLIENT|PARTNER", message = "app deve ser CLIENT ou PARTNER") String app,
        @JsonProperty("app_version") @Size(max = 40) String appVersion,
        @Size(max = 120) String device
) {
}
