package com.ospaulos.vupth.domain.payment.service;

import com.ospaulos.vupth.api.payment.dto.request.PixKeyRequest;
import com.ospaulos.vupth.api.payment.dto.response.PaymentResponse;
import com.ospaulos.vupth.api.payment.dto.response.PendingPaymentsResponse;
import com.ospaulos.vupth.api.payment.dto.response.PixKeyResponse;
import com.ospaulos.vupth.api.payment.dto.response.ProfessionalPendingPaymentsResponse;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.payment.PaymentStatus;
import com.ospaulos.vupth.domain.payment.PixBrCode;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.payment.exception.PaymentAccessDeniedException;
import com.ospaulos.vupth.domain.payment.exception.PaymentInvalidStatusException;
import com.ospaulos.vupth.domain.payment.exception.PaymentNotAvailableException;
import com.ospaulos.vupth.domain.payment.exception.PaymentPendingException;
import com.ospaulos.vupth.domain.payment.exception.PixKeyInvalidException;
import com.ospaulos.vupth.domain.payment.exception.PixKeyNotSetException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O pagamento por Pix, sem gateway (pagamento-pix, contrato 2a2b0ec + adendos 1 e 2 de 10/10).
 *
 * <p>O pagamento e uma flag do atendimento, ao lado do status: nasce {@code PENDING} na criacao, o
 * cliente toca "Ja paguei" ({@code AWAITING_CONFIRMATION}) e o profissional ou o dono da loja toca
 * "Recebi" ({@code PAID}) — antes ou depois do corte. O portao barra o cliente so por atendimento ja
 * finalizado e nao pago. Os avisos (push e WS) ficam com o controller, depois do commit.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final AttendanceRepository attendanceRepository;
    private final ClientPenaltyEventRepository penaltyEventRepository;
    private final com.ospaulos.vupth.domain.user.repository.UserRepository userRepository;
    private final Clock clock = Clock.systemDefaultZone();

    /** O Pix do app, que recebe a multa (DEC-83). Opcional: sem ele (testes de unidade) a multa so mostra o valor. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ospaulos.vupth.infrastructure.config.AppPix appPix;

    /** O modo do dinheiro (DEC-85): sem ele (testes de unidade), vale o padrao, o Pix total ao profissional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ospaulos.vupth.infrastructure.config.PaymentMode paymentMode;

    /** O livro do app por origem (DEC-93): sem ele (testes de unidade) nada e gravado. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AppLedgerService appLedger;

    /** A divida do corte pago por fora (divida-por-fora-e-abatimento): sem ele (testes de unidade) nada e gravado. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ospaulos.vupth.domain.debt.service.ProfessionalDebtService debtService;

    /** A conexao com o Asaas (DEC-119): sem ele (testes de unidade) vale so o fluxo de sempre. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PaymentGatewayService gateway;

    /** O contador de atrasos zera quando a multa de atraso e paga junto com o corte (nao-chegou-tres-atrasos). Opcional (testes de unidade). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ospaulos.vupth.domain.client.service.LateArrivalService lateArrival;

    private boolean splitPix() {
        return paymentMode != null && paymentMode.splitPix();
    }

    // ─── a chave Pix da pessoa (DEC-65: profissional ou dono) ───

    public PixKeyResponse setPixKey(UUID userId, PixKeyRequest request) {
        BaseUser person = person(userId);
        String key = request.keyType().normalize(request.key())
                .orElseThrow(() -> new PixKeyInvalidException(
                        "A chave nao e um " + request.keyType() + " valido."));
        String holder = request.holderName() == null ? "" : request.holderName().trim();
        if (holder.isEmpty() || holder.length() > 25) {
            throw new PixKeyInvalidException("O nome do titular precisa ter de 1 a 25 letras.");
        }
        person.setPixKeyType(request.keyType());
        person.setPixKey(key);
        person.setPixHolderName(holder);
        userRepository.save(person);
        log.info("[Payment] chave Pix salva — user={} tipo={}", userId, request.keyType());
        return toPixKey(person);
    }

    @Transactional(readOnly = true)
    public PixKeyResponse getPixKey(UUID userId) {
        BaseUser person = person(userId);
        if (person.getPixKey() == null) {
            throw new PixKeyNotSetException();
        }
        return toPixKey(person);
    }

    public void deletePixKey(UUID userId) {
        BaseUser person = person(userId);
        person.setPixKeyType(null);
        person.setPixKey(null);
        person.setPixHolderName(null);
        userRepository.save(person);
        log.info("[Payment] chave Pix apagada — user={}", userId);
    }

    // ─── o pagamento de um atendimento ───

    public PaymentResponse getPayment(UUID attendanceId, UUID userId) {
        Attendance attendance = attendance(attendanceId);
        if (!isClient(attendance, userId) && !canConfirm(attendance, userId)) {
            throw new PaymentAccessDeniedException("So o cliente, o profissional e o dono da loja veem este pagamento.");
        }
        assertAvailable(attendance);
        // DEC-119: com a conexao ligada, a primeira vez que o cliente abre o pagamento pendente nasce a cobranca do Asaas
        if (gateway != null && isClient(attendance, userId) && attendance.getEffectivePaymentStatus() == PaymentStatus.PENDING
                && attendance.getAsaasPaymentId() == null) {
            gateway.ensureCharge(attendance, attendance.getAmountDue()
                    .add(penaltiesOf(List.of(attendance.getId())).getOrDefault(attendance.getId(), ZERO)));
            if (attendance.getAsaasPaymentId() != null) {
                attendanceRepository.save(attendance);
            }
        }
        return toPayment(attendance, isClient(attendance, userId));
    }

    /** "Ja paguei": so o cliente, de PENDING para AWAITING_CONFIRMATION. */
    public PaymentResponse claim(UUID attendanceId, UUID userId) {
        Attendance attendance = attendance(attendanceId);
        if (!isClient(attendance, userId)) {
            throw new PaymentAccessDeniedException("So o cliente do atendimento diz que pagou.");
        }
        assertAvailable(attendance);
        if (attendance.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new PaymentInvalidStatusException(attendance.getPaymentStatus(), "dizer que pagou");
        }
        attendance.setPaymentStatus(PaymentStatus.AWAITING_CONFIRMATION);
        attendance.setPaymentClaimedAt(OffsetDateTime.now(clock));
        attendanceRepository.save(attendance);
        log.info("[Payment] Ja paguei — attendance={} client={}", attendanceId, userId);
        return toPayment(attendance, true);
    }

    /** "Recebi": o profissional do atendimento ou o dono da loja, de PENDING ou AWAITING_CONFIRMATION para PAID. */
    public PaymentResponse confirm(UUID attendanceId, UUID userId) {
        return confirm(attendanceId, userId, null);
    }

    /** "Recebi" com a forma de recebimento (opcional): Pix, cartao, dinheiro ou nota — qualquer uma quita (DEC-85). */
    public PaymentResponse confirm(UUID attendanceId, UUID userId, com.ospaulos.vupth.domain.payment.PaymentMethod method) {
        return confirm(attendanceId, userId, method, null);
    }

    /** DEC-127: com o valor que o profissional recebeu (nulo = o valor com desconto). */
    public PaymentResponse confirm(UUID attendanceId, UUID userId, com.ospaulos.vupth.domain.payment.PaymentMethod method,
                                   BigDecimal receivedAmount) {
        Attendance attendance = attendance(attendanceId);
        if (!canConfirm(attendance, userId)) {
            throw new PaymentAccessDeniedException("So o profissional do atendimento ou o dono da loja confirmam o pagamento.");
        }
        assertAvailable(attendance);
        if (attendance.getPaymentStatus() == PaymentStatus.PAID) {
            throw new PaymentInvalidStatusException(PaymentStatus.PAID, "confirmar");
        }
        markPaid(attendance, method, receivedAmount);
        cancelGatewayCharge(attendance);
        log.info("[Payment] Recebi — attendance={} by={} forma={}", attendanceId, userId, method);
        return toPayment(attendance, isClient(attendance, userId));
    }

    /**
     * "Nao recebi": o profissional ou o dono recusa o "Ja paguei" do cliente. O pagamento volta a PENDING (o atendimento segue em
     * aberto, com a cobranca do Asaas viva) e o cliente e avisado pelo controller para conferir.
     */
    public PaymentResponse refuse(UUID attendanceId, UUID userId) {
        Attendance attendance = attendance(attendanceId);
        if (!canConfirm(attendance, userId)) {
            throw new PaymentAccessDeniedException("So o profissional do atendimento ou o dono da loja recusam o pagamento.");
        }
        assertAvailable(attendance);
        if (attendance.getPaymentStatus() != PaymentStatus.AWAITING_CONFIRMATION) {
            throw new PaymentInvalidStatusException(attendance.getPaymentStatus(), "recusar");
        }
        attendance.setPaymentStatus(PaymentStatus.PENDING);
        attendance.setPaymentClaimedAt(null);
        attendanceRepository.save(attendance);
        log.info("[Payment] Nao recebi — attendance={} by={}", attendanceId, userId);
        return toPayment(attendance, isClient(attendance, userId));
    }

    /** Quitado a mao: cancela a cobranca do Asaas, se houver (uma via anula a outra). */
    private void cancelGatewayCharge(Attendance attendance) {
        if (gateway != null && attendance.getAsaasPaymentId() != null && gateway.cancelCharge(attendance)) {
            attendanceRepository.save(attendance);
        }
    }

    /**
     * O "Sim" do modal no Finalizar (adendo 1). Ja pago: nada muda e nao e erro. Devolve se marcou
     * agora (so entao o cliente recebe o aviso de pagamento confirmado).
     */
    public boolean confirmOnFinish(UUID attendanceId, UUID userId) {
        return confirmOnFinish(attendanceId, userId, null);
    }

    public boolean confirmOnFinish(UUID attendanceId, UUID userId, com.ospaulos.vupth.domain.payment.PaymentMethod method) {
        return confirmOnFinish(attendanceId, userId, method, null);
    }

    public boolean confirmOnFinish(UUID attendanceId, UUID userId, com.ospaulos.vupth.domain.payment.PaymentMethod method,
                                   BigDecimal receivedAmount) {
        Attendance attendance = attendance(attendanceId);
        if (!canConfirm(attendance, userId)) {
            throw new PaymentAccessDeniedException("So o profissional do atendimento ou o dono da loja confirmam o pagamento.");
        }
        PaymentStatus current = attendance.getEffectivePaymentStatus();
        if (current == null || current == PaymentStatus.PAID) {
            return false;
        }
        markPaid(attendance, method, receivedAmount);
        cancelGatewayCharge(attendance);
        log.info("[Payment] Recebi no Finalizar — attendance={} by={} forma={}", attendanceId, userId, method);
        return true;
    }

    /**
     * O aviso do Asaas sobre a cobranca do atendimento (webhook): guarda o status e, se o Pix foi recebido, quita o pagamento como
     * o "Recebi" faria (forma PIX). So vale se a cobranca e a que este atendimento guardou; aviso repetido nao muda nada.
     */
    public boolean onGatewayEvent(UUID attendanceId, String asaasPaymentId, String status, boolean paid) {
        Attendance attendance = attendanceRepository.findById(attendanceId).orElse(null);
        if (attendance == null || asaasPaymentId == null || !asaasPaymentId.equals(attendance.getAsaasPaymentId())) {
            return false;
        }
        if (status != null) {
            attendance.setAsaasStatus(status);
        }
        boolean marked = false;
        if (paid && attendance.getPaymentStatus() != PaymentStatus.PAID) {
            markPaid(attendance, com.ospaulos.vupth.domain.payment.PaymentMethod.PIX);
            marked = true;
            log.info("[Payment] Pix recebido pelo Asaas — attendance={} payment={}", attendanceId, asaasPaymentId);
        } else {
            attendanceRepository.save(attendance);
        }
        return marked;
    }

    /**
     * Rota de teste do ADMIN (newman-asaas): o atendimento que ja tem cobranca do Asaas nao ganha outra (409 ASAAS_CHARGE_EXISTS).
     * So essa rota usa isto; o fluxo normal ja respeita a cobranca guardada. Atendimento que nao existe: nada a conferir.
     */
    @Transactional(readOnly = true)
    public void assertNoGatewayCharge(UUID attendanceId) {
        attendanceRepository.findById(attendanceId).ifPresent(a -> {
            if (a.getAsaasPaymentId() != null) {
                throw new com.ospaulos.vupth.infrastructure.asaas.AsaasException(org.springframework.http.HttpStatus.CONFLICT,
                        "ASAAS_CHARGE_EXISTS", "Este atendimento ja tem uma cobranca do Asaas.");
            }
        });
    }

    /** A cobranca criada pela rota de teste fica ligada ao atendimento: o webhook a quita e o relatorio a encontra. */
    public void attachGatewayCharge(UUID attendanceId, String asaasPaymentId, String mode, String status, String invoiceUrl) {
        attendanceRepository.findById(attendanceId).ifPresent(a -> {
            a.setAsaasPaymentId(asaasPaymentId);
            a.setAsaasMode(mode);
            a.setAsaasStatus(status);
            a.setAsaasInvoiceUrl(invoiceUrl);
            attendanceRepository.save(a);
            log.info("[Payment] cobranca de teste ligada ao atendimento — attendance={} payment={}", attendanceId, asaasPaymentId);
        });
    }

    /** O status do pagamento para mostrar (o efetivo), sem checar quem pergunta: so para a resposta do finish. */
    @Transactional(readOnly = true)
    public PaymentStatus statusOf(UUID attendanceId) {
        return attendance(attendanceId).getEffectivePaymentStatus();
    }

    /** O valor que falta pagar (servicos mais multas) quando o pagamento esta PENDENTE; nulo nos outros casos. */
    @Transactional(readOnly = true)
    public BigDecimal pendingAmountOf(UUID attendanceId) {
        Attendance a = attendance(attendanceId);
        if (a.getEffectivePaymentStatus() != PaymentStatus.PENDING) {
            return null;
        }
        return a.getAmountDue().add(penaltiesOf(List.of(attendanceId)).getOrDefault(attendanceId, ZERO));
    }

    /** Um lembrete de pagamento pendente (push-pagamento-pendente): para quem, qual atendimento e quanto. */
    public record PaymentReminder(UUID clientId, UUID attendanceId, UUID establishmentId, BigDecimal amount) {
    }

    /** Os pagamentos PENDENTES de atendimentos finalizados antes de {@code before}, com o valor de cada um. */
    @Transactional(readOnly = true)
    public List<PaymentReminder> remindersFinishedBefore(OffsetDateTime before) {
        List<Attendance> open = attendanceRepository.findPendingPaymentsFinishedBefore(before);
        Map<UUID, BigDecimal> penalties = penaltiesOf(open.stream().map(Attendance::getId).toList());
        return open.stream()
                .map(a -> new PaymentReminder(a.getClient().getId(), a.getId(), a.getEstablishment().getId(),
                        a.getAmountDue().add(penalties.getOrDefault(a.getId(), ZERO))))
                .toList();
    }

    // ─── as pendencias ───

    @Transactional(readOnly = true)
    public PendingPaymentsResponse pendingOfClient(UUID clientId) {
        List<Attendance> open = attendanceRepository.findOpenPaymentsOfClient(clientId);
        Map<UUID, BigDecimal> penalties = penaltiesOf(open.stream().map(Attendance::getId).toList());
        List<PendingPaymentsResponse.Item> items = open.stream()
                .map(a -> new PendingPaymentsResponse.Item(
                        a.getId(),
                        a.getPaymentStatus(),
                        a.getAmountDue().add(penalties.getOrDefault(a.getId(), ZERO)),
                        a.getProfessional().getName(),
                        a.getEstablishment().getName(),
                        a.getEndTime()))
                .toList();
        BigDecimal total = items.stream().map(PendingPaymentsResponse.Item::amount).reduce(ZERO, BigDecimal::add);
        return new PendingPaymentsResponse(items.size(), total, items);
    }

    /** O portao: com pagamento pendente, nada de fila nova, agenda ou pedido de corte. */
    @Transactional(readOnly = true)
    public void assertNoPendingPayment(UUID clientId) {
        PendingPaymentsResponse pending = pendingOfClient(clientId);
        if (pending.count() > 0) {
            log.warn("[Payment] portao — client={} com {} pagamento(s) pendente(s)", clientId, pending.count());
            throw new PaymentPendingException(pending);
        }
    }

    @Transactional(readOnly = true)
    public ProfessionalPendingPaymentsResponse pendingOfProfessional(UUID professionalId) {
        List<Attendance> open = attendanceRepository.findOpenPaymentsOfProfessional(professionalId);
        Map<UUID, BigDecimal> penalties = splitPix() ? Map.of()
                : penaltiesOf(open.stream().map(Attendance::getId).toList());
        List<ProfessionalPendingPaymentsResponse.Item> items = open.stream()
                .map(a -> new ProfessionalPendingPaymentsResponse.Item(
                        a.getId(),
                        a.getPaymentStatus(),
                        // SPLIT_PIX: so o servico (a multa e do app, DEC-83); fase de testes: o total que cai no Pix (DEC-85)
                        a.getAmountDue().add(penalties.getOrDefault(a.getId(), ZERO)),
                        a.getClient() != null ? a.getClient().getFirstName() : a.getGuestName(),
                        a.getEndTime(),
                        a.getPaymentClaimedAt()))
                .toList();
        int awaiting = (int) items.stream()
                .filter(i -> i.status() == PaymentStatus.AWAITING_CONFIRMATION)
                .count();
        return new ProfessionalPendingPaymentsResponse(awaiting, items);
    }

    // ─── apoio ───

    private void markPaid(Attendance attendance, com.ospaulos.vupth.domain.payment.PaymentMethod method) {
        markPaid(attendance, method, null);
    }

    private void markPaid(Attendance attendance, com.ospaulos.vupth.domain.payment.PaymentMethod method, BigDecimal receivedAmount) {
        if (receivedAmount != null && receivedAmount.signum() >= 0) {
            attendance.setReceivedAmount(receivedAmount.setScale(2, java.math.RoundingMode.HALF_UP));
        }
        attendance.setPaymentStatus(PaymentStatus.PAID);
        if (method != null) {
            attendance.setPaymentMethod(method);
        }
        attendance.setPaymentConfirmedAt(OffsetDateTime.now(clock));
        attendanceRepository.save(attendance);
        if (debtService != null) {
            debtService.recordOnReceived(attendance, attendance.getPaymentMethod());
        }
        if (lateArrival != null && attendance.getClient() != null
                && penaltyEventRepository.findByChargedAttendanceIdInOrderByCreatedAtAsc(List.of(attendance.getId())).stream()
                        .anyMatch(e -> e.getType() == com.ospaulos.vupth.domain.client.model.PenaltyType.LATE_ARRIVAL
                                && e.getAmount() != null && e.getAmount().signum() > 0)) {
            lateArrival.resetOnPenaltyPaid(attendance.getClient());
        }
        // atendimento de teste (piloto, DEC-106): o Recebi quita como sempre, mas fica fora do livro-caixa do app
        if (appLedger != null && !attendance.isTest()) {
            appLedger.record(attendance, penaltiesOf(List.of(attendance.getId())).get(attendance.getId()),
                    attendance.getPaymentConfirmedAt());
        }
    }

    private void assertAvailable(Attendance attendance) {
        if (attendance.getEffectivePaymentStatus() == null) {
            throw new PaymentNotAvailableException(attendance.getId());
        }
    }

    private static boolean isClient(Attendance attendance, UUID userId) {
        return attendance.getClient() != null && attendance.getClient().getId().equals(userId);
    }

    /** O profissional do atendimento ou o dono da loja (no futuro, a recepcao — item secretario). */
    private static boolean canConfirm(Attendance attendance, UUID userId) {
        if (attendance.getProfessional() != null && attendance.getProfessional().getId().equals(userId)) {
            return true;
        }
        return attendance.getEstablishment() != null
                && attendance.getEstablishment().getOwner() != null
                && userId.equals(attendance.getEstablishment().getOwner().getId());
    }

    /**
     * @param forClient o cliente ve a multa e o total com ela. O profissional e o dono veem: com os dois Pix
     *                  ({@code SPLIT_PIX}), so o valor do servico (DEC-83: a multa e do app); na fase de testes
     *                  ({@code TEST_PIX_TOTAL}, DEC-85), o total que cai no Pix, como "valor a receber" e sem rotulo de multa.
     */
    private PaymentResponse toPayment(Attendance attendance, boolean forClient) {
        BigDecimal services = attendance.getAmountDue();
        BigDecimal realPenalties = penaltiesOf(List.of(attendance.getId())).getOrDefault(attendance.getId(), ZERO);
        boolean split = splitPix();
        BigDecimal shownServices;
        BigDecimal shownPenalties;
        BigDecimal amount;
        if (forClient) {
            shownServices = services;
            shownPenalties = realPenalties;
            amount = services.add(realPenalties);
        } else if (split) {
            shownServices = services;
            shownPenalties = ZERO;
            amount = services;
        } else {
            shownServices = services.add(realPenalties); // "valor a receber": bate com o Pix, sem dizer que ha multa
            shownPenalties = ZERO;
            amount = shownServices;
        }
        BaseUser keyOwner = pixKeyOwner(attendance);
        String copyPaste = null;
        if (keyOwner != null) {
            // SPLIT_PIX: o Pix do profissional leva so o servico (a multa vai por outro Pix, ao app); fase de testes: o total
            BigDecimal pixAmount = split ? services : services.add(realPenalties);
            copyPaste = PixBrCode.build(keyOwner.getPixKey(), keyOwner.getPixHolderName(),
                    cityOf(attendance), pixAmount, "VUPTH" + attendance.getId().toString().replace("-", ""));
        }
        boolean hasKey = copyPaste != null;
        boolean penaltyPix = split && forClient && realPenalties.signum() > 0 && appPix != null;
        return new PaymentResponse(
                attendance.getEffectivePaymentStatus(),
                shownServices,
                shownPenalties,
                amount,
                copyPaste,
                hasKey ? keyOwner.getPixHolderName() : null,
                hasKey ? keyOwner.getPixKeyType() : null,
                attendance.getPaymentClaimedAt(),
                attendance.getPaymentConfirmedAt(),
                !hasKey ? null : isProfessional(attendance, keyOwner) ? "PROFESSIONAL" : "OWNER",
                penaltyPix ? appPix.copyPaste(realPenalties, "VUPTHMULTA" + attendance.getId().toString().replace("-", "")) : null,
                penaltyPix && appPix.configured() ? appPix.holderName() : null,
                attendance.getPaymentMethod(),
                hasKey && forClient ? keyOwner.getPixKey() : null,
                gatewayOf(attendance, forClient),
                com.ospaulos.vupth.domain.payment.DiscountBreakdown.of(attendance.getTotalValue(), attendance.getProReceives(),
                        attendance.getDiscount(), realPenalties, attendance.getReceivedAmount()));
    }

    /** A cobranca do Asaas deste atendimento (DEC-119): o Pix so vai ao cliente; o profissional ve o modo e o status do webhook. */
    private static PaymentResponse.Gateway gatewayOf(Attendance attendance, boolean forClient) {
        if (attendance.getAsaasPaymentId() == null) {
            return null;
        }
        return new PaymentResponse.Gateway("ASAAS", attendance.getAsaasMode(), attendance.getAsaasStatus(),
                forClient ? attendance.getAsaasPixPayload() : null, forClient ? attendance.getAsaasPixImage() : null,
                forClient ? attendance.getAsaasInvoiceUrl() : null);
    }

    /** O valor que cai no Pix do profissional: o servico (SPLIT_PIX) ou o total (fase de testes). Para o aviso "diz que pagou". */
    @Transactional(readOnly = true)
    public BigDecimal amountForProfessional(UUID attendanceId) {
        Attendance a = attendance(attendanceId);
        return splitPix() ? a.getAmountDue()
                : a.getAmountDue().add(penaltiesOf(List.of(attendanceId)).getOrDefault(attendanceId, ZERO));
    }

    /** DEC-65: a chave do profissional que atendeu; sem ela, a do dono da loja; sem as duas, nenhuma. */
    private static BaseUser pixKeyOwner(Attendance attendance) {
        BaseUser professional = attendance.getProfessional() == null ? null : attendance.getProfessional().getBaseUser();
        if (professional != null && professional.getPixKey() != null) {
            return professional;
        }
        BaseUser owner = attendance.getEstablishment() == null || attendance.getEstablishment().getOwner() == null
                ? null : attendance.getEstablishment().getOwner().getBaseUser();
        return owner != null && owner.getPixKey() != null ? owner : null;
    }

    private static boolean isProfessional(Attendance attendance, BaseUser person) {
        return attendance.getProfessional() != null && attendance.getProfessional().getBaseUser() == person;
    }

    private static String cityOf(Attendance attendance) {
        Address address = attendance.getEstablishment() == null ? null : attendance.getEstablishment().getMainAddress();
        return address == null ? null : address.getCity();
    }

    /** Multas que cada atendimento carrega (D4). */
    private Map<UUID, BigDecimal> penaltiesOf(Collection<UUID> attendanceIds) {
        if (attendanceIds.isEmpty()) {
            return Map.of();
        }
        return penaltyEventRepository.findByChargedAttendanceIdInOrderByCreatedAtAsc(attendanceIds).stream()
                .filter(e -> e.getAmount() != null && e.getAmount().signum() > 0)
                .collect(Collectors.groupingBy(ClientPenaltyEvent::getChargedAttendanceId,
                        Collectors.reducing(ZERO, ClientPenaltyEvent::getAmount, BigDecimal::add)));
    }

    private Attendance attendance(UUID attendanceId) {
        return attendanceRepository.findById(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));
    }

    private BaseUser person(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new com.ospaulos.vupth.domain.user.exception.NotFoundUserException("User not found: " + userId));
    }

    private static PixKeyResponse toPixKey(BaseUser person) {
        return new PixKeyResponse(person.getPixKeyType(), person.getPixKey(), person.getPixHolderName());
    }
}
