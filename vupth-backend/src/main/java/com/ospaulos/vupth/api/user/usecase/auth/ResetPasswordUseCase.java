package com.ospaulos.vupth.api.user.usecase.auth;

import com.ospaulos.vupth.api.user.dto.request.auth.ResetPasswordRequest;
import com.ospaulos.vupth.api.user.exception.InvalidResetCodeException;
import com.ospaulos.vupth.api.user.exception.ResetCodeExpiredException;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.PasswordResetCode;
import com.ospaulos.vupth.domain.user.repository.PasswordResetCodeRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * "Esqueci minha senha" — consumo do código (backlog #1). Confere o código de 6 dígitos e define a
 * nova senha. Código inexistente/errado/usado/esgotado -> {@link InvalidResetCodeException} (400
 * {@code RESET_CODE_INVALID}); código válido mas vencido -> {@link ResetCodeExpiredException} (400
 * {@code RESET_CODE_EXPIRED}). Uso único: ao acertar, o código é marcado como consumido.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResetPasswordUseCase {

    private final UserRepository userRepository;
    private final PasswordResetCodeRepository resetCodeRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void execute(ResetPasswordRequest request) {
        // E-mail desconhecido cai no mesmo erro genérico de código inválido (não vaza existência).
        BaseUser user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidResetCodeException::new);

        PasswordResetCode reset = resetCodeRepository
                .findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow(InvalidResetCodeException::new);

        // Já usado ou tentativas esgotadas -> inválido.
        if (reset.isConsumed() || reset.getAttempts() >= PasswordResetCode.MAX_ATTEMPTS) {
            throw new InvalidResetCodeException();
        }

        // Expirado -> queima o código e sinaliza (front pede um novo).
        if (reset.isExpired()) {
            reset.setConsumed(true);
            resetCodeRepository.save(reset);
            throw new ResetCodeExpiredException();
        }

        // Código errado -> conta a tentativa; ao estourar o teto, invalida de vez.
        if (!passwordEncoder.matches(request.code(), reset.getCodeHash())) {
            reset.setAttempts(reset.getAttempts() + 1);
            if (reset.getAttempts() >= PasswordResetCode.MAX_ATTEMPTS) {
                reset.setConsumed(true);
            }
            resetCodeRepository.save(reset);
            throw new InvalidResetCodeException();
        }

        // Acertou: troca a senha e queima o código (uso único).
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        reset.setConsumed(true);
        resetCodeRepository.save(reset);

        log.info("[reset-password] senha redefinida com sucesso para o usuário {}", user.getId());
    }
}
