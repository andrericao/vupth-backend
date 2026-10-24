package com.ospaulos.vupth.web.rest.payment.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.service.PaymentAccountService;
import com.ospaulos.vupth.domain.payment.service.PaymentAccountService.State;
import com.ospaulos.vupth.infrastructure.asaas.AsaasException;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * A conta de recebimento do profissional (gateway-de-pagamento, fatia 1): {@code POST /professional/me/payment-account} abre a
 * subconta (hoje, de teste no sandbox) com o aceite dos termos, e o {@code GET} mostra o estado. PROFISSIONAL ou DONO.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/professional/me/payment-account")
public class ProfessionalPaymentAccountController {

    private final PaymentAccountService service;

    public record Accept(@NotNull @JsonProperty("accept") Boolean accept,
                         @NotBlank @Size(max = 40) @JsonProperty("accept_version") String acceptVersion) {}

    @PostMapping
    public ResponseEntity<State> open(@RequestBody @Valid Accept body, @AuthenticationPrincipal UserDetailsImpl user,
                                      HttpServletRequest request) {
        if (!Boolean.TRUE.equals(body.accept())) {
            throw new AsaasException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_ACCOUNT_ACCEPT_REQUIRED",
                    "E preciso aceitar os termos para abrir a conta de recebimento.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.open(user.getUserId(), body.acceptVersion(), ipOf(request)));
    }

    @GetMapping
    public ResponseEntity<State> state(@AuthenticationPrincipal UserDetailsImpl user) {
        return service.state(user.getUserId()).map(ResponseEntity::ok)
                .orElseThrow(() -> new AsaasException(HttpStatus.NOT_FOUND, "PAYMENT_ACCOUNT_NOT_FOUND",
                        "Voce ainda nao abriu a conta de recebimento."));
    }

    private static String ipOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return ip != null && ip.length() > 60 ? ip.substring(0, 60) : ip;
    }
}
