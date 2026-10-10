package com.ospaulos.vupth.infrastructure.mail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Envia e-mail pela <b>API HTTP do Brevo</b> ({@code POST /v3/smtp/email}, porta 443). Ativo quando
 * {@code vupth.mail.provider=brevo}.
 *
 * <p><b>Por que existe, se o {@link SmtpEmailSender} ja enviava.</b> No Render a conexao TCP com
 * {@code smtp-relay.brevo.com:587} <i>nao completa</i>. Isso deixou de ser suspeita em 2026-08-17: o
 * indicador de saude de mail do Actuator abre conexao SMTP a cada checagem, e o {@code /actuator/health}
 * de homolog respondia <b>503 em 10,7s</b>, tres medicoes seguidas — exatamente o
 * {@code mail.smtp.connectiontimeout} de 10s estourando. Host errado, credencial invalida ou chave
 * expirada falhariam <b>na hora</b>, com mensagem do provedor; so bloqueio de saida na porta produz
 * espera ate o timeout. Ou seja: as env vars chegaram na JVM e o problema esta na porta, nao na conta.
 * A API HTTP usa 443, que o Render deixa sair — mesmo provedor, mesma caixa de entrada, outro
 * transporte.</p>
 *
 * <p><b>A chave nao e a mesma do SMTP.</b> O Brevo tem duas credenciais distintas: a <i>SMTP key</i>
 * (usada em {@code MAIL_PASS}) e a <i>API key v3</i>, que vai no header {@code api-key} daqui.
 * Trocar uma pela outra devolve {@code 401 unauthorized} — e por isso o corpo da resposta de erro e
 * logado: e ele que distingue "chave errada" de "conta bloqueada" de "remetente nao verificado".</p>
 *
 * <p><b>Best-effort, como o SMTP.</b> Falha vira {@link EmailDeliveryException} e quem chama
 * (o {@code ForgotPasswordUseCase}) loga e engole — o codigo de reset continua valido, e a resposta
 * do endpoint segue 200 em qualquer caso (anti-enumeracao).</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vupth.mail", name = "provider", havingValue = "brevo")
public class BrevoApiEmailSender implements EmailSender {

    private final RestClient restClient;
    private final String url;
    private final String apiKey;
    private final String from;
    private final String fromName;

    public BrevoApiEmailSender(
            RestClient brevoRestClient,
            @Value("${vupth.mail.brevo.url:https://api.brevo.com/v3/smtp/email}") String url,
            @Value("${vupth.mail.brevo.api-key:}") String apiKey,
            @Value("${vupth.mail.from:no-reply@vupth.app}") String from,
            @Value("${vupth.mail.brevo.from-name:VUPTH}") String fromName) {
        // O cliente vem pronto do BrevoRestClientConfig (que e quem aplica os timeouts).
        this.restClient = brevoRestClient;
        this.url = url;
        this.apiKey = apiKey;
        this.from = from;
        this.fromName = fromName;
    }

    /**
     * Contraparte do aviso do {@link NoOpEmailSender}: o boot diz qual provider esta no ar. Chave
     * vazia com provider=brevo e o caso pior — o bean sobe, o endpoint responde 200 e todo envio
     * morre em 401 que ninguem le, porque o unico sintoma visivel e "o e-mail nao chegou".
     */
    @PostConstruct
    void anunciaProvider() {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("[MAIL] provider=brevo mas vupth.mail.brevo.api-key esta VAZIA — nenhum e-mail vai sair. "
                    + "Configure BREVO_API_KEY (API key v3, NAO a SMTP key).");
        } else {
            log.info("[MAIL] provider=brevo — url={} from={} (API HTTP, porta 443)", url, from);
        }
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String code) {
        send(toEmail, PasswordResetMessage.SUBJECT, PasswordResetMessage.body(code));
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        if (ReservedEmailDomains.isReserved(toEmail)) {
            log.info("[MAIL:brevo] '{}' nao enviado para {}: dominio reservado de teste", subject, toEmail);
            return;
        }
        Map<String, Object> payload = Map.of(
                "sender", Map.of("email", from, "name", fromName),
                "to", List.of(Map.of("email", toEmail)),
                "subject", subject,
                "textContent", body);
        try {
            BrevoSendResponse response = restClient.post()
                    .uri(url)
                    .header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(BrevoSendResponse.class);
            String messageId = response == null ? null : response.messageId();
            log.info("[MAIL:brevo] '{}' enviado para {} (messageId={})", subject, toEmail, messageId);
        } catch (RestClientResponseException e) {
            // O corpo do Brevo e o unico lugar onde aparece o MOTIVO ("Key not found", "sender not
            // valid"). Sem ele sobra o status, que nao separa chave errada de remetente nao verificado.
            log.error("[MAIL:brevo] falha ao enviar '{}' para {}: HTTP {} — {}",
                    subject, toEmail, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new EmailDeliveryException("Brevo recusou o envio (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (Exception e) {
            log.error("[MAIL:brevo] falha ao enviar '{}' para {}: {}", subject, toEmail, e.getMessage());
            throw new EmailDeliveryException("Falha ao chamar a API do Brevo", e);
        }
    }

    /**
     * Resposta do {@code POST /v3/smtp/email}: {@code 201 {"messageId":"<...>"}}. Guardar o id no log
     * e o que permite casar "o backend mandou" com a entrega no painel do Brevo — sem ele, um e-mail
     * que nao chega nao tem como ser rastreado do lado de la.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BrevoSendResponse(String messageId) {
    }
}
