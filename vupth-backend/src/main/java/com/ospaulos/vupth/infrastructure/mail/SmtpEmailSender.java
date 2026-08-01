package com.ospaulos.vupth.infrastructure.mail;

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

    public SmtpEmailSender(JavaMailSender mailSender,
                           @Value("${vupth.mail.from:no-reply@vupth.app}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject("VUPTH — código de recuperação de senha");
        message.setText(
                "Seu código de recuperação de senha é: " + code + "\n\n"
                        + "Ele expira em 15 minutos e só pode ser usado uma vez.\n"
                        + "Se você não pediu a troca de senha, ignore este e-mail.");
        try {
            mailSender.send(message);
            log.info("[MAIL:smtp] reset code enviado para {}", toEmail);
        } catch (MailException e) {
            log.error("[MAIL:smtp] falha ao enviar reset code para {}: {}", toEmail, e.getMessage());
            throw new EmailDeliveryException("Falha ao enviar e-mail de reset", e);
        }
    }
}
