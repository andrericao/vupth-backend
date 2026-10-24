package com.ospaulos.vupth.unit.security;

import com.ospaulos.vupth.domain.setting.AppSettingService;
import com.ospaulos.vupth.infrastructure.asaas.AsaasProperties;
import com.ospaulos.vupth.infrastructure.security.jwt.token.TestTokenPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** token-de-teste-sandbox: so ligado E no sandbox; escopo curto; revogacao de um ou de todos. */
class TestTokenPolicyTest {

    private static final String SANDBOX = "https://api-sandbox.asaas.com/v3";
    private static final String REAL = "https://api.asaas.com/v3";
    private final AppSettingService settings = mock(AppSettingService.class);

    private TestTokenPolicy policy(boolean enabled, String url) {
        return new TestTokenPolicy(enabled, new AsaasProperties("k", url, ""), settings);
    }

    @Test
    @DisplayName("so vale ligado E com o Asaas em sandbox: desligado ou no modo real, nunca")
    void usable() {
        assertThat(policy(true, SANDBOX).usable()).isTrue();
        assertThat(policy(false, SANDBOX).usable()).as("desligado (padrao)").isFalse();
        assertThat(policy(true, REAL).usable()).as("modo real").isFalse();
        assertThat(policy(false, REAL).usable()).isFalse();
    }

    @Test
    @DisplayName("escopo: financeiro, campanhas, bonificacao, dividas, valor minimo, liga/desliga da conexao e a leitura do estado; o resto, nao")
    void scope() {
        assertThat(TestTokenPolicy.inScope("GET", "/admin/finance/app-balance")).isTrue();
        assertThat(TestTokenPolicy.inScope("POST", "/admin/finance/asaas/sandbox/charges")).isTrue();
        assertThat(TestTokenPolicy.inScope("POST", "/admin/campaigns")).isTrue();
        assertThat(TestTokenPolicy.inScope("PATCH", "/admin/campaigns/abc/end")).isTrue();
        assertThat(TestTokenPolicy.inScope("GET", "/admin/referrals/pending-release")).isTrue();
        assertThat(TestTokenPolicy.inScope("GET", "/admin/debts")).isTrue();
        assertThat(TestTokenPolicy.inScope("PUT", "/admin/settings/service-min-price")).isTrue();
        assertThat(TestTokenPolicy.inScope("GET", "/admin/settings/asaas-connection")).isTrue();
        assertThat(TestTokenPolicy.inScope("PUT", "/admin/settings/asaas-connection")).isTrue();
        assertThat(TestTokenPolicy.inScope("GET", "/settings/service-min-price")).isTrue();
        assertThat(TestTokenPolicy.inScope("GET", "/payment-gateway")).isTrue();

        assertThat(TestTokenPolicy.inScope("DELETE", "/admin/settings/asaas-connection")).isFalse();
        assertThat(TestTokenPolicy.inScope("POST", "/payment-gateway")).isFalse();
        assertThat(TestTokenPolicy.inScope("POST", "/admin/test-tokens")).as("um token de teste nao emite outro").isFalse();
        assertThat(TestTokenPolicy.inScope("DELETE", "/admin/test-tokens")).isFalse();
        assertThat(TestTokenPolicy.inScope("GET", "/admin/users-admin")).isFalse();
        assertThat(TestTokenPolicy.inScope("POST", "/admin/invitations")).isFalse();
        assertThat(TestTokenPolicy.inScope("POST", "/admin/auth/login")).isFalse();
        assertThat(TestTokenPolicy.inScope("GET", "/admin/overview")).isFalse();
        assertThat(TestTokenPolicy.inScope("GET", "/admin/financeiro-falso")).as("prefixo so em fronteira de segmento").isFalse();
        assertThat(TestTokenPolicy.inScope("GET", "/clients/me")).isFalse();
        assertThat(TestTokenPolicy.inScope("GET", null)).isFalse();
    }

    @Test
    @DisplayName("revogado: pelo jti na lista, ou por ser de uma geracao anterior (revogar todos); token sem geracao conta como 0")
    void revoked() {
        TestTokenPolicy p = policy(true, SANDBOX);
        assertThat(p.revoked("a", 0L)).isFalse();
        assertThat(p.revoked("a", null)).as("sem geracao = 0").isFalse();

        when(settings.text("test_token.revoked_jtis")).thenReturn("x,a,y");
        assertThat(p.revoked("a", 0L)).isTrue();
        assertThat(p.revoked("b", 0L)).isFalse();

        when(settings.text("test_token.generation")).thenReturn("2");
        assertThat(p.generation()).isEqualTo(2);
        assertThat(p.revoked("b", 1L)).as("emitido antes da ultima revogacao de todos").isTrue();
        assertThat(p.revoked("b", null)).isTrue();
        assertThat(p.revoked("b", 2L)).as("emitido na geracao vigente").isFalse();
    }

    @Test
    @DisplayName("revogar um acumula na lista (so os 5 ultimos cabem na configuracao); revogar todos soma 1 na geracao")
    void revokeWrites() {
        TestTokenPolicy p = policy(true, SANDBOX);
        UUID admin = UUID.randomUUID();
        when(settings.text("test_token.revoked_jtis")).thenReturn("1,2,3,4,5");

        p.revoke("6", admin);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(settings).setText(eq("test_token.revoked_jtis"), value.capture(), eq(admin));
        assertThat(value.getValue()).isEqualTo("2,3,4,5,6");

        when(settings.text("test_token.generation")).thenReturn("3");
        p.revokeAll(admin);
        verify(settings).setText("test_token.generation", "4", admin);
    }
}
