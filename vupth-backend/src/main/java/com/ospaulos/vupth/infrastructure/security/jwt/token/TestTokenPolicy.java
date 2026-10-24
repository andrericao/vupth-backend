package com.ospaulos.vupth.infrastructure.security.jwt.token;

import com.ospaulos.vupth.domain.setting.AppSettingService;
import com.ospaulos.vupth.infrastructure.asaas.AsaasProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * token-de-teste-sandbox: o token de ADMIN de 7 dias que o Andre entrega ao Newman. Por ser longo, so existe com a chave
 * {@code TEST_TOKEN_ENABLED} ligada (padrao: desligada; so o Render do sandbox a liga) E o Asaas em sandbox (pela URL); no modo
 * real ele e recusado (401). Alem disso so abre uma lista curta de rotas e pode ser revogado (um, ou todos de uma vez).
 */
@Component
public class TestTokenPolicy {

    /** A validade fixa do token de teste. */
    public static final Duration TTL = Duration.ofDays(7);
    /** A autoridade que marca a autenticacao de um token de teste (o filtro de escopo a procura). */
    public static final String AUTHORITY = "TEST_TOKEN";
    public static final String CLAIM = "test";

    static final String REVOKED_BEFORE = "test_token.revoked_before";
    static final String REVOKED_JTIS = "test_token.revoked_jtis";
    /** A configuracao guarda ate 200 letras: cabem os 5 ultimos jti; revogar todos cobre o resto. */
    static final int MAX_JTIS = 5;

    private static final List<String> PREFIXES = List.of("/admin/finance", "/admin/campaigns", "/admin/referrals",
            "/admin/debts", "/admin/settings/service-min-price");

    private final boolean enabled;
    private final AsaasProperties asaas;
    private final AppSettingService settings;

    public TestTokenPolicy(@Value("${TEST_TOKEN_ENABLED:false}") boolean enabled, AsaasProperties asaas, AppSettingService settings) {
        this.enabled = enabled;
        this.asaas = asaas;
        this.settings = settings;
    }

    /** Ligado e com o Asaas em sandbox: so assim o token e emitido e aceito. */
    public boolean usable() {
        return enabled && asaas.sandbox();
    }

    public boolean revoked(String jti, Instant issuedAt) {
        String before = settings.text(REVOKED_BEFORE);
        // o iat do JWT e em segundos: quem foi emitido ate o segundo da revogacao cai (a revogacao vence na duvida)
        if (before != null && issuedAt != null && !issuedAt.isAfter(Instant.parse(before))) {
            return true;
        }
        String list = settings.text(REVOKED_JTIS);
        return jti != null && list != null && Arrays.asList(list.split(",")).contains(jti);
    }

    public void revokeAll(UUID by) {
        settings.setText(REVOKED_BEFORE, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), by);
    }

    public void revoke(String jti, UUID by) {
        String list = settings.text(REVOKED_JTIS);
        Set<String> all = new LinkedHashSet<>();
        if (list != null && !list.isBlank()) {
            all.addAll(Arrays.asList(list.split(",")));
        }
        all.add(jti);
        List<String> kept = all.stream().toList();
        if (kept.size() > MAX_JTIS) {
            kept = kept.subList(kept.size() - MAX_JTIS, kept.size());
        }
        settings.setText(REVOKED_JTIS, String.join(",", kept), by);
    }

    /**
     * A lista de permitidos: o financeiro, as campanhas, a bonificacao, as dividas, o valor minimo, o estado e o liga/desliga da
     * conexao do Asaas (o MODO sandbox/real so muda por variavel de ambiente, nunca por aqui) e a leitura publica do estado.
     * Fora dela (usuarios do painel, convites, outros tokens de teste, o resto do app): 403.
     */
    public static boolean inScope(String method, String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        boolean read = HttpMethod.GET.matches(method);
        if (path.equals("/admin/settings/asaas-connection")) {
            return read || HttpMethod.PUT.matches(method);
        }
        return read && (path.equals("/settings/service-min-price") || path.equals("/payment-gateway"));
    }
}
