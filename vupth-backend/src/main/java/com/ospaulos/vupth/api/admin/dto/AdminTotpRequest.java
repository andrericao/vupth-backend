package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** O codigo do app (6 digitos) ou um codigo de recuperacao ({@code ABCDE-FGHIJ}). */
public record AdminTotpRequest(@JsonProperty("code") @NotBlank @Size(max = 20) String code) {
}
