package com.ospaulos.vupth.domain.payment.service;

import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests — os avisos do pagamento: cada lado com a sua mensagem (pagamento-pix, adendos 1 e 3). */
class PaymentNotifierTest {

    private AttendanceRepository attendanceRepository;
    private QueueWebSocketPublisher queuePublisher;
    private PushNotificationPublisher pushPublisher;
    private PaymentNotifier notifier;
    private Attendance attendance;
    private final UUID clientId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        attendanceRepository = mock(AttendanceRepository.class);
        queuePublisher = mock(QueueWebSocketPublisher.class);
        pushPublisher = mock(PushNotificationPublisher.class);
        notifier = new PaymentNotifier(attendanceRepository, queuePublisher, pushPublisher);

        BaseUser cu = new BaseUser("a@b.com");
        cu.setFirstName("Ana");
        Client client = new Client(cu);
        client.setId(clientId);
        BaseUser pu = new BaseUser("b@b.com");
        pu.setFirstName("Beto");
        Professional professional = new Professional(pu);
        professional.setId(professionalId);
        Establishment establishment = new Establishment();
        establishment.setId(UUID.randomUUID());
        establishment.setName("Barbearia Central");
        attendance = new Attendance();
        attendance.setId(UUID.randomUUID());
        attendance.setClient(client);
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setStatus(AttendanceStatus.FINISHED);
        when(attendanceRepository.findById(attendance.getId())).thenReturn(Optional.of(attendance));
    }

    @Test
    @DisplayName("Ja paguei: o profissional recebe push e WS PAYMENT_CLAIMED com o valor, para conferir")
    void claimedNotifiesProfessional() {
        notifier.claimed(attendance.getId(), new BigDecimal("1045.5"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(pushPublisher).notifyUser(eq(professionalId), eq("Confira o pagamento"), body.capture(), anyMap());
        assertThat(body.getValue()).isEqualTo("Ana diz que pagou R$ 1.045,50 na Barbearia Central. Confira se consta na sua conta.");
        ArgumentCaptor<QueueNotification> ws = ArgumentCaptor.forClass(QueueNotification.class);
        verify(queuePublisher).notifyClient(eq(professionalId), ws.capture());
        assertThat(ws.getValue().type()).isEqualTo("PAYMENT_CLAIMED");
    }

    @Test
    @DisplayName("Convidado no Ja paguei vira 'O cliente'; valor nulo vira R$ 0,00")
    void claimedGuest() {
        attendance.setClient(null);
        notifier.claimed(attendance.getId(), null);

        verify(pushPublisher).notifyUser(eq(professionalId), any(), eq("O cliente diz que pagou R$ 0,00 na Barbearia Central. Confira se consta na sua conta."), anyMap());
    }

    @Test
    @DisplayName("Recebi: o cliente recebe 'Pagamento efetuado' com o agradecimento; na fila, sem 'concluido'")
    void confirmedNotifiesClient() {
        notifier.confirmed(attendance.getId());
        verify(pushPublisher).notifyUser(eq(clientId), eq("Pagamento efetuado"),
                eq("Obrigado, Ana! Beto confirmou o seu pagamento. Atendimento concluído."), anyMap());
        ArgumentCaptor<QueueNotification> ws = ArgumentCaptor.forClass(QueueNotification.class);
        verify(queuePublisher).notifyClient(eq(clientId), ws.capture());
        assertThat(ws.getValue().type()).isEqualTo("PAYMENT_CONFIRMED");

        attendance.setStatus(AttendanceStatus.SCHEDULED);
        notifier.confirmed(attendance.getId());
        verify(pushPublisher).notifyUser(eq(clientId), eq("Pagamento efetuado"),
                eq("Obrigado, Ana! Beto confirmou o seu pagamento."), anyMap());
    }

    @Test
    @DisplayName("Recebi de convidado nao avisa ninguem; o profissional nunca recebe push do proprio Recebi")
    void confirmedGuestAndNoSelfPush() {
        attendance.setClient(null);
        notifier.confirmed(attendance.getId());

        verifyNoInteractions(pushPublisher, queuePublisher);
    }

    @Test
    @DisplayName("Atendimento sumiu ou o banco falhou: nao lanca")
    void bestEffort() {
        UUID missing = UUID.randomUUID();
        when(attendanceRepository.findById(missing)).thenReturn(Optional.empty());
        notifier.confirmed(missing);
        verifyNoInteractions(pushPublisher);

        when(attendanceRepository.findById(attendance.getId())).thenThrow(new RuntimeException("banco fora"));
        assertThatCode(() -> notifier.claimed(attendance.getId(), BigDecimal.ONE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Nao recebi: o cliente recebe push e WS PAYMENT_REFUSED dizendo que o profissional nao confirmou; convidado nao recebe")
    void refusedNotifiesClient() {
        notifier.refused(attendance.getId());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(pushPublisher).notifyUser(eq(clientId), eq("Pagamento não confirmado"), body.capture(), anyMap());
        assertThat(body.getValue()).startsWith("Beto não confirmou o seu pagamento.").contains("segue pendente");
        ArgumentCaptor<QueueNotification> ws = ArgumentCaptor.forClass(QueueNotification.class);
        verify(queuePublisher).notifyClient(eq(clientId), ws.capture());
        assertThat(ws.getValue().type()).isEqualTo("PAYMENT_REFUSED");

        attendance.setClient(null);
        clearInvocations(pushPublisher, queuePublisher);
        notifier.refused(attendance.getId());
        verifyNoInteractions(pushPublisher, queuePublisher);
    }
}
