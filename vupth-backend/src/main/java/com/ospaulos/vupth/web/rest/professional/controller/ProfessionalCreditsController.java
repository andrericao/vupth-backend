package com.ospaulos.vupth.web.rest.professional.controller;

import com.ospaulos.vupth.domain.campaign.service.CouponCreditService;
import com.ospaulos.vupth.domain.campaign.service.CouponCreditService.Credits;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** cupom-ativado-em-configuracoes: o que o app deve ao profissional pelos descontos dos cupons, e o proximo pagamento. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/professional/me/credits")
public class ProfessionalCreditsController {

    private final CouponCreditService service;

    @GetMapping
    public ResponseEntity<Credits> credits(@AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(service.creditsOf(user.getUserId()));
    }
}
