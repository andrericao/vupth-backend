package com.ospaulos.vupth.web.rest.admin;

import com.ospaulos.vupth.api.client.dto.response.PenaltyPaymentResponse;
import com.ospaulos.vupth.domain.client.service.PenaltyPaymentService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * As multas pagas avulso, a confirmar no painel (pagar-multa-avulso, DEC-83): a multa e do app, e o Pix cai na conta do
 * app; o André confere e confirma aqui. So ADMIN, com a permissao FINANCE.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/penalty-payments")
public class AdminPenaltyPaymentController {

    private final PenaltyPaymentService service;

    @GetMapping
    public ResponseEntity<List<PenaltyPaymentResponse>> awaitingConfirmation() {
        return ResponseEntity.ok(service.awaitingConfirmation());
    }

    @PostMapping("/{paymentId}/paid")
    public ResponseEntity<PenaltyPaymentResponse> confirm(@PathVariable UUID paymentId,
                                                          @AuthenticationPrincipal UserDetailsImpl admin) {
        return ResponseEntity.ok(service.confirm(paymentId, admin.getUserId()));
    }
}
