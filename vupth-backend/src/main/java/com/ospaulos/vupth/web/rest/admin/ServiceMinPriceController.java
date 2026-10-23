package com.ospaulos.vupth.web.rest.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.setting.AppSettingService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * valor-minimo-do-servico (DEC-121): o menor preco de servico sem aviso (R$ 5,00 por padrao), numa flag de configuracao. O Partner
 * le o minimo em {@code GET /settings/service-min-price} (qualquer usuario logado); o Painel muda em {@code /admin/settings/service-min-price}.
 */
@RestController
@RequiredArgsConstructor
public class ServiceMinPriceController {

    private final AppSettingService settings;

    public record MinPrice(@NotNull @DecimalMin("0.00") @JsonProperty("min_price") BigDecimal minPrice) {}

    @GetMapping("/settings/service-min-price")
    public ResponseEntity<MinPrice> read() {
        return ResponseEntity.ok(new MinPrice(settings.serviceMinPrice()));
    }

    @GetMapping("/admin/settings/service-min-price")
    public ResponseEntity<MinPrice> adminRead() {
        return read();
    }

    @PutMapping("/admin/settings/service-min-price")
    public ResponseEntity<MinPrice> adminWrite(@AuthenticationPrincipal UserDetailsImpl user, @RequestBody @Valid MinPrice body) {
        settings.setDecimal(AppSettingService.SERVICE_MIN_PRICE, body.minPrice(), user.getUserId());
        return read();
    }
}
