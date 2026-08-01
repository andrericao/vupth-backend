package com.ospaulos.vupth.api.user.usecase.auth;

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
import java.util.Optional;

/**
 * "Esqueci minha senha" — fatia 1 (backlog #1). Gera um código de 6 dígitos, guarda só o hash
 * (validade 15min, uso único) e dispara por e-mail.
 *
 * <p><b>Anti-enumeração:</b> a resposta do endpoint é sempre 200, exista o e-mail ou não. Se não
 * existir, não fazemos nada aqui (sem código, sem e-mail) — o chamador não sabe a diferença.</p>
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
        Optional<BaseUser> maybeUser = userRepository.findByEmail(request.email());
        if (maybeUser.isEmpty()) {
            // Anti-enumeração: silêncio proposital — o endpoint responde 200 igual.
            log.info("[forgot-password] e-mail não encontrado (resposta 200 mesmo assim)");
            return;
        }
        BaseUser user = maybeUser.get();

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
