package com.ospaulos.vupth.infrastructure.security.jwt.converter;

import com.ospaulos.vupth.infrastructure.logging.LogMask;

import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository repository;

    /** O token de teste (token-de-teste-sandbox): sem ele (testes de unidade) nenhum token de teste e aceito. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ospaulos.vupth.infrastructure.security.jwt.token.TestTokenPolicy testTokens;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt){

        try{
            boolean testToken = Boolean.TRUE.equals(jwt.getClaim(com.ospaulos.vupth.infrastructure.security.jwt.token.TestTokenPolicy.CLAIM));
            if (testToken && (testTokens == null || !testTokens.usable() || testTokens.revoked(jwt.getId(), jwt.getIssuedAt()))) {
                // desligado, fora do sandbox (modo real) ou revogado: 401, como se o token nao existisse
                log.warn("[JWT] token de teste recusado (desligado, modo real ou revogado) jti={}", jwt.getId());
                throw new DisabledException("Test token refused");
            }
            String id = jwt.getSubject();
            String email = jwt.getClaimAsString("email");

            log.info("JWT CONVERTER START - id: {}", id);

            BaseUser user = repository.findById(UUID.fromString(id))
                    .orElseThrow(() -> {
                        log.error("[JWT] User not found for id={}", id);
                        return new UsernameNotFoundException("User don't found: " + id);
                    });

            // Conta apagada/desativada: o access token que ja estava na mao do app morre AQUI, no
            // primeiro request — sem isto ele seguiria valido ate expirar (ate 1h depois).
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                log.warn("[JWT] Rejected token of a disabled/deleted account id={}", id);
                throw new DisabledException("Account is disabled: " + id);
            }

            UserDetailsImpl userDetails = new UserDetailsImpl(user);

            log.info("JWT CONVERTER END - id: {}", id);

            java.util.List<org.springframework.security.core.GrantedAuthority> authorities =
                    new java.util.ArrayList<>(userDetails.getAuthorities());
            if (testToken) {
                // marca o token de teste: o filtro de escopo so deixa passar a lista curta de rotas dele
                authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        com.ospaulos.vupth.infrastructure.security.jwt.token.TestTokenPolicy.AUTHORITY));
            }
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    authorities);
            // Como o login foi feito (pwd, totp): o painel confere se o segundo fator foi cumprido
            java.util.List<String> amr = jwt.getClaimAsStringList("amr");
            authentication.setDetails(amr == null ? java.util.List.<String>of() : amr);
            return authentication;

        } catch (Exception e){
            log.error("❌ Error to converter JWT: ", e);
            throw new UsernameNotFoundException("Error to authenticate user", e);
        }

    }
}
