package com.ospaulos.vupth.web.rest.referral.controller;

import com.ospaulos.vupth.api.referral.dto.AdminReferralPaidResponse;
import com.ospaulos.vupth.api.referral.dto.AdminReferralPayoutResponse;
import com.ospaulos.vupth.api.referral.dto.AdminReferralReleasedResponse;
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

    /** Premios ja ganhos que esperam o Andre liberar (compartilhe-e-ganhe: o premio nao e pago sozinho). */
    @GetMapping("/pending-release")
    public ResponseEntity<List<AdminReferralPayoutResponse>> pendingRelease() {
        return ResponseEntity.ok(adminReferralService.pendingRelease());
    }

    /** Libera os premios travados da campanha (de uma pessoa com {@code beneficiary_id}, ou de todas); eles entram no "a pagar". */
    @PostMapping("/release")
    public ResponseEntity<AdminReferralReleasedResponse> release(@RequestBody @jakarta.validation.Valid ReleaseRequest request,
                                                                 @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(adminReferralService.release(principal.getUserId(), request.campaignId(), request.beneficiaryId()));
    }

    public record ReleaseRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("campaign_id") @jakarta.validation.constraints.NotNull UUID campaignId,
            @com.fasterxml.jackson.annotation.JsonProperty("beneficiary_id") UUID beneficiaryId) {
    }

    @PostMapping("/payouts/{campaignId}/{userId}/paid")
    public ResponseEntity<AdminReferralPaidResponse> markPaid(@PathVariable UUID campaignId, @PathVariable UUID userId,
                                                             @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(adminReferralService.markPaid(principal.getUserId(), campaignId, userId));
    }

    public record AutoPaidResponse(@com.fasterxml.jackson.annotation.JsonProperty("paid_credits") int paidCredits,
                                   @com.fasterxml.jackson.annotation.JsonProperty("paid_at") java.time.OffsetDateTime paidAt,
                                   @com.fasterxml.jackson.annotation.JsonProperty("amount") java.math.BigDecimal amount,
                                   @com.fasterxml.jackson.annotation.JsonProperty("transfer_id") String transferId) {}

    /** Repasse automatico pelo Asaas (DEC-132): pronto e DESLIGADO por padrao (409 PAYOUT_AUTO_DISABLED); o manual acima segue. */
    @PostMapping("/payouts/{campaignId}/{userId}/pay-auto")
    public ResponseEntity<AutoPaidResponse> payAuto(@PathVariable UUID campaignId, @PathVariable UUID userId,
                                                    @AuthenticationPrincipal UserDetailsImpl principal) {
        var r = adminReferralService.payAuto(principal.getUserId(), campaignId, userId);
        return ResponseEntity.ok(new AutoPaidResponse(r.paid().paidCredits(), r.paid().paidAt(), r.amount(), r.transferId()));
    }
}
