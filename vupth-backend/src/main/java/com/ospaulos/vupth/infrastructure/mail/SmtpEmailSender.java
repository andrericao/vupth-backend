package com.ospaulos.vupth.infrastructure.mail;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Envia e-mail via SMTP ({@link JavaMailSender}, do {@code spring-boot-starter-mail}). Ativo quando
 * {@code vupth.mail.provider=smtp}; caso contrário cai no {@link NoOpEmailSender}.
 *
 * <p>Funciona com qualquer SMTP grátis (Gmail 500/dia, Brevo 300/dia sem cartão, Mailtrap p/ teste),
 * configurado só por env vars ({@code spring.mail.host/port/username/password} + {@code vupth.mail.from}).
 * Nenhuma mudança de código entre provedores — este bean é o único ponto concreto.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vupth.mail", name = "provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;
    private final String host;
    private final int port;

    public SmtpEmailSender(JavaMailSender mailSender,
                           @Value("${vupth.mail.from:no-reply@vupth.app}") String from,
                           @Value("${spring.mail.host:}") String host,
                           @Value("${spring.mail.port:587}") int port) {
        this.mailSender = mailSender;
        this.from = from;
        this.host = host;
        this.port = port;
    }

    /**
     * Contraparte do aviso do {@link NoOpEmailSender}: o boot diz qual provider esta no ar, sem que
     * ninguem precise pedir um reset para descobrir. Host vazio com provider=smtp e o caso pior —
     * o bean sobe, o endpoint responde 200 e cada envio falha para um destino que nao existe.
     */
    @PostConstruct
    void anunciaProvider() {
        if (host == null || host.isBlank()) {
            log.error("[MAIL] provider=smtp mas spring.mail.host esta VAZIO — nenhum e-mail vai sair. Configure MAIL_HOST.");
        } else {
            log.info("[MAIL] provider=smtp — host={} port={} from={}", host, port, from);
        }
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String code) {
        send(toEmail, PasswordResetMessage.SUBJECT, PasswordResetMessage.body(code));
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        if (ReservedEmailDomains.isReserved(toEmail)) {
            log.info("[MAIL:smtp] '{}' nao enviado para {}: dominio reservado de teste", subject, toEmail);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            log.info("[MAIL:smtp] '{}' enviado para {}", subject, toEmail);
        } catch (MailException e) {
            log.error("[MAIL:smtp] falha ao enviar '{}' para {}: {}", subject, toEmail, e.getMessage());
            throw new EmailDeliveryException("Falha ao enviar e-mail", e);
        }
    }
}
