// Mesmo pacote da classe sob teste: ReservedEmailDomains e package-private.
package com.ospaulos.vupth.infrastructure.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;

/**
 * Unit tests — e-mail para dominio reservado de teste nao sai (teste-na-producao, 10/10).
 */
class ReservedEmailDomainsTest {

    @ParameterizedTest
    @ValueSource(strings = {"prof_1@vupth.test", "a@VUPTH.TEST", "x@foo.invalid", "y@example.com",
            "z@mail.example.org", "w@host.example", "v@localhost", "u@vupth.test."})
    @DisplayName("dominios reservados (RFC 2606/6761) sao reconhecidos")
    void reserved(String email) {
        assertThat(ReservedEmailDomains.isReserved(email)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"cliente@gmail.com", "dono@vupth.app", "a@test.com", "b@exemplo.com",
            "c@notexample.com", "sem-arroba", "termina@"})
    @DisplayName("dominios reais (inclusive test.com, que existe) e enderecos invalidos nao sao reservados")
    void notReserved(String email) {
        assertThat(ReservedEmailDomains.isReserved(email)).isFalse();
    }

    @Test
    @DisplayName("a Brevo nao e chamada para dominio reservado")
    void brevoSkipsReservedDomain() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer brevo = MockRestServiceServer.bindTo(builder).build();
        brevo.expect(ExpectedCount.never(), anything());
        BrevoApiEmailSender sender = new BrevoApiEmailSender(builder.build(),
                "https://api.brevo.com/v3/smtp/email", "chave", "no-reply@vupth.app", "VUPTH");

        assertThatCode(() -> sender.send("prof_1@vupth.test", "VUPTH — Pedido de vínculo", "corpo"))
                .doesNotThrowAnyException();

        brevo.verify();
    }
}
