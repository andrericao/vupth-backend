package com.ospaulos.vupth.infrastructure.security.jwt.token;

import com.ospaulos.vupth.domain.refreshToken.exception.RefreshTokenInvalidException;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.refreshToken.repository.RefreshTokenRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenProvider {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-token.expiration:P7D}")
    private Duration expirationTime;

    /** Teto de saltos ao encerrar uma sessao — protege contra cadeia de tokens malformada. */
    private static final int MAX_SESSION_CHAIN = 50;

    /**
     * Emite um refresh token novo <b>sem derrubar as sessoes que ja existem</b>.
     *
     * <p>Ate 06/09 esta linha era {@code deleteByUserId(user.getId())}: emitir um token apagava
     * <b>todos</b> os do usuario. Como o login tambem passa por aqui, o efeito era
     * <b>uma sessao por conta</b> — entrar no Partner derrubava o Client, e vice-versa. Num produto
     * onde os papeis se acumulam (a mesma pessoa e CLIENT e PROFESSIONAL) isso e permanente, e o app
     * so consegue dizer "sessao expirada". Nao era intencional: o Andre confirmou em 06/09.</p>
     *
     * <p>No lugar, higiene: saem os tokens <b>mortos</b> deste usuario (vencidos, e revogados ha mais
     * de um dia). A tabela continua sem crescer sem limite, e o aparelho do lado continua logado. A
     * folga de um dia para os revogados existe para nao apagar o elo da janela de graca antes da hora
     * e para deixar rastro de reuso de token por um tempo.</p>
     */
    public RefreshToken createRefreshToken(BaseUser user){

        OffsetDateTime now = OffsetDateTime.now();
        refreshTokenRepository.deletePrunableByUserId(user.getId(), now, now.minusDays(1));

        String secureToken = generateSecureToken();

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(secureToken)
                .expiryDate(OffsetDateTime.now().plus(expirationTime))
                .createdAt(OffsetDateTime.now())
                .build();

        return refreshTokenRepository.saveRefreshToken(refreshToken);
    }

    public Optional<RefreshToken> findByToken(String refreshToken) {
        return refreshTokenRepository.findByToken(refreshToken);
    }

    public RefreshToken verifyExpiration(RefreshToken refreshToken) {
        if (!refreshToken.isValid()) {
            refreshTokenRepository.deleteByToken(refreshToken);
            throw new RefreshTokenInvalidException();
        }
        return refreshToken;
    }

    private String generateSecureToken(){
        SecureRandom secureRandom = new SecureRandom();
        byte[] randomBytes = new byte[32]; // 256 bits
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

    }

    /**
     * Encerra a SESSAO a que o token pertence, e nao apenas aquele token.
     *
     * <p><b>Por que nao basta revogar o token apresentado.</b> Com rotacao, o app pode mandar sair
     * com um token que ja foi trocado — e ai o {@code revokeByToken} (que so age em linha nao
     * revogada) nao faz nada, o sucessor continua vivo e <b>o usuario pediu para sair e nao saiu</b>.
     * Com a janela de graca isso ficou pior: o token velho ainda devolveria o sucessor por alguns
     * segundos. Foi um teste antigo de logout que expos o buraco — ele afirmava a coisa certa
     * ("depois do logout, aquele refresh token nao funciona") e passou a falhar quando a janela
     * entrou.</p>
     *
     * <p>Entao se caminha pelo elo {@code replacedBy} ate o fim da cadeia, revogando o que ainda
     * estiver vivo. O limite de saltos existe so para nao depender de os dados estarem sempre
     * sadios: cadeia com ciclo (que nao deveria existir) para de rodar em vez de travar o logout.</p>
     */
    @Transactional
    public void revokeSession(String refreshToken) {

        RefreshToken node = refreshTokenRepository.findByToken(refreshToken).orElse(null);

        if (node == null) {
            log.warn("Logout com refresh token desconhecido — nada a encerrar");
            return;
        }

        int hops = 0;
        while (node != null && hops++ < MAX_SESSION_CHAIN) {
            if (!node.isRevoked()) {
                node.revoke();
                refreshTokenRepository.saveRefreshToken(node);
            }
            node = node.getReplacedBy();
        }
    }

    /**
     * Fecha o token apresentado apontando para o que o substituiu. E a rotacao completa: sem o elo,
     * a segunda chamada de um refresh concorrente nao teria como receber o sucessor e levaria 401 —
     * e o {@link #revokeSession} nao teria como achar a sessao viva a partir de um token velho.
     */
    @Transactional
    public void revokeReplacedBy(RefreshToken oldToken, RefreshToken replacement) {
        oldToken.revoke();
        oldToken.setReplacedBy(replacement);
        refreshTokenRepository.saveRefreshToken(oldToken);
    }

    @Transactional
    public void revokeAllUserTokens(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    @Transactional
    public void cleanupOldTokens(BaseUser user) {

        refreshTokenRepository.deleteOldRevokedTokens(
                user.getId(),
                OffsetDateTime.now().minusDays(14)
        );
    }
}
