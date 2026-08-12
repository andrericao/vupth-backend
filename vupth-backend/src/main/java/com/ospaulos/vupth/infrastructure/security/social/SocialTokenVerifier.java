package com.ospaulos.vupth.infrastructure.security.social;

import com.ospaulos.vupth.domain.user.model.SocialProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Valida o id-token de Google e Apple.
 *
 * <p>Usa o {@link NimbusJwtDecoder} que o projeto ja tem — nenhuma dependencia
 * nova. Isso importa por seguranca, nao por conveniencia: a verificacao de
 * assinatura, o cache do JWKS e a rotacao de chaves vem de biblioteca testada.
 * Validacao de id-token escrita a mao e onde moram os bypass de autenticacao.
 *
 * <p>As quatro validacoes que decidem se o token presta:
 * <ol>
 *   <li><b>assinatura</b> contra o JWKS do provedor;</li>
 *   <li><b>iss</b> entre os emissores aceitos daquele provedor;</li>
 *   <li><b>aud</b> igual a um dos nossos client ids — <b>esta e a critica</b>:
 *       sem ela, um id-token emitido para QUALQUER outro app do provedor seria
 *       aceito, o que e bypass de autenticacao e nao detalhe de configuracao;</li>
 *   <li><b>exp/nbf</b> pelo validador de tempo padrao.</li>
 * </ol>
 *
 * <p>Sem client id configurado o provedor fica <b>desligado</b>, e nao aberto:
 * sem {@code aud} para comparar nao existe validacao possivel.
 */
@Slf4j
@Component
public class SocialTokenVerifier {

    /** O Google emitiu token com as duas formas de issuer ao longo do tempo. */
    private static final Set<String> GOOGLE_ISSUERS =
            Set.of("https://accounts.google.com", "accounts.google.com");
    private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";

    private static final Set<String> APPLE_ISSUERS = Set.of("https://appleid.apple.com");
    private static final String APPLE_JWKS = "https://appleid.apple.com/auth/keys";

    private final Map<SocialProvider, JwtDecoder> decoders = new EnumMap<>(SocialProvider.class);

    public SocialTokenVerifier(
            @Value("${social.google.client-ids:}") String googleClientIds,
            @Value("${social.apple.client-ids:}") String appleClientIds) {

        register(SocialProvider.GOOGLE, GOOGLE_JWKS, GOOGLE_ISSUERS, googleClientIds);
        register(SocialProvider.APPLE, APPLE_JWKS, APPLE_ISSUERS, appleClientIds);
    }

    /**
     * Devolve a identidade que o token prova.
     *
     * @throws SocialTokenInvalidException token invalido, provedor desligado, ou
     *                                     token sem o {@code sub}/{@code email}
     *                                     que o vinculo exige.
     */
    public SocialIdentity verify(SocialProvider provider, String idToken) {
        JwtDecoder decoder = decoders.get(provider);
        if (decoder == null) {
            log.warn("SOCIAL LOGIN — {} sem client id configurado; provedor desligado.", provider);
            throw new SocialTokenInvalidException(provider, "provedor nao configurado");
        }

        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException e) {
            // A mensagem do Nimbus diz qual validacao falhou e ajuda no diagnostico,
            // mas nao pode chegar ao cliente: quem chama traduz por codigo.
            log.warn("SOCIAL LOGIN — id-token recusado ({}): {}", provider, e.getMessage());
            throw new SocialTokenInvalidException(provider, e.getMessage());
        }

        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
            throw new SocialTokenInvalidException(provider, "token sem sub ou email");
        }

        return new SocialIdentity(provider, subject, email.toLowerCase(), isEmailVerified(jwt));
    }

    /**
     * A Apple manda {@code email_verified} como <b>string</b> ("true"), o Google
     * como booleano. Ler so um dos dois formatos reprovaria contas legitimas de
     * um dos provedores — e tratar ausencia como verdadeiro abriria vinculo de
     * conta por e-mail nao provado.
     */
    private boolean isEmailVerified(Jwt jwt) {
        Object claim = jwt.getClaim("email_verified");
        if (claim instanceof Boolean verified) {
            return verified;
        }
        if (claim instanceof String verified) {
            return Boolean.parseBoolean(verified);
        }
        return false;
    }

    private void register(SocialProvider provider, String jwkSetUri,
                          Set<String> issuers, String rawClientIds) {

        Set<String> clientIds = Arrays.stream(rawClientIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toSet());

        if (clientIds.isEmpty()) {
            return;
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingValidator(List.of(
                new JwtTimestampValidator(),
                claimIn("iss", issuers),
                audienceIn(clientIds)
        )));

        decoders.put(provider, decoder);
        log.info("SOCIAL LOGIN — {} habilitado com {} client id(s).", provider, clientIds.size());
    }

    private static OAuth2TokenValidator<Jwt> claimIn(String claim, Set<String> accepted) {
        return jwt -> accepted.contains(jwt.getClaimAsString(claim))
                ? OAuth2TokenValidatorResult.success()
                : failure(claim + " nao aceito");
    }

    /** O {@code aud} do id-token e lista; basta uma entrada casar. */
    private static OAuth2TokenValidator<Jwt> audienceIn(Set<String> clientIds) {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            boolean matches = audience != null && audience.stream().anyMatch(clientIds::contains);
            return matches ? OAuth2TokenValidatorResult.success() : failure("aud nao aceito");
        };
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }

    /** Roda todos os validadores e junta os erros num resultado so. */
    private record DelegatingValidator(List<OAuth2TokenValidator<Jwt>> delegates)
            implements OAuth2TokenValidator<Jwt> {

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            List<OAuth2Error> errors = delegates.stream()
                    .map(validator -> validator.validate(token))
                    .filter(OAuth2TokenValidatorResult::hasErrors)
                    .flatMap(result -> result.getErrors().stream())
                    .toList();

            return errors.isEmpty()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(errors);
        }
    }

    /**
     * O que o token prova. O {@code providerUserId} e o {@code sub} e e a chave
     * estavel: o e-mail da Apple pode ser um relay que o usuario desativa depois.
     */
    public record SocialIdentity(
            SocialProvider provider,
            String providerUserId,
            String email,
            boolean emailVerified
    ) {
    }
}
