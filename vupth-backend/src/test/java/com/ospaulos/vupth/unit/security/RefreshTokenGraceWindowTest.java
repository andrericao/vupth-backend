package com.ospaulos.vupth.unit.security;

import com.ospaulos.vupth.api.user.dto.request.auth.RefreshTokenRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.usecase.auth.RefreshTokenUseCase;
import com.ospaulos.vupth.domain.refreshToken.exception.RefreshTokenInvalidException;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.infrastructure.security.jwt.token.RefreshTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests — <b>a janela de graca do refresh</b> (corrida de refresh, 2026-09-06).
 *
 * <p>O caso que originou isto veio do log de PROD: quatro {@code POST /auth/refresh} no mesmo
 * instante. Com rotacao, so a primeira pode ganhar; as outras chegam com o token que ela acabou de
 * revogar. Sem janela, elas levam 401 e o app desloga o usuario por uma corrida que ele nao causou.</p>
 *
 * <p>O que estes testes fixam e a <b>fronteira</b>: o que a janela absorve e o que ela continua
 * recusando. Uma janela que aceita demais apaga o sinal de token roubado, que e o motivo de a
 * rotacao existir.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenUseCase — janela de graca")
class RefreshTokenGraceWindowTest {

    @Mock RefreshTokenProvider refreshTokenProvider;
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock UserRepository userRepository;

    @InjectMocks RefreshTokenUseCase useCase;

    private BaseUser user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(useCase, "graceWindow", Duration.ofSeconds(30));
        user = new BaseUser();
        user.setId(UUID.randomUUID());
        user.setLastTokenCleanupAt(OffsetDateTime.now()); // sem cleanup no meio do caminho
    }

    private RefreshToken token(String value, OffsetDateTime expiry) {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .token(value)
                .user(user)
                .expiryDate(expiry)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("rotacao normal")
    class Rotation {

        @Test
        @DisplayName("revoga o apresentado, aponta para o sucessor e devolve o par novo")
        void rotatesAndLinks() {
            RefreshToken presented = token("antigo", OffsetDateTime.now().plusDays(3));
            RefreshToken successor = token("novo", OffsetDateTime.now().plusDays(7));

            when(refreshTokenProvider.findByToken("antigo")).thenReturn(Optional.of(presented));
            when(refreshTokenProvider.createRefreshToken(user)).thenReturn(successor);
            when(jwtTokenProvider.createTokenFromUser(user)).thenReturn("access-novo");

            RefreshTokenResponse response = useCase.execute(new RefreshTokenRequest("antigo"));

            assertThat(response.refreshToken()).isEqualTo("novo");
            assertThat(response.accessToken()).isEqualTo("access-novo");
            verify(refreshTokenProvider).revokeReplacedBy(presented, successor);
        }

        @Test
        @DisplayName("o prazo devolvido e o do token NOVO, nao o que sobrava do antigo")
        void expiresInComesFromTheNewToken() {
            RefreshToken presented = token("antigo", OffsetDateTime.now().plusMinutes(5));
            RefreshToken successor = token("novo", OffsetDateTime.now().plusDays(7));

            when(refreshTokenProvider.findByToken("antigo")).thenReturn(Optional.of(presented));
            when(refreshTokenProvider.createRefreshToken(user)).thenReturn(successor);
            when(jwtTokenProvider.createTokenFromUser(user)).thenReturn("access-novo");

            RefreshTokenResponse response = useCase.execute(new RefreshTokenRequest("antigo"));

            assertThat(response.expiresIn())
                    .as("o antigo vencia em 5 minutos; o novo vale dias — quem manda e o entregue")
                    .isGreaterThan(Duration.ofDays(6).toSeconds());
        }
    }

    @Nested
    @DisplayName("token revogado")
    class Revoked {

        @Test
        @DisplayName("revogado agora, com sucessor vivo: devolve o SUCESSOR e nao rotaciona de novo")
        void concurrentRefreshGetsTheSuccessor() {
            RefreshToken successor = token("novo", OffsetDateTime.now().plusDays(7));
            RefreshToken presented = token("antigo", OffsetDateTime.now().plusDays(3));
            presented.revoke();
            presented.setReplacedBy(successor);

            when(refreshTokenProvider.findByToken("antigo")).thenReturn(Optional.of(presented));
            when(jwtTokenProvider.createTokenFromUser(user)).thenReturn("access-novo");

            RefreshTokenResponse response = useCase.execute(new RefreshTokenRequest("antigo"));

            assertThat(response.refreshToken())
                    .as("as chamadas da corrida convergem para o MESMO par")
                    .isEqualTo("novo");
            verify(refreshTokenProvider, never()).createRefreshToken(any());
        }

        @Test
        @DisplayName("revogado ha mais tempo que a janela: 401, o sinal de reuso volta a valer")
        void staleRevokedTokenIsRejected() {
            RefreshToken successor = token("novo", OffsetDateTime.now().plusDays(7));
            RefreshToken presented = token("antigo", OffsetDateTime.now().plusDays(3));
            presented.setRevokedAt(OffsetDateTime.now().minusMinutes(5));
            presented.setReplacedBy(successor);

            when(refreshTokenProvider.findByToken("antigo")).thenReturn(Optional.of(presented));

            assertThatThrownBy(() -> useCase.execute(new RefreshTokenRequest("antigo")))
                    .isInstanceOf(RefreshTokenInvalidException.class);
        }

        @Test
        @DisplayName("revogado sem sucessor (logout, troca de senha): 401")
        void revokedWithoutSuccessorIsRejected() {
            RefreshToken presented = token("antigo", OffsetDateTime.now().plusDays(3));
            presented.revoke();

            when(refreshTokenProvider.findByToken("antigo")).thenReturn(Optional.of(presented));

            assertThatThrownBy(() -> useCase.execute(new RefreshTokenRequest("antigo")))
                    .isInstanceOf(RefreshTokenInvalidException.class);
        }

        @Test
        @DisplayName("sucessor ja revogado tambem nao serve: 401")
        void revokedSuccessorIsRejected() {
            RefreshToken successor = token("novo", OffsetDateTime.now().plusDays(7));
            successor.revoke();
            RefreshToken presented = token("antigo", OffsetDateTime.now().plusDays(3));
            presented.revoke();
            presented.setReplacedBy(successor);

            when(refreshTokenProvider.findByToken("antigo")).thenReturn(Optional.of(presented));

            assertThatThrownBy(() -> useCase.execute(new RefreshTokenRequest("antigo")))
                    .isInstanceOf(RefreshTokenInvalidException.class);
        }

        @Test
        @DisplayName("token desconhecido: 401")
        void unknownTokenIsRejected() {
            when(refreshTokenProvider.findByToken("inventado")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(new RefreshTokenRequest("inventado")))
                    .isInstanceOf(RefreshTokenInvalidException.class);
        }
    }
}
