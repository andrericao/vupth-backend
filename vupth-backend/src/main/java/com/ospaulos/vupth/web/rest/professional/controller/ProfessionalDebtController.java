package com.ospaulos.vupth.web.rest.professional.controller;

import com.ospaulos.vupth.domain.debt.service.ProfessionalDebtService;
import com.ospaulos.vupth.domain.debt.service.ProfessionalDebtService.Debt;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** divida-por-fora-e-abatimento: o que o profissional deve ao app pelos cortes pagos por fora, o limite e o Pix de cobranca. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/professional/me/debt")
public class ProfessionalDebtController {

    private final ProfessionalDebtService service;

    @GetMapping
    public ResponseEntity<Debt> debt(@AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(service.debtOf(user.getUserId()));
    }
}
