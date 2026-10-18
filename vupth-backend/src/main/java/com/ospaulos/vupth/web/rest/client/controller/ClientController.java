package com.ospaulos.vupth.web.rest.client.controller;

import com.ospaulos.vupth.api.client.dto.response.ClientResponse;
import com.ospaulos.vupth.api.client.dto.response.PendingPenaltiesResponse;
import com.ospaulos.vupth.api.client.dto.response.PenaltyPaymentResponse;
import com.ospaulos.vupth.api.client.usecase.GetClientUseCase;
import com.ospaulos.vupth.domain.client.service.ClientPenaltyService;
import com.ospaulos.vupth.domain.client.service.PenaltyPaymentService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/clients")
@RequiredArgsConstructor
public class ClientController {

    private final GetClientUseCase getClientUseCase;
    private final ClientPenaltyService clientPenaltyService;
    private final PenaltyPaymentService penaltyPaymentService;

    @GetMapping("/{id}")
    //@PreAuthorize("hasRole('PROFESSIONAL')")
    public ResponseEntity<ClientResponse> getClient(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetailsImpl user){

        log.info("CLIENT CONTROLLER BEGINS");

        ClientResponse response = getClientUseCase.execute(id);

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * Multas que o cliente logado ainda deve (D4). O app as soma aos servicos marcados antes de entrar
     * na fila e cita cada uma pela origem ("corte do dia tal com o profissional tal").
     */
    @GetMapping("/me/pending-penalties")
    public ResponseEntity<PendingPenaltiesResponse> pendingPenalties(
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(clientPenaltyService.pending(user.getUserId()));
    }

    /**
     * Pagar a multa avulso (pagar-multa-avulso, DEC-83): gera o Pix do app com todas as multas pendentes agora.
     * 201 com o pagamento; 409 PENALTY_NOTHING_TO_PAY; 503 PENALTY_PIX_NOT_CONFIGURED. Sem multa nova e com um
     * pagamento ainda vigente, devolve esse (201 tambem, o mesmo pagamento).
     */
    @PostMapping("/me/penalties/pay")
    public ResponseEntity<PenaltyPaymentResponse> payPenalties(@AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(penaltyPaymentService.pay(user.getUserId()));
    }

    /** O estado de um pagamento da multa (PENDING, PAID ou EXPIRED), para o app saber quando acabou. */
    @GetMapping("/me/penalties/payments/{paymentId}")
    public ResponseEntity<PenaltyPaymentResponse> penaltyPayment(@AuthenticationPrincipal UserDetailsImpl user,
                                                                 @PathVariable java.util.UUID paymentId) {
        return ResponseEntity.ok(penaltyPaymentService.get(user.getUserId(), paymentId));
    }
}
