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

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID attendanceId, UUID userId) {
        Attendance attendance = attendance(attendanceId);
        if (!isClient(attendance, userId) && !canConfirm(attendance, userId)) {
            throw new PaymentAccessDeniedException("So o cliente, o profissional e o dono da loja veem este pagamento.");
        }
        assertAvailable(attendance);
        return toPayment(attendance);
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
        return toPayment(attendance);
    }

    /** "Recebi": o profissional do atendimento ou o dono da loja, de PENDING ou AWAITING_CONFIRMATION para PAID. */
    public PaymentResponse confirm(UUID attendanceId, UUID userId) {
        Attendance attendance = attendance(attendanceId);
        if (!canConfirm(attendance, userId)) {
            throw new PaymentAccessDeniedException("So o profissional do atendimento ou o dono da loja confirmam o pagamento.");
        }
        assertAvailable(attendance);
        if (attendance.getPaymentStatus() == PaymentStatus.PAID) {
            throw new PaymentInvalidStatusException(PaymentStatus.PAID, "confirmar");
        }
        markPaid(attendance);
        log.info("[Payment] Recebi — attendance={} by={}", attendanceId, userId);
        return toPayment(attendance);
    }

    /**
     * O "Sim" do modal no Finalizar (adendo 1). Ja pago: nada muda e nao e erro. Devolve se marcou
     * agora (so entao o cliente recebe o aviso de pagamento confirmado).
     */
    public boolean confirmOnFinish(UUID attendanceId, UUID userId) {
        Attendance attendance = attendance(attendanceId);
        if (!canConfirm(attendance, userId)) {
            throw new PaymentAccessDeniedException("So o profissional do atendimento ou o dono da loja confirmam o pagamento.");
        }
        PaymentStatus current = attendance.getEffectivePaymentStatus();
        if (current == null || current == PaymentStatus.PAID) {
            return false;
        }
        markPaid(attendance);
        log.info("[Payment] Recebi no Finalizar — attendance={} by={}", attendanceId, userId);
        return true;
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
        return a.getTotalValue().add(penaltiesOf(List.of(attendanceId)).getOrDefault(attendanceId, ZERO));
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
                        a.getTotalValue().add(penalties.getOrDefault(a.getId(), ZERO))))
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
                        a.getTotalValue().add(penalties.getOrDefault(a.getId(), ZERO)),
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
        Map<UUID, BigDecimal> penalties = penaltiesOf(open.stream().map(Attendance::getId).toList());
        List<ProfessionalPendingPaymentsResponse.Item> items = open.stream()
                .map(a -> new ProfessionalPendingPaymentsResponse.Item(
                        a.getId(),
                        a.getPaymentStatus(),
                        a.getTotalValue().add(penalties.getOrDefault(a.getId(), ZERO)),
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

    private void markPaid(Attendance attendance) {
        attendance.setPaymentStatus(PaymentStatus.PAID);
        attendance.setPaymentConfirmedAt(OffsetDateTime.now(clock));
        attendanceRepository.save(attendance);
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

    private PaymentResponse toPayment(Attendance attendance) {
        BigDecimal services = attendance.getTotalValue();
        BigDecimal penalties = penaltiesOf(List.of(attendance.getId())).getOrDefault(attendance.getId(), ZERO);
        BigDecimal amount = services.add(penalties);
        BaseUser keyOwner = pixKeyOwner(attendance);
        String copyPaste = null;
        if (keyOwner != null) {
            copyPaste = PixBrCode.build(keyOwner.getPixKey(), keyOwner.getPixHolderName(),
                    cityOf(attendance), amount, "VUPTH" + attendance.getId().toString().replace("-", ""));
        }
        boolean hasKey = copyPaste != null;
        return new PaymentResponse(
                attendance.getEffectivePaymentStatus(),
                services,
                penalties,
                amount,
                copyPaste,
                hasKey ? keyOwner.getPixHolderName() : null,
                hasKey ? keyOwner.getPixKeyType() : null,
                attendance.getPaymentClaimedAt(),
                attendance.getPaymentConfirmedAt(),
                !hasKey ? null : isProfessional(attendance, keyOwner) ? "PROFESSIONAL" : "OWNER");
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
