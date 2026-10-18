package com.ospaulos.vupth.domain.client.service;

import com.ospaulos.vupth.api.client.dto.response.PenaltyPaymentResponse;
import com.ospaulos.vupth.domain.client.exception.PenaltyPaymentException;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyPayment;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.client.repository.PenaltyPaymentRepository;
import com.ospaulos.vupth.infrastructure.config.AppPix;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Pagar a multa a qualquer momento (pagar-multa-avulso, DEC-83): a multa e do app, e o cliente a paga por um Pix do app
 * sem esperar o proximo corte. O pagamento cobre todas as multas pendentes na hora e vale {@value #VALID_HOURS} h; nesse
 * prazo (e depois de pago) elas nao entram em outro corte — ninguem paga duas vezes. O app confirma no painel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PenaltyPaymentService {

    static final int VALID_HOURS = 24;

    private final PenaltyPaymentRepository payments;
    private final ClientPenaltyEventRepository events;
    private final ClientPenaltyService penaltyService;
    private final ClientRepository clientRepository;
    private final AppPix appPix;

    /** Gera o Pix das multas pendentes; sem multa pendente devolve o pagamento vigente, se houver. */
    public PenaltyPaymentResponse pay(UUID clientId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<ClientPenaltyEvent> pending = penaltyService.pendingFor(clientId);
        if (pending.isEmpty()) {
            return payments.findAll().stream()
                    .filter(p -> p.getClientId().equals(clientId) && p.getStatus() == PenaltyPayment.Status.PENDING
                            && p.getExpiresAt().isAfter(now))
                    .max(java.util.Comparator.comparing(PenaltyPayment::getCreatedAt))
                    .map(p -> toResponse(p, false))
                    .orElseThrow(() -> new PenaltyPaymentException(HttpStatus.CONFLICT, "PENALTY_NOTHING_TO_PAY",
                            "Voce nao tem multa a pagar."));
        }
        if (!appPix.configured()) {
            throw new PenaltyPaymentException(HttpStatus.SERVICE_UNAVAILABLE, "PENALTY_PIX_NOT_CONFIGURED",
                    "O Pix para pagar a multa ainda nao esta disponivel.");
        }
        BigDecimal total = pending.stream().map(ClientPenaltyEvent::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        PenaltyPayment payment = new PenaltyPayment();
        payment.setId(UUID.randomUUID());
        payment.setClientId(clientId);
        payment.setAmount(total);
        payment.setStatus(PenaltyPayment.Status.PENDING);
        payment.setCreatedAt(now);
        payment.setExpiresAt(now.plusHours(VALID_HOURS));
        payments.save(payment);
        pending.forEach(e -> e.setPenaltyPaymentId(payment.getId()));
        events.saveAll(pending);
        log.info("[PenaltyPayment] client={} pagamento={} valor={} ({} multa(s))", clientId, payment.getId(), total, pending.size());
        return toResponse(payment, false);
    }

    @Transactional(readOnly = true)
    public PenaltyPaymentResponse get(UUID clientId, UUID paymentId) {
        PenaltyPayment payment = payments.findById(paymentId)
                .filter(p -> p.getClientId().equals(clientId))
                .orElseThrow(() -> new PenaltyPaymentException(HttpStatus.NOT_FOUND, "PENALTY_PAYMENT_NOT_FOUND",
                        "Pagamento nao encontrado."));
        return toResponse(payment, false);
    }

    /** O painel: os pagamentos aguardando confirmacao, do mais antigo ao mais novo (vencidos tambem, para conferir o Pix). */
    @Transactional(readOnly = true)
    public List<PenaltyPaymentResponse> awaitingConfirmation() {
        return payments.findByStatusOrderByCreatedAtAsc(PenaltyPayment.Status.PENDING).stream()
                .map(p -> toResponse(p, true)).toList();
    }

    /**
     * O painel confirma que o Pix chegou. Idempotente. Pagamento vencido so se confirma se as multas ainda forem dele
     * (nao tiverem sido cobradas em outro corte); senao 409 e o app resolve a mao.
     */
    public PenaltyPaymentResponse confirm(UUID paymentId, UUID adminId) {
        PenaltyPayment payment = payments.findById(paymentId).orElseThrow(() ->
                new PenaltyPaymentException(HttpStatus.NOT_FOUND, "PENALTY_PAYMENT_NOT_FOUND", "Pagamento nao encontrado."));
        if (payment.getStatus() == PenaltyPayment.Status.PAID) {
            return toResponse(payment, true);
        }
        List<ClientPenaltyEvent> covered = events.findByPenaltyPaymentId(paymentId);
        if (covered.isEmpty() || covered.stream().anyMatch(e -> e.getChargedAttendanceId() != null)) {
            throw new PenaltyPaymentException(HttpStatus.CONFLICT, "PENALTY_PAYMENT_EXPIRED",
                    "O pagamento venceu e as multas ja foram cobradas em outro corte.");
        }
        payment.setStatus(PenaltyPayment.Status.PAID);
        payment.setPaidAt(OffsetDateTime.now());
        payment.setConfirmedBy(adminId);
        payments.save(payment);
        log.info("[PenaltyPayment] pagamento={} confirmado por {}", paymentId, adminId);
        return toResponse(payment, true);
    }

    private PenaltyPaymentResponse toResponse(PenaltyPayment p, boolean forAdmin) {
        boolean expired = p.getStatus() == PenaltyPayment.Status.PENDING && !p.getExpiresAt().isAfter(OffsetDateTime.now());
        String status = expired ? "EXPIRED" : p.getStatus().name();
        boolean live = p.getStatus() == PenaltyPayment.Status.PENDING && !expired;
        String clientName = forAdmin ? clientRepository.findById(p.getClientId())
                .map(c -> c.getFirstName()).orElse(null) : null;
        return new PenaltyPaymentResponse(p.getId(), p.getAmount(), status,
                live ? appPix.copyPaste(p.getAmount(), "VUPTHMULTA" + p.getId().toString().replace("-", "")) : null,
                live && appPix.configured() ? appPix.holderName() : null,
                p.getCreatedAt(), p.getExpiresAt(), p.getPaidAt(),
                forAdmin ? p.getClientId() : null, clientName);
    }
}
