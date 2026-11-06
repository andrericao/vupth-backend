package com.ospaulos.vupth.web.rest.feature;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.feature.ProfessionalFeeService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** chave-profissional-paga-taxa: o modal unico do profissional e a tabela de taxas do Asaas no painel (permissao FINANCE). */
@RestController
@RequiredArgsConstructor
public class ProfessionalFeeController {

    private final ProfessionalFeeService service;

    public record NoticeResponse(@JsonProperty("show") boolean show, @JsonProperty("fees_active") boolean feesActive) {
    }

    public record GatewayFees(
            @JsonProperty("pix_fee") @NotNull @DecimalMin("0") BigDecimal pixFee,
            @JsonProperty("card_fixed_fee") @NotNull @DecimalMin("0") BigDecimal cardFixedFee,
            @JsonProperty("card_rate") @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal cardRate) {
    }

    @GetMapping("/professional/me/fee-notice")
    public ResponseEntity<NoticeResponse> notice(@AuthenticationPrincipal UserDetailsImpl user) {
        var notice = service.notice(user.getUserId());
        return ResponseEntity.ok(new NoticeResponse(notice.show(), notice.feesActive()));
    }

    @PostMapping("/professional/me/fee-notice/seen")
    public ResponseEntity<Void> seen(@AuthenticationPrincipal UserDetailsImpl user) {
        service.seen(user.getUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/settings/asaas-fees")
    public ResponseEntity<GatewayFees> fees() {
        return ResponseEntity.ok(toDto(service.gatewayTable()));
    }

    @PutMapping("/admin/settings/asaas-fees")
    public ResponseEntity<GatewayFees> setFees(@RequestBody @Valid GatewayFees body, @AuthenticationPrincipal UserDetailsImpl admin) {
        return ResponseEntity.ok(toDto(service.setGatewayTable(
                new ProfessionalFeeService.GatewayTable(body.pixFee(), body.cardFixedFee(), body.cardRate()), admin.getUserId())));
    }

    private static GatewayFees toDto(ProfessionalFeeService.GatewayTable table) {
        return new GatewayFees(table.pixFee(), table.cardFixedFee(), table.cardRate());
    }
}
