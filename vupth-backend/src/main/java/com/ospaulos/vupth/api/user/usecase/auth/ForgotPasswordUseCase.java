package com.ospaulos.vupth.api.user.usecase.auth;

import com.ospaulos.vupth.domain.user.exception.EmailNotRegisteredException;
import com.ospaulos.vupth.api.user.dto.request.auth.ForgotPasswordRequest;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.PasswordResetCode;
import com.ospaulos.vupth.domain.user.repository.PasswordResetCodeRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

/**
 * "Esqueci minha senha" — fatia 1 (backlog #1). Gera um código de 6 dígitos, guarda só o hash
 * (validade 15min, uso único) e dispara por e-mail.
 *
 * <p><b>E-mail sem conta:</b> {@link EmailNotRegisteredException} (404 {@code EMAIL_NOT_FOUND}) — decisão
 * D2 (16/09), BAK-6. Até 03/10 a resposta era 200 sempre (anti-enumeração); a sondagem em massa
 * agora esbarra no rate limit das rotas de autenticação.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForgotPasswordUseCase {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetCodeRepository resetCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;

    @Value("${vupth.mail.reset-code.ttl-minutes:15}")
    private long ttlMinutes;

    @Transactional
    public void execute(ForgotPasswordRequest request) {
        BaseUser user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> {
                    log.info("[forgot-password] e-mail não cadastrado");
                    return new EmailNotRegisteredException();
                });

        // Um código ativo por vez: o novo pedido invalida os anteriores.
        resetCodeRepository.deleteByUserId(user.getId());

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        PasswordResetCode entity = new PasswordResetCode(
                user.getId(),
                passwordEncoder.encode(code),
                OffsetDateTime.now().plusMinutes(ttlMinutes));
        resetCodeRepository.save(entity);

        // Best-effort: falha de entrega é logada e engolida — não vaza ao usuário nem desfaz o código.
        try {
            emailSender.sendPasswordResetCode(user.getEmail(), code);
        } catch (EmailDeliveryException e) {
            log.error("[forgot-password] falha ao enviar e-mail (código segue válido): {}", e.getMessage());
        }
    }
}
