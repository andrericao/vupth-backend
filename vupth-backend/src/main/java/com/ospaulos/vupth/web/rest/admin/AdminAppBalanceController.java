package com.ospaulos.vupth.web.rest.admin;

import com.ospaulos.vupth.domain.referral.service.AppBalanceService;
import com.ospaulos.vupth.domain.referral.service.AppBalanceService.Balance;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /admin/finance/app-balance — saldo do app, saldo devedor e bonificacao por pessoa (saldo-do-app-e-bonificacao, DEC-120). So
 * leitura, so ADMIN com a permissao FINANCE; o Backend calcula e mostra, o deposito na conta Asaas e do Andre.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/finance")
public class AdminAppBalanceController {

    private final AppBalanceService service;

    @GetMapping("/app-balance")
    public ResponseEntity<Balance> balance() {
        return ResponseEntity.ok(service.balance());
    }
}
