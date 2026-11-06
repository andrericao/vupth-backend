package com.ospaulos.vupth.web.rest.referral.controller;

import com.ospaulos.vupth.api.referral.dto.ReferralClaimRequest;
import com.ospaulos.vupth.api.referral.dto.ReferralClaimResponse;
import com.ospaulos.vupth.api.referral.dto.ReferralMeResponse;
import com.ospaulos.vupth.domain.referral.service.ReferralService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Indicacao (BAK-9): o profissional e o dono veem o proprio link ({@code GET /referrals/me}, Partner); o
 * cliente que chegou pelo link manda o codigo ({@code POST /referrals/claim}, Client).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/referrals")
public class ReferralController {

    private final ReferralService referralService;
    private final com.ospaulos.vupth.domain.referral.service.ProfessionalPaymentService paymentService;
    private final com.ospaulos.vupth.domain.referral.service.ReferralCreditListService referralCreditListService;

    @GetMapping("/me")
    public ResponseEntity<ReferralMeResponse> me(@AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(referralService.me(principal.getUserId()));
    }

    /** premio-em-pendentes: os premios de quem chama, um por linha, com o atendimento que os gerou; sem premio, {@code []}. */
    /** pagamento-ao-profissional: o que o app ja pagou a quem chama ("pago em"), com a nota e os estornos. */
    @GetMapping("/me/payments")
    public ResponseEntity<java.util.List<com.ospaulos.vupth.api.referral.dto.ProfessionalPaymentResponse>> payments(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl user) {
        return ResponseEntity.ok(paymentService.history(user.getUserId(), null, null, null));
    }

    @GetMapping("/me/credits")
    public ResponseEntity<java.util.List<com.ospaulos.vupth.api.referral.dto.ReferralCreditItemResponse>> credits(
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(referralCreditListService.credits(principal.getUserId()));
    }

    @PostMapping("/claim")
    public ResponseEntity<ReferralClaimResponse> claim(@RequestBody @Valid ReferralClaimRequest request,
                                                       @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(referralService.claim(principal.getUserId(), request.code(), request.campaignId()));
    }
}
