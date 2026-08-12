package com.ospaulos.vupth.api.user.usecase.auth;

import com.ospaulos.vupth.api.user.dto.request.auth.SocialLoginRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.exception.SocialEmailNotVerifiedException;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.UserSocialIdentity;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.persistence.jpa.user.UserSocialIdentityJpaRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.infrastructure.security.jwt.token.RefreshTokenProvider;
import com.ospaulos.vupth.infrastructure.security.social.SocialTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Entrar com Google ou Apple.
 *
 * <p>Fluxo: o app manda o id-token que recebeu do provedor, aqui ele e validado
 * e sao emitidos os NOSSOS tokens. A resposta e a mesma do {@code /auth/login},
 * entao o app nao precisa de tratamento separado depois de autenticar.
 *
 * <p>Tres decisoes de produto ficaram assim (e estao no PROPOSTA_LOGIN_SOCIAL.md):
 * <ul>
 *   <li><b>Vinculo por e-mail verificado.</b> Se ja existe conta com aquele
 *       e-mail, o social e ligado a ela em vez de criar uma segunda. E seguro
 *       porque o provedor provou a posse do e-mail — sem {@code email_verified}
 *       nada e vinculado.</li>
 *   <li><b>Cria conta</b> no primeiro acesso, com perfil de cliente.</li>
 *   <li><b>Sem senha.</b> A conta nasce com {@code password} nulo; quem entra
 *       por social nao tem o que digitar. O changeset 024 liberou a coluna.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SocialLoginUseCase {

    private final SocialTokenVerifier verifier;
    private final UserRepository userRepository;
    private final UserSocialIdentityJpaRepository socialIdentityRepository;
    private final UserMapper userMapper;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenProvider refreshTokenProvider;

    public UserProfileGeneralInfosAndToken execute(SocialLoginRequest request) {
        var identity = verifier.verify(request.provider(), request.idToken());
        log.info("USECASE SOCIAL LOGIN — provider={} sub={}", identity.provider(), identity.providerUserId());

        if (!identity.emailVerified()) {
            throw new SocialEmailNotVerifiedException(identity.email());
        }

        BaseUser user = socialIdentityRepository
                .findByProviderAndProviderUserId(identity.provider(), identity.providerUserId())
                .map(UserSocialIdentity::getUser)
                .orElseGet(() -> linkOrCreate(identity));

        assertUsable(user);

        return issueTokens(user);
    }

    /**
     * Primeira vez desta identidade: liga a conta que ja existe com o mesmo
     * e-mail, ou cria uma nova.
     */
    private BaseUser linkOrCreate(SocialTokenVerifier.SocialIdentity identity) {
        BaseUser user = userRepository.findByEmail(identity.email())
                .orElseGet(() -> createSocialUser(identity.email()));

        socialIdentityRepository.save(UserSocialIdentity.builder()
                .user(user)
                .provider(identity.provider())
                .providerUserId(identity.providerUserId())
                .createdAt(OffsetDateTime.now())
                .build());

        log.info("SOCIAL LOGIN — identidade {} vinculada ao usuario {}", identity.provider(), user.getId());
        return user;
    }

    private BaseUser createSocialUser(String email) {
        BaseUser user = new BaseUser();
        user.setEmail(email);
        // Sem senha de proposito: nao existe senha que esta conta possa usar, e
        // gravar um hash inutilizavel faria o esqueci-senha oferecer redefinicao
        // para quem entra por social.
        user.setPassword(null);
        user.addClientProfile();
        return userRepository.save(user);
    }

    /**
     * Conta apagada ou desativada nao entra por social. Sem isto, o social seria
     * uma porta lateral: o login por senha barra pelo {@code is_active}, e este
     * caminho nao passa pelo AuthenticationManager que faz essa checagem.
     */
    private void assertUsable(BaseUser user) {
        boolean inactive = Boolean.FALSE.equals(user.getIsActive()) || user.getDeletedAt() != null;
        if (inactive) {
            log.warn("SOCIAL LOGIN — conta inativa ou apagada: {}", user.getId());
            throw new DisabledException("Account is disabled");
        }
    }

    /** Mesma emissao do LoginUsecase, para os dois caminhos gerarem o mesmo par. */
    private UserProfileGeneralInfosAndToken issueTokens(BaseUser user) {
        UserDetailsImpl userDetails = new UserDetailsImpl(user);

        String accessToken = tokenProvider.createToken(userDetails);
        RefreshToken refreshToken = refreshTokenProvider.createRefreshToken(user);

        Long refreshExpiresIn = Duration.between(
                OffsetDateTime.now(), refreshToken.getExpiryDate()
        ).getSeconds();

        RefreshTokenResponse tokens =
                new RefreshTokenResponse(accessToken, refreshToken.getToken(), refreshExpiresIn);
        UserProfileGeneralInfosResponse userResponse = userMapper.toGeneralInfosUserResponse(user);

        return new UserProfileGeneralInfosAndToken(userResponse, tokens);
    }
}
