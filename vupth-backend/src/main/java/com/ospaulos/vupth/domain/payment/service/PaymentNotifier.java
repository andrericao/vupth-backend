package com.ospaulos.vupth.domain.payment.service;

import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.shared.Brl;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Os avisos do pagamento (pagamento-pix, adendo 1): push e WS pessoal, cada lado com a sua mensagem.
 * O profissional que tocou "Recebi" ve a mensagem na tela, pelo 200 — nao recebe push de si mesmo.
 * Best-effort: chamado depois do commit, nunca lanca.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentNotifier {

    static final String PAYMENT_CLAIMED = "PAYMENT_CLAIMED";
    static final String PAYMENT_CONFIRMED = "PAYMENT_CONFIRMED";
    static final String PAYMENT_REFUSED = "PAYMENT_REFUSED";

    private final AttendanceRepository attendanceRepository;
    private final QueueWebSocketPublisher queuePublisher;
    private final PushNotificationPublisher pushPublisher;

    /** "Ja paguei": o profissional recebe notificacao e o dot. */
    public void claimed(UUID attendanceId, BigDecimal amount) {
        withAttendance(attendanceId, a -> {
            UUID professionalId = a.getProfessional().getId();
            String client = a.getClient() != null ? a.getClient().getFirstName() : "convidado";
            // push-confirmar-pagamento-texto (24/10): "Pagamento de R$ XX,XX do cliente <nome>", sem a loja
            String message = "Pagamento de " + money(amount) + " do cliente " + client;
            queuePublisher.notifyClient(professionalId, QueueNotification.of(
                    PAYMENT_CLAIMED, attendanceId, a.getEstablishment().getId(), message));
            pushPublisher.notifyUser(professionalId, "Confirmar pagamento", message,
                    data(PAYMENT_CLAIMED, a));
        });
    }

    /** "Recebi" (botao ou o "Sim" do modal): o cliente recebe a mensagem dele. */
    public void confirmed(UUID attendanceId) {
        withAttendance(attendanceId, a -> {
            if (a.getClient() == null) {
                return; // convidado nao tem conta
            }
            String professional = a.getProfessional().getName();
            String body = a.getStatus() == AttendanceStatus.FINISHED
                    ? "Obrigado, " + a.getClient().getFirstName() + "! " + professional + " confirmou o seu pagamento. Atendimento concluído."
                    : "Obrigado, " + a.getClient().getFirstName() + "! " + professional + " confirmou o seu pagamento.";
            queuePublisher.notifyClient(a.getClient().getId(), QueueNotification.of(
                    PAYMENT_CONFIRMED, attendanceId, a.getEstablishment().getId(), body));
            pushPublisher.notifyUser(a.getClient().getId(), "Pagamento efetuado", body,
                    data(PAYMENT_CONFIRMED, a));
        });
    }

    /** "Nao recebi": o cliente fica sabendo que o profissional nao confirmou e que o atendimento segue pendente. */
    public void refused(UUID attendanceId) {
        withAttendance(attendanceId, a -> {
            if (a.getClient() == null) {
                return;
            }
            String body = a.getProfessional().getName() + " não confirmou o seu pagamento. Confira se o pagamento saiu da sua conta; "
                    + "o atendimento segue pendente.";
            queuePublisher.notifyClient(a.getClient().getId(), QueueNotification.of(
                    PAYMENT_REFUSED, attendanceId, a.getEstablishment().getId(), body));
            pushPublisher.notifyUser(a.getClient().getId(), "Pagamento não confirmado", body, data(PAYMENT_REFUSED, a));
        });
    }

    private void withAttendance(UUID attendanceId, java.util.function.Consumer<Attendance> action) {
        try {
            attendanceRepository.findById(attendanceId).ifPresent(action);
        } catch (Exception e) {
            log.warn("[Payment] falha ao avisar o pagamento do attendance {}: {}", attendanceId, e.getMessage());
        }
    }

    private static Map<String, Object> data(String type, Attendance a) {
        return Map.of("type", type,
                "attendanceId", a.getId().toString(),
                "establishmentId", String.valueOf(a.getEstablishment().getId()));
    }

    static String money(BigDecimal amount) {
        return Brl.of(amount);
    }
}
