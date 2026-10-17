package com.ospaulos.vupth.web.rest.referral.controller;

import com.ospaulos.vupth.api.referral.dto.AdminReferralPaidResponse;
import com.ospaulos.vupth.api.referral.dto.AdminReferralPayoutResponse;
import com.ospaulos.vupth.domain.referral.service.AdminReferralService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** O "a pagar" da bonificacao por link no painel (BAK-9, fatia 3). So ADMIN ({@code /admin/**}). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/referrals")
public class AdminReferralController {

    private final AdminReferralService adminReferralService;

    @GetMapping("/payouts")
    public ResponseEntity<List<AdminReferralPayoutResponse>> payouts() {
        return ResponseEntity.ok(adminReferralService.payouts());
    }

    @PostMapping("/payouts/{campaignId}/{userId}/paid")
    public ResponseEntity<AdminReferralPaidResponse> markPaid(@PathVariable UUID campaignId, @PathVariable UUID userId,
                                                             @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(adminReferralService.markPaid(principal.getUserId(), campaignId, userId));
    }
}
