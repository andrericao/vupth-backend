package com.ospaulos.vupth.unit.security;

import com.ospaulos.vupth.infrastructure.security.jwt.config.JsonBearerEntryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import static org.assertj.core.api.Assertions.assertThat;

/** newman-asaas-ajustes: o 401 diz o motivo no corpo, sem vazar a mensagem interna nem dado do token. */
class JsonBearerEntryPointTest {

    private final JsonBearerEntryPoint entryPoint = new JsonBearerEntryPoint();

    private MockHttpServletResponse call(boolean withToken, AuthenticationException ex) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/test-tokens");
        if (withToken) {
            request.addHeader("Authorization", "Bearer abc.def.ghi");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(request, response, ex);
        return response;
    }

    @Test
    @DisplayName("sem token: 401 TOKEN_MISSING; o cabecalho WWW-Authenticate: Bearer segue")
    void missing() throws Exception {
        MockHttpServletResponse r = call(false, new BadCredentialsException("x"));
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(r.getContentAsString()).contains("\"code\":\"TOKEN_MISSING\"").contains("\"status\":401");
    }

    @Test
    @DisplayName("token vencido (a mensagem do decodificador diz expired): 401 TOKEN_EXPIRED")
    void expired() throws Exception {
        MockHttpServletResponse r = call(true, new InvalidBearerTokenException("An error occurred while attempting to decode the Jwt: Jwt expired at 2026-10-24T05:00:00Z"));
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getContentAsString()).contains("\"code\":\"TOKEN_EXPIRED\"").doesNotContain("Jwt expired").doesNotContain("05:00:00Z");
    }

    @Test
    @DisplayName("token de teste recusado (desligado, modo real ou revogado): 401 TEST_TOKEN_REFUSED")
    void testTokenRefused() throws Exception {
        MockHttpServletResponse r = call(true, new UsernameNotFoundException("Error to authenticate user", new DisabledException("Test token refused")));
        assertThat(r.getContentAsString()).contains("\"code\":\"TEST_TOKEN_REFUSED\"");
    }

    @Test
    @DisplayName("qualquer outro token ruim (malformado, assinatura, conta desativada): 401 TOKEN_INVALID, sem a mensagem interna")
    void invalid() throws Exception {
        MockHttpServletResponse r = call(true, new InvalidBearerTokenException("Jwt strange internal detail"));
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getContentAsString()).contains("\"code\":\"TOKEN_INVALID\"").doesNotContain("strange internal detail");
        MockHttpServletResponse disabled = call(true, new UsernameNotFoundException("Error to authenticate user", new DisabledException("Account is disabled: 1")));
        assertThat(disabled.getContentAsString()).contains("\"code\":\"TOKEN_INVALID\"").doesNotContain("Account is disabled");
    }
}
