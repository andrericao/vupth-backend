package com.ospaulos.vupth.api.user.usecase.auth;

import com.ospaulos.vupth.api.user.dto.request.auth.RefreshTokenRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.domain.refreshToken.exception.RefreshTokenInvalidException;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.infrastructure.security.jwt.token.RefreshTokenProvider;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Troca um refresh token por um par novo (access + refresh), rotacionando: o token apresentado e
 * revogado e um sucessor e emitido.
 *
 * <h2>A janela de graca (2026-09-06)</h2>
 * Rotacao e corrida nao se dao bem. O log de PROD de 06/09 mostrou <b>quatro</b>
 * {@code POST /auth/refresh} no mesmo instante: o app tem varias chamadas tomando 401 ao mesmo tempo
 * e cada uma dispara o proprio refresh. Com rotacao, no maximo <b>uma</b> pode ganhar — as outras
 * chegam com um token que a primeira acabou de revogar. Respondendo 401 a elas, o usuario e
 * deslogado por uma corrida que ele nao causou, e o sintoma e aleatorio: depende de quantas chamadas
 * a tela disparou no instante em que o access token venceu.
 *
 * <p>Por isso, um token revogado ha <b>poucos segundos</b> nao e tratado como invalido: devolve-se o
 * <b>sucessor daquela sessao</b> ({@code replacedBy}), e as chamadas da corrida convergem para o
 * mesmo par. Passada a janela, volta a ser 401.</p>
 *
 * <p><b>O que isso custa, dito de frente:</b> "token revogado sendo reapresentado" e tambem o sinal
 * de <b>roubo de token</b>. Durante a janela, esse sinal fica cego. Por isso ela e curta
 * ({@code jwt.refresh-token.grace}, 30s por padrao) e por isso o caso e <b>logado</b> — e por isso a
 * correcao na origem continua valendo: o app deve fazer <i>single-flight</i> (um refresh em voo, as
 * outras chamadas esperam o mesmo resultado). A janela e a rede de seguranca, nao o conserto.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenUseCase {

    private final RefreshTokenProvider refreshTokenProvider;
    private final JwtTokenProvider jwtTokenProvider;

    private final UserRepository userRepository;

    /** Quanto tempo um token recem-rotacionado ainda devolve o sucessor em vez de 401. */
    @Value("${jwt.refresh-token.grace:PT30S}")
    private Duration graceWindow;

    @Transactional
    public RefreshTokenResponse execute(RefreshTokenRequest request){

        RefreshToken presented = refreshTokenProvider
                .findByToken(request.refreshToken())
                .orElseThrow(RefreshTokenInvalidException::new);

        if (presented.isRevoked()) {
            return withinGraceWindow(presented);
        }

        refreshTokenProvider.verifyExpiration(presented);

        BaseUser user = presented.getUser();

        cleanupOlderTokens(user);

        RefreshToken newRefreshToken = refreshTokenProvider.createRefreshToken(user);
        refreshTokenProvider.revokeReplacedBy(presented, newRefreshToken);

        return respond(newRefreshToken, user);
    }

    /**
     * Token revogado: so nao e 401 se a revogacao acabou de acontecer E existe sucessor vivo — o
     * caso da corrida de refresh. Fora disso (janela vencida, sem sucessor, sucessor ja revogado por
     * logout ou troca de senha), o 401 continua sendo a resposta certa.
     */
    private RefreshTokenResponse withinGraceWindow(RefreshToken presented) {
        RefreshToken replacement = presented.getReplacedBy();
        boolean fresh = presented.getRevokedAt() != null
                && presented.getRevokedAt().isAfter(OffsetDateTime.now().minus(graceWindow));

        if (!fresh || replacement == null || !replacement.isValid()) {
            throw new RefreshTokenInvalidException();
        }

        // Vale um WARN, e nao um INFO: e o mesmo evento que denuncia reuso de token roubado. Aqui
        // ele foi absorvido, mas se aparecer com frequencia, o app nao esta serializando o refresh.
        log.warn("[Auth] refresh concorrente absorvido pela janela de graca — token revogado em {}",
                presented.getRevokedAt());

        return respond(replacement, replacement.getUser());
    }

    /**
     * O prazo informado e o do token que esta sendo entregue.
     *
     * <p>Ate 06/09 a conta usava a validade do token <b>antigo</b>: o novo nascia com os 7 dias
     * cheios e a resposta anunciava o que sobrava do anterior. O app remarcava o fim da sessao para
     * antes da hora — errava sempre para menos, e o erro crescia a cada refresh.</p>
     */
    private RefreshTokenResponse respond(RefreshToken refreshToken, BaseUser user) {
        String accessToken = jwtTokenProvider.createTokenFromUser(user);

        long refreshExpiresIn = Duration.between(
                OffsetDateTime.now(), refreshToken.getExpiryDate()
        ).getSeconds();

        return new RefreshTokenResponse(
                accessToken,
                refreshToken.getToken(),
                refreshExpiresIn
        );
    }

    public void cleanupOlderTokens(BaseUser user) {

        OffsetDateTime now = OffsetDateTime.now();

        if (user.getLastTokenCleanupAt() == null ||
                user.getLastTokenCleanupAt().isBefore(now.minusHours(24))) {

            refreshTokenProvider.cleanupOldTokens(user);

            user.setLastTokenCleanupAt(now);
            userRepository.save(user);
        }
    }
}
