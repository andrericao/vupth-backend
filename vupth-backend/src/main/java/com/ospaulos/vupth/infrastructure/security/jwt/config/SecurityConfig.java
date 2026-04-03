package com.ospaulos.vupth.infrastructure.security.jwt.config;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ospaulos.vupth.infrastructure.security.jwt.converter.JwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity           // enables @PreAuthorize on controllers when needed
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${jwt.public.key}")
    private RSAPublicKey publicKey;

    @Value("${jwt.private.key}")
    private RSAPrivateKey privateKey;

    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity httpSecurity) throws Exception{

        log.info("SECURITY CONFIG!");

        return httpSecurity
                .authorizeHttpRequests(authorize -> authorize

                        // ── Public (no authentication required) ─────────────────────────
                        // Registro e login: qualquer um pode se registrar/autenticar
                        .requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/refresh").permitAll()

                        // Public listing of establishments (clients browse in the app)
                        .requestMatchers(HttpMethod.GET, "/establishment/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/users/**").permitAll()

                        // Public listing of services by professional (clients choose before booking)
                        .requestMatchers(HttpMethod.GET, "/professional/*/service-items").permitAll()

                        // Guest joins the queue without authentication (MVP)
                        .requestMatchers(HttpMethod.POST, "/professional-queue/add-quest-attendance").permitAll()

                        // ── Requer ROLE_CLIENT ────────────────────────────────────────────
                        // Every authenticated user already has CLIENT role (added at registration).
                        // Therefore hasRole(CLIENT) = any normally logged-in user.

                        // Entrar na fila como cliente autenticado
                        .requestMatchers(HttpMethod.POST, "/attendances").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.PUT, "/attendances/*/cancel").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.DELETE, "/attendances/*/leave-queue").hasRole("CLIENT")

                        // Consultar fila e tempo de espera (como cliente)
                        .requestMatchers(HttpMethod.GET, "/client-queue/**").hasRole("CLIENT")

                        // Criar establishment ou se filiar (qualquer autenticado = CLIENT)
                        .requestMatchers(HttpMethod.POST, "/establishment/quick-create").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.POST, "/professional/quick-create").hasRole("CLIENT")

                        // Perfil pessoal (qualquer autenticado = CLIENT)
                        .requestMatchers(HttpMethod.PATCH, "/profile/**").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.DELETE, "/profile/delete/me").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.POST, "/auth/logout").hasRole("CLIENT")

                        // ── Requer ROLE_PROFESSIONAL ──────────────────────────────────────
                        // Iniciar, finalizar, pausar e retomar attendances
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/start").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/finish").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/pause").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/resume").hasRole("PROFESSIONAL")

                        // Professional queue management
                        .requestMatchers(HttpMethod.POST, "/professional-queue/**").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PUT, "/professional-queue/**").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.DELETE, "/professional-queue/**").hasRole("PROFESSIONAL")

                        // ── Requer ROLE_OWNER ─────────────────────────────────────────────
                        // Criar e gerenciar service items do establishment
                        .requestMatchers(HttpMethod.POST, "/establishment/*/service-items").hasRole("OWNER")
                        .requestMatchers(HttpMethod.POST, "/establishment/create").hasRole("OWNER")

                        .requestMatchers("/actuator/**").permitAll()

                        // ── Fallback: qualquer autenticado ────────────────────────────────
                        .anyRequest().authenticated())
                        .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(
                        oauth2 -> oauth2
                                .jwt(jwt -> jwt
                                        .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                        .sessionManagement(
                                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public JwtEncoder jwtEncoder(){
        JWK jwk = new RSAKey.Builder(this.publicKey).privateKey(privateKey).build();
        var jwks = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwks);
    }

    @Bean
    public JwtDecoder jwtDecoder(){
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }
}
