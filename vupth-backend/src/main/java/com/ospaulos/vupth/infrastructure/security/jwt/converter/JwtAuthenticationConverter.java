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

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt){

        try{
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

            return new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities());

        } catch (Exception e){
            log.error("❌ Error to converter JWT: ", e);
            throw new UsernameNotFoundException("Error to authenticate user", e);
        }

    }
}
