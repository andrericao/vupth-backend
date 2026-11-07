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
    private final com.ospaulos.vupth.domain.referral.service.ProfessionalPaymentService paymentService;
    private final com.ospaulos.vupth.domain.referral.service.AdminReferralCreditListService creditListService;

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
                                                             @RequestBody(required = false) @jakarta.validation.Valid MarkPaidRequest request,
                                                             @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(adminReferralService.markPaid(principal.getUserId(), campaignId, userId,
                request == null ? null : request.note()));
    }

    /** pagamento-ao-profissional: paga tudo o que a pessoa tem a pagar, uma linha do historico por campanha. */
    @PostMapping("/payouts/{userId}/paid")
    public ResponseEntity<AdminReferralPaidResponse> markAllPaid(@PathVariable UUID userId,
                                                                 @RequestBody(required = false) @jakarta.validation.Valid MarkPaidRequest request,
                                                                 @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(adminReferralService.markAllPaid(principal.getUserId(), userId, request == null ? null : request.note()));
    }

    /** {@code note}: a nota ou o ID do PIX (opcional). */
    public record MarkPaidRequest(@jakarta.validation.constraints.Size(max = 200) String note) {
    }

    public record ReverseRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String reason) {
    }

    /** lista-de-premios-no-painel: os premios um a um; {@code person_id} acha quem cortou ou quem recebe; teste fora por padrao. */
    @GetMapping("/credits")
    public ResponseEntity<java.util.List<com.ospaulos.vupth.api.referral.dto.AdminReferralCreditRow>> credits(
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(name = "person_id", required = false) UUID personId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.OffsetDateTime from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.OffsetDateTime to,
            @RequestParam(required = false) com.ospaulos.vupth.domain.referral.service.AdminReferralCreditListService.Status status,
            @RequestParam(name = "include_test", defaultValue = "false") boolean includeTest) {
        return ResponseEntity.ok(creditListService.list(campaignId, personId, from, to, status, includeTest));
    }

    /** pagamento-ao-profissional: a pagar agora x pago no periodo, por pessoa. */
    @GetMapping("/payments/summary")
    public ResponseEntity<java.util.List<com.ospaulos.vupth.api.referral.dto.ProfessionalPaymentSummaryRow>> paymentsSummary(
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.OffsetDateTime from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.OffsetDateTime to) {
        return ResponseEntity.ok(paymentService.summary(campaignId, from, to));
    }

    /** O historico de pagamentos e estornos, com filtro por pessoa e por periodo. */
    @GetMapping("/payments")
    public ResponseEntity<java.util.List<com.ospaulos.vupth.api.referral.dto.ProfessionalPaymentResponse>> payments(
            @RequestParam(name = "professional_id", required = false) UUID professionalId,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.OffsetDateTime from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.OffsetDateTime to) {
        return ResponseEntity.ok(paymentService.history(professionalId, campaignId, from, to));
    }

    /** O estorno e uma linha nova; os premios daquele pagamento voltam ao "a pagar". */
    @PostMapping("/payments/{id}/reverse")
    public ResponseEntity<com.ospaulos.vupth.api.referral.dto.ProfessionalPaymentResponse> reverse(
            @PathVariable UUID id, @RequestBody @jakarta.validation.Valid ReverseRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(paymentService.reverse(principal.getUserId(), id, request.reason()));
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
