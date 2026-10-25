package com.ospaulos.vupth.web.rest.payment.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.service.PaymentGatewayService;
import com.ospaulos.vupth.infrastructure.asaas.AsaasProperties;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * asaas-sandbox-no-fluxo (DEC-119): o estado da conexao com o Asaas. Os apps leem {@code GET /payment-gateway} (publico, sem dado
 * sensivel): conectado, mostram o pagamento do Asaas; desconectado, o de sempre. O Painel liga e desliga em
 * {@code /admin/settings/asaas-connection}, sem deploy; a chave e a URL (sandbox ou real) vem das variaveis de ambiente.
 */
@RestController
@RequiredArgsConstructor
public class PaymentGatewayController {

    private final PaymentGatewayService gateway;
    private final AsaasProperties properties;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record State(@JsonProperty("connected") boolean connected, @JsonProperty("mode") String mode) {}

    public record Connection(@NotNull @JsonProperty("connected") Boolean connected) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminState(@JsonProperty("connected") boolean connected, @JsonProperty("mode") String mode,
                             @JsonProperty("configured") boolean configured) {}

    @GetMapping("/payment-gateway")
    public ResponseEntity<State> state() {
        boolean connected = gateway.connected();
        return ResponseEntity.ok(new State(connected, connected ? gateway.mode() : null));
    }

    @GetMapping("/admin/settings/asaas-connection")
    public ResponseEntity<AdminState> adminState() {
        return ResponseEntity.ok(adminView());
    }

    @PutMapping("/admin/settings/asaas-connection")
    public ResponseEntity<AdminState> setConnection(@AuthenticationPrincipal UserDetailsImpl user,
                                                    @RequestBody @Valid Connection body) {
        gateway.setConnected(body.connected(), user.getUserId());
        return ResponseEntity.ok(adminView());
    }

    private AdminState adminView() {
        return new AdminState(gateway.connected(), gateway.mode(), properties.keyConfigured());
    }
}
