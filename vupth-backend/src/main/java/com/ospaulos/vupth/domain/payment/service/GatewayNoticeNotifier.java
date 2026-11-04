package com.ospaulos.vupth.domain.payment.service;

import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * virada-do-gateway (DEC-163): quando o Painel liga o Asaas REAL, todo profissional recebe o aviso de 7 dias por push e por e-mail
 * (o banner vem do {@code GET /payment-gateway}). Falha de um nao impede os outros; nunca derruba a ligacao.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayNoticeNotifier {

    private final ProfessionalRepository professionals;
    private final PushNotificationPublisher push;
    private final EmailSender email;

    public int notifyProfessionals(OffsetDateTime changesAt) {
        String day = changesAt.atZoneSameInstant(BusinessClock.zone()).format(DateTimeFormatter.ofPattern("dd/MM"));
        String title = "Pagamentos pelo app a partir de " + day;
        String body = "Os pagamentos dos clientes passam a ser recebidos pelo app em " + day
                + ". Para continuar atendendo, informe o seu CPF nas Configurações.";
        int sent = 0;
        for (Professional p : professionals.findAll()) {
            try {
                if (p.getBaseUser() == null || !Boolean.TRUE.equals(p.getBaseUser().getIsActive())) {
                    continue;
                }
                push.notifyUser(p.getId(), title, body, Map.of("type", "GATEWAY_NOTICE", "changesAt", changesAt.toString()));
                if (p.getBaseUser().getEmail() != null) {
                    email.send(p.getBaseUser().getEmail(), "[VUPTH] " + title, body);
                }
                sent++;
            } catch (RuntimeException e) {
                log.warn("[Gateway] aviso de 7 dias nao enviado ao profissional {}: {}", p.getId(), e.toString());
            }
        }
        log.info("[Gateway] aviso de 7 dias enviado a {} profissionais (vale a partir de {})", sent, changesAt);
        return sent;
    }
}
