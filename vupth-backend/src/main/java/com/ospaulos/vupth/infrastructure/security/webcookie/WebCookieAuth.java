package com.ospaulos.vupth.infrastructure.security.webcookie;

import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * token-web-httponly (DEC-172/179): na web o token vai em cookie HttpOnly, fora do alcance do JavaScript da pagina. Tudo atras da
 * chave {@code vupth.auth.web-cookie.enabled} (padrao desligada): so liga com o dominio proprio (DEC-155), porque sem ele o cookie e
 * de terceiro e o Safari o bloqueia. Os apps nativos seguem com {@code Authorization: Bearer} e nao passam por aqui.
 *
 * <ul>
 *   <li>{@value #ACCESS} — o token de acesso (HttpOnly, Secure, Path=/).</li>
 *   <li>{@value #REFRESH} — o refresh (HttpOnly, Secure, so em {@value #REFRESH_PATH}).</li>
 *   <li>{@value #CSRF} — o token duplo de CSRF, legivel pela pagina; toda escrita repete o valor em {@value #CSRF_HEADER}.</li>
 * </ul>
 */
@Component
public class WebCookieAuth {

    public static final String MODE_HEADER = "X-Auth-Mode";
    public static final String MODE_COOKIE = "cookie";
    public static final String ACCESS = "vupth_at";
    public static final String REFRESH = "vupth_rt";
    public static final String CSRF = "vupth_csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String REFRESH_PATH = "/auth/web";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final boolean enabled;
    private final String sameSite;
    private final String domain;
    private final long accessSeconds;

    public WebCookieAuth(@Value("${vupth.auth.web-cookie.enabled:false}") boolean enabled,
                         @Value("${vupth.auth.web-cookie.same-site:None}") String sameSite,
                         @Value("${vupth.auth.web-cookie.domain:}") String domain,
                         @Value("${vupth.auth.web-cookie.access-max-age-seconds:900}") long accessSeconds) {
        this.enabled = enabled;
        this.sameSite = sameSite;
        this.domain = domain == null || domain.isBlank() ? null : domain.trim();
        this.accessSeconds = accessSeconds;
    }

    public boolean enabled() {
        return enabled;
    }

    /** A web pediu o modo cookie (e a chave esta ligada). */
    public boolean wants(HttpServletRequest request) {
        return enabled && MODE_COOKIE.equalsIgnoreCase(request.getHeader(MODE_HEADER));
    }

    /** Grava os tres cookies da sessao. */
    public void write(HttpServletResponse response, RefreshTokenResponse tokens) {
        long refreshSeconds = tokens.expiresIn() == null ? 0 : tokens.expiresIn();
        add(response, cookie(ACCESS, tokens.accessToken(), "/", accessSeconds, true));
        add(response, cookie(REFRESH, tokens.refreshToken(), REFRESH_PATH, refreshSeconds, true));
        add(response, cookie(CSRF, newCsrf(), "/", refreshSeconds, false));
    }

    /** Apaga os tres (Max-Age=0), no logout. */
    public void clear(HttpServletResponse response) {
        add(response, cookie(ACCESS, "", "/", 0, true));
        add(response, cookie(REFRESH, "", REFRESH_PATH, 0, true));
        add(response, cookie(CSRF, "", "/", 0, false));
    }

    public static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    static String newCsrf() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseCookie cookie(String name, String value, String path, long maxAge, boolean httpOnly) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value == null ? "" : value)
                .path(path).maxAge(Duration.ofSeconds(maxAge)).secure(true).httpOnly(httpOnly).sameSite(sameSite);
        if (domain != null) {
            b.domain(domain);
        }
        return b.build();
    }

    private void add(HttpServletResponse response, ResponseCookie cookie) {
        // SameSite=None (sem dominio proprio): o cookie de terceiro so sobrevive no Chrome com Partitioned (CHIPS)
        String header = cookie.toString();
        if ("None".equalsIgnoreCase(sameSite)) {
            header += "; Partitioned";
        }
        response.addHeader("Set-Cookie", header);
    }
}
