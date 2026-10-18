package com.ospaulos.vupth.domain.payment;

import com.ospaulos.vupth.api.payment.dto.request.PixKeyRequest;
import com.ospaulos.vupth.api.payment.dto.response.PaymentResponse;
import com.ospaulos.vupth.api.payment.dto.response.PendingPaymentsResponse;
import com.ospaulos.vupth.api.payment.dto.response.ProfessionalPendingPaymentsResponse;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyType;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.model.EstablishmentAddress;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.payment.exception.PaymentAccessDeniedException;
import com.ospaulos.vupth.domain.payment.exception.PaymentInvalidStatusException;
import com.ospaulos.vupth.domain.payment.exception.PaymentNotAvailableException;
import com.ospaulos.vupth.domain.payment.exception.PaymentPendingException;
import com.ospaulos.vupth.domain.payment.exception.PixKeyInvalidException;
import com.ospaulos.vupth.domain.payment.exception.PixKeyNotSetException;
import com.ospaulos.vupth.domain.payment.service.PaymentService;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests — o pagamento como flag do atendimento (pagamento-pix, contrato + adendos 1, 2 e 3). */
class PaymentServiceTest {

    private AttendanceRepository attendanceRepository;
    private com.ospaulos.vupth.domain.user.repository.UserRepository userRepository;
    private ClientPenaltyEventRepository penaltyRepository;
    private PaymentService service;
    private com.ospaulos.vupth.infrastructure.config.AppPix appPixForPenalties;

    private final UUID clientId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private Professional professional;
    private Client client;
    private Establishment establishment;

    @BeforeEach
    void setUp() {
        appPixForPenalties = new com.ospaulos.vupth.infrastructure.config.AppPix("+5511988887777", "VUPTH", "SAO PAULO");
        attendanceRepository = mock(AttendanceRepository.class);
        userRepository = mock(com.ospaulos.vupth.domain.user.repository.UserRepository.class);
        penaltyRepository = mock(ClientPenaltyEventRepository.class);
        service = new PaymentService(attendanceRepository, penaltyRepository, userRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "appPix", appPixForPenalties);
        when(penaltyRepository.findByChargedAttendanceIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());

        professional = new Professional(user("Beto"));
        professional.setId(professionalId);
        client = new Client(user("Ana"));
        client.setId(clientId);
        Owner owner = new Owner(user("Dona"));
        owner.setId(ownerId);
        establishment = new Establishment();
        establishment.setId(UUID.randomUUID());
        establishment.setName("Loja");
        establishment.setOwner(owner);
    }

    private static BaseUser user(String name) {
        BaseUser u = new BaseUser("x@y.com");
        u.setFirstName(name);
        return u;
    }

    private Attendance attendance(AttendanceStatus status, PaymentStatus payment) {
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        a.setClient(client);
        a.setProfessional(professional);
        a.setEstablishment(establishment);
        a.setStatus(status);
        a.setTotalValue(new BigDecimal("40.00"));
        a.setPaymentStatus(payment);
        a.setEndTime(OffsetDateTime.now());
        when(attendanceRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    private void withPixKey() {
        professional.getBaseUser().setPixKeyType(PixKeyType.EMAIL);
        professional.getBaseUser().setPixKey("beto@mail.com");
        professional.getBaseUser().setPixHolderName("Beto");
    }

    // ─── nasce na criacao ───

    @Test
    @DisplayName("O atendimento nasce PENDING; cancelado ou retirado ainda pendente sai como null")
    void bornPendingAndEffective() {
        Attendance a = new Attendance();
        a.onCreate();
        assertThat(a.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);

        a.setStatus(AttendanceStatus.CANCELLED);
        assertThat(a.getEffectivePaymentStatus()).isNull();
        a.setStatus(AttendanceStatus.REMOVED);
        a.setPaymentStatus(PaymentStatus.AWAITING_CONFIRMATION);
        assertThat(a.getEffectivePaymentStatus()).isEqualTo(PaymentStatus.AWAITING_CONFIRMATION);
        a.setStatus(null);
        a.setPaymentStatus(PaymentStatus.PENDING);
        assertThat(a.getEffectivePaymentStatus()).isEqualTo(PaymentStatus.PENDING);

        Attendance paid = new Attendance();
        paid.setPaymentStatus(PaymentStatus.PAID);
        paid.onCreate();
        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    // ─── a chave ───

    @Test
    @DisplayName("Chave: salva normalizada; tipo errado ou nome fora de 1..25 da 422; GET sem chave 404; DELETE limpa")
    void pixKey() {
        when(userRepository.findById(professionalId)).thenReturn(Optional.of(professional.getBaseUser()));

        var saved = service.setPixKey(professionalId, new PixKeyRequest(PixKeyType.PHONE, "(11) 98765-4321", " Beto "));
        assertThat(saved.key()).isEqualTo("+5511987654321");
        assertThat(saved.holderName()).isEqualTo("Beto");
        assertThat(service.getPixKey(professionalId).keyType()).isEqualTo(PixKeyType.PHONE);

        assertThatThrownBy(() -> service.setPixKey(professionalId, new PixKeyRequest(PixKeyType.CPF, "123", "Beto")))
                .isInstanceOf(PixKeyInvalidException.class);
        assertThatThrownBy(() -> service.setPixKey(professionalId, new PixKeyRequest(PixKeyType.EMAIL, "b@m.com", "  ")))
                .isInstanceOf(PixKeyInvalidException.class);
        assertThatThrownBy(() -> service.setPixKey(professionalId, new PixKeyRequest(PixKeyType.EMAIL, "b@m.com", null)))
                .isInstanceOf(PixKeyInvalidException.class);
        assertThatThrownBy(() -> service.setPixKey(professionalId, new PixKeyRequest(PixKeyType.EMAIL, "b@m.com", "x".repeat(26))))
                .isInstanceOf(PixKeyInvalidException.class);

        service.deletePixKey(professionalId);
        assertThat(professional.getBaseUser().getPixKey()).isNull();
        assertThatThrownBy(() -> service.getPixKey(professionalId)).isInstanceOf(PixKeyNotSetException.class);

        when(userRepository.findById(strangerId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPixKey(strangerId))
                .isInstanceOf(com.ospaulos.vupth.domain.user.exception.NotFoundUserException.class);
    }

    // ─── o pagamento ───

    @Test
    @DisplayName("GET: valor = servicos + multas, com o copia-e-cola; leem cliente, profissional e dono")
    void getPaymentWithKeyAndPenalties() {
        withPixKey();
        Address address = new Address();
        address.setCity("Campinas");
        EstablishmentAddress ea = new EstablishmentAddress(address);
        establishment.getAddresses().add(ea);
        Attendance a = attendance(AttendanceStatus.SCHEDULED, PaymentStatus.PENDING);
        ClientPenaltyEvent penalty = new ClientPenaltyEvent(client, PenaltyType.values()[0], null, 0, new BigDecimal("5.00"));
        penalty.setChargedAttendanceId(a.getId());
        ClientPenaltyEvent zero = new ClientPenaltyEvent(client, PenaltyType.values()[0], null, 0, BigDecimal.ZERO);
        zero.setChargedAttendanceId(a.getId());
        ClientPenaltyEvent noAmount = new ClientPenaltyEvent(client, PenaltyType.values()[0], null, 0);
        noAmount.setChargedAttendanceId(a.getId());
        when(penaltyRepository.findByChargedAttendanceIdInOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(penalty, zero, noAmount));

        PaymentResponse r = service.getPayment(a.getId(), clientId);

        assertThat(r.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(r.servicesValue()).isEqualByComparingTo("40");
        assertThat(r.penaltiesValue()).isEqualByComparingTo("5");
        assertThat(r.amount()).isEqualByComparingTo("45");
        // fase de testes sem gateway (DEC-85, o padrao): o Pix do profissional leva o total (40,00 + 5,00)
        assertThat(r.pixCopyPaste()).contains("beto@mail.com").contains("540545.00").contains("CAMPINAS");
        assertThat(r.penaltyPixCopyPaste()).isNull();
        assertThat(r.holderName()).isEqualTo("Beto");
        assertThat(r.keyType()).isEqualTo(PixKeyType.EMAIL);
        assertThat(r.pixOwner()).isEqualTo("PROFESSIONAL");
        assertThat(service.getPayment(a.getId(), professionalId).status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(service.getPayment(a.getId(), ownerId).status()).isEqualTo(PaymentStatus.PENDING);
        assertThatThrownBy(() -> service.getPayment(a.getId(), strangerId)).isInstanceOf(PaymentAccessDeniedException.class);
    }

    @Test
    @DisplayName("DEC-65: profissional sem chave -> o Pix sai na chave do dono (pix_owner OWNER); com a dele, vale a dele")
    void ownerKeyIsTheAlternative() {
        BaseUser dona = establishment.getOwner().getBaseUser();
        dona.setPixKeyType(PixKeyType.EMAIL);
        dona.setPixKey("dona@mail.com");
        dona.setPixHolderName("Dona");
        Attendance a = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);

        PaymentResponse r = service.getPayment(a.getId(), clientId);
        assertThat(r.pixCopyPaste()).contains("dona@mail.com");
        assertThat(r.holderName()).isEqualTo("Dona");
        assertThat(r.pixOwner()).isEqualTo("OWNER");

        withPixKey();
        PaymentResponse own = service.getPayment(a.getId(), clientId);
        assertThat(own.pixCopyPaste()).contains("beto@mail.com");
        assertThat(own.pixOwner()).isEqualTo("PROFESSIONAL");
    }

    @Test
    @DisplayName("GET sem chave: so o valor; cancelado pendente ou antigo da 409; inexistente 404")
    void getPaymentWithoutKey() {
        Attendance a = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        PaymentResponse r = service.getPayment(a.getId(), clientId);
        assertThat(r.pixCopyPaste()).isNull();
        assertThat(r.holderName()).isNull();
        assertThat(r.keyType()).isNull();
        assertThat(r.pixOwner()).isNull();
        assertThat(r.amount()).isEqualByComparingTo("40");

        Attendance cancelled = attendance(AttendanceStatus.CANCELLED, PaymentStatus.PENDING);
        assertThatThrownBy(() -> service.getPayment(cancelled.getId(), clientId)).isInstanceOf(PaymentNotAvailableException.class);
        Attendance old = attendance(AttendanceStatus.FINISHED, null);
        assertThatThrownBy(() -> service.getPayment(old.getId(), clientId)).isInstanceOf(PaymentNotAvailableException.class);

        UUID missing = UUID.randomUUID();
        when(attendanceRepository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPayment(missing, clientId)).isInstanceOf(AttendanceNotFoundException.class);
    }

    @Test
    @DisplayName("Ja paguei: so o cliente, so de PENDING (na fila ou finalizado)")
    void claim() {
        Attendance a = attendance(AttendanceStatus.SCHEDULED, PaymentStatus.PENDING);

        assertThatThrownBy(() -> service.claim(a.getId(), professionalId)).isInstanceOf(PaymentAccessDeniedException.class);
        PaymentResponse r = service.claim(a.getId(), clientId);

        assertThat(r.status()).isEqualTo(PaymentStatus.AWAITING_CONFIRMATION);
        assertThat(r.claimedAt()).isNotNull();
        verify(attendanceRepository).save(a);
        assertThatThrownBy(() -> service.claim(a.getId(), clientId)).isInstanceOf(PaymentInvalidStatusException.class);

        Attendance guest = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        guest.setClient(null);
        assertThatThrownBy(() -> service.claim(guest.getId(), clientId)).isInstanceOf(PaymentAccessDeniedException.class);
    }

    @Test
    @DisplayName("Recebi: profissional ou dono, de PENDING ou AWAITING; pago de novo da 409; outro da 403")
    void confirm() {
        Attendance awaiting = attendance(AttendanceStatus.FINISHED, PaymentStatus.AWAITING_CONFIRMATION);
        assertThat(service.confirm(awaiting.getId(), ownerId).status()).isEqualTo(PaymentStatus.PAID);
        assertThat(awaiting.getPaymentConfirmedAt()).isNotNull();
        assertThatThrownBy(() -> service.confirm(awaiting.getId(), professionalId)).isInstanceOf(PaymentInvalidStatusException.class);

        Attendance pending = attendance(AttendanceStatus.IN_PROGRESS, PaymentStatus.PENDING);
        assertThat(service.confirm(pending.getId(), professionalId).status()).isEqualTo(PaymentStatus.PAID);
        assertThatThrownBy(() -> service.confirm(pending.getId(), clientId)).isInstanceOf(PaymentAccessDeniedException.class);

        Attendance noOwner = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        establishment.setOwner(null);
        assertThatThrownBy(() -> service.confirm(noOwner.getId(), ownerId)).isInstanceOf(PaymentAccessDeniedException.class);
        noOwner.setEstablishment(null);
        noOwner.setProfessional(null);
        assertThatThrownBy(() -> service.confirm(noOwner.getId(), ownerId)).isInstanceOf(PaymentAccessDeniedException.class);
    }

    @Test
    @DisplayName("Sim do modal: marca pago; ja pago ou sem pagamento nao e erro e nao marca; outro da 403")
    void confirmOnFinish() {
        Attendance pending = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        assertThat(service.confirmOnFinish(pending.getId(), professionalId)).isTrue();
        assertThat(pending.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(service.confirmOnFinish(pending.getId(), professionalId)).isFalse();

        Attendance old = attendance(AttendanceStatus.FINISHED, null);
        assertThat(service.confirmOnFinish(old.getId(), professionalId)).isFalse();
        assertThatThrownBy(() -> service.confirmOnFinish(old.getId(), clientId)).isInstanceOf(PaymentAccessDeniedException.class);
    }

    // ─── as pendencias ───

    @Test
    @DisplayName("Pendencia do cliente e o portao: 409 PAYMENT_PENDING com os itens; sem pendencia passa")
    void clientPendingAndGate() {
        Attendance a = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        when(attendanceRepository.findOpenPaymentsOfClient(clientId)).thenReturn(List.of(a));
        when(attendanceRepository.findOpenPaymentsOfClient(strangerId)).thenReturn(List.of());

        PendingPaymentsResponse pending = service.pendingOfClient(clientId);
        assertThat(pending.count()).isEqualTo(1);
        assertThat(pending.total()).isEqualByComparingTo("40");
        assertThat(pending.items().get(0).professionalName()).isEqualTo("Beto");
        assertThat(pending.items().get(0).establishmentName()).isEqualTo("Loja");

        assertThatThrownBy(() -> service.assertNoPendingPayment(clientId))
                .isInstanceOfSatisfying(PaymentPendingException.class,
                        e -> assertThat(e.getPending().items()).hasSize(1));
        service.assertNoPendingPayment(strangerId);
    }

    @Test
    @DisplayName("DEC-83 (SPLIT_PIX): o cliente ve a multa e o total com ela; o profissional e o dono veem so o servico")
    void penaltyOnlyForClient() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "paymentMode",
                new com.ospaulos.vupth.infrastructure.config.PaymentMode("SPLIT_PIX"));
        withPixKey();
        Attendance a = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        ClientPenaltyEvent fine = new ClientPenaltyEvent();
        fine.setAmount(new BigDecimal("5.00"));
        fine.setChargedAttendanceId(a.getId());
        when(penaltyRepository.findByChargedAttendanceIdInOrderByCreatedAtAsc(List.of(a.getId()))).thenReturn(List.of(fine));
        when(attendanceRepository.findOpenPaymentsOfProfessional(professionalId)).thenReturn(List.of(a));

        PaymentResponse forClient = service.getPayment(a.getId(), clientId);
        assertThat(forClient.penaltiesValue()).isEqualByComparingTo("5.00");
        assertThat(forClient.amount()).isEqualByComparingTo("45.00");

        // dois Pix (DEC-83): o do servico vai ao profissional, o da multa ao app; so o cliente recebe o da multa
        assertThat(forClient.pixCopyPaste()).isNotNull().contains("540540.00");
        assertThat(forClient.penaltyPixCopyPaste()).isNotNull().contains("54045.00");
        assertThat(forClient.penaltyHolderName()).isEqualTo("VUPTH");

        PaymentResponse forProfessional = service.getPayment(a.getId(), professionalId);
        assertThat(forProfessional.penaltyPixCopyPaste()).isNull();
        assertThat(forProfessional.penaltiesValue()).isEqualByComparingTo("0");
        assertThat(forProfessional.amount()).isEqualByComparingTo("40.00");
        assertThat(service.pendingOfProfessional(professionalId).items().get(0).amount()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("DEC-85: o Recebi quita por qualquer forma (Pix, cartao, dinheiro ou nota) e guarda a forma; sem informar, fica nula")
    void confirmWithMethod() {
        Attendance card = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        PaymentResponse r = service.confirm(card.getId(), professionalId, com.ospaulos.vupth.domain.payment.PaymentMethod.CARTAO);
        assertThat(r.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(r.paymentMethod()).isEqualTo(com.ospaulos.vupth.domain.payment.PaymentMethod.CARTAO);
        assertThat(card.getPaymentMethod()).isEqualTo(com.ospaulos.vupth.domain.payment.PaymentMethod.CARTAO);

        Attendance noMethod = attendance(AttendanceStatus.FINISHED, PaymentStatus.AWAITING_CONFIRMATION);
        assertThat(service.confirm(noMethod.getId(), ownerId).paymentMethod()).isNull();
        assertThat(noMethod.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);

        Attendance onFinish = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        assertThat(service.confirmOnFinish(onFinish.getId(), professionalId,
                com.ospaulos.vupth.domain.payment.PaymentMethod.NOTA)).isTrue();
        assertThat(onFinish.getPaymentMethod()).isEqualTo(com.ospaulos.vupth.domain.payment.PaymentMethod.NOTA);
        assertThat(onFinish.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("DEC-93: ao confirmar o pagamento o corte e a multa que foi junto entram no livro do app; sem o livro nada quebra")
    void confirmRecordsLedger() {
        com.ospaulos.vupth.domain.payment.service.AppLedgerService ledger =
                mock(com.ospaulos.vupth.domain.payment.service.AppLedgerService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "appLedger", ledger);
        Attendance a = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent penalty = mock(com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent.class);
        when(penalty.getAmount()).thenReturn(new BigDecimal("5.00"));
        when(penalty.getChargedAttendanceId()).thenReturn(a.getId());
        when(penaltyRepository.findByChargedAttendanceIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(penalty));

        service.confirm(a.getId(), professionalId);

        verify(ledger).record(eq(a), argThat(v -> v.compareTo(new BigDecimal("5.00")) == 0), eq(a.getPaymentConfirmedAt()));
    }

    @Test
    @DisplayName("DEC-85 (fase de testes, o padrao): o profissional ve o total como valor a receber, sem rotulo de multa; Pix total; sem Pix da multa")
    void testModeProfessionalSeesTotal() {
        withPixKey();
        Attendance a = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        ClientPenaltyEvent fine = new ClientPenaltyEvent();
        fine.setAmount(new BigDecimal("5.00"));
        fine.setChargedAttendanceId(a.getId());
        when(penaltyRepository.findByChargedAttendanceIdInOrderByCreatedAtAsc(List.of(a.getId()))).thenReturn(List.of(fine));
        when(attendanceRepository.findOpenPaymentsOfProfessional(professionalId)).thenReturn(List.of(a));

        PaymentResponse forClient = service.getPayment(a.getId(), clientId);
        assertThat(forClient.penaltiesValue()).isEqualByComparingTo("5.00");
        assertThat(forClient.amount()).isEqualByComparingTo("45.00");
        assertThat(forClient.pixCopyPaste()).contains("540545.00");
        assertThat(forClient.penaltyPixCopyPaste()).isNull();

        PaymentResponse forProfessional = service.getPayment(a.getId(), professionalId);
        assertThat(forProfessional.penaltiesValue()).isEqualByComparingTo("0");
        assertThat(forProfessional.servicesValue()).isEqualByComparingTo("45.00");
        assertThat(forProfessional.amount()).isEqualByComparingTo("45.00");
        assertThat(service.pendingOfProfessional(professionalId).items().get(0).amount()).isEqualByComparingTo("45.00");
        assertThat(service.amountForProfessional(a.getId())).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("push-pagamento-pendente: o valor so existe com o pagamento PENDENTE (servicos mais multas)")
    void pendingAmount() {
        Attendance pending = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        Attendance awaiting = attendance(AttendanceStatus.FINISHED, PaymentStatus.AWAITING_CONFIRMATION);
        Attendance paid = attendance(AttendanceStatus.FINISHED, PaymentStatus.PAID);
        ClientPenaltyEvent fine = new ClientPenaltyEvent();
        fine.setAmount(new BigDecimal("5.00"));
        fine.setChargedAttendanceId(pending.getId());
        when(penaltyRepository.findByChargedAttendanceIdInOrderByCreatedAtAsc(List.of(pending.getId())))
                .thenReturn(List.of(fine));

        assertThat(service.pendingAmountOf(pending.getId())).isEqualByComparingTo("45.00");
        assertThat(service.pendingAmountOf(awaiting.getId())).isNull();
        assertThat(service.pendingAmountOf(paid.getId())).isNull();
    }

    @Test
    @DisplayName("push-pagamento-pendente: os lembretes trazem cliente, atendimento, loja e o valor com a multa")
    void reminders() {
        Attendance a = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        OffsetDateTime before = OffsetDateTime.now();
        when(attendanceRepository.findPendingPaymentsFinishedBefore(before)).thenReturn(List.of(a));

        var reminders = service.remindersFinishedBefore(before);

        assertThat(reminders).hasSize(1);
        assertThat(reminders.get(0).clientId()).isEqualTo(clientId);
        assertThat(reminders.get(0).attendanceId()).isEqualTo(a.getId());
        assertThat(reminders.get(0).establishmentId()).isEqualTo(establishment.getId());
        assertThat(reminders.get(0).amount()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("Dot do profissional: awaiting_count so dos AWAITING; itens com o nome (convidado tambem)")
    void professionalPending() {
        Attendance awaiting = attendance(AttendanceStatus.FINISHED, PaymentStatus.AWAITING_CONFIRMATION);
        Attendance guest = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        guest.setClient(null);
        guest.setGuestName("Visitante");
        when(attendanceRepository.findOpenPaymentsOfProfessional(professionalId)).thenReturn(List.of(awaiting, guest));

        ProfessionalPendingPaymentsResponse r = service.pendingOfProfessional(professionalId);

        assertThat(r.awaitingCount()).isEqualTo(1);
        assertThat(r.items()).extracting(ProfessionalPendingPaymentsResponse.Item::clientName)
                .containsExactly("Ana", "Visitante");

        when(attendanceRepository.findOpenPaymentsOfProfessional(strangerId)).thenReturn(List.of());
        assertThat(service.pendingOfProfessional(strangerId).items()).isEmpty();
    }

    // ─── testes-de-pagamento-massivos (DEC-92): todos os ramos do dinheiro ───

    private void chargePenalty(Attendance a, String amount) {
        ClientPenaltyEvent fine = new ClientPenaltyEvent();
        fine.setAmount(new BigDecimal(amount));
        fine.setChargedAttendanceId(a.getId());
        when(penaltyRepository.findByChargedAttendanceIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(fine));
    }

    @Test
    @DisplayName("SPLIT_PIX: sem o Pix do app, sem multa ou com o app sem chave, a multa nao gera o copia-e-cola dela")
    void splitWithoutPenaltyPix() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "paymentMode",
                new com.ospaulos.vupth.infrastructure.config.PaymentMode("SPLIT_PIX"));
        withPixKey();
        Attendance a = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);

        // sem multa carregada
        assertThat(service.getPayment(a.getId(), clientId).penaltyPixCopyPaste()).isNull();

        chargePenalty(a, "5.00");
        // multa, mas o app nao tem Pix configurado no contexto (testes de unidade)
        org.springframework.test.util.ReflectionTestUtils.setField(service, "appPix", null);
        PaymentResponse noAppPix = service.getPayment(a.getId(), clientId);
        assertThat(noAppPix.penaltiesValue()).isEqualByComparingTo("5.00");
        assertThat(noAppPix.penaltyPixCopyPaste()).isNull();
        assertThat(noAppPix.penaltyHolderName()).isNull();

        // multa, app com Pix mas sem chave: o copia-e-cola e o titular saem nulos
        org.springframework.test.util.ReflectionTestUtils.setField(service, "appPix",
                new com.ospaulos.vupth.infrastructure.config.AppPix("", "VUPTH", "SAO PAULO"));
        PaymentResponse unconfigured = service.getPayment(a.getId(), clientId);
        assertThat(unconfigured.penaltyPixCopyPaste()).isNull();
        assertThat(unconfigured.penaltyHolderName()).isNull();
    }

    @Test
    @DisplayName("quem recebe o Pix: profissional sem chave e sem dono, dono sem chave, atendimento sem profissional ou sem loja")
    void pixKeyOwnerCorners() {
        BaseUser dona = establishment.getOwner().getBaseUser();
        dona.setPixKeyType(PixKeyType.EMAIL);
        dona.setPixKey("dona@mail.com");
        dona.setPixHolderName("Dona");

        // sem profissional na linha (removido): vale a chave do dono, e nao e "PROFESSIONAL"
        Attendance noProfessional = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        noProfessional.setProfessional(null);
        PaymentResponse r = service.getPayment(noProfessional.getId(), clientId);
        assertThat(r.pixOwner()).isEqualTo("OWNER");
        assertThat(r.pixCopyPaste()).contains("dona@mail.com");

        // sem loja: sem dono, sem cidade e sem chave
        Attendance noEstablishment = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        noEstablishment.setEstablishment(null);
        PaymentResponse none = service.getPayment(noEstablishment.getId(), clientId);
        assertThat(none.pixCopyPaste()).isNull();
        assertThat(none.pixOwner()).isNull();

        // loja sem dono: sem chave
        establishment.setOwner(null);
        Attendance noOwner = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        assertThat(service.getPayment(noOwner.getId(), clientId).pixCopyPaste()).isNull();

        // dono sem chave Pix: sem chave
        Owner ownerWithoutKey = new Owner(user("Dona sem chave"));
        establishment.setOwner(ownerWithoutKey);
        Attendance ownerNoKey = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        assertThat(service.getPayment(ownerNoKey.getId(), clientId).pixCopyPaste()).isNull();
    }

    @Test
    @DisplayName("amountForProfessional: o servico no SPLIT_PIX e o total (servico + multa) na fase de testes")
    void amountForProfessionalByMode() {
        Attendance a = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        chargePenalty(a, "5.00");

        assertThat(service.amountForProfessional(a.getId())).isEqualByComparingTo("45.00");

        org.springframework.test.util.ReflectionTestUtils.setField(service, "paymentMode",
                new com.ospaulos.vupth.infrastructure.config.PaymentMode("SPLIT_PIX"));
        assertThat(service.amountForProfessional(a.getId())).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("a chave do profissional vale mesmo com o atendimento sem loja: o Pix sai sem cidade")
    void keyWithoutEstablishmentHasNoCity() {
        withPixKey();
        Attendance a = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        a.setEstablishment(null);

        PaymentResponse r = service.getPayment(a.getId(), clientId);

        assertThat(r.pixOwner()).isEqualTo("PROFESSIONAL");
        assertThat(r.pixCopyPaste()).contains("beto@mail.com");
    }

    // ─── mutacao-pit-no-dinheiro (DEC-99): os mutantes que sobreviveram ───

    @Test
    @DisplayName("PIT: cancelado e ainda pendente nao tem pagamento: dizer que pagou e o Recebi dao 409 PaymentNotAvailable, nao seguem")
    void claimAndConfirmNeedAnAvailablePayment() {
        Attendance cancelled = attendance(AttendanceStatus.CANCELLED, PaymentStatus.PENDING);

        assertThatThrownBy(() -> service.claim(cancelled.getId(), clientId)).isInstanceOf(PaymentNotAvailableException.class);
        assertThatThrownBy(() -> service.confirm(cancelled.getId(), professionalId)).isInstanceOf(PaymentNotAvailableException.class);
        assertThat(cancelled.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("PIT: apagar a chave limpa a chave, o tipo e o titular; o titular de 25 letras vale e o de 26 nao")
    void deleteClearsEverythingAndHolderLimit() {
        when(userRepository.findById(professionalId)).thenReturn(Optional.of(professional.getBaseUser()));
        service.setPixKey(professionalId, new PixKeyRequest(PixKeyType.EMAIL, "b@m.com", "x".repeat(25)));
        assertThat(professional.getBaseUser().getPixHolderName()).hasSize(25);

        service.deletePixKey(professionalId);

        assertThat(professional.getBaseUser().getPixKey()).isNull();
        assertThat(professional.getBaseUser().getPixKeyType()).isNull();
        assertThat(professional.getBaseUser().getPixHolderName()).isNull();
    }

    @Test
    @DisplayName("PIT: a fila do profissional conta so os que dizem que pagaram; o status de um atendimento sai direto")
    void awaitingCountAndStatusOf() {
        Attendance awaiting = attendance(AttendanceStatus.FINISHED, PaymentStatus.AWAITING_CONFIRMATION);
        Attendance awaitingToo = attendance(AttendanceStatus.FINISHED, PaymentStatus.AWAITING_CONFIRMATION);
        Attendance pending = attendance(AttendanceStatus.FINISHED, PaymentStatus.PENDING);
        when(attendanceRepository.findOpenPaymentsOfProfessional(professionalId))
                .thenReturn(List.of(awaiting, awaitingToo, pending));

        assertThat(service.pendingOfProfessional(professionalId).awaitingCount()).isEqualTo(2);
        assertThat(service.statusOf(awaiting.getId())).isEqualTo(PaymentStatus.AWAITING_CONFIRMATION);
        assertThat(service.statusOf(pending.getId())).isEqualTo(PaymentStatus.PENDING);
    }
}
