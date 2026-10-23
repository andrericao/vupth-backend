package com.ospaulos.vupth.infrastructure.sentry;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpInputMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("sentry-contexto-e-limpeza: nada pessoal sai para o Sentry")
class SentryScrubberTest {

    private final SentryScrubber scrubber = new SentryScrubber();

    @Test
    @DisplayName("pedido: sem corpo, sem cookie, sem Authorization, query limpa")
    void scrubsRequest() {
        SentryEvent event = new SentryEvent();
        Request request = new Request();
        request.setData("{\"password\":\"abc123\",\"email\":\"ana@x.com\"}");
        request.setCookies("sessao=segredo");
        request.setQueryString("page=1&token=abc.def&email=ana@x.com&chave=11987654321");
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer eyJhbGciOi.eyJzdWIi.assinatura");
        headers.put("Cookie", "a=b");
        headers.put("Asaas-Access-Token", "t0k3n");
        headers.put("User-Agent", "VUPTH/1.0");
        request.setHeaders(headers);
        event.setRequest(request);

        SentryEvent out = scrubber.execute(event, new Hint());

        assertThat(out.getRequest().getData()).isNull();
        assertThat(out.getRequest().getCookies()).isNull();
        assertThat(out.getRequest().getHeaders()).containsOnlyKeys("User-Agent");
        assertThat(out.getRequest().getQueryString()).startsWith("page=1")
                .doesNotContain("abc.def").doesNotContain("ana@x.com").doesNotContain("11987654321");
    }

    @Test
    @DisplayName("mensagem, excecao e breadcrumb: e-mail, telefone, CPF, token e Bearer saem")
    void scrubsTexts() {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setFormatted("Falha para ana@x.com no telefone (11) 98765-4321 cpf 123.456.789-09");
        event.setMessage(message);
        io.sentry.protocol.SentryException ex = new io.sentry.protocol.SentryException();
        ex.setValue("401 com Bearer abc.def.ghi e senha=1234");
        event.setExceptions(java.util.List.of(ex));
        Breadcrumb crumb = new Breadcrumb("login de bia@y.com");
        crumb.setData("url", "https://x/y?token=zzz");
        crumb.setData("status", 200);
        event.setBreadcrumbs(java.util.List.of(crumb));

        SentryEvent out = scrubber.execute(event, new Hint());

        assertThat(out.getMessage().getFormatted()).doesNotContain("ana@x.com").doesNotContain("98765").doesNotContain("123.456");
        assertThat(out.getExceptions().get(0).getValue()).doesNotContain("abc.def.ghi").doesNotContain("1234");
        assertThat(out.getBreadcrumbs().get(0).getMessage()).doesNotContain("bia@y.com");
        assertThat(out.getBreadcrumbs().get(0).getData().get("url").toString()).doesNotContain("zzz");
        assertThat(out.getBreadcrumbs().get(0).getData().get("status")).isEqualTo(200);
    }

    @Test
    @DisplayName("id, rota e texto sem dado pessoal passam intactos")
    void keepsHarmlessText() {
        assertThat(SentryScrubber.clean("GET /attendances/6f1c2b1e-0a6f-4a31-9a55-0d2f8f7c1a11 falhou em 2026-10-23"))
                .isEqualTo("GET /attendances/6f1c2b1e-0a6f-4a31-9a55-0d2f8f7c1a11 falhou em 2026-10-23");
        assertThat(SentryScrubber.clean(null)).isNull();
    }

    @Test
    @DisplayName("erro de validacao 4xx nao vai ao Sentry; erro de servidor vai")
    void dropsValidationErrors() {
        SentryEvent validation = new SentryEvent(new ConstraintViolationException("x", Set.of()));
        SentryEvent bind = new SentryEvent(new HttpMessageNotReadableException("json ruim", mock(HttpInputMessage.class)));
        SentryEvent server = new SentryEvent(new IllegalStateException("quebrou"));

        assertThat(scrubber.execute(validation, new Hint())).isNull();
        assertThat(scrubber.execute(bind, new Hint())).isNull();
        assertThat(scrubber.execute(server, new Hint())).isNotNull();
    }
}
