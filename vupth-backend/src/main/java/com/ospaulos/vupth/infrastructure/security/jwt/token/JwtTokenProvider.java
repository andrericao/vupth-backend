package com.ospaulos.vupth.infrastructure.security.jwt.token;

import com.auth0.jwt.exceptions.JWTCreationException;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    @Value("${jwt.access-token.expiration:PT1H}")
    private Duration expirationTime;

    @Value("${jwt.issuer:vupth-backend}")
    private String issuer;

    private final JwtEncoder jwtEncoder;

    public String createTokenFromUser(BaseUser user){
        UserDetailsImpl userDetails = new UserDetailsImpl(user);
        return buildToken(userDetails);
    }

    public String createToken(UserDetailsImpl userDetails){
            return buildToken(userDetails);
    }

    private String buildToken(UserDetailsImpl user){

        try{
            String roles = user.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.joining(","));

            var claims = JwtClaimsSet.builder()
                    .issuer(issuer)
                    .subject(user.getUserId().toString())
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plus(expirationTime))
                    .claim("email", user.getUsername())
                    .claim("roles", roles)
                    .build();

            var jwtToken = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
            return jwtToken;

        }catch (JWTCreationException exception){
            throw new RuntimeException("Error while generate token", exception);
        }
    }
}
