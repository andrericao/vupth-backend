package com.ospaulos.vupth.domain.payment.service;

import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
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
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private final AttendanceRepository attendanceRepository;
    private final QueueWebSocketPublisher queuePublisher;
    private final PushNotificationPublisher pushPublisher;

    /** "Ja paguei": o profissional recebe notificacao e o dot. */
    public void claimed(UUID attendanceId) {
        withAttendance(attendanceId, a -> {
            UUID professionalId = a.getProfessional().getId();
            String client = a.getClient() != null ? a.getClient().getFirstName() : "O cliente";
            // multi-loja: o dono que tem varias lojas precisa saber de qual e o pagamento
            String store = a.getEstablishment().getName() == null ? "" : " na " + a.getEstablishment().getName();
            String message = client + " diz que pagou " + money(a.getTotalValue()) + store + ". Confira se consta na sua conta.";
            queuePublisher.notifyClient(professionalId, QueueNotification.of(
                    PAYMENT_CLAIMED, attendanceId, a.getEstablishment().getId(), message));
            pushPublisher.notifyUser(professionalId, "Confira o pagamento", message,
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
        return String.format(PT_BR, "R$ %,.2f", amount == null ? BigDecimal.ZERO : amount);
    }
}
