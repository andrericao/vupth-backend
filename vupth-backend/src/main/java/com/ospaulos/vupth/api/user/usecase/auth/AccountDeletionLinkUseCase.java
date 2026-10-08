package com.ospaulos.vupth.api.user.usecase.auth;

import com.ospaulos.vupth.api.user.dto.request.auth.AccountDeletionConfirmRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.AccountDeletionRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.AccountDeletionLinkResponse;
import com.ospaulos.vupth.api.user.exception.AccountDeletionLinkInvalidException;
import com.ospaulos.vupth.api.user.exception.InvalidCurrentPasswordException;
import com.ospaulos.vupth.api.user.usecase.profile.DeleteAccountUseCase;
import com.ospaulos.vupth.domain.user.model.AccountDeletionToken;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.AccountDeletionTokenRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Exclusao de conta pela web, sem login (exclusao-por-link, Andre 08/10: "ele confirma por meio de
 * link de e-mail recebido e digita a senha novamente e exclui").
 *
 * <ol>
 *   <li><b>Pedir</b> — sempre 202, exista a conta ou nao (nao revela quem tem conta). Com conta ativa,
 *       sai o e-mail com o link de uso unico.</li>
 *   <li><b>Abrir</b> — devolve o e-mail mascarado, para a tela mostrar de qual conta se trata.</li>
 *   <li><b>Confirmar</b> — confere a senha e chama o MESMO {@link DeleteAccountUseCase} do app: os
 *       portoes 409 e o desmonte sao os mesmos. Senha errada conta tentativa (a 5a mata o link) e o
 *       link segue valendo; 409 tambem nao gasta o link.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionLinkUseCase {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AccountDeletionTokenRepository tokenRepository;
    private final DeleteAccountUseCase deleteAccountUseCase;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;

    @Value("${vupth.account-deletion.link-base-url:https://vupth.pages.dev/excluir-conta}")
    private String linkBaseUrl;

    @Value("${vupth.account-deletion.ttl-minutes:30}")
    private long ttlMinutes;

    @Transactional
    public void request(AccountDeletionRequest request) {
        BaseUser user = userRepository.findByEmail(request.email())
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()) && !u.isDeleted())
                .orElse(null);
        if (user == null) {
            log.info("[account-deletion] pedido para e-mail sem conta ativa — 202 sem e-mail");
            return;
        }

        // Um link ativo por vez: o novo pedido invalida os anteriores.
        tokenRepository.deleteByUserId(user.getId());

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokenRepository.save(new AccountDeletionToken(
                user.getId(), sha256(token), OffsetDateTime.now().plusMinutes(ttlMinutes)));

        String link = linkBaseUrl + "?token=" + token;
        // Best-effort, como o reset: a falha de entrega e logada e engolida — o 202 nao muda.
        try {
            emailSender.send(user.getEmail(), "Excluir sua conta VUPTH",
                    "Recebemos um pedido para excluir a sua conta VUPTH.\n\n"
                            + "Para confirmar, abra o link abaixo e digite a sua senha. Ele vale "
                            + ttlMinutes + " minutos e só pode ser usado uma vez:\n\n" + link + "\n\n"
                            + "Se não foi você, ignore este e-mail: nada muda na sua conta.");
        } catch (EmailDeliveryException e) {
            log.error("[account-deletion] falha ao enviar o link (segue valido): {}", e.getMessage());
        }
        log.info("[account-deletion] link emitido — user={}", user.getId());
    }

    @Transactional(readOnly = true)
    public AccountDeletionLinkResponse peek(String token) {
        AccountDeletionToken link = usable(token);
        BaseUser user = userRepository.findById(link.getUserId())
                .orElseThrow(AccountDeletionLinkInvalidException::new);
        return new AccountDeletionLinkResponse(mask(user.getEmail()), link.getExpiresAt());
    }

    // noRollbackFor: a tentativa errada tem que ficar gravada mesmo com o 401.
    @Transactional(noRollbackFor = InvalidCurrentPasswordException.class)
    public void confirm(AccountDeletionConfirmRequest request) {
        AccountDeletionToken link = usable(request.token());
        BaseUser user = userRepository.findById(link.getUserId())
                .orElseThrow(AccountDeletionLinkInvalidException::new);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            link.registerWrongPassword();
            tokenRepository.save(link);
            log.info("[account-deletion] senha errada — user={} tentativas={}", user.getId(), link.getAttempts());
            throw new InvalidCurrentPasswordException();
        }

        // Os portoes 409 rodam aqui dentro; se recusar, o rollback deixa o link como estava.
        deleteAccountUseCase.execute(user.getId(), request.password());
        tokenRepository.deleteByUserId(user.getId());
        log.info("[account-deletion] conta excluida pelo link — user={}", user.getId());
    }

    private AccountDeletionToken usable(String token) {
        if (token == null || token.isBlank()) {
            throw new AccountDeletionLinkInvalidException();
        }
        return tokenRepository.findByTokenHash(sha256(token))
                .filter(AccountDeletionToken::isUsable)
                .orElseThrow(AccountDeletionLinkInvalidException::new);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }

    /** "andre@gmail.com" -> "an***@gm***.com"; "a@b.com.br" -> "a***@b***.com.br". */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int dot = domain.indexOf('.');
        String name = dot < 0 ? domain : domain.substring(0, dot);
        String tld = dot < 0 ? "" : domain.substring(dot);
        return local.substring(0, Math.min(2, local.length())) + "***@"
                + name.substring(0, Math.min(2, name.length())) + "***" + tld;
    }
}
