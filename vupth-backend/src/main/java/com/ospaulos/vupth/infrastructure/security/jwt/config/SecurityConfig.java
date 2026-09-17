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
import org.springframework.security.authorization.AuthorizationDecision;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${jwt.public.key}")
    private RSAPublicKey publicKey;

    @Value("${jwt.private.key}")
    private RSAPrivateKey privateKey;

    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    /**
     * Origens de navegador autorizadas (env {@code CORS_ALLOWED_ORIGINS}, separadas por virgula).
     * <b>Default vazio = nenhuma</b>: sem a variavel, nenhuma build web funciona e os apps nativos
     * seguem intactos, porque cliente nativo nao manda {@code Origin} e portanto nao e request CORS.
     */
    @Value("${vupth.cors.allowed-origin-patterns:}")
    private String corsAllowedOriginPatterns;

    /**
     * Libera TODO o {@code /actuator/**}. {@code true} so em {@code dev} — e o que faz o
     * {@code management.endpoints.web.exposure.include=*} do perfil local continuar significando
     * alguma coisa. Em staging/prod fica {@code false}: passam apenas {@code health} e {@code info}.
     */
    @Value("${vupth.actuator.expose-all:false}")
    private boolean actuatorExposeAll;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {

        log.info("SECURITY CONFIG!");

        return httpSecurity
                .authorizeHttpRequests(authorize -> authorize

                        // Publicos de proposito e com consumidor conhecido: o portao
                        // waitForBackend() dos apps (readiness), o ci.yml (startedAtEpochMs do
                        // /info) e os fronts (bootMillis). Por isso o /info e livre de segredo
                        // por construcao — ver MailInfoContributor.
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/info").permitAll()
                        // O resto do actuator fecha fora de dev. Antes de 26/08 era permitAll
                        // largo, e com `metrics` exposto em staging E prod isso deixava publico o
                        // inventario de rotas (http.server.requests), a saturacao do pool
                        // (hikaricp.connections) e o dimensionamento da JVM — sem token. A regra
                        // por ULTIMO tambem impede que expor `env`/`heapdump`/`loggers` um dia
                        // abra a porta junto.
                        .requestMatchers("/actuator/**")
                        .access((authentication, context) -> new AuthorizationDecision(actuatorExposeAll))

                        // WebSocket handshake — autenticacao da sessao STOMP e feita
                        // no StompAuthChannelInterceptor (frame CONNECT), nao no HTTP.
                        .requestMatchers("/ws/**").permitAll()

                        // ── Public ───────────────────────────────────────────────────────
                        .requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/forgot-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/reset-password").permitAll()

                        .requestMatchers(HttpMethod.GET, "/establishment/mine").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/professional").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/professional/me/reports").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.GET, "/professional/me").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.GET, "/establishment/*/affiliations").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/establishment/*/reports").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/establishment/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/users/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/professional/*/service-items").permitAll()
                        .requestMatchers(HttpMethod.GET, "/service-types/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/professional-queue/add-quest-attendance").permitAll()

                        // ── ROLE_CLIENT (qualquer autenticado) ────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/attendances").hasAnyRole("OWNER", "PROFESSIONAL", "CLIENT")
                        .requestMatchers(HttpMethod.GET, "/attendances/client/me").hasRole("CLIENT")
                        // Pedidos de corte pendentes do profissional (BUG5 C2 — antes do generico /attendances/*)
                        .requestMatchers(HttpMethod.GET, "/attendances/cut-requests").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.GET, "/attendances/*").authenticated()
                        // Visao de agenda (#4, fatia 2): agenda do profissional e a do cliente (intencoes futuras)
                        .requestMatchers(HttpMethod.GET, "/attendances/intentions/professional/me").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.GET, "/attendances/intentions/client/me").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.POST, "/attendances/intentions").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.POST, "/attendances/cut-requests").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/cut-requests/*/accept").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/cut-requests/*/refuse").hasRole("PROFESSIONAL")
                        // Intencao de atendimento (#4, fatia 3): dono edita/cancela a propria intencao
                        .requestMatchers(HttpMethod.PATCH, "/attendances/intentions/*").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.DELETE, "/attendances/intentions/*").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.POST, "/attendances").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.PUT, "/attendances/*/cancel").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.DELETE, "/attendances/*/leave-queue").hasRole("CLIENT")
                        // Item #43 — cliente remaneja a propria vez (espera longa): horario-alvo ou fim da fila
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/reschedule").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/move-to-end").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.POST, "/attendances/*/rating").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.GET, "/client-queue/**").hasRole("CLIENT")

                        // Troca de posicao na fila por consentimento mutuo (item #42) — cliente<->cliente
                        .requestMatchers(HttpMethod.POST, "/swap-requests").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.GET, "/swap-requests/me").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.PATCH, "/swap-requests/*").hasRole("CLIENT")

                        // Oferta ABERTA de troca de posicao (Client C13 / Partner S11) — cliente publica, elegivel aceita
                        .requestMatchers(HttpMethod.POST, "/swap-offers").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.GET, "/swap-offers").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.GET, "/swap-offers/me").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.PATCH, "/swap-offers/*/accept").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.DELETE, "/swap-offers/*").hasRole("CLIENT")

                        .requestMatchers(HttpMethod.POST, "/establishment/quick-create").hasRole("CLIENT")

                        // App parceiro — fluxo "sou profissional" (sem establishment)
                        .requestMatchers(HttpMethod.POST, "/professional/register").hasRole("CLIENT")
                        // App parceiro — fluxo "solo shop" (owner que também trabalha)
                        .requestMatchers(HttpMethod.POST, "/professional/quick-create").hasRole("CLIENT")

                        .requestMatchers(HttpMethod.PATCH, "/profile/**").hasRole("CLIENT")
                        // Apagar a conta e de QUALQUER autenticado: os papeis sao acumulativos e o
                        // dono tambem tem de conseguir apagar a propria conta (exigencia das lojas).
                        .requestMatchers(HttpMethod.DELETE, "/profile/delete/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/auth/logout").hasRole("CLIENT")

                        // ── ROLE_PROFESSIONAL ─────────────────────────────────────────────
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/start").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/finish").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/pause").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/resume").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/late").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/undo-late").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/no-show").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/promote").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/fit-in").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/attendances/*/undo-fit").hasRole("PROFESSIONAL")

                        .requestMatchers(HttpMethod.POST, "/professional-queue/**").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PUT, "/professional-queue/**").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/professional-queue/**").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.DELETE, "/professional-queue/**").hasRole("PROFESSIONAL")

                        // Profissional se afilia/desafilia de um establishment
                        .requestMatchers(HttpMethod.PATCH, "/professional/me/status").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/professional/me/accepting-new").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/professional/me").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.POST, "/professional/affiliate").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.DELETE, "/professional/me/establishment").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.POST, "/professional/me/affiliations").hasRole("PROFESSIONAL")
                        .requestMatchers(HttpMethod.GET, "/professional/me/affiliations").hasRole("PROFESSIONAL")

                        // ── ROLE_OWNER ────────────────────────────────────────────────────
                        // S1: alem do owner, um profissional AFILIADO a loja pode cadastrar o proprio servico
                        // (a checagem owner-ou-afiliado fica no service).
                        .requestMatchers(HttpMethod.POST, "/establishment/*/service-items").hasAnyRole("OWNER", "PROFESSIONAL")
                        // Editar/remover servico da loja: owner OU o proprio profissional dono (checagem fina no service)
                        .requestMatchers(HttpMethod.PUT, "/establishment/*/service-items/*").hasAnyRole("OWNER", "PROFESSIONAL")
                        .requestMatchers(HttpMethod.DELETE, "/establishment/*/service-items/*").hasAnyRole("OWNER", "PROFESSIONAL")
                        .requestMatchers(HttpMethod.POST, "/establishment/create").hasRole("OWNER")
                        .requestMatchers(HttpMethod.PATCH, "/establishment/*/general-infos").hasRole("OWNER")
                        // Horario por dia (#2): so o dono escreve; o GET cai no permitAll de /establishment/**
                        .requestMatchers(HttpMethod.PUT, "/establishment/*/hours/**").hasRole("OWNER")
                        .requestMatchers(HttpMethod.DELETE, "/establishment/*/hours/**").hasRole("OWNER")
                        .requestMatchers(HttpMethod.PATCH, "/establishment/*/open-status").hasAnyRole("OWNER", "PROFESSIONAL")
                        .requestMatchers(HttpMethod.PATCH, "/establishment/*/owner").hasRole("OWNER")

                        // Owner adiciona/remove profissional do seu establishment
                        .requestMatchers(HttpMethod.POST, "/establishment/*/professionals").hasRole("OWNER")
                        .requestMatchers(HttpMethod.DELETE, "/establishment/*/professionals/*").hasRole("OWNER")
                        .requestMatchers(HttpMethod.POST, "/establishment/*/affiliations").hasRole("OWNER")
                        .requestMatchers(HttpMethod.POST, "/service-types/*/keywords").hasRole("PROFESSIONAL")

                        // ── Fallback ──────────────────────────────────────────────────────
                        .anyRequest().authenticated())
                // Sem isto o preflight OPTIONS cai no anyRequest().authenticated() e, como
                // preflight NAO carrega Authorization, o navegador leva 401 antes de qualquer
                // chamada real — o erro generico de CORS no console, sem corpo e sem status util.
                // Com o .cors(), o CorsFilter responde o preflight ANTES da autorizacao.
                .cors(Customizer.withDefaults())
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

    /**
     * Politica de CORS da API.
     *
     * <p><b>Por que existe (26/08).</b> Os testadores deixaram de instalar APK e passaram a abrir o
     * app por link, numa build web (Cloudflare Pages). Isso troca o cliente de app nativo por
     * navegador — e navegador aplica CORS. Ate aqui o backend nao tinha CORS <i>nenhum</i>: nem
     * {@code .cors()}, nem este bean, nem {@code addCorsMappings}; so uma chave morta
     * {@code cors.allowed-origins} nos {@code .properties} que ninguem lia.
     *
     * <p><b>{@code allowedOriginPatterns}, nao {@code allowedOrigins}:</b> os previews da branch
     * {@code dev} caem em {@code https://<hash>.vupth-partner.pages.dev} — curinga no <i>meio</i>,
     * que a API antiga nao aceita. ⚠️ O padrao com curinga <b>nao casa o apex</b>: para autorizar
     * {@code https://vupth-partner.pages.dev} e os previews sao <b>duas</b> entradas na variavel.
     *
     * <p><b>{@code allowCredentials} fica FALSO</b> porque a autenticacao e por
     * {@code Authorization: Bearer}, nao por cookie. Com credenciais desligadas, liberar
     * {@code allowedHeaders=*} nao abre nada — a origem continua restrita — e evita a classe de bug
     * "esqueci de liberar um header", que falha exatamente com a mesma tela muda.
     *
     * <p><b>⚠️ {@code /ws/**} fica de fora, retornando {@code null}.</b> Nao e esquecimento: o
     * SockJS do Spring emite os <i>proprios</i> cabecalhos de CORS a partir do
     * {@code setAllowedOriginPatterns("*")} do {@code WebSocketConfig}. Se este filtro tambem
     * escrevesse os dele, a resposta sairia com {@code Access-Control-Allow-Origin} <b>duplicado</b>
     * e o navegador recusaria — quebrando a fila em tempo real, que hoje funciona. Retornar
     * {@code null} faz o {@code CorsFilter} passar reto e deixa o WebSocket exatamente como esta.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> patterns = parseOriginPatterns(corsAllowedOriginPatterns);

        if (patterns.isEmpty()) {
            log.warn("[CORS] nenhuma origem autorizada (CORS_ALLOWED_ORIGINS vazia) — "
                    + "build web nao funciona; apps nativos nao sao afetados.");
        } else {
            log.info("[CORS] origens autorizadas: {}", patterns);
        }

        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOriginPatterns(patterns);
        api.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        api.setAllowedHeaders(List.of("*"));
        api.setAllowCredentials(false);
        api.setMaxAge(3600L);

        return request -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            boolean isWebSocket = path.equals("/ws") || path.startsWith("/ws/");
            return isWebSocket ? null : api;
        };
    }

    /**
     * Quebra a variavel de ambiente em padroes de origem. Extraido para poder ser testado sem subir
     * contexto: o caso que importa e a <b>string vazia</b> — ela tem de virar lista <b>vazia</b>
     * (nenhuma origem autorizada), e nao uma lista com um elemento em branco, que o
     * {@code CorsConfiguration} trataria como padrao invalido em vez de "fechado".
     *
     * <p>Publico porque os testes unitarios vivem em {@code unit/...} e nao espelham o pacote de
     * producao — mesma razao do construtor de teste do {@code GitCommitInfoContributor}.
     */
    public static List<String> parseOriginPatterns(String raw) {
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toList();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        JWK jwk = new RSAKey.Builder(this.publicKey).privateKey(privateKey).build();
        var jwks = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwks);
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
