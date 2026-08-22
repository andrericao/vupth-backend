package com.ospaulos.vupth.unit.mail;

import com.ospaulos.vupth.infrastructure.mail.BrevoApiEmailSender;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Unit tests — envio do codigo de reset pela API HTTP do Brevo (backlog #1).
 *
 * <p><b>O que este teste guarda.</b> O sintoma do defeito de producao e "o e-mail nao chegou", e ele
 * e <b>invisivel de fora</b>: o {@code POST /auth/forgot-password} responde 200 por anti-enumeracao
 * tanto quando envia quanto quando falha. Nenhum teste de fluxo consegue distinguir os dois — so
 * aqui, no transporte, da para afirmar que a requisicao sai <b>com a forma que o Brevo aceita</b>.</p>
 *
 * <p>Os tres pontos que mais quebram na pratica estao cobertos: o header <b>{@code api-key}</b> (o
 * Brevo nao usa {@code Authorization: Bearer}, e a diferenca so aparece como 401), o <b>codigo dentro
 * do corpo</b> (um e-mail que chega sem o codigo e tao inutil quanto e-mail nenhum, e o usuario nao
 * tem como reportar a causa) e o <b>401 virando {@link EmailDeliveryException}</b> em vez de escapar
 * como excecao crua, que e o que mantem o envio best-effort sem derrubar o reset.</p>
 */
class BrevoApiEmailSenderTest {

    private static final String URL = "https://api.brevo.com/v3/smtp/email";
    private static final String API_KEY = "xkeysib-chave-v3-de-teste";

    private MockRestServiceServer brevo;
    private BrevoApiEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        brevo = MockRestServiceServer.bindTo(builder).build();
        sender = new BrevoApiEmailSender(builder.build(), URL, API_KEY, "no-reply@vupth.app", "VUPTH");
    }

    @Test
    @DisplayName("envia POST /v3/smtp/email com api-key, remetente, destinatario e o codigo no corpo")
    void enviaComOFormatoQueOBrevoAceita() {
        brevo.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                // O Brevo autentica por header proprio. Mandar Bearer aqui devolve 401 e o unico
                // sintoma visivel do lado do usuario continua sendo "o e-mail nao chegou".
                .andExpect(header("api-key", API_KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sender.email").value("no-reply@vupth.app"))
                .andExpect(jsonPath("$.sender.name").value("VUPTH"))
                .andExpect(jsonPath("$.to[0].email").value("cliente@exemplo.com"))
                .andExpect(jsonPath("$.subject").value(containsString("recupera")))
                .andExpect(jsonPath("$.textContent").value(containsString("123456")))
                .andExpect(jsonPath("$.textContent").value(containsString("15 minutos")))
                // Campo extra no corpo da resposta: o cliente NAO pode quebrar por isso — o provedor
                // acrescenta campo sem avisar, e um envio bem-sucedido viraria falha de parse.
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"messageId\":\"<202608221200.1@brevo>\",\"campoNovoDoProvedor\":true}"));

        assertThatCode(() -> sender.sendPasswordResetCode("cliente@exemplo.com", "123456"))
                .doesNotThrowAnyException();

        brevo.verify();
    }

    @Test
    @DisplayName("401 (chave SMTP no lugar da API key v3) vira EmailDeliveryException, nao excecao crua")
    void chaveInvalidaViraEmailDeliveryException() {
        brevo.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}"));

        // O tipo importa: o ForgotPasswordUseCase so engole EmailDeliveryException. Qualquer outra
        // sobe pelo @Transactional e o cliente recebe 500 num endpoint que deve responder 200 sempre.
        assertThatThrownBy(() -> sender.sendPasswordResetCode("cliente@exemplo.com", "123456"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("401");

        brevo.verify();
    }

    @Test
    @DisplayName("500 do provedor tambem vira EmailDeliveryException (o codigo de reset segue valido)")
    void falhaDoProvedorViraEmailDeliveryException() {
        brevo.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"internal_error\"}"));

        assertThatThrownBy(() -> sender.sendPasswordResetCode("cliente@exemplo.com", "123456"))
                .isInstanceOf(EmailDeliveryException.class);

        brevo.verify();
    }

    @Test
    @DisplayName("resposta 201 sem corpo nao quebra o envio")
    void respostaSemCorpoNaoQuebra() {
        // Nao e hipotese: 2xx sem corpo e resposta legitima de HTTP, e o sender le o messageId so
        // para o log. Deixar o NPE passar transformaria um envio BEM-SUCEDIDO em falha registrada.
        brevo.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CREATED));

        assertThatCode(() -> sender.sendPasswordResetCode("cliente@exemplo.com", "123456"))
                .doesNotThrowAnyException();

        brevo.verify();
    }
}
