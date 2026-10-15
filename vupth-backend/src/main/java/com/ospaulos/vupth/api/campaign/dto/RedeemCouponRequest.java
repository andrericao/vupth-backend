package com.ospaulos.vupth.api.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** O cupom digitado (Perfil > Promocoes, no Client e no Partner). */
public record RedeemCouponRequest(@NotBlank @Size(max = 40) String code) {
}
