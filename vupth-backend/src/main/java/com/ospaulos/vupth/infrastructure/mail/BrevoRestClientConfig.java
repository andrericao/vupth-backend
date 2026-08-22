package com.ospaulos.vupth.infrastructure.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * O {@link RestClient} do {@link BrevoApiEmailSender} — separado do sender por causa do
 * <b>timeout</b>, que e a unica coisa configurada aqui e a unica que o sender nao pode decidir
 * sozinho sem ficar intestavel (quem fixa a fabrica de requests no proprio construtor sobrescreve a
 * do {@code MockRestServiceServer} e o teste vai bater na internet de verdade).
 *
 * <p><b>Por que timeout explicito.</b> O envio roda <b>dentro</b> do {@code @Transactional} do
 * {@code ForgotPasswordUseCase}: uma conexao que abre e nao responde segura a transacao e a conexao
 * do pool ate o socket cair sozinho. No Render free, com pool pequeno, poucas dessas esgotam o pool
 * — a partir de um endpoint <b>publico e sem rate limit</b>. Mesmo motivo e mesmo teto dos
 * {@code mail.smtp.*timeout} do SMTP.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "vupth.mail", name = "provider", havingValue = "brevo")
public class BrevoRestClientConfig {

    @Bean
    public RestClient brevoRestClient(RestClient.Builder builder,
                                      @Value("${vupth.mail.brevo.timeout-ms:10000}") long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return builder.requestFactory(factory).build();
    }
}
