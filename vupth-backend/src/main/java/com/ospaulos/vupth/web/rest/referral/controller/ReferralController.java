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

    @GetMapping("/me")
    public ResponseEntity<ReferralMeResponse> me(@AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(referralService.me(principal.getUserId()));
    }

    @PostMapping("/claim")
    public ResponseEntity<ReferralClaimResponse> claim(@RequestBody @Valid ReferralClaimRequest request,
                                                       @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(referralService.claim(principal.getUserId(), request.code()));
    }
}
