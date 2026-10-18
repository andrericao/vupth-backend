package com.ospaulos.vupth.web.rest.payment.controller;

import com.ospaulos.vupth.api.payment.dto.request.PixKeyRequest;
import com.ospaulos.vupth.api.payment.dto.response.PaymentResponse;
import com.ospaulos.vupth.api.payment.dto.response.PendingPaymentsResponse;
import com.ospaulos.vupth.api.payment.dto.response.PixKeyResponse;
import com.ospaulos.vupth.api.payment.dto.response.ProfessionalPendingPaymentsResponse;
import com.ospaulos.vupth.domain.payment.service.PaymentNotifier;
import com.ospaulos.vupth.domain.payment.service.PaymentService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Pagamento por Pix, sem gateway (pagamento-pix, contrato 2a2b0ec + adendos de 10/10). JSON em
 * snake_case. Os avisos saem depois do commit, pelo {@link PaymentNotifier}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentNotifier paymentNotifier;

    @PutMapping("/professional/me/pix")
    public ResponseEntity<PixKeyResponse> setPixKey(@RequestBody @Valid PixKeyRequest request,
                                                    @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(paymentService.setPixKey(principal.getUserId(), request));
    }

    @GetMapping("/professional/me/pix")
    public ResponseEntity<PixKeyResponse> getPixKey(@AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(paymentService.getPixKey(principal.getUserId()));
    }

    @DeleteMapping("/professional/me/pix")
    public ResponseEntity<Void> deletePixKey(@AuthenticationPrincipal UserDetailsImpl principal) {
        paymentService.deletePixKey(principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/attendances/{attendanceId}/payment")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID attendanceId,
                                                      @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(paymentService.getPayment(attendanceId, principal.getUserId()));
    }

    /** "Ja paguei" (cliente). Avisa o profissional: push e WS PAYMENT_CLAIMED (o dot). */
    @PostMapping("/attendances/{attendanceId}/payment/claim")
    public ResponseEntity<PaymentResponse> claim(@PathVariable UUID attendanceId,
                                                 @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("PAYMENT CONTROLLER CLAIM {} by client={}", attendanceId, principal.getUserId());
        PaymentResponse response = paymentService.claim(attendanceId, principal.getUserId());
        paymentNotifier.claimed(attendanceId, paymentService.amountForProfessional(attendanceId)); // o que cai no Pix do profissional (DEC-83/85)
        return ResponseEntity.ok(response);
    }

    /** "Recebi" (profissional ou dono). Avisa o cliente: push e WS PAYMENT_CONFIRMED. */
    @PostMapping("/attendances/{attendanceId}/payment/confirm")
    public ResponseEntity<PaymentResponse> confirm(@PathVariable UUID attendanceId,
                                                   @RequestBody(required = false)
                                                   com.ospaulos.vupth.api.payment.dto.request.ConfirmPaymentRequest body,
                                                   @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("PAYMENT CONTROLLER CONFIRM {} by={}", attendanceId, principal.getUserId());
        PaymentResponse response = paymentService.confirm(attendanceId, principal.getUserId(),
                body == null ? null : body.method());
        paymentNotifier.confirmed(attendanceId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/clients/me/pending-payments")
    public ResponseEntity<PendingPaymentsResponse> pendingOfClient(@AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(paymentService.pendingOfClient(principal.getUserId()));
    }

    @GetMapping("/professional/me/pending-payments")
    public ResponseEntity<ProfessionalPendingPaymentsResponse> pendingOfProfessional(
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(paymentService.pendingOfProfessional(principal.getUserId()));
    }
}
