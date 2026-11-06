package com.ospaulos.vupth.api.feature.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Troca a regra inteira da chave. {@code regions}: UF ou cidade do endereco principal da conta. {@code reason} e obrigatorio.
 * {@code totp}: o codigo do segundo fator, conferido a cada mudanca; sem ele, 403.
 */
public record AppFeatureUpdateRequest(
        @NotNull Boolean enabled,
        @NotNull @Min(0) @Max(100) Integer percent,
        @NotNull List<@NotNull @Pattern(regexp = "CLIENT|PROFESSIONAL|ESTABLISHMENT") String> profiles,
        @NotNull List<@NotBlank @Pattern(regexp = "[^,]+") @Size(max = 60) String> regions,
        @NotNull List<@NotNull UUID> accounts,
        @NotBlank @Size(max = 300) String reason,
        @Size(max = 40) String totp) {
}
